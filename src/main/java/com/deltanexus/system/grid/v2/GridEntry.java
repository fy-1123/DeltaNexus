package com.deltanexus.system.grid.v2;

import com.deltanexus.system.grid.GridSizes;
import com.deltanexus.system.grid.core.GridDim;
import net.minecraft.world.item.ItemStack;

/**
 * 网格条目（重写版内核，v2）：<b>一件物品在网格中的完整身份</b>。
 *
 * <p>与旧设计的根本区别：占用尺寸与旋转姿态<b>属于条目本身</b>，不属于物品 NBT，
 * 也不存在“占位物”这种派生状态。条目只有锚点一个位置，足迹由 {@link GridInventory} 推导。</p>
 *
 * <ul>
 *   <li>{@code stack} —— 只读引用，写入时必须 {@link #stackForWrite()} 取拷贝（禁止引用共享）；</li>
 *   <li>{@code dim} —— 已含旋转后的实际占用（宽高已交换），因此渲染/求解都不需要再判断旋转；</li>
 *   <li>{@code rotated} —— 逻辑姿态（true 表示相对配置尺寸转了 90°）。</li>
 * </ul>
 */
public record GridEntry(ItemStack stack, GridDim dim, boolean rotated) {

    public GridEntry {
        if (stack == null) {
            stack = ItemStack.EMPTY;
        }
        if (dim == null) {
            dim = GridDim.ONE;
        }
    }

    /** 由物品构造（尺寸取**配置表**原始尺寸，姿态由 {@code rotated} 决定——绝不看物品 NBT 的旋转键）。 */
    public static GridEntry of(ItemStack stack, boolean rotated) {
        GridDim base = configuredDim(stack);
        return new GridEntry(stack, rotated ? base.rotated() : base, rotated);
    }

    /** 配置表里的原始尺寸（未旋转）；未配置 = 1x1。 */
    private static GridDim configuredDim(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return GridDim.ONE;
        }
        var cfg = com.deltanexus.system.grid.ItemSizeConfig.getSize(stack.getItem());
        return cfg == null ? GridDim.ONE : new GridDim(cfg.w(), cfg.h());
    }

    /** 由物品构造（显式指定基础尺寸，供迁移/测试使用）。 */
    public static GridEntry of(ItemStack stack, GridDim baseDim, boolean rotated) {
        GridDim base = baseDim == null ? GridDim.ONE : baseDim;
        return new GridEntry(stack, rotated ? base.rotated() : base, rotated);
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public int count() {
        return stack.getCount();
    }

    public int maxStackSize() {
        return stack.getMaxStackSize();
    }

    /** 取写用拷贝（数量可调）；绝不返回内部引用。 */
    public ItemStack stackForWrite() {
        return stack.copy();
    }

    public ItemStack stackForWrite(int count) {
        ItemStack copy = stack.copy();
        copy.setCount(Math.max(0, count));
        return copy;
    }

    /** 同种（物品 + NBT）且姿态一致——跨姿态不合并。 */
    public boolean sameKind(GridEntry other) {
        return other != null && !other.isEmpty() && !isEmpty()
                && rotated == other.rotated
                && ItemStack.isSameItemSameTags(stack, other.stack);
    }

    /** 换个数量（保持姿态）。 */
    public GridEntry withCount(int count) {
        return new GridEntry(stackForWrite(count), dim, rotated);
    }

    /** 切换姿态（宽高互换）。 */
    public GridEntry withRotated(boolean newRotated) {
        if (newRotated == rotated) {
            return this;
        }
        return new GridEntry(stack, dim.rotated(), newRotated);
    }

    /** 足迹面积。 */
    public int area() {
        return dim.area();
    }

    @Override
    public String toString() {
        return stack.getItem() + "x" + stack.getCount() + " " + dim + (rotated ? "(R)" : "");
    }
}
