package com.deltanexus.system.grid;

/**
 * 占用尺寸（宽 x 高）——不可变值对象，格子背包的几何唯一来源。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>宽高一律夹在 {@code 1..9}，非法输入退化为 1x1 的分量而不是抛异常（存档容错优先）；</li>
 *   <li>{@link #swapped()} = 旋转 90°（宽高互换），是「旋转」在几何上的全部含义；</li>
 *   <li>不承载物品、位置、姿态——姿态由 {@link GridEntry#rotated()} 记录，位置由锚点格号记录。</li>
 * </ul>
 */
public record GridSize(int w, int h) {

    /** 1x1：未配置尺寸的物品默认占用。 */
    public static final GridSize SINGLE = new GridSize(1, 1);

    public GridSize {
        w = clamp(w);
        h = clamp(h);
    }

    private static int clamp(int v) {
        return Math.max(1, Math.min(9, v));
    }

    public static GridSize of(int w, int h) {
        return new GridSize(w, h);
    }

    /** 旋转 90°（宽高互换）。 */
    public GridSize swapped() {
        return new GridSize(h, w);
    }

    /** 足迹格数。 */
    public int area() {
        return w * h;
    }

    /** 是否为 1x1（1x1 走最短判定路径）。 */
    public boolean single() {
        return w == 1 && h == 1;
    }

    @Override
    public String toString() {
        return w + "x" + h;
    }
}