package com.deltanexus.system.menu;

import com.deltanexus.system.grid.GearNest;
import com.deltanexus.system.grid.GridEntry;
import com.deltanexus.system.grid.StoreContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 装备网格槽位（0.5.0Beta）——一个菜单槽位对应网格里的一格。
 *
 * <p>三种格：</p>
 * <ul>
 *   <li><b>锚点格</b>：物品本体所在格，可拿可取；</li>
 *   <li><b>空地格</b>：没有物品，可落位（受尺寸与解锁限制）；</li>
 *   <li><b>足迹格</b>：被其它锚点的多格物品覆盖的格——不渲染、不可交互
 *       （{@link #isActive()} false），因为没有物品挂在这一格上。</li>
 * </ul>
 *
 * <p>放置一律先问内核「腾空这一格后放得下吗」（{@link GridStore#canPlaceAfterRemoving}），
 * 放不下直接拒绝、物品留在光标，不做自动重排。装备物品按嵌套规则放行：仅内部为空的装备可放入
 * （见 {@link GearNest}）。</p>
 */
public class GearSlot extends Slot {

    private final StoreContainer grid;

    public GearSlot(StoreContainer grid, int cell, int x, int y) {
        super(grid, cell, x, y);
        this.grid = grid;
    }

    private int cell() {
        return getSlotIndex();
    }

    @Override
    public boolean isActive() {
        return !grid.isCovered(cell());
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        if (stack == null || stack.isEmpty() || grid.isCovered(cell())) {
            return false;
        }
        // 装备嵌套规则：仅「内部为空」的装备可放入（本菜单为已装备容器，自身深度 1）
        if (!GearNest.allows(stack, 1)) {
            return false;
        }
        return grid.store().canPlaceAfterRemoving(cell(), GridEntry.configuredSize(stack));
    }

    @Override
    public boolean mayPickup(Player player) {
        return grid.isAnchor(cell());
    }
}