package com.deltanexus.system.grid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

/**
 * 充能核心能量工具（2.0.0Alpha；NBT 键改为 deltanexus 命名空间）。
 */
public class OverdriveUtils {
    public static final String ENERGY_KEY = "deltanexus.energy";
    public static final String SHIELD_CD_KEY = "deltanexus.shield_cd";
    public static final String RECHARGE_CD_KEY = "deltanexus.recharge_cd";

    public static float getEnergy(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains(ENERGY_KEY)) {
            return stack.getTag().getFloat(ENERGY_KEY);
        }
        return 0f;
    }

    public static void setEnergy(ItemStack stack, float energy) {
        stack.getOrCreateTag().putFloat(ENERGY_KEY, Math.max(0, energy));
    }

    public static int getMaxEnergy(ItemStack stack, int level) {
        if (stack.getItem() instanceof ArmorItem) {
            return level * 15; // 护甲每级上限 15
        }
        return level * 125;
    }

    public static long getLastShieldTime(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getLong(SHIELD_CD_KEY) : 0L;
    }

    public static void setLastShieldTime(ItemStack stack, long time) {
        stack.getOrCreateTag().putLong(SHIELD_CD_KEY, time);
    }

    public static long getLastRechargeTime(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getLong(RECHARGE_CD_KEY) : 0L;
    }

    public static void setLastRechargeTime(ItemStack stack, long time) {
        stack.getOrCreateTag().putLong(RECHARGE_CD_KEY, time);
    }
}
