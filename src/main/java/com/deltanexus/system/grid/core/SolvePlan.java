package com.deltanexus.system.grid.core;

import java.util.List;

/**
 * 求解计划（0.3.0Beta）：纯数据，描述「物品最终落在哪、谁占哪些格、哪些东西出不来」。
 *
 * <p>求解器只产出它，真正的写入由 {@link GridMutation} 执行——求解过程不触碰任何槽位。</p>
 *
 * @param owner      每格归属：-1 = 无归属（空格，或"保留原位待下轮"的物品原格）；否则为落位物品的主格索引
 * @param items      落位物品清单（含未移动的物品，便于统一校验）
 * @param merges     合并导致的来源格数量变更（数量 0 = 清空该格）
 * @param evictions  无法安放者的处置建议
 * @param anyChange  计划是否改变了任何格（false 时调用方可直接跳过写入）
 */
public record SolvePlan(int[] owner,
                        List<PlanItem> items,
                        List<CountChange> merges,
                        List<Eviction> evictions,
                        boolean anyChange) {

    /** 落位物品。 */
    public record PlanItem(int sourceIndex,
                           int targetIndex,
                           GridDim dim,
                           boolean rotated,
                           int count,
                           StackSnapshot source) {

        /** 是否需要真正写入目标格（位置/旋转/数量任一变化）。 */
        public boolean needsWrite() {
            return sourceIndex != targetIndex
                    || source.isRotated() != rotated
                    || source.count() != count;
        }
    }

    /** 合并后来源格的数量变更（0 = 清空）；{@code masterIndex} = 被并入的主格（写入前置条件，防丢失/复制）。 */
    public record CountChange(int index, int newCount, int masterIndex) {
    }

    /** 无处置建议。 */
    public enum EvictionKind {
        /** 交给光标（原格物理不可用或分区非法，且光标为空）。 */
        TO_CURSOR,
        /** 合并进光标（光标持有同类物品且未满）。 */
        MERGE_TO_CURSOR,
        /** 掉落兜底（原格不可用且光标无法承载——绝不静默吞物品）。 */
        DROP
    }

    /** 出不来者的处置建议（不再有 KEEP：保留原位即不产生 Eviction）。 */
    public record Eviction(int sourceIndex, EvictionKind kind, StackSnapshot source) {
    }

    public static SolvePlan empty(int size) {
        int[] owner = new int[Math.max(0, size)];
        java.util.Arrays.fill(owner, -1);
        return new SolvePlan(owner, List.of(), List.of(), List.of(), false);
    }
}
