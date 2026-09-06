package com.deltanexus.system.grid.enchantment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/** 充能核心：红石充能工具/护甲（能量机制见 {@code OverdriveUtils}）。 */
public class RedstoneOverdriveEnchantment extends Enchantment {
    public RedstoneOverdriveEnchantment() {
        super(
            Rarity.RARE,
            EnchantmentCategory.BREAKABLE, // 允许附魔在耐久度物品上(工具/武器/盔甲)
            EquipmentSlot.values() // 允许所有槽位
        );
    }

    @Override
    public int getMinLevel() {
        return 1;
    }

    @Override
    public int getMaxLevel() {
        return 5;
    }

    @Override
    public int getMinCost(int level) {
        return 10 + 20 * (level - 1);
    }

    @Override
    public int getMaxCost(int level) {
        return super.getMinCost(level) + 50;
    }
}
