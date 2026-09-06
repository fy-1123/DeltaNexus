package com.deltanexus.system.menu;

import com.deltanexus.system.grid.InventoryGridHandler;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 网格感知槽位（2.0.10）：手动放入前做尺寸预校验（{@code fitsManualPlacement}），
 * 放不下的物品（如大于 1x1 塞入口袋区）直接拒绝、物品回到鼠标指针，
 * 而非落到槽位后被 processGrid 自动重排到别处。
 *
 * <p>用于仓库菜单的快捷栏槽位，以及原版背包（InventoryMenu）的口袋槽位（服务端补丁）。</p>
 */
public class GridAwareSlot extends Slot {

    private final Player player;

    public GridAwareSlot(Inventory inv, int index, int x, int y, Player player) {
        super(inv, index, x, y);
        this.player = player;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return super.mayPlace(stack)
                && InventoryGridHandler.fitsManualPlacement(player, this, stack);
    }
}