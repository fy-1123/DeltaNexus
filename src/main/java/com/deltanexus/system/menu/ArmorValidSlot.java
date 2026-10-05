package com.deltanexus.system.menu;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 盔甲槽（从 {@code WarehouseMenu} 提取共用）：只允许对应部位的物品放入。
 * 等价于原版 {@code InventoryMenu} 装甲槽行为（{@link LivingEntity#getEquipmentSlotForItem}）。
 */
public class ArmorValidSlot extends Slot {

    private final EquipmentSlot equipmentSlot;

    public ArmorValidSlot(Inventory inv, int index, int x, int y, EquipmentSlot equipmentSlot) {
        super(inv, index, x, y);
        this.equipmentSlot = equipmentSlot;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return !stack.isEmpty()
                && super.mayPlace(stack)
                && LivingEntity.getEquipmentSlotForItem(stack) == equipmentSlot;
    }
}
