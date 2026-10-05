package com.deltanexus.system.server;

import com.deltanexus.system.grid.GearConfig;
import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.grid.GearNest;
import com.deltanexus.system.grid.GridEntry;
import com.deltanexus.system.grid.GridStore;
import com.deltanexus.system.grid.StoreContainer;
import com.deltanexus.system.grid.adapter.InputGate;
import com.deltanexus.system.network.packet.C2SOverlayClickPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

/**
 * 「非菜单槽」点击的服务端裁决（0.5.0Beta 类塔克夫界面）。
 *
 * <p>处理的都是不在原版槽位表里的位置：内嵌在背包界面中的胸挂/背包网格、
 * 以及容器界面下的盔甲/副手覆盖槽。语义对齐原版槽位点击：</p>
 * <ul>
 *   <li>左键：光标空则整件取起到光标；光标有物则放置（同类叠加，异类交换）；</li>
 *   <li>右键：光标空则取一半；光标有物则放一个；</li>
 *   <li>Shift：整件移入玩家背包（网格）/ 卸到光标（盔甲、副手）。</li>
 * </ul>
 *
 * <p>物品守恒：放置前先问内核 {@code canPlace}，放不下直接拒绝；
 * 取走/写入统一走 {@link StoreContainer}（内核写入口，不静默丢弃）。</p>
 */
public final class GearGridService {

    /** 盔甲位序号 → 装备槽（0=头盔 1=胸甲 2=护腿 3=靴子）。 */
    private static final EquipmentSlot[] ARMOR_ORDER = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private GearGridService() {
    }

    /** 统一入口（网络包调用）。 */
    public static void handleOverlayClick(net.minecraft.server.level.ServerPlayer player,
                                          int target, int index, int button, boolean shift) {
        if (player == null || !PermissionManager.canUseFeatures(player)) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || InputGate.serverSellMode(player)) {
            return;
        }
        switch (target) {
            case C2SOverlayClickPacket.TARGET_RIG ->
                    gearClick(player, menu, GearKind.RIG, index, button, shift);
            case C2SOverlayClickPacket.TARGET_BACKPACK ->
                    gearClick(player, menu, GearKind.BACKPACK, index, button, shift);
            case C2SOverlayClickPacket.TARGET_ARMOR -> armorClick(player, menu, index);
            case C2SOverlayClickPacket.TARGET_OFFHAND -> offhandClick(player, menu);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // 装备网格（胸挂 / 背包）
    // ------------------------------------------------------------------

    private static void gearClick(net.minecraft.server.level.ServerPlayer player, AbstractContainerMenu menu,
                                  GearKind kind, int cell, int button, boolean shift) {
        if (cell < 0) {
            // 装备本体槽（非网格格）：卸下 / 移动
            gearBodyClick(player, menu, kind, button, shift);
            return;
        }
        ItemStack gear = GearService.equipped(player, kind);
        if (gear.isEmpty()) {
            return;
        }
        GridStore store = GearService.store(gear);
        if (store == null || cell < 0 || cell >= store.size()) {
            return;
        }
        // 足迹格统一按锚点解析（客户端已重定向，服务端不信任）
        int anchor = store.anchorAt(cell);
        int targetCell = anchor >= 0 ? anchor : cell;
        StoreContainer box = new StoreContainer(store);
        applyGridClick(player, menu, box, store, targetCell, anchor, button, shift, 1);
        commit(player, kind, gear, store);
    }

    /**
     * 网格点击的统一语义（内嵌装备网格与装备窗口共用）：
     * Shift = 整件移入玩家背包；左键 = 取起 / 放下（同类叠加、异类交换）；右键 = 取半 / 放一。
     *
     * <p>装备嵌套规则由 {@code containerDepth} 裁定（见 {@link GearNest}）：把非空装备或嵌套后
     * 超深的装备放入目标网格一律拒绝；目标网格属于非装备容器时传 1 即可（非装备不受限）。</p>
     *
     * @param containerDepth 目标网格所属装备自身的嵌套深度（已装备容器 = 1）
     */
    public static void applyGridClick(net.minecraft.world.entity.player.Player player, AbstractContainerMenu menu,
                                      StoreContainer box, GridStore store, int cell, int anchor,
                                      int button, boolean shift, int containerDepth) {
        if (shift) {
            if (anchor < 0) {
                return;
            }
            ItemStack taken = box.removeItem(cell, 64);
            if (taken.isEmpty()) {
                return;
            }
            GearService.insertInventory(player, taken, -1);
            if (!taken.isEmpty()) {
                // 背包满：放回网格（绝不丢弃）
                store.insert(GridEntry.of(taken, false));
            }
            return;
        }
        ItemStack carried = menu.getCarried();
        // 嵌套层（套包：胸挂/背包里的胸挂/背包）只接受胸挂/背包本体。普通物品一旦放进去，
        // 这件内层装备就再也取不出来了（嵌套规则要求它内部为空），所以这里直接拒绝。
        if (!carried.isEmpty() && containerDepth > 1 && !GearConfig.isGear(carried)) {
            return;
        }
        // 装备嵌套规则：非空装备 / 超深装备拒绝放入
        if (!carried.isEmpty() && !GearNest.allows(carried, containerDepth)) {
            return;
        }
        if (button == 1) {
            rightClick(box, store, menu, cell, anchor, carried);
        } else {
            leftClick(box, store, menu, cell, anchor, carried);
        }
    }

    /**
     * 装备本体槽点击（菜单里没有该槽，走覆盖槽通道）。
     *
     * <p>右键（光标空）= 卸下到光标；Shift+左键 = 卸到玩家背包（放不下则留在装备槽）。
     * 左键的「打开 / 装备」走 {@code C2SOpenGearPacket}。</p>
     */
    private static void gearBodyClick(net.minecraft.server.level.ServerPlayer player, AbstractContainerMenu menu,
                                      GearKind kind, int button, boolean shift) {
        ItemStack equipped = GearService.equipped(player, kind);
        if (equipped.isEmpty()) {
            return;
        }
        if (shift) {
            ItemStack moving = equipped.copy();
            GearService.insertInventory(player, moving, -1);
            if (moving.isEmpty()) {
                GearService.setEquipped(player, kind, ItemStack.EMPTY);
                GearService.sendSync(player, kind);
            }
            return;
        }
        if (button == 1) {
            if (!menu.getCarried().isEmpty()) {
                return; // 光标有物：不卸下（避免物品丢失）
            }
            GearService.setEquipped(player, kind, ItemStack.EMPTY);
            menu.setCarried(equipped.copy());
            menu.broadcastChanges();
            GearService.sendSync(player, kind);
        }
    }

    private static void leftClick(StoreContainer box, GridStore store, AbstractContainerMenu menu,
                                 int cell, int anchor, ItemStack carried) {
        if (carried.isEmpty()) {
            if (anchor < 0) {
                return;
            }
            ItemStack got = box.removeItem(cell, 64);
            if (!got.isEmpty()) {
                menu.setCarried(got);
            }
            return;
        }
        if (anchor < 0) {
            if (!fits(store, cell, carried)) {
                return;
            }
            box.setItem(cell, carried.copy());
            menu.setCarried(ItemStack.EMPTY);
            return;
        }
        GridEntry entry = store.entryAt(anchor);
        if (entry != null && entry.sameKind(GridEntry.of(carried, false))) {
            int move = Math.min(entry.maxStackSize() - entry.count(), carried.getCount());
            if (move > 0) {
                entry.stack().grow(move);
                carried.shrink(move);
            }
            return;
        }
        // 交换：先把原物取到手上，再放光标物（尺寸不匹配则整体拒绝）
        if (!fits(store, cell, carried)) {
            return;
        }
        ItemStack prev = box.removeItem(cell, 64);
        box.setItem(cell, carried.copy());
        menu.setCarried(prev);
    }

    private static void rightClick(StoreContainer box, GridStore store, AbstractContainerMenu menu,
                                  int cell, int anchor, ItemStack carried) {
        if (carried.isEmpty()) {
            if (anchor < 0) {
                return;
            }
            GridEntry entry = store.entryAt(anchor);
            if (entry == null) {
                return;
            }
            ItemStack got = box.removeItem(cell, (entry.count() + 1) / 2);
            if (!got.isEmpty()) {
                menu.setCarried(got);
            }
            return;
        }
        GridEntry entry = anchor < 0 ? null : store.entryAt(anchor);
        if (entry == null) {
            if (!fits(store, cell, carried)) {
                return;
            }
            box.setItem(cell, carried.copyWithCount(1));
            carried.shrink(1);
            return;
        }
        if (entry.sameKind(GridEntry.of(carried, false)) && entry.count() < entry.maxStackSize()) {
            entry.stack().grow(1);
            carried.shrink(1);
        }
    }

    /** 该格能否容纳这件物品（尺寸 + 占用 + 是否可用格，由内核判定）。 */
    private static boolean fits(GridStore store, int cell, ItemStack stack) {
        return store.canPlace(cell, GridEntry.configuredSize(stack)) == null;
    }

    private static void commit(net.minecraft.server.level.ServerPlayer player, GearKind kind,
                               ItemStack gear, GridStore store) {
        GearService.commit(gear, store);
        GearService.sendSync(player, kind);
    }

    // ------------------------------------------------------------------
    // 盔甲 / 副手（容器界面下的覆盖槽）
    // ------------------------------------------------------------------

    private static void armorClick(net.minecraft.server.level.ServerPlayer player,
                                  AbstractContainerMenu menu, int index) {
        if (index < 0 || index >= ARMOR_ORDER.length) {
            return;
        }
        Inventory inv = player.getInventory();
        // Inventory.armor 下标：0=靴 1=护腿 2=胸甲 3=头盔
        int armorIndex = ARMOR_ORDER.length - 1 - index;
        ItemStack here = inv.armor.get(armorIndex);
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && !canEquip(carried, ARMOR_ORDER[index])) {
            return;
        }
        inv.armor.set(armorIndex, carried);
        menu.setCarried(here);
        inv.setChanged();
        // 容器界面下盔甲不在该菜单的槽位表里：走玩家背包直发通道，否则客户端仍渲染旧装备
        sendInventorySlot(player, 36 + armorIndex, inv.armor.get(armorIndex).copy());
    }

    private static void offhandClick(net.minecraft.server.level.ServerPlayer player, AbstractContainerMenu menu) {
        Inventory inv = player.getInventory();
        ItemStack here = inv.offhand.get(0);
        ItemStack carried = menu.getCarried();
        inv.offhand.set(0, carried);
        menu.setCarried(here);
        inv.setChanged();
        // 容器界面下副手不在该菜单的槽位表里：走玩家背包直发通道
        sendInventorySlot(player, 40, inv.offhand.get(0).copy());
    }

    /**
     * 直发玩家背包槽位（绕开菜单槽位校验）。
     *
     * <p>{@code containerId = -2} 是原版约定：客户端收到后直接
     * {@code player.getInventory().setItem(slot, stack)}，不看当前打开的是哪个菜单。
     * 盔甲 / 副手在容器界面下不属于该菜单的槽位表，容器界面打开时以 {@code InventoryMenu}
     * 的 containerId 发送会被客户端按「非当前菜单」丢弃，只有这条通道能刷新客户端。</p>
     *
     * @param inventoryIndex {@code Inventory} 扁平下标（36–39 = 盔甲，40 = 副手）
     */
    private static void sendInventorySlot(net.minecraft.server.level.ServerPlayer player,
                                         int inventoryIndex, ItemStack stack) {
        player.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, inventoryIndex, stack));
    }

    /** 该物品是否可装备到指定部位（与原版盔甲槽 mayPlace 同口径）。 */
    private static boolean canEquip(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return true;
        }
        if (!(stack.getItem() instanceof ArmorItem armor)) {
            return false;
        }
        return armor.getEquipmentSlot() == slot;
    }
}