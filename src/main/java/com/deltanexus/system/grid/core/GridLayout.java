package com.deltanexus.system.grid.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 网格布局推导（0.3.0Beta 第二阶段）：<b>全模组唯一的几何真相</b>。
 *
 * <p>输入「容器每格内容 + 容器几何（宽度/可用格/口袋分区）」，输出：</p>
 * <ul>
 *   <li>{@link Placement} 列表——哪个格是锚点、占多大、是否旋转、覆盖哪些格；</li>
 *   <li>{@code owner[]}——每格归属哪个锚点（-1 = 空格 / 1x1 退化）；</li>
 *   <li>违规列表——几何非法（越界/跨锁定格/跨分区/口袋区放大件）、逻辑重叠、缺占位物、孤立占位物。</li>
 * </ul>
 *
 * <p>此前这套推导散落在三处（求解器的占用、{@code GridMutation} 的占位物重建、{@code GridIntegrity} 的校验），
 * 任何一处口径不同都会导致「服务端认为合法、客户端认为非法」这类假性复制/重叠。现在统一到这里，
 * 求解、写入、校验、下发客户端布局全部使用同一结果。</p>
 */
public final class GridLayout {

    /** 一个落位物品。 */
    public record Placement(int anchor, int containerIndex, GridDim dim, boolean rotated,
                            int[] cells, boolean degraded) {

        /** 覆盖格数量（>1 表示跨格物品）。 */
        public int span() {
            return cells.length;
        }
    }

    /** 推导结果。 */
    public record Result(List<Placement> placements, int[] owner, List<GridIntegrity.Violation> violations,
                        List<Integer> issueCells) {

        public boolean consistent() {
            return violations.isEmpty();
        }

        public String describe() {
            if (consistent()) {
                return "consistent";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < violations.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(violations.get(i)).append('@').append(i < issueCells.size() ? issueCells.get(i) : -1);
            }
            return sb.toString();
        }
    }

    private GridLayout() {
    }

    /**
     * 推导布局（纯读）。
     *
     * @param cells 与 {@code ctx.size()} 对齐的每格快照（含尺寸与实际旋转姿态）
     */
    public static Result derive(GridContext ctx, List<StackSnapshot> cells) {
        int size = ctx.size();
        int[] owner = new int[size];
        Arrays.fill(owner, -1);
        List<Placement> placements = new ArrayList<>();
        List<GridIntegrity.Violation> violations = new ArrayList<>();
        List<Integer> issueCells = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            StackSnapshot snap = snapshotAt(cells, i);
            if (snap.isEmpty() || snap.isSlave()) {
                continue;
            }
            GridDim dim = snap.dim();
            boolean legal = ctx.inBounds(i, dim) && footprintFree(ctx, cells, i, dim, snap.containerIndex());
            if (!legal) {
                // 退化：按 1x1 处理（保留物品、不写占位物、不覆盖别人），并记一条违规
                boolean geometryBad = !ctx.inBounds(i, dim) || (ctx.hotbarZone(i) && !dim.is1x1());
                violations.add(geometryBad ? GridIntegrity.Violation.ILLEGAL_FOOTPRINT
                        : GridIntegrity.Violation.OVERLAP);
                issueCells.add(i);
                placements.add(new Placement(i, snap.containerIndex(), GridDim.ONE, snap.isRotated(),
                        new int[]{i}, true));
                owner[i] = i;
                continue;
            }
            int[] cellsArr = new int[dim.w() * dim.h()];
            int k = 0;
            for (int dy = 0; dy < dim.h(); dy++) {
                for (int dx = 0; dx < dim.w(); dx++) {
                    int cell = ctx.cellAt(i, dx, dy);
                    cellsArr[k++] = cell;
                    owner[cell] = i;
                }
            }
            placements.add(new Placement(i, snap.containerIndex(), dim, snap.isRotated(), cellsArr, false));
        }

        // 占位物只剩「历史残留」一种身份：0.3.0Beta 第三阶段起不再写入占位物，
        // 足迹内的非主格保持为空；任何残留占位物都视为待清理（ORPHAN_SLAVE → 收敛时清除）。
        for (int i = 0; i < size; i++) {
            StackSnapshot snap = snapshotAt(cells, i);
            int anchor = owner[i];
            if (anchor == -1) {
                if (snap.isSlave()) {
                    violations.add(GridIntegrity.Violation.ORPHAN_SLAVE);
                    issueCells.add(i);
                }
                continue;
            }
            if (anchor == i) {
                continue;
            }
            if (snap.isSlave()) {
                // 残留占位物：清除即可（足迹不再需要它来阻挡原版——菜单层重定向 + 收敛位移负责）
                violations.add(GridIntegrity.Violation.ORPHAN_SLAVE);
                issueCells.add(i);
            }
        }
        return new Result(List.copyOf(placements), owner, List.copyOf(violations), List.copyOf(issueCells));
    }

    /** 足迹是否整体可落位（在界内、可用、分区一致、格子为空或属于自己的旧占位物）。 */
    private static boolean footprintFree(GridContext ctx, List<StackSnapshot> cells, int anchor,
                                         GridDim dim, int containerIndex) {
        int size = ctx.size();
        if (!ctx.inBounds(anchor, dim)) {
            return false;
        }
        boolean zone = ctx.hotbarZone(anchor);
        if (zone && !dim.is1x1()) {
            return false;
        }
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                int cell = ctx.cellAt(anchor, dx, dy);
                if (cell < 0 || cell >= size || !ctx.usable(cell) || ctx.hotbarZone(cell) != zone) {
                    return false;
                }
                if (cell == anchor) {
                    continue;
                }
                StackSnapshot other = snapshotAt(cells, cell);
                if (other.isEmpty() || (other.isSlave() && other.masterIndex() == containerIndex)) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    private static StackSnapshot snapshotAt(List<StackSnapshot> cells, int index) {
        if (cells == null || index < 0 || index >= cells.size() || cells.get(index) == null) {
            return StackSnapshot.empty(index);
        }
        return cells.get(index);
    }
}
