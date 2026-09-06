package com.deltanexus.system.grid;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.config.SafeBoxRestrictions;
import com.deltanexus.system.menu.GridAwareSlot;
import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import java.util.*;

/**
 * 格子格式（格式背包）网格引擎 —— 集成自 expansionpack-1.0.0（已获原作者授权）。
 *
 * <p>2.0.0 适配（三角联结融合）：</p>
 * <ul>
 *   <li>网格作用域限定为三个目标容器：玩家背包（原版 E 界面/各菜单玩家区）、
 *       仓库（{@link IPlayerData#getWarehouseHandler()}）、安全箱（{@link IPlayerData#getSafeBoxHandler()}）；
 *       原版容器（箱子等）保持原版槽位行为。</li>
 *   <li>按容器网格宽度：背包/仓库固定 9 列，安全箱固定 3 列（最大 3x3）。</li>
 *   <li>锁定格视为不可用：仓库未解锁槽位 / 安全箱未解锁格不可落位、不可跨越；</li>
 *   <li>安全箱 NBT 限制（{@link SafeBoxRestrictions}）在自动整理时同样拦截；</li>
 *   <li>菜单关闭时清理仓库/安全箱容器中的占位物（{@code blocked_slot}），存档保持干净；</li>
 *   <li>背包界面（原版 E）安全箱覆盖层点击后同步调用 {@link #arrange} 立即整理。</li>
 * </ul>
 *
 * <p>机制（2.0.9 重构，贴合三角联结）：服务端每 Tick 扫描打开菜单的槽位分组，
 * 按物品占用尺寸（{@link ItemSizeConfig} + 内置规则 + {@link GridConfig} 快捷栏规则）
 * 做冲突检测与最佳空位重排（大件优先排序），用占位物填充物品足迹内的非主格；
 * 冲突物品无处安放时保留原位等待下轮（原格不可用才掉落），
 * 大幅降低模式切换（创造-&gt;生存）时物品被强制丢弃的频率；
 * 客户端在容器界面渲染超大图标与灰色墙底，R 键旋转光标物品。</p>
 *
 * <p>注意：Forge {@link SlotItemHandler} 的 {@code slot.container} 是占位空容器，
 * 真实处理器须经 {@code SlotItemHandler#getItemHandler()} 获取（见 {@link #gridContainerOf}）。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID)
public class InventoryGridHandler {
    public static final String IS_SLAVE = "deltanexus.is_slave";
    public static final String MASTER_SLOT = "deltanexus.master_slot";
    public static final String IS_ROTATED = "deltanexus.is_rotated";
    private static final int GRID_WIDTH = 9;

    /** 客户端菜单影子容器（菜单构造时记录；服务端以玩家能力身份判断网格容器）。 */
    public static volatile ItemStackHandler CLIENT_SAFE_HANDLER;
    public static volatile ItemStackHandler CLIENT_WAREHOUSE_HANDLER;
    /** 客户端安全箱当前宽度（2.0.3：按行列形状渲染与求解；菜单构造时记录）。 */
    public static volatile int CLIENT_SAFE_WIDTH = 3;

    public record ItemDim(int w, int h) { public boolean is1x1() { return w == 1 && h == 1; } }

    // ------------------------------------------------------------------
    // 网格容器识别（2.0.0：玩家背包 / 仓库 / 安全箱；2.0.1：扩展全部 >= 9 格容器）
    // ------------------------------------------------------------------

    /** 槽位背后的真实容器（SlotItemHandler 的 container 是占位空容器，须取处理器）。包可见：GridClientRendering 渲染用。 */
    static Object gridContainerOf(Slot slot) {
        if (slot instanceof SlotItemHandler sih) {
            IItemHandler handler = sih.getItemHandler();
            return handler != null ? handler : slot.container;
        }
        return slot.container;
    }

    /**
     * 该容器是否启用格子格式（container 可为 Container 或 IItemHandler）：
     * 玩家背包（Inventory）、仓库/安全箱处理器，以及 2.0.1 起所有 >= 9 格的原版/模组容器
     * （箱子/潜影盒/末影箱/发射器/驴箱等）；合成格（{@link CraftingContainer}，3x3 工作台）
     * 与小于 9 格的容器（熔炉/铁砧等）保持原版槽位行为。
     */
    public static boolean isGridContainer(Player player, Object container) {
        if (container == null) {
            return false;
        }
        if (container instanceof Inventory) {
            return true;
        }
        if (container == CLIENT_SAFE_HANDLER || container == CLIENT_WAREHOUSE_HANDLER) {
            return true;
        }
        // 2.0.1：原版/模组大容器启用格子格式（合成格除外，避免破坏 3x3 工作台）
        if (container instanceof net.minecraft.world.inventory.CraftingContainer) {
            return false;
        }
        if (container instanceof Container c) {
            return c.getContainerSize() >= 9;
        }
        IPlayerData data = ManufacturingService.data(player);
        if (data == null) {
            return false;
        }
        return container == data.getWarehouseHandler() || container == data.getSafeBoxHandler();
    }

    /** 网格宽度（列数）：安全箱按实际解锁列数（2.0.3 行列形状），其余 9。 */
    public static int gridWidth(Player player, Object container) {
        if (container == null) {
            return GRID_WIDTH;
        }
        if (container == CLIENT_SAFE_HANDLER) {
            return Math.max(1, Math.min(3, CLIENT_SAFE_WIDTH));
        }
        IPlayerData data = ManufacturingService.data(player);
        if (data != null && container == data.getSafeBoxHandler()) {
            return Math.max(1, Math.min(3, data.getSafeBoxWidth()));
        }
        return GRID_WIDTH;
    }

    /** 槽位是否可用（仓库/安全箱的锁定格视为不可用，不可落位亦不可跨越）。 */
    private static boolean isSlotUsable(Player player, Object container, int slotIndex) {
        IPlayerData data = ManufacturingService.data(player);
        if (data == null) {
            return true;
        }
        if (container == data.getWarehouseHandler()) {
            return data.isSlotUnlocked(slotIndex);
        }
        if (container == data.getSafeBoxHandler()) {
            return data.isSafeSlotUnlocked(slotIndex);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 物品尺寸
    // ------------------------------------------------------------------

    public static boolean isIrregular(ItemStack stack) { return false; }

    public static boolean isItemPart(ItemStack stack, int dx, int dy, boolean rotated) {
        return !stack.isEmpty();
    }

    /**
     * 基础占用尺寸（2.1）：仅配置了尺寸的物品（{@code config/deltanexus-sizes.json}
     * 或运行时指令/Web 配置）才有占用尺寸；未配置一律 1x1（不再内置 箱子/剑/盔甲等默认尺寸）。
     */
    public static ItemDim getBaseDim(ItemStack stack) {
        if (stack.isEmpty()) return new ItemDim(1, 1);
        Item item = stack.getItem();
        ItemDim configSize = ItemSizeConfig.getSize(item);
        if (configSize != null) {
            if (stack.hasTag() && stack.getTag().getBoolean(IS_ROTATED)) return new ItemDim(configSize.h, configSize.w);
            return configSize;
        }
        return new ItemDim(1, 1);
    }

    /** 实际占用尺寸（含快捷栏规则；非网格容器一律 1x1）。 */
    public static ItemDim getActualDim(ItemStack stack, Slot slot, boolean isCreative, Player player) {
        if (stack.isEmpty() || slot == null) return new ItemDim(1, 1);
        boolean isHotbar = (slot.container instanceof Inventory) && (slot.getContainerSlot() < 9);
        if (isCreative || !isGridContainer(player, gridContainerOf(slot))) return new ItemDim(1, 1);
        ItemDim base = getBaseDim(stack);
        if (isHotbar) {
            int index = slot.getContainerSlot();
            List<? extends String> rules = GridConfig.rules();
            for (String rule : rules) {
                try {
                    String[] parts = rule.split(":");
                    String[] range = parts[0].split("-");
                    int start = Integer.parseInt(range[0]), end = Integer.parseInt(range[1]);
                    if (index >= start && index <= end) {
                        String mode = parts[1].toUpperCase();
                        if (mode.equals("ANY")) return new ItemDim(1, 1);
                        if (mode.equals("FOOD")) return (stack.getItem().isEdible() || base.is1x1()) ? new ItemDim(1, 1) : base;
                        if (mode.equals("GRID")) break;
                    }
                } catch (Exception ignored) {}
            }
        }
        return base;
    }

    /**
     * 手动放置到槽位前的尺寸预校验（2.0.10）：口袋区（快捷栏 5-9 号格 =
     * containerSlot 4-8）仅 1x1 留存。放不下的物品应直接拒绝放置、回到鼠标指针，
     * 而不是交给 processGrid 自动重排（自动重排会造成“塞进 A 却跳去 B”的错觉）。
     */
    public static boolean fitsManualPlacement(Player player, Slot slot, ItemStack stack) {
        if (stack.isEmpty() || slot == null) {
            return true;
        }
        if (slot.container instanceof Inventory && slot.getContainerSlot() >= 4 && slot.getContainerSlot() <= 8) {
            return getActualDim(stack, slot, player.isCreative(), player).is1x1();
        }
        return true;
    }

    /**
     * 口袋区（快捷栏 5-9 号格 = containerSlot 4-8）尺寸守卫（2.0.10）：
     * 将普通 Slot 幂等替换为 {@link GridAwareSlot}，使手动塞入大件（&gt;1x1）时
     * {@code mayPlace} 拒绝、物品回到光标，而非落入后由 {@code processGrid} 自动重排。
     *
     * <p>原版 {@code InventoryMenu} 的快捷栏槽为普通 {@link Slot}（无尺寸校验），
     * dn 背包/容器界面复用原版菜单，须在服务端与客户端各自把口袋槽替换为守卫槽
     * （槽位 index 不变，网络协议按 index 对齐，替换无副作用）。</p>
     */
    public static void ensurePocketGuards(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null) {
            return;
        }
        java.util.List<Slot> slots = menu.slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s == null || s instanceof GridAwareSlot) {
                continue;
            }
            if (s.container instanceof Inventory && s.getContainerSlot() >= 4 && s.getContainerSlot() <= 8) {
                // 2.1 修复：必须保留原槽位 index（协议以 index 为槽位 ID，clicked/quickMove 均依赖）。
                // 直接 new 会导致 index=0，点击口袋槽误发到合成结果格：无法取出/仅 shift 可放入。
                GridAwareSlot gs = new GridAwareSlot((Inventory) s.container, s.getContainerSlot(), s.x, s.y, player);
                gs.index = s.index;
                slots.set(i, gs);
            }
        }
    }

    // ------------------------------------------------------------------
    // 服务端：Tick 网格整理
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (isSlave(event.getEntity().getItem())) { event.setCanceled(true); event.getEntity().discard(); }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide || event.player.isCreative()) return;
        Player player = event.player;
        // 2.2：玩家功能被禁用（/dn feature deny）→ 网格引擎完全不介入该玩家
        // （其他模组（如 COD 模式）添加的槽位保持原版行为，不整理、不写占位物）
        if (!com.deltanexus.system.server.PermissionManager.canUseFeatures(
                player instanceof net.minecraft.server.level.ServerPlayer sp ? sp : null)) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;

        // 2.0.10：口袋槽尺寸守卫（幂等替换普通快捷栏槽为 GridAwareSlot），
        // 手动塞大件回光标而非自动重排——服务端权威兜底（客户端界面 init 同调）
        ensurePocketGuards(menu, player);

        ItemStack carried = menu.getCarried();
        if (isSlave(carried)) {
            if (carried.hasTag() && carried.getTag().contains(MASTER_SLOT)) {
                int masterId = carried.getTag().getInt(MASTER_SLOT);
                if (masterId >= 0 && masterId < menu.slots.size()) {
                    Slot masterSlot = menu.slots.get(masterId);
                    if (!masterSlot.getItem().isEmpty() && !isSlave(masterSlot.getItem())) {
                        menu.setCarried(masterSlot.getItem()); masterSlot.set(ItemStack.EMPTY);
                    } else menu.setCarried(ItemStack.EMPTY);
                } else menu.setCarried(ItemStack.EMPTY);
            } else menu.setCarried(ItemStack.EMPTY);
        }

        // 性能（2.0.1）：非玩家容器（箱子等）仅在对应菜单打开时整理，避免无谓 Tick 扫描
        boolean menuOpen = !(menu instanceof net.minecraft.world.inventory.InventoryMenu);
        Map<Object, List<Slot>> groups = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            Object container = gridContainerOf(slot);
            if (container instanceof Inventory inv) {
                if (inv == slot.container && slot.getContainerSlot() < 36) {
                    groups.computeIfAbsent(container, k -> new ArrayList<>()).add(slot);
                } else if (inv == slot.container && isSlave(slot.getItem())) {
                    slot.set(ItemStack.EMPTY);
                }
            } else if (menuOpen && isGridContainer(player, container)) {
                groups.computeIfAbsent(container, k -> new ArrayList<>()).add(slot);
            }
        }

        boolean changed = false;
        for (Map.Entry<Object, List<Slot>> e : groups.entrySet()) {
            if (processGrid(player, e.getValue(), e.getKey())) changed = true;
        }
        if (changed) menu.broadcastChanges();
    }

    /** 菜单关闭：清理所有非玩家容器中残留的占位物（存档保持干净，重开自动重建）。 */
    @SubscribeEvent
    public static void onContainerClosed(PlayerContainerEvent.Close event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        IPlayerData data = ManufacturingService.data(player);
        if (data == null) return;
        cleanSlaves(data.getWarehouseHandler());
        cleanSlaves(data.getSafeBoxHandler());
        // 2.0.1：关闭菜单中的原版容器（箱子等）同样清理，防止占位物写入容器 NBT
        for (Slot slot : event.getContainer().slots) {
            Object c = gridContainerOf(slot);
            if (c instanceof Container cc && !(cc instanceof Inventory)) {
                cleanSlaves(cc);
            } else if (c instanceof ItemStackHandler h) {
                cleanSlaves(h);
            }
        }
    }

    private static void cleanSlaves(ItemStackHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (isSlave(handler.getStackInSlot(i))) {
                handler.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
    }

    private static void cleanSlaves(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (isSlave(container.getItem(i))) {
                container.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /** 独立容器整理（无菜单上下文时使用：背包界面安全箱覆盖层点击后立即调用）。 */
    public static void arrange(Player player, ItemStackHandler container) {
        if (container == null || !isGridContainer(player, container)) {
            return;
        }
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < container.getSlots(); i++) {
            slots.add(new SlotItemHandler(container, i, 0, 0));
        }
        processGrid(player, slots, container);
    }

    /**
     * 网格求解（2.0.9 重构，贴合三角联结）：
     *
     * <p>阶段一 预扫描——计算每格物品实际尺寸并按占用面积降序排序（大件优先，
     * 先安放大件可显著减少碎片，降低后续物品放不下的概率）；</p>
     *
     * <p>阶段二 冲突检测——越界/跨越锁定格/重叠/跨界快捷栏/口袋区非 1x1
     * （同类物品自动叠加合并，2.0.1 起足迹内真实占用一并校验，杜绝重叠放置）；</p>
     *
     * <p>阶段三 重排——原方向找空位 -> 旋转 90° 重放 -> 保留原位兜底：
     * 原格可用但暂无空间时不再强制丢弃（等待下轮重排），仅原格不可用
     * （管理员缩格残留）才掉落——大幅降低模式切换（创造->生存）等场景
     * 物品被强制丢弃的频率；</p>
     *
     * <p>阶段四 占位物写入——足迹非主格统一写占位物，孤立占位物清除。</p>
     */
    private static boolean processGrid(Player player, List<Slot> slots, Object container) {
        int size = slots.size();
        int width = gridWidth(player, container);
        int totalRows = (size + width - 1) / width;
        boolean[] usable = new boolean[size];
        for (int i = 0; i < size; i++) {
            usable[i] = isSlotUsable(player, container, slots.get(i).getSlotIndex());
        }
        int[] ownerMap = new int[size]; Arrays.fill(ownerMap, -1);
        boolean changed = false;

        // 性能快路径：无跨格物品且无占位物 -> 无需求解
        boolean needSolve = false;
        for (Slot s : slots) {
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            if (isSlave(st)) { needSolve = true; break; }
            if (!getBaseDim(st).is1x1()) { needSolve = true; break; }
        }
        if (!needSolve) {
            return false;
        }

        // 阶段一：预扫描尺寸 + 大件优先排序（面积降序，面积相同按槽位序保持稳定）
        Integer[] order = new Integer[size];
        ItemDim[] dims = new ItemDim[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
            ItemStack st = slots.get(i).getItem();
            dims[i] = st.isEmpty() || isSlave(st) ? null
                    : getActualDim(st, slots.get(i), false, player);
        }
        final int[] area = new int[size];
        for (int i = 0; i < size; i++) {
            area[i] = dims[i] == null ? 0 : dims[i].w() * dims[i].h();
        }
        Arrays.sort(order, (a, b) -> area[a] != area[b] ? area[b] - area[a] : a - b);

        for (int oi = 0; oi < size; oi++) {
            int i = order[oi];
            Slot slot = slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || isSlave(stack)) continue;

            ItemDim dim = dims[i];
            if (dim == null) {
                // 状态漂移防御：预扫描后本格被更早处理的重排物品占据（原本为空 -> dims null），
                // 按当前物品重算尺寸（崩溃修复：dim.is1x1() 直接使用 null 会 NPE）
                dim = getActualDim(stack, slot, false, player);
                dims[i] = dim;
            }
            int sc = i % width, sr = i / width;
            boolean isOriginHotbar = (slot.container instanceof Inventory) && (slot.getContainerSlot() < 9);
            // 2.0.9：口袋区（快捷栏 GRID/FOOD 格）仅 1x1 留存——大件强制重排至背包区
            // （ANY 格物品经 getActualDim 已折算 1x1 不受影响；背包满时保留原位不丢弃）
            boolean conflict = !usable[i] || (isOriginHotbar && !dim.is1x1())
                    || (sc + dim.w > width || sr + dim.h > totalRows);

            if (!conflict) {
                for (int dx = 0; dx < dim.w; dx++) {
                    for (int dy = 0; dy < dim.h; dy++) {
                        int sid = (sr + dy) * width + (sc + dx);
                        if (sid >= size || !usable[sid]) { conflict = true; break; }
                        if (sid != i) {
                            // 足迹内其他格：已被占用（真实物品或他人占位物）即冲突；
                            // 自己上次求解残留的占位物视为自己的空间
                            ItemStack other = slots.get(sid).getItem();
                            if (ownerMap[sid] != -1) { conflict = true; break; }
                            if (!other.isEmpty() && !isOwnSlave(other, slot.index)) {
                                // 2.0.2：点击物品非左上角（占位物格）放入同类物品 -> 叠加合并到主物品
                                if (ItemStack.isSameItemSameTags(stack, other)
                                        && stack.getCount() < stack.getMaxStackSize()) {
                                    int add = Math.min(other.getCount(), stack.getMaxStackSize() - stack.getCount());
                                    stack.grow(add);
                                    other.shrink(add);
                                    if (other.isEmpty()) {
                                        slots.get(sid).set(ItemStack.EMPTY);
                                    } else {
                                        // 超出堆叠上限的剩余部分留在原格，交由求解器重排
                                        conflict = true;
                                        break;
                                    }
                                    changed = true;
                                } else {
                                    conflict = true;
                                    break;
                                }
                            }
                        }
                        if (((slots.get(sid).container instanceof Inventory) && (slots.get(sid).getContainerSlot() < 9)) != isOriginHotbar) { conflict = true; break; }
                    }
                    if (conflict) break;
                }
            }

            if (conflict) {
                // 2.0.10 重排优化：单遍扫描 + 双方向——每个候选槽位先试原方向、
                // 失败立即试旋转 90°，行优先靠上靠左自然紧凑；
                // 旧实现（先全扫原方向再全扫旋转）在空间紧张时第二遍扫描往往已无可用位，
                // 旋转物品失败率偏高。另：直接移动 stack 引用，省去逐物品 copy 分配。
                boolean moved = false;
                for (int j = 0; j < size && !moved; j++) {
                    ItemDim placeDim = dim;
                    boolean rotated = false;
                    if (!canPlaceItem(player, container, j, placeDim, width, totalRows, size, ownerMap, usable, slots, stack, slot.index)) {
                        ItemDim alt = new ItemDim(dim.h, dim.w);
                        if (alt.w <= 0 || alt.h <= 0
                                || !canPlaceItem(player, container, j, alt, width, totalRows, size, ownerMap, usable, slots, stack, slot.index)) {
                            continue;
                        }
                        placeDim = alt;
                        rotated = true;
                    }
                    ItemStack placed = stack;
                    if (rotated) {
                        // 旋转放置：写标记供渲染层按旋转姿态绘制
                        placed = stack.copy();
                        placed.getOrCreateTag().putBoolean(IS_ROTATED, true);
                    }
                    slots.get(j).set(placed);
                    slot.set(ItemStack.EMPTY);
                    moved = true;
                    int tsc = j % width, tsr = j / width;
                    for (int dx = 0; dx < placeDim.w; dx++) {
                        for (int dy = 0; dy < placeDim.h; dy++) {
                            int sid = (tsr + dy) * width + (tsc + dx);
                            if (sid < size) ownerMap[sid] = j;
                        }
                    }
                }
                if (!moved) {
                    // 2.0.10：重排失败的非法状态（大于 1x1 滞留 1x1 格/原格不可用）
                    // 不再强制丢弃——物品回到鼠标光标；光标已有同类物品则合并，
                    // 异类占满光标时保留原位（绝不静默吞物品）
                    boolean toCursor = !usable[i] || (isOriginHotbar && !dim.is1x1());
                    if (toCursor) {
                        ItemStack carried = player.containerMenu.getCarried();
                        if (carried.isEmpty()) {
                            player.containerMenu.setCarried(stack.copy());
                            slot.set(ItemStack.EMPTY);
                            changed = true;
                        } else if (ItemStack.isSameItemSameTags(carried, stack)
                                && carried.getCount() < carried.getMaxStackSize()) {
                            // 同类合并进光标；余量保留原位待下轮/手动处理
                            int add = Math.min(stack.getCount(), carried.getMaxStackSize() - carried.getCount());
                            carried.grow(add);
                            stack.shrink(add);
                            if (stack.isEmpty()) {
                                slot.set(ItemStack.EMPTY);
                            }
                            changed = true;
                        } else if (!usable[i]) {
                            // 原格物理不可用且光标无法承载：掉落兜底（避免凭空消失）
                            player.drop(stack.copy(), false);
                            slot.set(ItemStack.EMPTY);
                            changed = true;
                        }
                        // 光标异类占用但原格可用：保留原位，等待玩家手动取出
                    }
                    // 常规冲突（越界/暂时无位）：保留原位等待下轮重排
                }
            } else {
                for (int dx = 0; dx < dim.w; dx++) {
                    for (int dy = 0; dy < dim.h; dy++) {
                        int sid = (sr + dy) * width + (sc + dx);
                        if (sid < size) ownerMap[sid] = i;
                    }
                }
            }
        }

        // 阶段四：占位物写入（足迹非主格）与孤立占位物清除
        for (int i = 0; i < size; i++) {
            int mLocal = ownerMap[i]; Slot slot = slots.get(i);
            if (mLocal == -1) {
                if (isSlave(slot.getItem())) { slot.set(ItemStack.EMPTY); changed = true; }
            } else if (mLocal != i) {
                int mGlobal = slots.get(mLocal).index;
                if (!isSlave(slot.getItem()) || slot.getItem().getOrCreateTag().getInt(MASTER_SLOT) != mGlobal) {
                    slot.set(createSlave(mGlobal)); changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * 目标位置可行性：足迹越界/跨越锁定格/重叠（真实物品或他人占位物）即拒绝；
     * 自己旧足迹残留的占位物视为自己的空间（2.0.1，与冲突检测一致）。
     */
    private static boolean canPlaceItem(Player player, Object container, int index, ItemDim dim,
                                        int width, int totalRows, int size, int[] ownerMap, boolean[] usable,
                                        List<Slot> slots, ItemStack stack, int masterIndex) {        // 安全箱 NBT 限制：命中限制规则的物品不可被网格自动放入安全箱
        IPlayerData data = ManufacturingService.data(player);
        if (data != null && container == data.getSafeBoxHandler() && SafeBoxRestrictions.isRestricted(stack)) {
            return false;
        }
        int c = index % width, r = index / width;
        if (c + dim.w > width || r + dim.h > totalRows) return false;
        boolean startHotbar = (slots.get(index).container instanceof Inventory) && (slots.get(index).getContainerSlot() < 9);
        // 2.0.9：非 1x1 物品不落入快捷栏（口袋仅 1x1 留存，避免重排-冲突循环；
        // ANY 格的大件折算 1x1 由玩家手动放置，自动整理始终优先背包区）
        if (startHotbar && !dim.is1x1()) return false;
        for (int dx = 0; dx < dim.w; dx++) {
            for (int dy = 0; dy < dim.h; dy++) {
                int sid = (r + dy) * width + (c + dx);
                if (sid >= size || !usable[sid] || ownerMap[sid] != -1) return false;
                ItemStack other = slots.get(sid).getItem();
                if (!other.isEmpty() && !isOwnSlave(other, masterIndex)) return false;
                if (((slots.get(sid).container instanceof Inventory) && (slots.get(sid).getContainerSlot() < 9)) != startHotbar) return false;
            }
        }
        return true;
    }

    /** 是否为某主格（菜单槽索引）自己的占位物。 */
    private static boolean isOwnSlave(ItemStack stack, int masterIndex) {
        return isSlave(stack) && stack.getOrCreateTag().getInt(MASTER_SLOT) == masterIndex;
    }

    private static ItemStack createSlave(int m) {
        ItemStack s = new ItemStack(GridItems.BLOCKED_SLOT.get());
        s.getOrCreateTag().putBoolean(IS_SLAVE, true); s.getOrCreateTag().putInt(MASTER_SLOT, m);
        return s;
    }

    public static boolean isSlave(ItemStack s) { return !s.isEmpty() && s.hasTag() && s.getTag().getBoolean(IS_SLAVE); }

    // ------------------------------------------------------------------
    // 客户端：超大物品渲染 + 旋转（2.0.7 拆分至 GridClientRendering，
    // 专用服务器不再加载 Screen 等客户端类型，修复 DEDICATED_SERVER 启动崩溃）
    // ------------------------------------------------------------------
}
