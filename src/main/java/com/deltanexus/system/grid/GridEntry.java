package com.deltanexus.system.grid;

import net.minecraft.world.item.ItemStack;

/**
 * 网格条目——一件物品在网格里的完整身份（0.5.0Beta 新内核）。
 *
 * <p>三件事分开存、各自只有一个真相：</p>
 * <ul>
 *   <li>{@code stack} —— 物品本体（只读引用；写入必须经 {@link #stackForWrite()} 取拷贝，禁止实例共享）；</li>
 *   <li>{@code size} —— <b>已含旋转</b>的实际占用尺寸（宽高已互换），因此渲染与求解都不必再判断姿态；</li>
 *   <li>{@code rotated} —— 逻辑姿态（{@code true} = 相对配置尺寸转了 90°），仅用于存档与再次旋转。</li>
 * </ul>
 *
 * <p>位置不存在条目里：锚点由 {@link GridStore} 的键记录，足迹一律现算（{@link GridStore#footprint(int)}）。
 * 与旧设计相比，这里<b>没有</b>占位物、没有派生标记、没有槽位侧表。</p>
 */
public record GridEntry(ItemStack stack, GridSize size, boolean rotated) {

    public GridEntry {
        if (stack == null) {
            stack = ItemStack.EMPTY;
        }
        if (size == null) {
            size = GridSize.SINGLE;
        }
    }

    /** 按物品尺寸表构造（{@code rotated} 决定是否交换宽高；绝不读取物品 NBT 的旋转键）。 */
    public static GridEntry of(ItemStack stack, boolean rotated) {
        return of(stack, configuredSize(stack), rotated);
    }

    /** 按显式基础尺寸构造（基础尺寸 = 未旋转尺寸）。 */
    public static GridEntry of(ItemStack stack, GridSize baseSize, boolean rotated) {
        GridSize base = baseSize == null ? GridSize.SINGLE : baseSize;
        return new GridEntry(stack, rotated ? base.swapped() : base, rotated);
    }

    /** 物品在尺寸表里的原始尺寸；未配置 = 1x1。 */
    public static GridSize configuredSize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return GridSize.SINGLE;
        }
        return ItemSizeConfig.sizeOf(stack.getItem());
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

    /** 是否 1x1（走最短判定路径）。 */
    public boolean single() {
        return size.single();
    }

    public int area() {
        return size.area();
    }

    /** 写用拷贝（默认数量）；绝不返回内部引用。 */
    public ItemStack stackForWrite() {
        return stack.copy();
    }

    /** 写用拷贝（指定数量）。 */
    public ItemStack stackForWrite(int count) {
        ItemStack copy = stack.copy();
        copy.setCount(Math.max(0, count));
        return copy;
    }

    /** 同种物品且姿态一致（跨姿态不合并，避免宽高互换后堆叠语义含糊）。 */
    public boolean sameKind(GridEntry other) {
        return other != null && !isEmpty() && !other.isEmpty()
                && rotated == other.rotated
                && ItemStack.isSameItemSameTags(stack, other.stack);
    }

    /** 换个数量（保持尺寸与姿态）。 */
    public GridEntry withCount(int count) {
        return new GridEntry(stackForWrite(count), size, rotated);
    }

    /** 切换姿态（宽高互换；同姿态返回自身）。 */
    public GridEntry withRotated(boolean value) {
        if (value == rotated) {
            return this;
        }
        return new GridEntry(stack, size.swapped(), value);
    }

    @Override
    public String toString() {
        return stack.getItem() + "x" + stack.getCount() + " " + size + (rotated ? "(R)" : "");
    }
}