package com.deltanexus.system.grid.core;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 网格一致性校验（0.3.0Beta 止血）：把「布局是否合法」与「物品是否守恒」变成**可判定**的检查，
 * 供收敛判定与事务安全网使用。
 *
 * <p>三条不变式（只依赖格子内容本身，不依赖任何派生状态）：</p>
 * <ol>
 *   <li>占位物的主格必须存在、是非占位物的真实物品，且该物品的足迹确实覆盖本格；</li>
 *   <li>没有主格的空格就是空格（不允许残留孤立占位物）；</li>
 *   <li>任意两个物品的足迹不得相交，且足迹必须整体落在容器内、落在可用格上、不跨越口袋分区。</li>
 * </ol>
 *
 * <p>第 3 条正是「2x2 塞进 1x1」这类几何 bug 的判定器：旧实现在自动整理时校验、手动放置时漏校验，
 * 现在把同一份判定放进收敛门（不一致才收敛）与事务写回前的最终校验里。</p>
 */
public final class GridIntegrity {

    private GridIntegrity() {
    }

    /** 违规种类。 */
    public enum Violation {
        /** 越界 / 跨越可用格 / 跨越口袋分区 / 口袋区放大件：几何非法。 */
        ILLEGAL_FOOTPRINT,
        /** 两个物品足迹相交。 */
        OVERLAP,
        /** 占位物没有活主格，或主格不再覆盖它。 */
        ORPHAN_SLAVE,
        /** 主格足迹内的非主格没有占位物——0.3.0Beta 第三阶段起**不再是违规**（足迹格本来就该是空的），保留常量仅为兼容。 */
        @Deprecated
        MISSING_SLAVE,
        /** 同一格出现两份内容（防御性：写入层出现引用共享时会命中）。 */
        DUPLICATE_CELL
    }

    /** 校验报告。 */
    public record Report(boolean consistent, List<Violation> violations, List<Integer> cells) {

        public String describe() {
            if (consistent) {
                return "consistent";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < violations.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(violations.get(i)).append('@').append(i < cells.size() ? cells.get(i) : -1);
            }
            return sb.toString();
        }
    }

    /** 布局校验（纯读，委托 {@link GridLayout#derive} 的同一份推导）。 */
    public static Report check(GridContext ctx, List<StackSnapshot> cells) {
        GridLayout.Result result = GridLayout.derive(ctx, cells);
        return new Report(result.consistent(), result.violations(), result.issueCells());
    }

    // ------------------------------------------------------------------
    // 事务安全网
    // ------------------------------------------------------------------

    /** 容器内物品总量（占位物不计）。 */
    public static long totalCount(List<ItemStack> cells) {
        long sum = 0L;
        for (ItemStack stack : cells) {
            if (stack.isEmpty() || GridTags.isSlave(stack)) {
                continue;
            }
            sum += stack.getCount();
        }
        return sum;
    }

    /**
     * 守恒校验：写入后的总量必须等于写入前总量减去「离开容器」的量。
     *
     * <p>这是复制 bug 的正面克星——任何凭空造物品 / 静默吞物品都会在这里被抓住。</p>
     */
    public static boolean conserved(List<ItemStack> before, List<ItemStack> after, long leftContainer) {
        return totalCount(after) + leftContainer == totalCount(before);
    }

    /** 同一份 {@link ItemStack} 实例是否被写进了多个格（引用共享 = 潜在复制源头）。 */
    public static boolean sharesInstance(List<ItemStack> cells) {
        Set<ItemStack> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (ItemStack stack : cells) {
            if (stack.isEmpty()) {
                continue;
            }
            if (!seen.add(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 占位物/物品的重复格检测（同一内容出现在多格不属于同一次写入时的防御）。 */
    public static Set<Integer> duplicatedCells(List<ItemStack> cells) {
        Set<Integer> dup = new HashSet<>();
        for (int i = 0; i < cells.size(); i++) {
            for (int j = i + 1; j < cells.size(); j++) {
                if (cells.get(i).isEmpty() || cells.get(j).isEmpty()) {
                    continue;
                }
                if (cells.get(i) == cells.get(j)) {
                    dup.add(i);
                    dup.add(j);
                }
            }
        }
        return dup;
    }
}
