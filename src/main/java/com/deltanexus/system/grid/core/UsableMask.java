package com.deltanexus.system.grid.core;

import java.util.Arrays;

/**
 * 可用格掩码（0.3.0Beta）：仓库未解锁槽位 / 安全箱未解锁格在求解中视为「不存在」——
 * 既不可落位，也不可被跨格物品的足迹跨越。
 *
 * <p>不可变值对象；构造时复制入参，绝不持有外部数组引用。</p>
 */
public final class UsableMask {

    private final int size;
    private final boolean[] usable;

    public UsableMask(int size, boolean[] usable) {
        this.size = Math.max(0, size);
        this.usable = new boolean[this.size];
        if (usable != null) {
            System.arraycopy(usable, 0, this.usable, 0, Math.min(usable.length, this.size));
        }
    }

    public static UsableMask all(int size) {
        boolean[] all = new boolean[Math.max(0, size)];
        Arrays.fill(all, true);
        return new UsableMask(size, all);
    }

    public int size() {
        return size;
    }

    public boolean usable(int index) {
        return index >= 0 && index < size && usable[index];
    }

    /** 以左上角 (row, col) + 尺寸检查整块足迹是否全部可用（不做越界以外的判断）。 */
    public boolean usableRegion(int row, int col, GridDim dim, int width, int rows) {
        if (col < 0 || row < 0 || col + dim.w() > width || row + dim.h() > rows) {
            return false;
        }
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                if (!usable(row + dy, col + dx, width)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean usable(int row, int col, int width) {
        return usable(row * width + col);
    }

    public boolean[] copy() {
        return Arrays.copyOf(usable, size);
    }
}
