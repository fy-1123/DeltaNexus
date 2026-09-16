package com.deltanexus.system.grid.core;

import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * 求解上下文（0.3.0Beta）：把一个容器抽象成「宽度 + 可用格 + 分区约束 + 额外限制」。
 *
 * <p>不可变；由适配器（{@code adapter/HandlerGridAdapter}、{@code adapter/MenuGridAdapter}）构造，
 * 求解器只读，不接触任何 Minecraft 可写状态。</p>
 */
public final class GridContext {

    /** 空限制谓词。 */
    public static final Predicate<ItemStack> NO_RESTRICTION = stack -> false;

    private final int width;
    private final int size;
    private final int rows;
    private final UsableMask usable;
    private final boolean[] hotbarZone;
    private final Predicate<ItemStack> restriction;

    private GridContext(int width, int size, UsableMask usable, boolean[] hotbarZone,
                        Predicate<ItemStack> restriction) {
        this.width = Math.max(1, width);
        this.size = Math.max(0, size);
        this.rows = (this.size + this.width - 1) / this.width;
        this.usable = usable != null ? usable : UsableMask.all(this.size);
        this.hotbarZone = hotbarZone != null ? hotbarZone : new boolean[this.size];
        this.restriction = restriction != null ? restriction : NO_RESTRICTION;
    }

    public static Builder builder(int width, int size) {
        return new Builder(width, size);
    }

    public int width() {
        return width;
    }

    public int size() {
        return size;
    }

    public int rows() {
        return rows;
    }

    public UsableMask usable() {
        return usable;
    }

    public boolean usable(int index) {
        return usable.usable(index);
    }

    /**
     * 该格是否属于「口袋/快捷栏分区」：分区内的格只接受 1x1 物品
     * （快捷栏 ANY 规则已由尺寸折算处理），且足迹不得跨分区。
     */
    public boolean hotbarZone(int index) {
        return index >= 0 && index < size && hotbarZone[index];
    }

    /** 该物品是否被容器规则禁止（安全箱 NBT 限制等）。 */
    public boolean forbidden(ItemStack stack) {
        return stack != null && !stack.isEmpty() && restriction.test(stack);
    }

    public boolean inBounds(int index, GridDim dim) {
        if (index < 0 || index >= size || dim == null) {
            return false;
        }
        int col = index % width;
        int row = index / width;
        return col + dim.w() <= width && row + dim.h() <= rows;
    }

    /** 足迹某格索引（pos = 左上角索引）。 */
    public int cellAt(int pos, int dx, int dy) {
        int col = pos % width + dx;
        int row = pos / width + dy;
        return row * width + col;
    }

    public static final class Builder {
        private final int width;
        private final int size;
        private UsableMask usable;
        private boolean[] hotbarZone;
        private Predicate<ItemStack> restriction;

        private Builder(int width, int size) {
            this.width = width;
            this.size = size;
            this.hotbarZone = new boolean[Math.max(0, size)];
        }

        public Builder usable(UsableMask mask) {
            this.usable = mask;
            return this;
        }

        /** 标记分区格（口袋/快捷栏：仅 1x1、足迹不可跨分区）。 */
        public Builder hotbarZone(boolean[] zone) {
            if (zone != null) {
                this.hotbarZone = Arrays.copyOf(zone, Math.max(0, size));
            }
            return this;
        }

        public Builder restriction(Predicate<ItemStack> predicate) {
            this.restriction = predicate;
            return this;
        }

        public GridContext build() {
            return new GridContext(width, size, usable, hotbarZone, restriction);
        }
    }
}
