package com.deltanexus.system.menu;

import com.deltanexus.system.client.GearClientState;
import com.deltanexus.system.grid.GearData;
import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.grid.GridSize;
import com.deltanexus.system.grid.GridStore;
import com.deltanexus.system.grid.StoreContainer;
import com.deltanexus.system.init.ModMenus;
import com.deltanexus.system.server.GearService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 装备容器菜单（0.5.0Beta）——胸挂 / 背包的格子容器界面。
 *
 * <p>布局：左上 = 装备网格（每格一个槽位，多格物品的足迹格隐藏）；下方 = 玩家保留区
 * （口袋 5 + 快捷栏 9）；右上 = 盔甲 4 + 副手 1。被装备策略屏蔽的 22 格<b>不进本菜单</b>，
 * 因此不涉及任何隐藏槽位。</p>
 *
 * <p>两侧同构：</p>
 * <ul>
 *   <li><b>服务端</b>：{@link StoreContainer} 直接架在装备物品 NBT 里的 {@link GridStore} 上，
 *       任何写入即写回物品 NBT（玩家数据持久化，无需额外同步槽位）；</li>
 *   <li><b>客户端</b>：影子容器来自 {@link GearClientState}（服务端在开菜单前整份下发几何），
 *       物品内容由原版槽位同步填充。</li>
 * </ul>
 *
 * <p>几何变化（旋转、多格物品落位/取出）后由 {@link #broadcastChanges()} 增量下发
 * {@code SyncGearPacket}，客户端据此重建占位形状。</p>
 */
public class GearMenu extends AbstractContainerMenu {

    /** 格子边长（与原版槽位间距一致）。 */
    public static final int CELL = 18;
    /** 网格左上角（屏幕局部坐标）。 */
    public static final int GRID_X = 8;
    public static final int GRID_Y = 20;
    /** 口袋槽位数（主背包前 5 格 = inv 9..13）。 */
    public static final int POCKETS = 5;

    private final GearKind kind;
    private final StoreContainer grid;
    private final Player player;
    /** 装备网格高度（像素）。 */
    private final int storageHeight;

    /** 玩家保留区起始槽位（= 装备格数量）。 */
    private final int playerSlotStart;

    /** 已下发的几何版本，避免每 Tick 重复下发。 */
    private long sentRevision = -1;

    /** 服务端构造（真实网格：读装备物品 NBT，写回同一物品栈）。 */
    public GearMenu(int id, Inventory inv, GearKind kind) {
        this(id, inv, kind, serverView(inv.player, kind));
    }

    /** 客户端构造（MenuType 供应商调用；几何来自服务端先前下发的 SyncGearPacket）。 */
    public GearMenu(int id, Inventory inv) {
        this(id, inv, GearClientState.lastKind(), GearClientState.container(GearClientState.lastKind()));
    }

    private GearMenu(int id, Inventory inv, GearKind kind, StoreContainer grid) {
        super(ModMenus.GEAR.get(), id);
        this.kind = kind;
        this.grid = grid;
        this.player = inv.player;

        int width = grid.store().width();
        int rows = grid.store().rows();
        int gearSlots = grid.store().size();
        this.storageHeight = rows * CELL;
        int pocketY = GRID_Y + storageHeight + 24;
        int hotbarY = pocketY + 21;

        // 装备网格（下标 = 格号）
        for (int cell = 0; cell < gearSlots; cell++) {
            addSlot(new GearSlot(grid, cell,
                    GRID_X + (cell % width) * CELL, GRID_Y + (cell / width) * CELL));
        }
        // 口袋（inv 9..13）
        for (int i = 0; i < POCKETS; i++) {
            addSlot(new Slot(inv, 9 + i, GRID_X + i * CELL, pocketY));
        }
        // 快捷栏（inv 0..8）
        for (int i = 0; i < 9; i++) {
            addSlot(new Slot(inv, i, GRID_X + i * CELL, hotbarY));
        }

        this.playerSlotStart = gearSlots;
    }

    /** 装备网格占用的高度（像素）；界面的口袋/快捷栏位置由它推出。 */
    public int storageHeight() {
        return storageHeight;
    }

    /** 装备网格行数。 */
    public int rows() {
        return Math.max(1, grid.store().rows());
    }

    public GearKind kind() {
        return kind;
    }

    public int gridCells() {
        return grid.store().size();
    }

    public int gridWidth() {
        return grid.store().width();
    }

    public int gridRows() {
        return grid.store().rows();
    }

    /** 足迹格（被跨格物品覆盖的非锚点格）：屏幕不画底格。 */
    public boolean covered(int cell) {
        return grid.isCovered(cell);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= this.slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean moved = index < playerSlotStart
                ? this.moveItemStackTo(stack, playerSlotStart, this.slots.size(), true)
                : this.moveItemStackTo(stack, 0, playerSlotStart, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == copy.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return copy;
    }

    /** 装备被卸下（或换了物品）时自动关闭，避免玩家对着已经不在身上的容器搬东西。 */
    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide()) {
            return true;
        }
        return !GearService.equipped(player, kind).isEmpty();
    }

    /**
     * 几何变更下发（服务端每 Tick 调用）：只有内核 revision 变了才发包，
     * 因此普通数量变化走原版槽位同步，不额外占用带宽。
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!player.level().isClientSide() && grid.store().revision() != sentRevision) {
            sentRevision = grid.store().revision();
            if (player instanceof ServerPlayer server) {
                GearService.sendSync(server, kind);
            }
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) {
            grid.setChanged();
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 服务端网格：读装备物品 NBT，任何写入写回<b>当前</b>已装备的那一件。
     *
     * <p>0.5.0Beta 修复（「装有东西的背包/胸挂放进容器后拿出来内容全无」）：写回不再使用
     * 构造菜单时捕获的那一份物品栈——菜单存活期间装备可能被卸下、被替换或被搬进容器，
     * 继续写回旧栈会让改动落到一件已经不在身上的副本上（玩家看到的就是「数据没了」）。
     * 每层写回都重新取一次当前装备，装备已卸下时直接放弃写回（内容随玩家手里的原件走）。</p>
     */
    private static StoreContainer serverView(Player player, GearKind kind) {
        ItemStack gear = GearService.equipped(player, kind);
        GridStore store = GearData.read(gear);
        if (store == null) {
            GridSize size = kind.defaultSize();
            store = new GridStore(size.w(), size.h());
        }
        return new StoreContainer(store, changed -> {
            ItemStack live = GearService.equipped(player, kind);
            if (live.isEmpty()) {
                return; // 已卸下：写回没有意义，也绝不能覆盖玩家手里的那一件
            }
            GearData.write(live, changed);
        });
    }
}