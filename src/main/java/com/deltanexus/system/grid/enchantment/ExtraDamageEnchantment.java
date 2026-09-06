package com.deltanexus.system.grid.enchantment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/** 固定打击：武器固定 +2.0 * level 伤害（1 级）。 */
public class ExtraDamageEnchantment extends Enchantment {
    public ExtraDamageEnchantment() {
        super(
            Rarity.COMMON,
            EnchantmentCategory.WEAPON,
            new EquipmentSlot[]{EquipmentSlot.MAINHAND}
        );
    }

    @Override
    public int getMinLevel() {
        return 1;
    }

    @Override
    public int getMaxLevel() {
        return 1;
    }

    @Override
    public int getMinCost(int level) {
        return 15;
    }

    @Override
    public int getMaxCost(int level) {
        return 65;
    }

    @Override
    public float getDamageBonus(int level, MobType mobType) {
        return 2.0f * level;
    }
}
