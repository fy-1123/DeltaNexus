package com.deltanexus.system.grid.core;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 纯函数网格求解器（0.3.0Beta 重写核心）。
 *
 * <p>输入：{@link GridContext}（宽度/可用格/分区/限制）+ 每格 {@link StackSnapshot} + 光标状态；
 * 输出：{@link SolvePlan}。全过程不写入任何槽位、不访问玩家、不发送网络包——可单元测试。</p>
 *
 * <p>求解规则（与旧引擎保持一致，避免行为回退）：</p>
 * <ol>
 *   <li><b>大件优先</b>：按占用面积降序、索引升序稳定排序；</li>
 *   <li><b>冲突检测</b>：越界 / 跨越不可用格 / 重叠（真实物品或他人占位物）/ 跨越口袋分区 /
 *       口袋区非 1x1；足迹内同类 1x1 物品自动合并进该物品；</li>
 *   <li><b>重排</b>：行优先逐个候选位，先试原方向、再试旋转 90°；</li>
 *   <li><b>兜底</b>：原格物理不可用（管理员缩格）或口袋区非法 → 光标/合并光标/掉落；
 *       只是暂时没空间 → 保留原位等待下轮，绝不静默吞物品。</li>
 * </ol>
 */
public final class GridSolver {

    private GridSolver() {
    }

    /** 光标状态（求解兜底时判断物品能否回到光标）。 */
    public record CursorState(boolean empty, StackSnapshot stack, int freeSpace) {

        public static CursorState none() {
            return new CursorState(true, null, 0);
        }

        public static CursorState of(ItemStack carried) {
            if (carried == null || carried.isEmpty()) {
                return none();
            }
            StackSnapshot snap = StackSnapshot.of(carried, GridDim.ONE, -1);
            return new CursorState(false, snap, Math.max(0, carried.getMaxStackSize() - carried.getCount()));
        }

        /** 光标能否接收该物品（同类且未满）。 */
        public boolean accepts(StackSnapshot other) {
            return !empty && stack != null && freeSpace > 0 && stack.sameKind(other);
        }
    }

    /**
     * 全量求解。
     *
     * @param ctx    求解上下文
     * @param cells  与 {@code ctx.size()} 对齐的每格快照（不足视为空格）
     * @param cursor 光标状态（无光标上下文可用 {@link CursorState#none()}）
     */
    public static SolvePlan solve(GridContext ctx, List<StackSnapshot> cells, CursorState cursor) {
        int size = ctx.size();
        int width = ctx.width();
        CursorState cur = cursor != null ? cursor : CursorState.none();

        StackSnapshot[] kinds = new StackSnapshot[size];
        int[] counts = new int[size];
        boolean[] slave = new boolean[size];
        int[] slaveMaster = new int[size];
        for (int i = 0; i < size; i++) {
            StackSnapshot s = cells != null && i < cells.size() && cells.get(i) != null
                    ? cells.get(i) : StackSnapshot.empty(i);
            kinds[i] = s;
            counts[i] = s.isEmpty() ? 0 : s.count();
            slave[i] = s.isSlave();
            slaveMaster[i] = s.masterIndex();
        }

        int[] owner = new int[size];
        Arrays.fill(owner, -1);
        List<SolvePlan.PlanItem> items = new ArrayList<>();
        List<SolvePlan.CountChange> merges = new ArrayList<>();
        List<SolvePlan.Eviction> evictions = new ArrayList<>();
        boolean[] anyChange = {false};

        // 阶段一：大件优先排序（面积降序，索引升序稳定）
        Integer[] order = new Integer[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int areaA = counts[a] > 0 && !slave[a] ? kinds[a].dim().area() : 0;
            int areaB = counts[b] > 0 && !slave[b] ? kinds[b].dim().area() : 0;
            return areaA != areaB ? areaB - areaA : a - b;
        });

        for (int oi = 0; oi < size; oi++) {
            int i = order[oi];
            if (counts[i] <= 0 || slave[i]) {
                continue;
            }
            StackSnapshot me = kinds[i];
            GridDim dim = me.dim();
            boolean originZone = ctx.hotbarZone(i);
            boolean originUsable = ctx.usable(i);
            boolean originForceSmall = originZone && !dim.is1x1();

            // 阶段二：冲突检测（含合并）
            boolean conflict = !originUsable || originForceSmall || !ctx.inBounds(i, dim);
            if (!conflict) {
                for (int dy = 0; dy < dim.h() && !conflict; dy++) {
                    for (int dx = 0; dx < dim.w() && !conflict; dx++) {
                        int cell = ctx.cellAt(i, dx, dy);
                        if (cell >= size || !ctx.usable(cell)) {
                            conflict = true;
                            break;
                        }
                        if (cell == i) {
                            continue;
                        }
                        if (owner[cell] != -1) {
                            conflict = true;
                            break;
                        }
                        if (counts[cell] > 0) {
                            if (slave[cell]) {
                                // 自己的旧占位物 = 自己的空间；他人占位物 = 冲突
                                if (slaveMaster[cell] != me.sourceIndex()) {
                                    conflict = true;
                                    break;
                                }
                            } else if (kinds[cell].dim().is1x1() && counts[i] < me.maxStackSize() && kinds[cell].sameKind(me)) {
                                int add = Math.min(counts[cell], me.maxStackSize() - counts[i]);
                                counts[i] += add;
                                counts[cell] -= add;
                                merges.add(new SolvePlan.CountChange(cell, counts[cell], i));
                                anyChange[0] = true;
                                if (counts[cell] > 0) {
                                    conflict = true;
                                    break;
                                }
                            } else {
                                conflict = true;
                                break;
                            }
                        }
                        if (ctx.hotbarZone(cell) != originZone) {
                            conflict = true;
                            break;
                        }
                    }
                }
            }

            if (!conflict) {
                commit(ctx, owner, items, anyChange, i, i, dim, me.isRotated(), counts[i], me);
                continue;
            }

            // 阶段三：重排（行优先；原位原方向 -> 原位旋转 90°）
            boolean moved = false;
            for (int j = 0; j < size && !moved; j++) {
                if (fits(ctx, owner, counts, kinds, slave, slaveMaster, j, dim, me.sourceIndex(), me)) {
                    commit(ctx, owner, items, anyChange, i, j, dim, me.isRotated(), counts[i], me);
                    moved = true;
                    break;
                }
                GridDim alt = dim.rotated();
                if (!alt.equals(dim) && fits(ctx, owner, counts, kinds, slave, slaveMaster, j, alt, me.sourceIndex(), me)) {
                    commit(ctx, owner, items, anyChange, i, j, alt, !me.isRotated(), counts[i], me);
                    moved = true;
                }
            }
            if (moved) {
                continue;
            }

            // 阶段四：兜底处置（保留原位时不产生建议，等待下轮）
            if (!originUsable || originForceSmall) {
                if (cur.empty()) {
                    evictions.add(new SolvePlan.Eviction(i, SolvePlan.EvictionKind.TO_CURSOR, me));
                    anyChange[0] = true;
                } else if (cur.accepts(me)) {
                    evictions.add(new SolvePlan.Eviction(i, SolvePlan.EvictionKind.MERGE_TO_CURSOR, me));
                    anyChange[0] = true;
                } else if (!originUsable) {
                    evictions.add(new SolvePlan.Eviction(i, SolvePlan.EvictionKind.DROP, me));
                    anyChange[0] = true;
                }
            }
        }

        return new SolvePlan(owner, items, merges, evictions, anyChange[0]);
    }

    /** 把物品登记为「落在 target」并占用其足迹。 */
    private static void commit(GridContext ctx, int[] owner, List<SolvePlan.PlanItem> items, boolean[] anyChange,
                               int source, int target, GridDim dim, boolean rotated, int count, StackSnapshot me) {
        SolvePlan.PlanItem item = new SolvePlan.PlanItem(source, target, dim, rotated, count, me);
        items.add(item);
        if (item.needsWrite()) {
            anyChange[0] = true;
        }
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                int cell = ctx.cellAt(target, dx, dy);
                if (cell >= 0 && cell < ctx.size()) {
                    owner[cell] = target;
                }
            }
        }
    }

    /** 目标位可行性（重排/落位共用）。 */
    private static boolean fits(GridContext ctx, int[] owner, int[] counts, StackSnapshot[] kinds,
                                boolean[] slave, int[] slaveMaster, int index, GridDim dim,
                                int sourceIndex, StackSnapshot moving) {
        if (!ctx.inBounds(index, dim)) {
            return false;
        }
        if (ctx.forbidden(moving.prototype())) {
            return false;
        }
        boolean targetZone = ctx.hotbarZone(index);
        if (targetZone && !dim.is1x1()) {
            return false;
        }
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                int cell = ctx.cellAt(index, dx, dy);
                if (cell < 0 || cell >= ctx.size() || !ctx.usable(cell) || owner[cell] != -1) {
                    return false;
                }
                if (counts[cell] > 0) {
                    boolean ownSlave = slave[cell] && slaveMaster[cell] == sourceIndex;
                    if (!ownSlave) {
                        return false;
                    }
                }
                if (ctx.hotbarZone(cell) != targetZone) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 供「非菜单」入口复用：手动放置 / 交易交付
    // ------------------------------------------------------------------

    /**
     * 指定落点是否可放（不含占位物宽恕：{@code occupied} 由调用方按容器现状计算，
     * 存活占位物视为占用、孤立占位物视为空位）。
     */
    public static boolean canPlaceAt(GridContext ctx, boolean[] occupied, int index, GridDim dim) {
        if (!ctx.inBounds(index, dim)) {
            return false;
        }
        boolean targetZone = ctx.hotbarZone(index);
        if (targetZone && !dim.is1x1()) {
            return false;
        }
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                int cell = ctx.cellAt(index, dx, dy);
                if (cell < 0 || cell >= ctx.size() || !ctx.usable(cell) || occupied[cell]) {
                    return false;
                }
                if (ctx.hotbarZone(cell) != targetZone) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 行优先找零冲突落点（找不到返回 -1）。 */
    public static int firstFreeSpot(GridContext ctx, boolean[] occupied, GridDim dim) {
        for (int j = 0; j < ctx.size(); j++) {
            if (!ctx.usable(j)) {
                continue;
            }
            if (canPlaceAt(ctx, occupied, j, dim)) {
                return j;
            }
        }
        return -1;
    }

    /** 落点确定后把足迹标记为占用（预测与真写入共用）。 */
    public static void markFootprint(GridContext ctx, boolean[] occupied, int index, GridDim dim) {
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                int cell = ctx.cellAt(index, dx, dy);
                if (cell >= 0 && cell < ctx.size()) {
                    occupied[cell] = true;
                }
            }
        }
    }
}
