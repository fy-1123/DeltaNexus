package com.deltanexus.system.grid;

import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 物品体积工具（2.0.0Alpha）。
 *
 * <p>体积规则：钻石/下界合金 4 格、三叉戟 3 格、剑斧镐铲锄 2 格、其余 1 格；
 * 占位物（blocked_slot）体积为 0。</p>
 */
public class InventoryManager {

    public static int getItemVolume(ItemStack stack) {
        if (stack.isEmpty() || stack.is(GridItems.BLOCKED_SLOT.get())) return 0;

        Item item = stack.getItem();

        // 钻石、下界合金占用 4 格 (额外占用 3 格)
        if (item == Items.DIAMOND || item == Items.NETHERITE_INGOT || item == Items.NETHERITE_SCRAP) return 4;

        // 三叉戟占用 3 格 (额外占用 2 格)
        if (item == Items.TRIDENT) return 3;

        // 剑、斧、镐、铲、锄占用 2 格 (额外占用 1 格)
        if (item instanceof SwordItem || item instanceof AxeItem ||
            item instanceof PickaxeItem || item instanceof ShovelItem ||
            item instanceof HoeItem) return 2;

        return 1; // 默认占 1 格
    }
}
