package com.deltanexus.system.grid.core;

import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.grid.adapter.HandlerGridAdapter;
import com.deltanexus.system.grid.adapter.MenuGridAdapter;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.grid.GridSizes;
import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网格服务（0.3.0Beta）：脏标记、节流、事务编排与生命周期。
 *
 * <p>事务顺序固定为 <b>锁 → 求解 → 写入 → 提交</b>：</p>
 * <ol>
 *   <li>{@link GridLockManager} 按玩家取锁（主线程无竞争，跨线程入口安全）；</li>
 *   <li>{@link GridSolver} 纯函数求解（只读快照）；</li>
 *   <li>{@link GridMutation} 写入真实槽位（占位物 / 孤立占位物清理）；</li>
 *   <li>提交屏障：置「待广播」标记，同一 Tick 内由 {@link #serverTick} 统一广播，
 *       保证客户端看到的是已收敛的状态。</li>
 * </ol>
 *
 * <p>覆盖触发来源：菜单点击（含 Shift / 数字键 / Q / 拖拽）、跨格拾取、旋转、交易交付、
 * 制造扣料、指令与 Web 配置、登录加载、死亡掉落、维度切换、共享容器外部变更（内容哈希兜底）。</p>
 */
public final class GridService {

    /** 需要在本 Tick 广播的玩家。 */
    private static final Set<UUID> PENDING_BROADCAST = ConcurrentHashMap.newKeySet();
    /** 每 20 tick 的内容哈希兜底（仓库 / 安全箱）。 */
    private static final Map<UUID, long[]> LAST_HASH = new ConcurrentHashMap<>();
    /** 已下发的布局指纹（玩家 → 指纹），避免重复发包。 */
    private static final Map<UUID, Long> LAST_LAYOUT = new ConcurrentHashMap<>();
    /** 布局版本（每玩家单调递增）。 */
    private static final Map<UUID, Long> LAYOUT_REVISION = new ConcurrentHashMap<>();
    /** 调试诊断日志开关（{@code -Ddeltanexus.grid.debug=true}）。 */
    private static final boolean DEBUG = Boolean.getBoolean("deltanexus.grid.debug");
    /** 异常限频（玩家 → 上次报告时间）。 */
    private static final Map<UUID, Long> LAST_FAILURE = new ConcurrentHashMap<>();
    private static int tickCounter;

    /**
     * 网格异常统一出口：打一次完整堆栈（每玩家 10 秒最多一次），<b>绝不外溢</b>。
     *
     * <p>外溢的代价是“连出售都识别不了”这类看起来毫不相关的故障——因为同一 tick 里
     * 交易目录下发、出售结算、界面同步都在跑，一个未捕获异常就会把它们一起掐断。</p>
     */
    public static void reportTickFailure(Player player, Throwable t) {
        if (player != null) {
            long now = System.currentTimeMillis();
            Long last = LAST_FAILURE.get(player.getUUID());
            if (last != null && now - last < 10_000L) {
                return;
            }
            LAST_FAILURE.put(player.getUUID(), now);
        }
        com.deltanexus.system.DeltaNexus.LOGGER.error(
                "[DN] 网格引擎异常（已隔离，不影响其它系统；请把这条堆栈发给开发者）", t);
    }

    private GridService() {
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 服务端每 Tick（玩家）：门闸 → 口袋守卫 → 光标占位物修复 → <b>一致性校验</b> → 提交广播 → 内容哈希兜底。
     *
     * <p>0.3.0Beta 止血：<b>不再每 tick 重排</b>。每 tick 只做只读的一致性校验
     * （{@link GridIntegrity#check}）；只有发现不一致（外部写入、旧数据残留、缺失占位物）时才收敛一次。
     * 收敛只允许“移动/重建占位物”，永不创建物品；写回由 {@link GridMutation} 的守恒安全网兜底。</p>
     */
    public static void serverTick(Player player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return;
        }
        InventoryGridHandler.ensurePocketGuards(menu, player);
        repairCarriedSlave(player);
        clearStraySlaves(menu);

        boolean changed = false;
        for (MenuGridAdapter group : MenuGridAdapter.groups(player, menu)) {
            if (!needsConverge(player, group)) {
                continue;
            }
            // 单个容器出错不得影响其它容器与其它系统
            try {
                changed |= solveGroup(player, group);
            } catch (Throwable t) {
                reportTickFailure(player, t);
            }
        }

        tickCounter++;
        if (tickCounter % 20 == 0) {
            changed |= hashFallback(player);
        }
        if (changed) {
            PENDING_BROADCAST.add(player.getUUID());
        }
        if (PENDING_BROADCAST.remove(player.getUUID())) {
            menu.broadcastChanges();
        }
        try {
            sendLayout(player, menu);
        } catch (Throwable t) {
            reportTickFailure(player, t);
        }
    }

    /**
     * 下发当前菜单的网格布局（0.3.0Beta 第二阶段，协议 dn4）。
     *
     * <p>只发跨格物品（span &gt; 1）的锚点、尺寸、旋转与行宽；内容未变（指纹相同）则不发包。
     * 客户端据此渲染与点击判定——服务端与客户端因此共享同一份几何真相，
     * 不再出现「客户端自己推导出另一套布局」导致的假性复制/重叠。</p>
     */
    public static void sendLayout(Player player, AbstractContainerMenu menu) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp) || menu == null) {
            return;
        }
        java.util.List<com.deltanexus.system.network.packet.SyncGridLayoutPacket.Entry> entries =
                new java.util.ArrayList<>();
        // 指纹包含菜单容器 id：切换菜单时强制重发一次（客户端切菜单会重置布局缓存）
        long fingerprint = 31L * menu.containerId + 7L;
        for (MenuGridAdapter group : MenuGridAdapter.groups(sp, menu)) {
            // v2 内核持有的容器：直接按内核条目下发布局（旋转/尺寸的真相在条目上，不在物品 NBT）
            if (isV2Backed(group)) {
                var grid = ((com.deltanexus.system.grid.v2.GridHandlerBridge) group.container()).grid();
                for (var e : grid.entries().entrySet()) {
                    com.deltanexus.system.grid.v2.GridEntry entry = e.getValue();
                    if (entry.dim().is1x1()) {
                        continue;
                    }
                    int local = group.localIndexOfContainerIndex(e.getKey());
                    int menuSlot = local < 0 ? -1 : group.menuSlotIndex(local);
                    if (menuSlot < 0) {
                        continue;
                    }
                    entries.add(new com.deltanexus.system.network.packet.SyncGridLayoutPacket.Entry(
                            menuSlot, entry.dim().w(), entry.dim().h(), entry.rotated(), group.context().width()));
                    fingerprint = fingerprint * 31 + menuSlot;
                    fingerprint = fingerprint * 31 + entry.dim().w() * 7 + entry.dim().h() * 13;
                    fingerprint = fingerprint * 31 + (entry.rotated() ? 1 : 0);
                }
                continue;
            }
            GridContext ctx = group.context();
            List<StackSnapshot> cells = group.snapshots();
            for (GridLayout.Placement p : GridLayout.derive(ctx, cells).placements()) {
                if (p.dim().is1x1() && !p.degraded()) {
                    continue; // 1x1 由原版渲染
                }
                int menuSlot = group.menuSlotIndex(p.anchor());
                if (menuSlot < 0) {
                    continue;
                }
                entries.add(new com.deltanexus.system.network.packet.SyncGridLayoutPacket.Entry(
                        menuSlot, p.dim().w(), p.dim().h(), p.rotated(), ctx.width()));
                fingerprint = fingerprint * 31 + menuSlot;
                fingerprint = fingerprint * 31 + p.dim().w() * 7 + p.dim().h() * 13;
                fingerprint = fingerprint * 31 + (p.rotated() ? 1 : 0);
            }
        }
        Long last = LAST_LAYOUT.get(sp.getUUID());
        if (last != null && last == fingerprint) {
            return;
        }
        LAST_LAYOUT.put(sp.getUUID(), fingerprint);
        long revision = LAYOUT_REVISION.merge(sp.getUUID(), 1L, Long::sum);
        com.deltanexus.system.network.PacketHandler.sendToPlayer(sp,
                new com.deltanexus.system.network.packet.SyncGridLayoutPacket(
                        menu.containerId, revision, entries));
    }

    /** 该组是否需要收敛：v2 内核持有的容器永不需要（内核自带不变式）；其余做只读布局校验。 */
    private static boolean needsConverge(Player player, MenuGridAdapter group) {
        if (isV2Backed(group)) {
            // 仓库 / 安全箱已由 grid.v2 的 GridInventory 直接持有：占用派生、唯一入口事务、
            // 每次提交都校验不变式——因此这里只做一次体检，绝不产生任何写入（消除双几何引擎）。
            String violation = ((com.deltanexus.system.grid.v2.GridHandlerBridge) group.container())
                    .grid().validate();
            if (violation != null) {
                reportTickFailure(player, new IllegalStateException("v2 网格不变式被破坏: " + violation));
            }
            return false;
        }
        if (!group.needsSolve()) {
            return false;
        }
        // 玩家背包（非 v2 容器）：改用派生视图判定——占用现算，只在非法/重叠时做「合法位移」
        com.deltanexus.system.grid.v2.PlayerInventoryView.Report report = analyseInventoryGroup(group);
        if (!report.consistent()) {
            if (DEBUG) {
                com.deltanexus.system.DeltaNexus.LOGGER.debug("[DN] 玩家背包需要收敛：{}（计划位移 {} 项，挪不动 {} 项）",
                        report.violation(), report.moves().size(), report.stuck().size());
            }
            return true;
        }
        return false;
    }

    /** 该组背后的容器是否由 v2 网格内核持有（仓库 / 安全箱）。 */
    private static boolean isV2Backed(MenuGridAdapter group) {
        return group != null && group.container() instanceof com.deltanexus.system.grid.v2.GridHandlerBridge;
    }

    /** 玩家背包组的派生体检（占用现算；不写任何格）。 */
    private static com.deltanexus.system.grid.v2.PlayerInventoryView.Report analyseInventoryGroup(MenuGridAdapter group) {
        int size = group.size();
        List<ItemStack> cells = new ArrayList<>(size);
        List<GridDim> dims = new ArrayList<>(size);
        int[] zone = new int[size];
        boolean[] usable = new boolean[size];
        for (int i = 0; i < size; i++) {
            var slot = group.slot(i);
            ItemStack stack = group.get(i);
            cells.add(stack);
            Player owner = menuPlayer(group.menu());
            // 创造模式：快捷栏 1~9 格无视大小（返回 1x1），背包区仍按配置尺寸；
            // 生存模式完全按 hotbar_rules 折算。
            boolean creative = owner != null && owner.isCreative();
            dims.add(com.deltanexus.system.grid.GridSizes.actualDim(stack, slot, creative,
                    owner, group.container()));
            // 分区：快捷栏/口袋（containerSlot < 9）与背包区互不跨越（与旧求解器口径一致）
            zone[i] = com.deltanexus.system.grid.GridSizes.isHotbarSlot(slot) ? 1 : 0;
            usable[i] = true;
        }
        return com.deltanexus.system.grid.v2.PlayerInventoryView.analyse(cells, dims,
                group.context().width(), group.context().rows(), zone, usable);
    }

    /**
     * 玩家背包组收敛：<b>只执行合法位移</b>（把非法/重叠的物品挪到首个空闲位），
     * 不创建、不删除、不复制物品；挪不动的保留原位并等下一轮（绝不丢弃）。
     */
    private static boolean solvePlayerInventoryGroup(Player player, MenuGridAdapter group) {
        var report = analyseInventoryGroup(group);
        if (report.consistent()) {
            return false;
        }
        boolean changed = false;
        for (var move : report.moves()) {
            ItemStack from = group.get(move.from());
            if (from.isEmpty()) {
                continue;
            }
            // 目标必须为空且不是别人的足迹（analyse 已保证，这里再校验一次，双保险）
            if (!group.get(move.to()).isEmpty()
                    || (report.owner().length > move.to() && report.owner()[move.to()] != -1)) {
                continue;
            }
            ItemStack payload = from.copy();
            group.set(move.to(), payload);
            group.set(move.from(), ItemStack.EMPTY);
            changed = true;
        }
        if (!report.stuck().isEmpty()) {
            com.deltanexus.system.DeltaNexus.LOGGER.warn(
                    "[DN] 玩家背包有 {} 件物品暂无合法位置（保留原位，等待玩家腾出空间）：格 {}",
                    report.stuck().size(), report.stuck());
        }
        return changed;
    }

    /**
     * 清理「不属于网格区」的槽位里的占位物：盔甲（36-39）与副手（40）不参与网格，
     * 旧数据或异常路径可能在里面留下占位物。
     */
    private static void clearStraySlaves(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory && slot.getContainerSlot() >= 36
                    && GridTags.isSlave(slot.getItem())) {
                slot.set(ItemStack.EMPTY);
            }
        }
    }

    /** 菜单关闭：清理所有容器中的占位物（存档保持干净，重开自动重建）+ 解除出售模式。 */
    public static void onMenuClosed(Player player, AbstractContainerMenu menu) {
        if (menu instanceof com.deltanexus.system.menu.WarehouseMenu wm) {
            // 0.3.0Beta：出售模式随菜单销毁（客户端异常断线也不会留下冻结状态）
            wm.setSellMode(false);
        }
        IPlayerData data = ManufacturingService.data(player);
        if (data != null) {
            cleanSlaves(data.getWarehouseHandler());
            cleanSlaves(data.getSafeBoxHandler());
        }
        if (menu == null) {
            return;
        }
        for (Slot slot : menu.slots) {
            Object container = InventoryGridHandler.gridContainerOf(slot);
            if (container instanceof Container c && !(c instanceof Inventory)) {
                cleanSlaves(c);
            } else if (container instanceof ItemStackHandler h) {
                cleanSlaves(h);
            }
        }
    }

    /** 玩家数据保存/登出/维度切换前：清掉仓库与安全箱里的占位物（占位物只存在于运行态）。 */
    public static void sanitizePlayerData(Player player) {
        IPlayerData data = ManufacturingService.data(player);
        if (data == null) {
            return;
        }
        cleanSlaves(data.getWarehouseHandler());
        cleanSlaves(data.getSafeBoxHandler());
        LAST_HASH.remove(player.getUUID());
    }

    /** 登录加载：清除历史残留占位物（旧版本可能把它们写进存档）。 */
    public static void onPlayerLoaded(Player player) {
        sanitizePlayerData(player);
    }

    // ------------------------------------------------------------------
    // 求解入口
    // ------------------------------------------------------------------

    /** 求解菜单内的全部网格组；返回是否发生了写入。 */
    public static boolean solveMenu(Player player, AbstractContainerMenu menu) {
        if (player == null || menu == null) {
            return false;
        }
        List<MenuGridAdapter> groups = MenuGridAdapter.groups(player, menu);
        if (groups.isEmpty()) {
            return false;
        }
        boolean changed = false;
        try (GridLockManager.LockSet ignored = GridLockManager.acquire(GridLockManager.player(player.getUUID()))) {
            for (MenuGridAdapter group : groups) {
                if (!needsConverge(player, group)) {
                    continue; // 已一致 / 由 v2 内核持有：不求解、不写入
                }
                changed |= solveGroup(player, group);
            }
        }
        return changed;
    }

    /** 收敛一个网格组：v2 容器由内核自持（不会走到这里）；其余（玩家背包）走派生 + 合法位移。 */
    private static boolean solveGroup(Player player, MenuGridAdapter group) {
        if (!group.needsSolve()) {
            return false;
        }
        if (isV2Backed(group)) {
            // 双保险：v2 容器内核自持不变式，这里只体检、不写入
            String violation = ((com.deltanexus.system.grid.v2.GridHandlerBridge) group.container())
                    .grid().validate();
            if (violation != null) {
                reportTickFailure(player, new IllegalStateException("v2 网格不变式被破坏: " + violation));
            }
            return false;
        }
        return solvePlayerInventoryGroup(player, group);
    }

    /** 整理裸容器（仓库 / 安全箱 / 已注册处理器）；返回是否发生了写入。 */
    public static boolean arrange(Player player, ItemStackHandler handler) {
        if (player == null || handler == null) {
            return false;
        }
        // v2 容器由内核自持：无需“整理”（占用派生、条目自洽），只做体检
        if (handler instanceof com.deltanexus.system.grid.v2.GridHandlerBridge bridge) {
            String violation = bridge.grid().validate();
            if (violation != null) {
                reportTickFailure(player, new IllegalStateException("v2 网格不变式被破坏: " + violation));
            }
            return false;
        }
        boolean changed;
        try (GridLockManager.LockSet ignored = GridLockManager.acquire(GridLockManager.player(player.getUUID()))) {
            HandlerGridAdapter adapter = HandlerGridAdapter.of(player, handler);
            if (!adapter.needsSolve()) {
                return false;
            }
            GridContext ctx = adapter.context();
            List<StackSnapshot> cells = adapter.snapshots();
            SolvePlan plan = GridSolver.solve(ctx, cells, cursorOf(player));
            changed = GridMutation.apply(adapter, ctx, cells, plan, sinkFor(player));
        }
        if (changed) {
            PENDING_BROADCAST.add(player.getUUID());
        }
        return changed;
    }

    /**
     * 把 {@code incoming} 放入容器（交易行买入交付、外部交付统一入口）。
     *
     * <p>遵守格式背包全部规则：只落已解锁格、足迹不越界、不覆盖真实物品或存活占位物
     * （孤立占位物视为空位）、同类同 NBT 优先合并进既有主格，并按占用尺寸写入占位物。</p>
     *
     * @param simulate true = 纯预测，不改动容器
     * @return 放不下的剩余量（{@link ItemStack#EMPTY} = 全部可放入/已放入）
     */
    public static ItemStack placeInto(Player player, ItemStackHandler handler, ItemStack incoming, boolean simulate) {
        if (player == null || handler == null || incoming == null || incoming.isEmpty()) {
            return incoming == null ? ItemStack.EMPTY : incoming.copy();
        }
        try (GridLockManager.LockSet ignored = GridLockManager.acquire(GridLockManager.player(player.getUUID()))) {
            // 0.3.0Beta v2：自有容器（仓库/安全箱）交付直接走内核——
            // 旧路径会为足迹写占位物，而 v2 没有占位物，那些标记会被当成普通物品落位、污染仓库
            if (handler instanceof com.deltanexus.system.grid.v2.GridHandlerBridge bridge) {
                ItemStack left = bridge.insertIntoGrid(incoming, simulate);
                if (!simulate) {
                    PENDING_BROADCAST.add(player.getUUID());
                }
                return left;
            }
            HandlerGridAdapter adapter = HandlerGridAdapter.of(player, handler);
            if (!com.deltanexus.system.grid.GridRegistry.isGridContainer(player, handler)) {
                return incoming.copy();
            }
            GridContext ctx = adapter.context();
            int size = ctx.size();
            GridDim dim = adapter.dimOf(incoming);
            int max = incoming.getMaxStackSize();
            int remain = incoming.getCount();

            // 0.3.0Beta 止血：交付也走「影子数组 + 守恒校验 + 原子写回」——
            // 先在 after 上算完，校验通过再一次性写回，绝不半写、绝不复制
            List<ItemStack> before = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                before.add(adapter.get(i).copy());
            }
            List<ItemStack> after = new ArrayList<>(before);
            GridTarget view = GridTarget.readOnly(after);

            boolean[] occupied = occupancy(ctx, view);
            // 1) 合并进既有同类主格
            if (remain > 0) {
                for (int i = 0; i < size && remain > 0; i++) {
                    if (!ctx.usable(i)) {
                        continue;
                    }
                    ItemStack cur = view.get(i);
                    if (cur.isEmpty() || GridTags.isSlave(cur)) {
                        continue;
                    }
                    if (!ItemStack.isSameItemSameTags(cur, incoming) || cur.getCount() >= cur.getMaxStackSize()) {
                        continue;
                    }
                    int add = Math.min(cur.getMaxStackSize() - cur.getCount(), remain);
                    ItemStack grown = cur.copy();
                    grown.grow(add);
                    after.set(i, grown);
                    remain -= add;
                }
            }
            // 2) 新足迹：每个落点一个 chunk（≤ 最大堆叠）
            while (remain > 0) {
                int chunk = Math.min(max, remain);
                int spot = GridSolver.firstFreeSpot(ctx, occupied, dim);
                if (spot < 0) {
                    break;
                }
                ItemStack placed = incoming.copy();
                placed.setCount(chunk);
                after.set(spot, placed);
                for (int dy = 0; dy < dim.h(); dy++) {
                    for (int dx = 0; dx < dim.w(); dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int cell = ctx.cellAt(spot, dx, dy);
                        if (cell >= 0 && cell < size) {
                            ItemStack slave = GridTags.createSlave(view.containerIndex(spot));
                            if (!slave.isEmpty()) {
                                after.set(cell, slave);
                            }
                        }
                    }
                }
                GridSolver.markFootprint(ctx, occupied, spot, dim);
                remain -= chunk;
            }

            long placedTotal = GridIntegrity.totalCount(after) - GridIntegrity.totalCount(before);
            if (placedTotal < 0 || placedTotal > incoming.getCount()) {
                com.deltanexus.system.DeltaNexus.LOGGER.error(
                        "[DN] 交付事务中止（数量异常）：放入 {}，期望 0~{} → 放弃本次交付",
                        placedTotal, incoming.getCount());
                return incoming.copy();
            }
            if (GridIntegrity.sharesInstance(after)) {
                com.deltanexus.system.DeltaNexus.LOGGER.error("[DN] 交付事务中止（同一 ItemStack 实例被写入多格）→ 放弃本次交付");
                return incoming.copy();
            }
            if (!simulate) {
                for (int i = 0; i < size; i++) {
                    if (ItemStack.matches(before.get(i), after.get(i))) {
                        continue;
                    }
                    ItemStack next = after.get(i);
                    adapter.set(i, next.isEmpty() ? ItemStack.EMPTY : next.copy());
                }
                PENDING_BROADCAST.add(player.getUUID());
            }
            int left = incoming.getCount() - (int) placedTotal;
            if (left <= 0) {
                return ItemStack.EMPTY;
            }
            ItemStack remainderStack = incoming.copy();
            remainderStack.setCount(left);
            return remainderStack;
        }
    }

    /**
     * 服务端：把菜单槽位解析成所属锚点槽位（0.3.0Beta 第三阶段，去占位物后的权威解析）。
     *
     * <p>用与下发布局完全相同的 {@link GridLayout#derive} 推导，因此服务端与客户端对「哪一格属于哪件物品」
     * 的认知永远一致；占位物不再参与判定（历史残留由收敛清理）。</p>
     *
     * @return 锚点的菜单槽位索引；该格不属于任何跨格物品时返回 {@code slotId}
     */
    public static int anchorSlotOf(AbstractContainerMenu menu, int slotId) {
        if (menu == null || slotId < 0 || slotId >= menu.slots.size()) {
            return slotId;
        }
        Player player = menuPlayer(menu);
        if (player == null || player.level().isClientSide) {
            return slotId;
        }
        for (MenuGridAdapter group : MenuGridAdapter.groups(player, menu)) {
            int local = group.localIndexOf(slotId);
            if (local < 0) {
                continue;
            }
            // v2 内核持有的容器：锚点由内核条目直接给出（旋转/尺寸的真相在条目上，不在物品 NBT）
            if (isV2Backed(group)) {
                var grid = ((com.deltanexus.system.grid.v2.GridHandlerBridge) group.container()).grid();
                int anchorCell = grid.anchorCovering(group.containerIndex(local));
                if (anchorCell >= 0 && anchorCell != group.containerIndex(local)) {
                    int anchorLocal = group.localIndexOfContainerIndex(anchorCell);
                    int menuSlot = anchorLocal < 0 ? -1 : group.menuSlotIndex(anchorLocal);
                    if (menuSlot >= 0) {
                        return menuSlot;
                    }
                }
                return slotId;
            }
            GridContext ctx = group.context();
            int[] owner = GridLayout.derive(ctx, group.snapshots()).owner();
            int anchor = local < owner.length ? owner[local] : -1;
            if (anchor >= 0 && anchor != local) {
                int menuSlot = group.menuSlotIndex(anchor);
                if (menuSlot >= 0) {
                    return menuSlot;
                }
            }
            return slotId;
        }
        return slotId;
    }

    /** 容器现状占用：非空格（含存活占位物）+ 主格足迹（孤立占位物视为空位）。 */
    private static boolean[] occupancy(GridContext ctx, GridTarget target) {
        int size = ctx.size();
        boolean[] occupied = new boolean[size];
        for (int i = 0; i < size; i++) {
            ItemStack stack = target.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (GridTags.isSlave(stack)) {
                int master = GridTags.masterOf(stack);
                ItemStack masterStack = master >= 0 && master < size ? target.get(master) : ItemStack.EMPTY;
                if (!masterStack.isEmpty() && !GridTags.isSlave(masterStack)) {
                    occupied[i] = true;
                }
                continue;
            }
            occupied[i] = true;
            GridDim dim = GridSizes.baseDim(stack);
            if (!dim.is1x1() && ctx.inBounds(i, dim)) {
                GridSolver.markFootprint(ctx, occupied, i, dim);
            }
        }
        return occupied;
    }

    // ------------------------------------------------------------------
    // 占位物清理与内容哈希
    // ------------------------------------------------------------------

    /** 清除处理器中的全部占位物，返回清除数量。 */
    public static int cleanSlaves(ItemStackHandler handler) {
        if (handler == null) {
            return 0;
        }
        int cleaned = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            if (GridTags.isSlave(handler.getStackInSlot(i))) {
                handler.setStackInSlot(i, ItemStack.EMPTY);
                cleaned++;
            }
        }
        return cleaned;
    }

    /** 清除容器中的全部占位物，返回清除数量。 */
    public static int cleanSlaves(Container container) {
        if (container == null) {
            return 0;
        }
        int cleaned = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (GridTags.isSlave(container.getItem(i))) {
                container.setItem(i, ItemStack.EMPTY);
                cleaned++;
            }
        }
        return cleaned;
    }

    /** 仅保留真实物品的内容哈希（占位物不参与，避免占位物抖动触发无谓求解）。 */
    public static long contentHash(ItemStackHandler handler) {
        long hash = 1L;
        if (handler == null) {
            return hash;
        }
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty() || GridTags.isSlave(stack)) {
                continue;
            }
            var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            hash = hash * 31 + (id == null ? 0 : id.hashCode());
            hash = hash * 31 + stack.getCount();
            hash = hash * 31 + (stack.getTag() == null ? 0 : stack.getTag().hashCode());
        }
        return hash;
    }

    /** 每 20 tick 兜底：仓库 / 安全箱内容外部变化（管道、指令、Web、其他模组）后补一次求解。 */
    private static boolean hashFallback(Player player) {
        IPlayerData data = ManufacturingService.data(player);
        if (data == null) {
            return false;
        }
        long warehouse = contentHash(data.getWarehouseHandler());
        long safe = contentHash(data.getSafeBoxHandler());
        long[] last = LAST_HASH.get(player.getUUID());
        if (last == null) {
            LAST_HASH.put(player.getUUID(), new long[]{warehouse, safe});
            return false;
        }
        if (last[0] == warehouse && last[1] == safe) {
            return false;
        }
        last[0] = warehouse;
        last[1] = safe;
        boolean changed = arrange(player, data.getWarehouseHandler());
        changed |= arrange(player, data.getSafeBoxHandler());
        return changed;
    }

    // ------------------------------------------------------------------
    // 光标
    // ------------------------------------------------------------------

    /** 当前光标状态（求解兜底判定用）。 */
    public static GridSolver.CursorState cursorOf(Player player) {
        if (player == null) {
            return GridSolver.CursorState.none();
        }
        AbstractContainerMenu menu = player.containerMenu;
        return menu == null ? GridSolver.CursorState.none() : GridSolver.CursorState.of(menu.getCarried());
    }

    /** 出不来物品的出口：服务端权威，写菜单光标 / 掉落。 */
    public static EvictionSink sinkFor(Player player) {
        if (player == null) {
            return EvictionSink.NONE;
        }
        return new EvictionSink() {
            @Override
            public int toCursor(ItemStack stack) {
                AbstractContainerMenu menu = player.containerMenu;
                if (menu == null || !menu.getCarried().isEmpty()) {
                    return 0;
                }
                menu.setCarried(stack.copy());
                return stack.getCount();
            }

            @Override
            public int mergeToCursor(ItemStack stack) {
                AbstractContainerMenu menu = player.containerMenu;
                if (menu == null) {
                    return 0;
                }
                ItemStack carried = menu.getCarried();
                if (carried.isEmpty() || !ItemStack.isSameItemSameTags(carried, stack)) {
                    return 0;
                }
                int space = carried.getMaxStackSize() - carried.getCount();
                int add = Math.min(space, stack.getCount());
                if (add <= 0) {
                    return 0;
                }
                carried.grow(add);
                return add;
            }

            @Override
            public boolean drop(ItemStack stack) {
                player.drop(stack.copy(), false);
                return true;
            }
        };
    }

    /**
     * 光标持有占位物（客户端异常/旧数据/恶意包）：按容器索引找回主格物品放上光标，
     * 主格清空；找不到主格则丢弃占位物。绝不把占位物当真物品交给玩家。
     */
    public static void repairCarriedSlave(Player player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return;
        }
        ItemStack carried = menu.getCarried();
        if (!GridTags.isSlave(carried)) {
            return;
        }
        int masterContainerIndex = GridTags.masterOf(carried);
        for (Slot slot : menu.slots) {
            if (!com.deltanexus.system.grid.GridRegistry.isGridContainer(
                    player, InventoryGridHandler.gridContainerOf(slot))) {
                continue;
            }
            if (slot.getSlotIndex() == masterContainerIndex
                    && !slot.getItem().isEmpty()
                    && !GridTags.isSlave(slot.getItem())) {
                menu.setCarried(slot.getItem().copy());
                slot.set(ItemStack.EMPTY);
                PENDING_BROADCAST.add(player.getUUID());
                return;
            }
        }
        menu.setCarried(ItemStack.EMPTY);
        PENDING_BROADCAST.add(player.getUUID());
    }

    // ------------------------------------------------------------------
    // 脏标记
    // ------------------------------------------------------------------

    /** 交互入口调用：本 Tick 内立即求解并广播（点击后同 Tick 收敛，客户端不会看到中间态）。 */
    public static void markDirty(Player player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        solveMenu(player, player.containerMenu);
        PENDING_BROADCAST.add(player.getUUID());
    }

    /** 交互入口调用（菜单版）。 */
    public static void markDirty(AbstractContainerMenu menu) {
        if (menu == null) {
            return;
        }
        Player player = menuPlayer(menu);
        if (player != null) {
            markDirty(player);
        }
    }

    /** 只要求下一次 tick 广播一次（物品已在别处写好后刷新客户端）。 */
    public static void requestBroadcast(Player player) {
        if (player != null) {
            PENDING_BROADCAST.add(player.getUUID());
        }
    }

    /** 玩家掉线清理。 */
    public static void onPlayerLoggedOut(Player player) {
        if (player == null) {
            return;
        }
        sanitizePlayerData(player);
        PENDING_BROADCAST.remove(player.getUUID());
        LAST_HASH.remove(player.getUUID());
        LAST_LAYOUT.remove(player.getUUID());
        LAYOUT_REVISION.remove(player.getUUID());
    }

    /** 从菜单反查玩家（Forge 的 AbstractContainerMenu 无 getPlayer，用槽位容器推导）。 */
    public static Player menuPlayer(AbstractContainerMenu menu) {
        if (menu == null) {
            return null;
        }
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory inv) {
                return inv.player;
            }
        }
        return null;
    }
}
