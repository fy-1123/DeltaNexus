package com.deltanexus.system.grid.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 格式背包 NBT 约定（0.3.0Beta 重写后唯一权威定义）。
 *
 * <p>键名与旧版保持兼容：</p>
 * <ul>
 *   <li>{@code deltanexus.is_slave} + {@code deltanexus.master_slot}：占位物标记与主格菜单槽位索引；</li>
 *   <li>{@code deltanexus.grid.rotated}：旋转标记（新版键）；
 *       {@code deltanexus.is_rotated} 为旧键，读取时永久兼容（写入只写新键）。</li>
 * </ul>
 *
 * <p>{@link #stripGridKeys(CompoundTag)} 用于对外比较（交易匹配 / 管道过滤 / 等价判断）——
 * 网格内部标记不应影响“这两个物品是不是同一种东西”的判定。</p>
 */
public final class GridTags {

    /** 占位物标记。 */
    public static final String IS_SLAVE = "deltanexus.is_slave";
    /** 占位物记录的主格（菜单槽位索引）。 */
    public static final String MASTER_SLOT = "deltanexus.master_slot";
    /** 旋转标记（新版）。 */
    public static final String ROTATED = "deltanexus.grid.rotated";
    /** 旋转标记（旧版，只读兼容）。 */
    public static final String ROTATED_LEGACY = "deltanexus.is_rotated";
    /** 网格私有键前缀（剥离用）。 */
    public static final String PREFIX = "deltanexus.";

    private GridTags() {
    }

    /** 是否占位物。 */
    public static boolean isSlave(ItemStack stack) {
        return !stack.isEmpty() && stack.hasTag() && stack.getTag().getBoolean(IS_SLAVE);
    }

    /** 占位物记录的主格索引（无则 -1）。 */
    public static int masterOf(ItemStack stack) {
        if (!isSlave(stack)) {
            return -1;
        }
        return stack.getTag().getInt(MASTER_SLOT);
    }

    /** 是否为某主格自己的占位物。 */
    public static boolean isOwnSlave(ItemStack stack, int masterIndex) {
        return isSlave(stack) && masterOf(stack) == masterIndex;
    }

    /** 是否处于旋转姿态（新旧键都认）。 */
    public static boolean isRotated(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag.getBoolean(ROTATED) || tag.getBoolean(ROTATED_LEGACY);
    }

    /** 写入旋转标记（清除旧键，保证只有一个真相）。 */
    public static void setRotated(ItemStack stack, boolean rotated) {
        if (rotated) {
            CompoundTag tag = stack.getOrCreateTag();
            tag.putBoolean(ROTATED, true);
            tag.remove(ROTATED_LEGACY);
        } else if (stack.hasTag()) {
            stack.getTag().remove(ROTATED);
            stack.getTag().remove(ROTATED_LEGACY);
        }
    }

    /**
     * 生成占位物（主格 = 菜单槽位索引）。
     *
     * <p>占位物物品经注册表按 id 查找（core 不直接依赖 {@code GridItems}，保持内核纯净；
     * 注册表未就绪时返回空栈，调用方据此跳过本次写入）。</p>
     */
    public static ItemStack createSlave(int masterMenuSlot) {
        Item item = ForgeRegistries.ITEMS.getValue(SLAVE_ITEM_ID);
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemStack slave = new ItemStack(item);
        CompoundTag tag = slave.getOrCreateTag();
        tag.putBoolean(IS_SLAVE, true);
        tag.putInt(MASTER_SLOT, masterMenuSlot);
        return slave;
    }

    /** 占位物物品 id（{@code GridItems.BLOCKED_SLOT} 注册名）。 */
    public static final ResourceLocation SLAVE_ITEM_ID =
            ResourceLocation.fromNamespaceAndPath("deltanexus", "blocked_slot");

    /**
     * 剥离网格私有键的副本（对外比较用）。
     *
     * @return 原物品的拷贝；若不含网格键则原样拷贝（绝不修改入参）
     */
    public static ItemStack stripped(ItemStack stack) {
        ItemStack copy = stack.copy();
        stripGridKeys(copy.getTag());
        return copy;
    }

    /** 就地剥离网格私有键（{@code tag} 可为 null；剥离后为空标签则移除）。 */
    public static void stripGridKeys(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        tag.remove(IS_SLAVE);
        tag.remove(MASTER_SLOT);
        tag.remove(ROTATED);
        tag.remove(ROTATED_LEGACY);
    }
}
