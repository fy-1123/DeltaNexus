package com.deltanexus.system.grid.core;

/**
 * 网格占用尺寸（格式背包，0.3.0Beta 重写）。
 *
 * <p>纯值对象：只描述「宽 x 高 占几格」，不依赖任何 Minecraft 可写状态，
 * 便于求解器保持纯函数性质（{@link GridSolver}）。</p>
 */
public record GridDim(int w, int h) {

    public static final GridDim ONE = new GridDim(1, 1);

    /** 宽高收敛到合法范围（>=1）。 */
    public GridDim {
        if (w < 1) {
            w = 1;
        }
        if (h < 1) {
            h = 1;
        }
    }

    public boolean is1x1() {
        return w == 1 && h == 1;
    }

    public int area() {
        return w * h;
    }

    /** 旋转 90°（宽高互换）。 */
    public GridDim rotated() {
        return w == h ? this : new GridDim(h, w);
    }

    @Override
    public String toString() {
        return w + "x" + h;
    }
}
