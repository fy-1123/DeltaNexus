package com.deltanexus.system.server;

import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.capability.CapabilityAttacher;
import com.deltanexus.system.grid.GearConfig;
import com.deltanexus.system.grid.GearData;
import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.grid.GearNest;
import com.deltanexus.system.grid.GearPolicy;
import com.deltanexus.system.grid.GridEntry;
import com.deltanexus.system.grid.GridNbt;
import com.deltanexus.system.grid.GridSize;
import com.deltanexus.system.grid.GridStore;
import com.deltanexus.system.menu.GearMenu;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.SyncGearPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 装备服务（0.5.0Beta）——胸挂/背包的读写与「物品该去哪儿」的统一裁决。
 *
 * <p>界面与 Mixin 都只调用本类，不各自实现一套搬运逻辑：</p>
 * <ul>
 *   <li>{@link #insertIntoGear} —— 把物品塞进已装备的胸挂/背包（先胸挂、后背包）；</li>
 *   <li>{@link #insertInventory} —— 装备策略下的「原版背包插入」：只在前 {@link GearPolicy#KEEP_SLOTS} 格找位置，
 *       剩下的进装备（Mixin 拦 {@code Inventory.add} 时调用）；</li>
 *   <li>{@link #moveToAllowed} —— 装备策略下的「Shift 快捷移动」：跳过被屏蔽格，溢出进装备。</li>
 * </ul>
 *
 * <p>装备物品本体存放在玩家数据（{@link IPlayerData#getEquipped}），其内容在物品 NBT 内
 * （见 {@link GearData}），因此这里没有「容器实体」，也没有需要额外同步的槽位。</p>
 */
public final class GearService {

    /** 「物品是否自带右键行为」的按类缓存（反射判定结果稳定，缓存避免重复反射）。 */
    private static final Map<Class<?>, Boolean> OWN_USE = new ConcurrentHashMap<>();

    private GearService() {
    }

    // ------------------------------------------------------------------
    // 基础访问
    // ------------------------------------------------------------------

    /** 玩家数据（无能力返回 {@code null}）。 */
    @Nullable
    public static IPlayerData data(Player player) {
        return player == null ? null : player.getCapability(CapabilityAttacher.PLAYER_DATA).orElse(null);
    }

    /** 已装备的指定种类装备（未装备 = 空栈）。 */
    public static ItemStack equipped(Player player, GearKind kind) {
        IPlayerData data = data(player);
        return data == null ? ItemStack.EMPTY : data.getEquipped(kind);
    }

    /** 装备内容的可写快照（非装备返回 {@code null}）。 */
    @Nullable
    public static GridStore store(ItemStack gear) {
        return GearData.read(gear);
    }

    /** 把内容快照写回装备物品 NBT（装备存于玩家数据，因此直接落盘可见）。 */
    public static void commit(ItemStack gear, GridStore store) {
        GearData.write(gear, store);
    }

    // ------------------------------------------------------------------
    // 打开与同步
    // ------------------------------------------------------------------

    /** 打开某种类装备的容器界面（几何先下发再开菜单：同通道有序，客户端构造即可读到）。 */
    public static void openGear(ServerPlayer player, GearKind kind) {
        if (player == null || kind == null || denied(player)) {
            return;
        }
        // 光标持有该种类的装备物品：左键点装备槽 = 装备 / 替换（塔克夫式「拖入装备槽」）
        boolean equippedByCursor = equipFromCursor(player, kind);
        if (equipped(player, kind).isEmpty()) {
            if (!equippedByCursor) {
                player.displayClientMessage(Component.literal("§c未装备" + displayName(kind)), true);
            }
            return;
        }
        sendSync(player, kind);
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new GearMenu(id, inv, kind),
                Component.literal(displayName(kind))));
    }

    /** 设置 / 清空装备（空栈 = 卸下）。 */
    public static boolean setEquipped(ServerPlayer player, GearKind kind, ItemStack stack) {
        IPlayerData data = data(player);
        return data != null && data.setEquipped(kind, stack == null ? ItemStack.EMPTY : stack);
    }

    /**
     * 左键点击装备槽（胸挂 / 背包）的统一裁决（塔克夫式拾取/放下切换）：
     *
     * <ul>
     *   <li>光标空 + 已装备 → <b>卸下到光标</b>；</li>
     *   <li>光标持同类装备 → <b>装上</b>（已装备时需光标只有一件，替换后旧装备回光标）；</li>
     *   <li>光标持同类但已装备且多件、或光标持非同类物品 → 不动作。</li>
     * </ul>
     */
    public static boolean equipFromCursor(ServerPlayer player, GearKind kind) {
        if (player == null || kind == null || denied(player)) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return false;
        }
        IPlayerData data = data(player);
        if (data == null) {
            return false;
        }
        ItemStack carried = menu.getCarried();
        ItemStack prev = data.getEquipped(kind);

        // 分支一：光标空 → 卸下到光标（空槽时空操作）
        if (carried.isEmpty()) {
            if (prev.isEmpty()) {
                return false;
            }
            if (!data.setEquipped(kind, ItemStack.EMPTY)) {
                return false;
            }
            menu.setCarried(prev.copy());
            menu.broadcastChanges();
            sendSync(player, kind);
            return true;
        }

        // 分支二：光标持同类 → 装上
        if (GearConfig.kindOf(carried.getItem()) != kind) {
            return false;
        }
        if (!prev.isEmpty() && carried.getCount() != 1) {
            return false; // 已有装备且光标多件：拒绝（避免旧装备无处安放）
        }
        if (!data.setEquipped(kind, carried.copyWithCount(1))) {
            return false;
        }
        carried.shrink(1);
        menu.setCarried(prev.isEmpty()
                ? (carried.isEmpty() ? ItemStack.EMPTY : carried)
                : prev.copy()); // 替换：旧装备回到光标
        menu.broadcastChanges();
        sendSync(player, kind);
        return true;
    }

    /** 装备容器权限闸门（功能硬开关 + 装备容器权限，与仓库/安全箱同构）；被拒时已发送提示。 */
    private static boolean denied(ServerPlayer player) {
        if (!PermissionManager.canUseFeatures(player)) {
            player.displayClientMessage(Component.translatable("msg.dn.feature.disabled"), true);
            return true;
        }
        if (!PermissionManager.canOpenGear(player)) {
            player.displayClientMessage(Component.translatable("msg.dn.perm.denied.gear"), true);
            return true;
        }
        return false;
    }

    /** 下发装备几何（打开前一次，之后每次几何变化一次；数量变化仍走原版槽位同步）。 */
    public static void sendSync(ServerPlayer player, GearKind kind) {
        ItemStack gear = equipped(player, kind);
        GridSize size = GearData.sizeOf(gear);
        if (size == null) {
            size = kind.defaultSize();
        }
        GridStore store = GearData.read(gear);
        if (store == null) {
            store = new GridStore(size.w(), size.h());
        }
        PacketHandler.sendToPlayer(player, new SyncGearPacket(
                kind, size.w(), size.h(), GridNbt.write(store), gear));
    }

    /** 给玩家两种装备各下发一次（登录时用：初始化/清空客户端缓存）。 */
    public static void sendAllSync(ServerPlayer player) {
        if (player == null) {
            return;
        }
        for (GearKind kind : GearKind.values()) {
            sendSync(player, kind);
        }
    }

    /**
     * 右键装备物品：该部位为空则装备（0.5.0Beta 起<b>不再打开独立装备界面</b>——
     * 装备内容通过仓库 / 背包 / 容器 UI 的中列网格查看）。
     *
     * <p>调用前须已确认物品<b>自身没有右键行为</b>（见 {@link #hasOwnUse}），否则会抢掉
     * 吃喝/弓/桶等原版玩法。</p>
     *
     * @return 是否已接管本次右键
     */
    public static boolean equip(Player player, ItemStack held, InteractionHand hand) {
        if (!(player instanceof ServerPlayer server) || held == null || held.isEmpty()) {
            return false;
        }
        GearKind kind = GearConfig.kindOf(held.getItem());
        if (kind == null) {
            return false;
        }
        // 权限不足时不消耗手持物品（装备后无法查看反而更难恢复）
        if (denied(server)) {
            return false;
        }
        IPlayerData data = data(player);
        if (data == null) {
            return false;
        }
        if (!data.getEquipped(kind).isEmpty()) {
            server.displayClientMessage(
                    Component.literal("§c" + displayName(kind) + "部位已装备物品，请先从装备槽卸下"), true);
            return false;
        }
        ItemStack equipped = held.copy();
        if (!data.setEquipped(kind, equipped)) {
            return false;
        }
        player.setItemInHand(hand, ItemStack.EMPTY);
        // 客户端刷新装备槽图标（原来由 openGear 内的 sendSync 负责）
        sendSync(server, kind);
        return true;
    }

    /** 中文名（界面标题与提示用；与物品显示名解耦，语言键阶段统一收敛）。 */
    public static String displayName(GearKind kind) {
        return kind == GearKind.RIG ? "胸挂" : "背包";
    }

    /**
     * 物品是否自带右键行为：食物/药水、可蓄力（弓/三叉戟/望远镜）、桶，
     * 或自行覆写了 {@code use} 的物品（反射判定，按类缓存）。
     *
     * <p>自带则不接管右键——用户明确要求「装备物品本身有右键行为时只能从装备槽打开」。</p>
     */
    public static boolean hasOwnUse(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        Item item = stack.getItem();
        if (item.isEdible() || stack.getUseDuration() > 0
                || item instanceof net.minecraft.world.item.BucketItem) {
            return true;
        }
        return OWN_USE.computeIfAbsent(item.getClass(), GearService::declaresUse);
    }

    /** 该物品类（含父类，止于 {@link Item}）是否自己声明了 {@code use}。 */
    private static boolean declaresUse(Class<?> type) {
        for (Class<?> c = type; c != null && c != Item.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod("use", Level.class, Player.class, InteractionHand.class);
                return true;
            } catch (NoSuchMethodException ignored) {
                // 继续向父类查找
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 物品去处
    // ------------------------------------------------------------------

    /**
     * 把物品塞进已装备的胸挂/背包（先胸挂、后背包）。
     *
     * <p><b>装备嵌套规则</b>（见 {@link GearNest}）：装备物品只有在<b>内部为空</b>且嵌套后
     * 深度不超过 {@link GearNest#MAX_DEPTH} 时才允许被塞进装备；否则原样返回。
     * 已装备容器的自身深度为 1。</p>
     *
     * @return 未能放入的剩余数量（0 = 全部放入）
     */
    public static int insertIntoGear(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        if (!GearNest.allows(stack, 1)) {
            return stack.getCount();
        }
        IPlayerData data = data(player);
        if (data == null) {
            return stack.getCount();
        }
        int rest = stack.getCount();
        for (GearKind kind : GearKind.values()) {
            if (rest <= 0) {
                break;
            }
            ItemStack gear = data.getEquipped(kind);
            if (gear.isEmpty()) {
                continue;
            }
            GridStore store = GearData.read(gear);
            if (store == null) {
                continue;
            }
            ItemStack probe = stack.copy();
            probe.setCount(rest);
            rest = store.insert(GridEntry.of(probe, false));
            GearData.write(gear, store);
        }
        return rest;
    }

    /**
     * 装备策略下的原版背包插入（Mixin 拦 {@code Inventory.add} 时调用）。
     *
     * <p>顺序：合并同类 → 首选位 → 空位（都只在前 {@link GearPolicy#KEEP_SLOTS} 格内）→ 剩下的进装备。
     * 全部放不下时返回 false，物品留在原栈里（<b>绝不丢弃</b>）。</p>
     */
    public static boolean insertInventory(Player player, ItemStack stack, int preferred) {
        if (player == null || stack == null || stack.isEmpty()) {
            return false;
        }
        Inventory inventory = player.getInventory();
        List<ItemStack> items = inventory.items;
        int before = stack.getCount();
        int keep = Math.min(GearPolicy.KEEP_SLOTS, items.size());

        for (int i = 0; i < keep && !stack.isEmpty(); i++) {
            if (!inventorySlotAccepts(i, stack)) {
                continue;
            }
            mergeInto(items.get(i), stack);
        }
        if (preferred >= 0 && preferred < keep && inventorySlotAccepts(preferred, stack)) {
            putInto(items, preferred, stack);
        }
        for (int i = 0; i < keep && !stack.isEmpty(); i++) {
            if (!inventorySlotAccepts(i, stack)) {
                continue;
            }
            putInto(items, i, stack);
        }
        if (!stack.isEmpty()) {
            stack.setCount(insertIntoGear(player, stack));
        }
        if (stack.getCount() != before) {
            inventory.setChanged();
            return true;
        }
        return false;
    }

    /**
     * 原版背包某格是否接收该物品（<b>口袋区只收 1x1 普通物品</b>）。
     *
     * <p>口袋 = {@code Inventory.items} 下标 9..13（主背包前 5 格）；装备（胸挂/背包）与大于 1x1
     * 的物品一律跳过这些格子，改由快捷栏或已装备的胸挂/背包接收。</p>
     */
    public static boolean inventorySlotAccepts(int inventoryIndex, ItemStack stack) {
        if (inventoryIndex < 9 || inventoryIndex > 13) {
            return true; // 快捷栏与其他格不受口袋规则限制
        }
        return com.deltanexus.system.grid.GridSizes.pocketAccepts(stack);
    }

    /** 目标区间内是否存在被屏蔽的槽位（决定是否需要接管快速移动）。 */
    public static boolean hasBlockedInRange(AbstractContainerMenu menu, int start, int end) {
        if (menu == null) {
            return false;
        }
        for (int i = Math.max(0, start); i < Math.min(end, menu.slots.size()); i++) {
            if (GearPolicy.isBlockedSlot(menu.slots.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** 某个槽位里物品对应的玩家（用于判定是否受装备策略限制）。 */
    @Nullable
    public static Player playerOf(Slot slot) {
        return slot != null && slot.container instanceof Inventory inventory ? inventory.player : null;
    }

    /**
     * 装备策略下的 Shift 快捷移动：跳过被屏蔽格，先合并再放空位；目的地是「玩家背包区」时，
     * 溢出部分继续塞进已装备的胸挂/背包。
     *
     * @return 是否搬动了物品
     */
    public static boolean moveToAllowed(AbstractContainerMenu menu, ItemStack stack, int start, int end,
                                        boolean reverse) {
        if (menu == null || stack == null || stack.isEmpty()) {
            return false;
        }
        int before = stack.getCount();
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            for (int j = 0; j < end - start && !stack.isEmpty(); j++) {
                Slot slot = menu.slots.get(reverse ? end - 1 - j : start + j);
                if (GearPolicy.isBlockedSlot(slot) || !slot.mayPlace(stack)) {
                    continue;
                }
                ItemStack current = slot.getItem();
                if (pass == 0) {
                    if (current.isEmpty() || current == stack || !ItemStack.isSameItemSameTags(current, stack)) {
                        continue;
                    }
                    int space = Math.min(slot.getMaxStackSize(stack), current.getMaxStackSize()) - current.getCount();
                    if (space <= 0) {
                        continue;
                    }
                    int move = Math.min(stack.getCount(), space);
                    // 必须用 slot.set 写回，不能就地 grow：
                    // SlotItemHandler（仓库视口 / 安全箱）的 getItem() 返回的是**拷贝**，
                    // 就地 grow 只改到那份拷贝上，而源栈照样 shrink —— 物品会凭空减少。
                    ItemStack grown = current.copy();
                    grown.grow(move);
                    slot.set(grown);
                    ItemStack after = slot.getItem();
                    int landed = (after.isEmpty() || !ItemStack.isSameItemSameTags(after, stack))
                            ? 0 : after.getCount() - current.getCount();
                    if (landed > 0) {
                        stack.shrink(landed);
                    }
                    slot.setChanged();
                } else {
                    if (!current.isEmpty()) {
                        continue;
                    }
                    int move = Math.min(stack.getCount(), slot.getMaxStackSize(stack));
                    if (move <= 0) {
                        continue;
                    }
                    slot.set(stack.split(move));
                    slot.setChanged();
                }
            }
        }
        // 目的地是玩家背包区且物品没搬完 → 溢出进装备（按嵌套规则：仅空装备可嵌套）
        if (!stack.isEmpty() && targetsPlayerInventory(menu, start, end) && GearNest.allows(stack, 1)) {
            Player player = firstPlayerIn(menu, start, end);
            if (player != null && GearPolicy.restricted(player)) {
                stack.setCount(insertIntoGear(player, stack));
            }
        }
        return stack.getCount() != before;
    }

    /** 区间内第一个空闲的玩家背包槽位（不含被屏蔽格；-1 = 无）。 */
    public static int firstFreeSlot(Player player) {
        if (player == null) {
            return -1;
        }
        List<ItemStack> items = player.getInventory().items;
        int keep = Math.min(GearPolicy.KEEP_SLOTS, items.size());
        for (int i = 0; i < keep; i++) {
            if (items.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 区间内第一个可继续堆叠的玩家背包槽位（-1 = 无）。 */
    public static int firstMergeSlot(Player player, ItemStack input) {
        if (player == null || input == null || input.isEmpty()) {
            return -1;
        }
        List<ItemStack> items = player.getInventory().items;
        int keep = Math.min(GearPolicy.KEEP_SLOTS, items.size());
        for (int i = 0; i < keep; i++) {
            if (!inventorySlotAccepts(i, input)) {
                continue; // 口袋只收 1x1 普通物品：大件/装备不在这里合并
            }
            ItemStack target = items.get(i);
            if (target.isEmpty() || target == input || !ItemStack.isSameItemSameTags(target, input)) {
                continue;
            }
            if (target.getCount() < Math.min(target.getMaxStackSize(), 64)) {
                return i;
            }
        }
        return -1;
    }

    /** 目标区间是否落在玩家背包上（决定溢出是否进装备）。 */
    private static boolean targetsPlayerInventory(AbstractContainerMenu menu, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(end, menu.slots.size()); i++) {
            if (menu.slots.get(i).container instanceof Inventory) {
                return true;
            }
        }
        return false;
    }

    /** 区间内第一个物品归属的玩家（用于取玩家数据）。 */
    @Nullable
    private static Player firstPlayerIn(AbstractContainerMenu menu, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(end, menu.slots.size()); i++) {
            Player player = playerOf(menu.slots.get(i));
            if (player != null) {
                return player;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 与目标槽合并同类（不改槽位类型，只加数量）。 */
    private static void mergeInto(ItemStack target, ItemStack input) {
        if (target.isEmpty() || input.isEmpty() || target == input
                || !ItemStack.isSameItemSameTags(target, input)) {
            return;
        }
        int space = Math.min(target.getMaxStackSize(), 64) - target.getCount();
        if (space <= 0) {
            return;
        }
        int move = Math.min(input.getCount(), space);
        target.grow(move);
        input.shrink(move);
    }

    /** 把物品放进指定下标（空位整栈 / 同类合并）。 */
    private static void putInto(List<ItemStack> items, int index, ItemStack input) {
        if (input.isEmpty() || index < 0 || index >= items.size()) {
            return;
        }
        ItemStack target = items.get(index);
        if (target == input) {
            return;
        }
        if (target.isEmpty()) {
            int move = Math.min(input.getCount(), Math.min(input.getMaxStackSize(), 64));
            ItemStack placed = input.copy();
            placed.setCount(move);
            items.set(index, placed);
            input.shrink(move);
        } else {
            mergeInto(target, input);
        }
    }
}