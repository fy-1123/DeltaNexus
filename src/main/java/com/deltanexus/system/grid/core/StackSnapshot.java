package com.deltanexus.system.grid.core;

import net.minecraft.world.item.ItemStack;

/**
 * 不可变物品快照（0.3.0Beta）：求解器只读它，绝不用它重建写入。
 *
 * <p>写入一律基于 {@link #prototype()}（原 {@code ItemStack.copy()}），
 * 因此 Capability（能量/流体/自定义数据）在搬运过程中完整保留。</p>
 */
public final class StackSnapshot {

    private final ItemStack stack;
    private final GridDim dim;
    private final int sourceIndex;
    private final int containerIndex;
    private final boolean slave;
    private final int masterIndex;

    private StackSnapshot(ItemStack stack, GridDim dim, int sourceIndex, int containerIndex,
                          boolean slave, int masterIndex) {
        this.stack = stack;
        this.dim = dim;
        this.sourceIndex = sourceIndex;
        this.containerIndex = containerIndex;
        this.slave = slave;
        this.masterIndex = masterIndex;
    }

    /** 空格快照。 */
    public static StackSnapshot empty(int sourceIndex) {
        return new StackSnapshot(ItemStack.EMPTY, GridDim.ONE, sourceIndex, sourceIndex, false, -1);
    }

    /**
     * 构造快照。
     *
     * @param stack       原始槽位物品（内部拷贝，调用方后续改动不影响快照）
     * @param dim         实际占用尺寸（已含旋转与快捷栏规则折算）
     * @param sourceIndex 来源格索引（菜单组内下标或容器索引；写回时据此定位原栈）
     */
    public static StackSnapshot of(ItemStack stack, GridDim dim, int sourceIndex) {
        return of(stack, dim, sourceIndex, sourceIndex);
    }

    /**
     * 构造快照（显式区分「组内下标」与「容器索引」）。
     *
     * @param containerIndex 该格在所属容器中的索引（占位物 master 记录它；仓库视口滚动不影响它）
     */
    public static StackSnapshot of(ItemStack stack, GridDim dim, int sourceIndex, int containerIndex) {
        if (stack == null || stack.isEmpty()) {
            return new StackSnapshot(ItemStack.EMPTY, GridDim.ONE, sourceIndex, containerIndex, false, -1);
        }
        return new StackSnapshot(stack.copy(), dim == null ? GridDim.ONE : dim, sourceIndex, containerIndex,
                GridTags.isSlave(stack), GridTags.masterOf(stack));
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public boolean isSlave() {
        return slave;
    }

    /** 占位物记录的主格索引（非占位物为 -1）。 */
    public int masterIndex() {
        return masterIndex;
    }

    public int sourceIndex() {
        return sourceIndex;
    }

    /** 该格在所属容器中的索引（占位物 master 的记录值；与组内下标区分）。 */
    public int containerIndex() {
        return containerIndex;
    }

    public GridDim dim() {
        return dim;
    }

    public int count() {
        return stack.getCount();
    }

    public int maxStackSize() {
        return stack.getMaxStackSize();
    }

    public boolean isRotated() {
        return GridTags.isRotated(stack);
    }

    /** 只读用途的拷贝（写入前必须再 copy 一次，见 {@link #prototypeWithCount(int)}）。 */
    public ItemStack prototype() {
        return stack.copy();
    }

    /** 指定数量的拷贝（合并/拆分后写回用）。 */
    public ItemStack prototypeWithCount(int count) {
        ItemStack copy = stack.copy();
        copy.setCount(Math.max(0, count));
        return copy;
    }

    /** 同种物品（物品 + NBT 一致）且旋转姿态一致——跨旋转不合并。 */
    public boolean sameKind(StackSnapshot other) {
        if (other == null || other.isEmpty() || isEmpty()) {
            return false;
        }
        return isRotated() == other.isRotated() && ItemStack.isSameItemSameTags(stack, other.stack);
    }

    /** 与给定物品是否同种且旋转一致。 */
    public boolean sameKind(ItemStack other) {
        if (other == null || other.isEmpty() || isEmpty()) {
            return false;
        }
        return isRotated() == GridTags.isRotated(other) && ItemStack.isSameItemSameTags(stack, other);
    }

    @Override
    public String toString() {
        return (slave ? "slave(master=" + masterIndex + ")" : stack.getItem() + "x" + stack.getCount())
                + "@" + sourceIndex + " " + dim;
    }
}
