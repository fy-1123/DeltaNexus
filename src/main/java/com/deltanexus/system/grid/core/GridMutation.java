package com.deltanexus.system.grid.core;

import com.deltanexus.system.DeltaNexus;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 网格事务写入（0.3.0Beta 止血重写）。
 *
 * <p>与旧实现的根本差别：<b>不再把“快照算出来的计划”直接写进可变存储</b>。现在的流程是</p>
 * <ol>
 *   <li>把容器内容读成 {@code before}（影子数组，拷贝）；</li>
 *   <li>计划里的每一项都做 <b>CAS（比较后写）</b>：只有当相关格子的当前内容与计划假设一致时才落到 {@code after}；
 *       任何一项前置条件不成立就整项跳过（过期计划绝不复活已移走的物品）；</li>
 *   <li>占位物由 {@code after} 的<b>实际最终内容</b>重新推导（不是照抄计划的 owner 表），
 *       因此不可能写出「不属于任何主格的占位物」或覆盖真实物品；</li>
 *   <li>守恒校验 + 引用共享校验：不通过就整体中止（一格都不写）并打日志；</li>
 *   <li>只把真正变化的格写回容器。</li>
 * </ol>
 *
 * <p>“决定”与“写入”因此彻底分离：复制（凭空造物品）与重叠（两物品足迹相交）在结构上被排除；
 * 万一仍有路径越界，守恒校验会当场拦下，而不是把损坏写进存档。</p>
 */
public final class GridMutation {

    private GridMutation() {
    }

    /** 兼容入口（无快照维度信息时：计划物品用计划尺寸，其余格按 1x1 推导）。 */
    public static boolean apply(GridTarget target, GridContext ctx, SolvePlan plan, EvictionSink sink) {
        return apply(target, ctx, null, plan, sink);
    }

    /**
     * 原子应用一个求解计划。
     *
     * @param cells 求解时使用的每格快照（提供每格尺寸；可为 null）
     * @return 是否真实改动了任意格
     */
    public static boolean apply(GridTarget target, GridContext ctx, List<StackSnapshot> cells,
                                SolvePlan plan, EvictionSink sink) {
        if (target == null || ctx == null || plan == null) {
            return false;
        }
        int size = ctx.size();
        List<ItemStack> before = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            before.add(target.get(i).copy());
        }
        List<ItemStack> after = new ArrayList<>(before);

        GridDim[] dims = new GridDim[size];
        for (int i = 0; i < size; i++) {
            dims[i] = GridDim.ONE;
        }
        if (cells != null) {
            for (int i = 0; i < size && i < cells.size(); i++) {
                if (cells.get(i) != null && !cells.get(i).isEmpty()) {
                    dims[i] = cells.get(i).dim();
                }
            }
        }
        for (SolvePlan.PlanItem item : plan.items()) {
            if (item.targetIndex() >= 0 && item.targetIndex() < size) {
                dims[item.targetIndex()] = item.dim();
            }
        }

        long[] leftContainer = {0L};
        boolean[] applied = new boolean[size];
        GridTarget view = GridTarget.readOnly(after);
        EvictionSink out = sink != null ? sink : EvictionSink.NONE;

        // 1) 落位物品：逐项 CAS（源格内容未变 + 目标足迹仍空闲/仍属于自己的旧占位物）
        for (SolvePlan.PlanItem item : plan.items()) {
            if (!applyItem(ctx, before, after, view, dims, item)) {
                continue;
            }
            if (item.targetIndex() >= 0 && item.targetIndex() < size) {
                applied[item.targetIndex()] = true;
            }
        }

        // 2) 合并：仅在「被并入的主格本次真正落位」且来源格未被改动时生效（否则会丢物品）
        for (SolvePlan.CountChange cc : plan.merges()) {
            if (cc.index() < 0 || cc.index() >= size
                    || cc.masterIndex() < 0 || cc.masterIndex() >= size || !applied[cc.masterIndex()]) {
                continue;
            }
            ItemStack cur = after.get(cc.index());
            ItemStack orig = before.get(cc.index());
            if (cur.isEmpty() || GridTags.isSlave(cur) || !ItemStack.matches(cur, orig)) {
                continue;
            }
            if (cc.newCount() <= 0) {
                after.set(cc.index(), ItemStack.EMPTY);
            } else if (orig.getCount() > cc.newCount()) {
                ItemStack reduced = orig.copy();
                reduced.setCount(cc.newCount());
                after.set(cc.index(), reduced);
            }
        }

        // 3) 出不来者：CAS 后交给出口；出口只收部分时按实际数量回退
        for (SolvePlan.Eviction ev : plan.evictions()) {
            int idx = ev.sourceIndex();
            if (idx < 0 || idx >= size) {
                continue;
            }
            ItemStack cur = after.get(idx);
            if (cur.isEmpty() || GridTags.isSlave(cur)) {
                continue;
            }
            if (!ItemStack.matches(cur, ev.source().prototype())) {
                continue; // 已被玩家/其他路径改动：不再处置（宁可留着，也绝不复制）
            }
            ItemStack payload = cur.copy();
            int consumed = switch (ev.kind()) {
                case TO_CURSOR -> out.toCursor(payload);
                case MERGE_TO_CURSOR -> out.mergeToCursor(payload);
                case DROP -> out.drop(payload) ? payload.getCount() : 0;
            };
            if (consumed >= cur.getCount()) {
                after.set(idx, ItemStack.EMPTY);
                leftContainer[0] += cur.getCount();
            } else if (consumed > 0) {
                ItemStack remainder = cur.copy();
                remainder.setCount(cur.getCount() - consumed);
                after.set(idx, remainder);
                leftContainer[0] += consumed;
            }
        }

        // 4) 占位物：0.3.0Beta 第三阶段起不再写入——只清理历史残留（足迹由布局推导，不靠占位物阻挡）
        clearLegacySlaves(ctx, after);

        // 5) 安全网：守恒 + 引用共享
        if (!GridIntegrity.conserved(before, after, leftContainer[0])) {
            DeltaNexus.LOGGER.error("[DN] 网格事务中止（物品不守恒）：before={} after={} 离开容器={} → 放弃本次写入",
                    GridIntegrity.totalCount(before), GridIntegrity.totalCount(after), leftContainer[0]);
            return false;
        }
        if (GridIntegrity.sharesInstance(after)) {
            DeltaNexus.LOGGER.error("[DN] 网格事务中止（同一 ItemStack 实例被写入多格）→ 放弃本次写入");
            return false;
        }

        // 6) 只写回变化的格
        boolean changed = false;
        for (int i = 0; i < size; i++) {
            if (ItemStack.matches(before.get(i), after.get(i))) {
                continue;
            }
            ItemStack next = after.get(i);
            target.set(i, next.isEmpty() ? ItemStack.EMPTY : next.copy());
            changed = true;
        }
        return changed;
    }

    /** 单项落位：源格内容未变 + 足迹（写回时）仍空闲或属于自己的旧占位物。 */
    private static boolean applyItem(GridContext ctx, List<ItemStack> before, List<ItemStack> after,
                                     GridTarget view, GridDim[] dims, SolvePlan.PlanItem item) {
        int src = item.sourceIndex();
        int dst = item.targetIndex();
        if (src < 0 || src >= before.size() || dst < 0 || dst >= after.size()) {
            return false;
        }
        if (!ItemStack.matches(before.get(src), item.source().prototype())) {
            return false; // 源格已被改动：本项过期
        }
        GridDim dim = item.dim();
        if (!ctx.inBounds(dst, dim)) {
            return false;
        }
        boolean zone = ctx.hotbarZone(dst);
        if (zone && !dim.is1x1()) {
            return false;
        }
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                int cell = ctx.cellAt(dst, dx, dy);
                if (cell < 0 || cell >= after.size() || !ctx.usable(cell) || ctx.hotbarZone(cell) != zone) {
                    return false;
                }
                if (cell == src) {
                    continue; // 源格稍后被覆盖
                }
                ItemStack cur = after.get(cell);
                if (cur.isEmpty()) {
                    continue;
                }
                if (GridTags.isOwnSlave(cur, item.source().containerIndex())) {
                    continue;
                }
                return false; // 已被别的物品/他人占位物占用：跳过本项，绝不覆盖
            }
        }
        ItemStack placed = item.source().prototypeWithCount(item.count());
        GridTags.setRotated(placed, item.rotated());
        if (src != dst) {
            after.set(src, ItemStack.EMPTY);
        }
        after.set(dst, placed);
        dims[dst] = dim;
        return true;
    }

    /**
     * 清理历史残留占位物（0.3.0Beta 第三阶段：<b>不再写入占位物</b>）。
     *
     * <p>足迹内非主格现在是普通空格；旧版本写下的占位物一律清除（它们只是历史数据，
     * 阻挡原版往足迹里塞东西的职责已由菜单层重定向 + 收敛位移承担）。</p>
     */
    private static void clearLegacySlaves(GridContext ctx, List<ItemStack> after) {
        for (int i = 0; i < ctx.size(); i++) {
            if (GridTags.isSlave(after.get(i))) {
                after.set(i, ItemStack.EMPTY);
            }
        }
    }
}
