package com.deltanexus.system.test;

import com.deltanexus.system.grid.core.GridContext;
import com.deltanexus.system.grid.core.GridDim;
import com.deltanexus.system.grid.core.GridMutation;
import com.deltanexus.system.grid.core.GridSolver;
import com.deltanexus.system.grid.core.GridTags;
import com.deltanexus.system.grid.core.GridTarget;
import com.deltanexus.system.grid.core.SolvePlan;
import com.deltanexus.system.grid.core.StackSnapshot;
import com.deltanexus.system.grid.core.UsableMask;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 格式背包（格子背包）内核回归测试（0.3.0Beta）。
 *
 * <p>只在 GameTestServer（{@code gradlew runGameTestServer}）执行，全部针对<b>纯函数内核</b>：
 * 求解器（大件优先 / 占位物足迹 / 重排 / 旋转 / 同类合并 / 保留原位）、
 * 事务写入（占位物与孤立占位物清理、精确数量回退）、NBT 约定（旋转键与剥离）。</p>
 */
public class GridRegressionTests {

    /** 由 {@code DeltaNexus} 构造器调用。 */
    public static void register() {
        GameTestRegistry.register(GridRegressionTests.class);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 用固定列表实现一个可写网格目标（容器索引 = 格索引）。 */
    private static final class ListTarget implements GridTarget {
        private final List<ItemStack> cells;

        ListTarget(int size) {
            cells = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                cells.add(ItemStack.EMPTY);
            }
        }

        @Override
        public int size() {
            return cells.size();
        }

        @Override
        public ItemStack get(int index) {
            return index >= 0 && index < cells.size() ? cells.get(index) : ItemStack.EMPTY;
        }

        @Override
        public void set(int index, ItemStack stack) {
            cells.set(index, stack == null ? ItemStack.EMPTY : stack);
        }
    }

    private static GridContext ctx(int width, int size) {
        return GridContext.builder(width, size).usable(UsableMask.all(size)).build();
    }

    private static void set(List<ItemStack> cells, int index, ItemStack stack) {
        cells.set(index, stack);
    }

    private static List<StackSnapshot> snapshots(List<ItemStack> cells, int width, GridDim dim, int dimIndex) {
        List<StackSnapshot> list = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            ItemStack stack = cells.get(i);
            list.add(StackSnapshot.of(stack, i == dimIndex ? dim : GridDim.ONE, i));
        }
        return list;
    }

    private static List<ItemStack> emptyCells(int size) {
        List<ItemStack> cells = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            cells.add(ItemStack.EMPTY);
        }
        return cells;
    }

    // ------------------------------------------------------------------
    // 求解器
    // ------------------------------------------------------------------

    /** 2x2 物品：足迹四格归属主格；0.3.0Beta 第三阶段起足迹格保持为空（不再写占位物）。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void solverFootprintAndSlaves(GameTestHelper helper) {
        int size = 27;
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 5));
        GridContext context = ctx(9, size);
        List<StackSnapshot> snaps = snapshots(cells, 9, new GridDim(2, 2), 0);

        SolvePlan plan = GridSolver.solve(context, snaps, GridSolver.CursorState.none());
        int[] footprint = {0, 1, 9, 10};
        for (int idx : footprint) {
            if (plan.owner()[idx] != 0) {
                helper.fail("足迹格 " + idx + " 应归属锚点 0，实际 " + plan.owner()[idx]);
            }
        }

        ListTarget target = new ListTarget(size);
        set(target.cells, 0, new ItemStack(Items.DIAMOND, 5));
        // 0.3.0Beta 第三阶段起：足迹格保持为空，已一致的状态<b>不应产生任何写入</b>（这正是“不再每 tick 重排”的保证）
        if (GridMutation.apply(target, context, snaps, plan, com.deltanexus.system.grid.core.EvictionSink.NONE)) {
            helper.fail("状态已一致时不应写入任何格");
        }
        for (int idx : new int[]{1, 9, 10}) {
            if (!target.get(idx).isEmpty()) {
                helper.fail("3.0Beta 第三阶段起足迹格应为空（不再写占位物），格 " + idx + " 实际 " + target.get(idx));
            }
        }
        if (!target.get(0).is(Items.DIAMOND) || target.get(0).getCount() != 5) {
            helper.fail("锚点应保留 2x2 钻石 x5");
        }
        helper.succeed();
    }

    /** 冲突重排：锁定格不可落位，2x2 物品应移到首个可行位。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void solverRelocatesOffLockedCells(GameTestHelper helper) {
        int size = 36;
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 1));
        // 第 1 行第 1 格锁定 -> 2x2 无法落在 0 位，应重排到 1 位
        boolean[] usable = new boolean[size];
        java.util.Arrays.fill(usable, true);
        usable[1] = false;
        GridContext context = GridContext.builder(9, size)
                .usable(new UsableMask(size, usable))
                .build();
        List<StackSnapshot> snaps = snapshots(cells, 9, new GridDim(2, 2), 0);

        SolvePlan plan = GridSolver.solve(context, snaps, GridSolver.CursorState.none());
        if (plan.owner()[1] != -1) {
            helper.fail("锁定格 1 不应被占用");
        }
        int master = -1;
        for (int i = 0; i < size; i++) {
            if (plan.owner()[i] != -1) {
                master = plan.owner()[i];
                break;
            }
        }
        if (master != 2) {
            helper.fail("2x2 物品应重排到行优先首个可行位 2，实际 " + master);
        }
        helper.succeed();
    }

    /** 旋转放置：原方向无位时用旋转方向落位，并写入旋转标记。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void solverRotatesWhenNeeded(GameTestHelper helper) {
        // 宽度 2 的容器、高度 3：3x1 物品在原方向放不下，旋转成 1x3 才能落位
        int size = 6;
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 1));
        GridContext context = ctx(2, size);
        List<StackSnapshot> snaps = snapshots(cells, 2, new GridDim(3, 1), 0);

        SolvePlan plan = GridSolver.solve(context, snaps, GridSolver.CursorState.none());
        if (plan.items().isEmpty()) {
            helper.fail("3x1 物品在 2 列容器中应旋转为 1x3 落位");
        }
        SolvePlan.PlanItem item = plan.items().get(0);
        if (!item.rotated()) {
            helper.fail("旋转落位应写入旋转标记");
        }
        if (item.dim().w() != 1 || item.dim().h() != 3) {
            helper.fail("旋转后尺寸应为 1x3，实际 " + item.dim());
        }
        helper.succeed();
    }

    /** 同类合并：足迹内的 1x1 同类物品并入移动中的物品；异类则冲突保留原位。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void solverMergesSameKindInFootprint(GameTestHelper helper) {
        int size = 27;
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 2));
        set(cells, 10, new ItemStack(Items.DIAMOND, 3)); // 位于 2x2 足迹内（主格 0 的右下角）
        GridContext context = ctx(9, size);
        List<StackSnapshot> snaps = snapshots(cells, 9, new GridDim(2, 2), 0);

        SolvePlan plan = GridSolver.solve(context, snaps, GridSolver.CursorState.none());
        SolvePlan.PlanItem di = plan.items().stream()
                .filter(it -> it.source().prototype().is(Items.DIAMOND))
                .findFirst().orElse(null);
        if (di == null) {
            helper.fail("钻石应被登记为落位物品");
        }
        if (di.count() != 5) {
            helper.fail("同类 1x1 物品应合并进 2x2 钻石（2+3=5），实际 " + di.count());
        }
        boolean mergeRecorded = plan.merges().stream().anyMatch(mc -> mc.index() == 10 && mc.newCount() == 0);
        if (!mergeRecorded) {
            helper.fail("应记录格 10 被合并清空");
        }
        helper.succeed();
    }

    /** 孤立占位物幂等清除；他人占位物视为占用（冲突保留原位）。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void mutationClearsOrphanSlaves(GameTestHelper helper) {
        int size = 9;
        List<ItemStack> cells = emptyCells(size);
        // 格 1 是「主格已空」的孤立占位物；格 0 是普通 1x1 物品
        set(cells, 1, GridTags.createSlave(0));
        set(cells, 0, new ItemStack(Items.IRON_INGOT, 1));
        GridContext context = ctx(9, size);
        List<StackSnapshot> snaps = snapshots(cells, 9, GridDim.ONE, -1);

        SolvePlan plan = GridSolver.solve(context, snaps, GridSolver.CursorState.none());
        ListTarget target = new ListTarget(size);
        set(target.cells, 0, new ItemStack(Items.IRON_INGOT, 1));
        set(target.cells, 1, GridTags.createSlave(0));
        GridMutation.apply(target, context, plan, com.deltanexus.system.grid.core.EvictionSink.NONE);
        if (!target.get(1).isEmpty()) {
            helper.fail("孤立占位物应被清除");
        }
        if (!target.get(0).is(Items.IRON_INGOT)) {
            helper.fail("普通物品应保留原位");
        }
        helper.succeed();
    }

    /** 区块外/锁定格上的物品：原格不可用 → 交给光标（出口收下才清空原格）。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void mutationEvictsToCursorExactly(GameTestHelper helper) {
        int size = 1;
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 4));
        GridContext context = GridContext.builder(9, size)
                .usable(new UsableMask(size, new boolean[]{false}))
                .build();
        List<StackSnapshot> snaps = snapshots(cells, 9, new GridDim(2, 2), 0);

        SolvePlan plan = GridSolver.solve(context, snaps, GridSolver.CursorState.none());
        if (plan.evictions().isEmpty()) {
            helper.fail("原格不可用且无空位时应产生处置建议");
        }
        // 出口只收 2 个：原格应剩 2 个（绝不复制、绝不吞）
        int[] carried = {0};
        com.deltanexus.system.grid.core.EvictionSink partial = new com.deltanexus.system.grid.core.EvictionSink() {
            @Override
            public int toCursor(ItemStack stack) {
                carried[0] += 2;
                return 2;
            }

            @Override
            public int mergeToCursor(ItemStack stack) {
                return 0;
            }

            @Override
            public boolean drop(ItemStack stack) {
                return false;
            }
        };
        ListTarget target = new ListTarget(size);
        set(target.cells, 0, new ItemStack(Items.DIAMOND, 4));
        GridMutation.apply(target, context, plan, partial);
        if (carried[0] != 2) {
            helper.fail("出口应只收下 2 个，实际 " + carried[0]);
        }
        if (target.get(0).getCount() != 2) {
            helper.fail("原格应剩 2 个（精确回退），实际 " + target.get(0).getCount());
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // NBT 约定
    // ------------------------------------------------------------------

    /** 旋转标记写新键、清旧键；剥离后不残留任何网格键。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void rotationTagMigrationAndStripping(GameTestHelper helper) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        stack.getOrCreateTag().putBoolean(GridTags.ROTATED_LEGACY, true);
        if (!GridTags.isRotated(stack)) {
            helper.fail("旧键 deltanexus.is_rotated 应被识别为旋转");
        }
        GridTags.setRotated(stack, true);
        if (!stack.getTag().getBoolean(GridTags.ROTATED)) {
            helper.fail("应写入新键 deltanexus.grid.rotated");
        }
        if (stack.getTag().contains(GridTags.ROTATED_LEGACY)) {
            helper.fail("写入新键后应清除旧键");
        }
        GridTags.setRotated(stack, false);
        if (GridTags.isRotated(stack)) {
            helper.fail("取消旋转后不应再判定为旋转");
        }

        ItemStack slave = GridTags.createSlave(7);
        if (!GridTags.isSlave(slave) || GridTags.masterOf(slave) != 7) {
            helper.fail("占位物应写入 is_slave 与 master_slot=7");
        }
        ItemStack stripped = GridTags.stripped(slave);
        if (GridTags.isSlave(stripped) || stripped.getTag() != null && stripped.getTag().contains(GridTags.MASTER_SLOT)) {
            helper.fail("剥离后不应残留网格键（交易/管道比较用）");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // 0.3.0Beta 止血安全网：CAS / 守恒 / 几何校验
    // ------------------------------------------------------------------

    /** CAS：计划生成后源格被玩家拿走 → 事务必须整项跳过，绝不复活物品、不写占位物。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void stalePlanNeverResurrectsItems(GameTestHelper helper) {
        int size = 27;
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 4));
        GridContext context = ctx(9, size);
        List<StackSnapshot> snaps = snapshots(cells, 9, new GridDim(2, 2), 0);
        SolvePlan plan = GridSolver.solve(context, snaps, GridSolver.CursorState.none());

        ListTarget target = new ListTarget(size); // 目标全空：模拟物品已被拿走
        boolean changed = GridMutation.apply(target, context, snaps, plan,
                com.deltanexus.system.grid.core.EvictionSink.NONE);
        if (changed) {
            helper.fail("源格已空时应整项跳过，实际发生了写入");
        }
        if (!target.get(0).isEmpty()) {
            helper.fail("不得复活已被移走的物品（典型复制形态）");
        }
        for (int i : new int[]{1, 9, 10}) {
            if (!target.get(i).isEmpty()) {
                helper.fail("不得为已消失的物品写占位物，格 " + i + " 应保持空");
            }
        }
        helper.succeed();
    }

    /** CAS：目标足迹被别的真实物品占用时跳过该项，绝不覆盖（重叠防线）。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void stalePlanNeverOverwritesOccupiedFootprint(GameTestHelper helper) {
        int size = 27;
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 1));
        GridContext context = ctx(9, size);
        List<StackSnapshot> snaps = snapshots(cells, 9, new GridDim(2, 2), 0);
        SolvePlan plan = GridSolver.solve(context, snaps, GridSolver.CursorState.none());

        ListTarget target = new ListTarget(size);
        set(target.cells, 0, new ItemStack(Items.DIAMOND, 1));
        set(target.cells, 1, new ItemStack(Items.IRON_INGOT, 2)); // 应用前足迹被占
        GridMutation.apply(target, context, snaps, plan, com.deltanexus.system.grid.core.EvictionSink.NONE);

        if (!target.get(1).is(Items.IRON_INGOT) || target.get(1).getCount() != 2) {
            helper.fail("被占用的足迹格不得被覆盖");
        }
        if (!target.get(0).is(Items.DIAMOND)) {
            helper.fail("钻石应留在原地（本轮跳过，等下轮收敛）");
        }
        if (!target.get(9).isEmpty() || !target.get(10).isEmpty()) {
            helper.fail("足迹不完整时不得写占位物");
        }
        helper.succeed();
    }

    /** 守恒：一次「重排 + 合并 + 占位物重建」事务后物品总量不变。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void transactionConservesItemCount(GameTestHelper helper) {
        int size = 36;
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 3));
        set(cells, 10, new ItemStack(Items.DIAMOND, 2)); // 位于 2x2 足迹内 → 合并
        set(cells, 4, new ItemStack(Items.IRON_INGOT, 5));
        boolean[] usable = new boolean[size];
        java.util.Arrays.fill(usable, true);
        usable[1] = false; // 迫使 2x2 重排
        GridContext context = GridContext.builder(9, size)
                .usable(new com.deltanexus.system.grid.core.UsableMask(size, usable))
                .build();
        List<StackSnapshot> snaps = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            snaps.add(StackSnapshot.of(cells.get(i), i == 0 ? new GridDim(2, 2) : GridDim.ONE, i));
        }
        SolvePlan plan = GridSolver.solve(context, snaps, GridSolver.CursorState.none());

        ListTarget target = new ListTarget(size);
        for (int i = 0; i < size; i++) {
            set(target.cells, i, cells.get(i));
        }
        long before = com.deltanexus.system.grid.core.GridIntegrity.totalCount(target.cells);
        GridMutation.apply(target, context, snaps, plan, com.deltanexus.system.grid.core.EvictionSink.NONE);
        long after = com.deltanexus.system.grid.core.GridIntegrity.totalCount(target.cells);
        if (before != after) {
            helper.fail("事务前后物品总量必须守恒：before=" + before + " after=" + after);
        }
        if (com.deltanexus.system.grid.core.GridIntegrity.sharesInstance(target.cells)) {
            helper.fail("不得把同一 ItemStack 实例写进多格");
        }
        helper.succeed();
    }

    /** 几何校验：口袋区放大件判非法；足迹格为空是**正常**状态；残留占位物判待清理。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void integrityDetectsIllegalLayouts(GameTestHelper helper) {
        int size = 27;
        // 1) 口袋区放 2x2：几何非法（旧版「2x2 塞进 1x1」的判定器）
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 1));
        boolean[] hotbarZone = new boolean[size];
        hotbarZone[0] = true;
        hotbarZone[1] = true;
        GridContext pocketCtx = GridContext.builder(9, size)
                .usable(com.deltanexus.system.grid.core.UsableMask.all(size))
                .hotbarZone(hotbarZone)
                .build();
        var report = com.deltanexus.system.grid.core.GridIntegrity.check(pocketCtx,
                snapshots(cells, 9, new GridDim(2, 2), 0));
        if (report.consistent() || !report.violations().contains(
                com.deltanexus.system.grid.core.GridIntegrity.Violation.ILLEGAL_FOOTPRINT)) {
            helper.fail("口袋区放大件应判定为几何非法，实际 " + report.describe());
        }

        // 2) 正常区放 2x2 且足迹格为空：这是第三阶段的标准形态，必须判定为「一致」
        List<ItemStack> cells2 = emptyCells(size);
        set(cells2, 0, new ItemStack(Items.DIAMOND, 1));
        GridContext normal = ctx(9, size);
        var r2 = com.deltanexus.system.grid.core.GridIntegrity.check(normal,
                snapshots(cells2, 9, new GridDim(2, 2), 0));
        if (!r2.consistent()) {
            helper.fail("足迹格为空是正常状态（不再写占位物），实际 " + r2.describe());
        }

        // 3) 足迹内残留旧版占位物：判定为待清理（收敛时会被清除）
        List<ItemStack> cells3 = emptyCells(size);
        set(cells3, 0, new ItemStack(Items.DIAMOND, 1));
        set(cells3, 1, GridTags.createSlave(0));
        var r3 = com.deltanexus.system.grid.core.GridIntegrity.check(normal,
                snapshots(cells3, 9, new GridDim(2, 2), 0));
        if (r3.consistent() || !r3.violations().contains(
                com.deltanexus.system.grid.core.GridIntegrity.Violation.ORPHAN_SLAVE)) {
            helper.fail("残留占位物应判定为待清理，实际 " + r3.describe());
        }
        helper.succeed();
    }

    /**
     * v2 内核（真实容器）：多格物品落位 / 取出 / 重放 / 整理必须正常工作。
     *
     * <p>这条用例是为了防住「多格物品触发内核无限递归 → 玩家数据加载失败 → 无效的玩家数据」这类事故：
     * 之前的测试都在旧求解器上跑，没有真正用过 {@code GridInventory} 的多格路径。</p>
     */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void v2KernelHandlesMultiCellEntries(GameTestHelper helper) {
        boolean[] usable = new boolean[108];
        java.util.Arrays.fill(usable, true);
        com.deltanexus.system.grid.v2.GridInventory inv =
                new com.deltanexus.system.grid.v2.GridInventory(9, 12, usable);

        com.deltanexus.system.grid.v2.GridEntry big =
                com.deltanexus.system.grid.v2.GridEntry.of(new ItemStack(Items.DIAMOND, 3),
                        new com.deltanexus.system.grid.core.GridDim(2, 2), false);
        if (inv.place(0, big).failed()) {
            helper.fail("2x2 物品应能落在 0 号格：" + inv.place(0, big).reason());
        }
        if (inv.footprint(0).length != 4) {
            helper.fail("2x2 足迹应为 4 格，实际 " + inv.footprint(0).length);
        }
        for (int cell : new int[]{1, 9, 10}) {
            if (inv.anchorCovering(cell) != 0) {
                helper.fail("格 " + cell + " 应归属于锚点 0，实际 " + inv.anchorCovering(cell));
            }
        }
        if (inv.validate() != null) {
            helper.fail("落位后容器应合法：" + inv.validate());
        }
        if (inv.place(1, com.deltanexus.system.grid.v2.GridEntry.of(new ItemStack(Items.IRON_INGOT), false)).ok()) {
            helper.fail("落在别人足迹内的格子上应被拒绝");
        }
        if (inv.take(1).failed()) {
            helper.fail("按覆盖格取出（重定向到锚点）应成功");
        }
        if (!inv.entries().isEmpty()) {
            helper.fail("取出后容器应为空");
        }
        if (inv.place(4, big).failed() || inv.validate() != null) {
            helper.fail("重新落位后应合法：" + inv.validate());
        }
        if (inv.compact().failed()) {
            helper.fail("整理（只移动、守恒）应成功");
        }
        if (inv.totalCount() != 3L) {
            helper.fail("整理前后总量应守恒（3），实际 " + inv.totalCount());
        }
        helper.succeed();
    }

    /** 安全网工具自身：守恒与引用共享必须能报出问题。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void integrityUtilsCatchDuplication(GameTestHelper helper) {
        ItemStack a = new ItemStack(Items.DIAMOND, 3);
        java.util.List<ItemStack> before = new java.util.ArrayList<>();
        before.add(a.copy());
        java.util.List<ItemStack> after = new java.util.ArrayList<>();
        after.add(a.copy());
        if (!com.deltanexus.system.grid.core.GridIntegrity.conserved(before, after, 0)) {
            helper.fail("数量相同应判定守恒");
        }
        java.util.List<ItemStack> duplicated = new java.util.ArrayList<>();
        duplicated.add(a.copy());
        duplicated.add(a.copy());
        if (com.deltanexus.system.grid.core.GridIntegrity.conserved(before, duplicated, 0)) {
            helper.fail("凭空多出的物品必须被守恒校验抓住");
        }

        ItemStack shared = new ItemStack(Items.IRON_INGOT);
        java.util.List<ItemStack> sharedCells = new java.util.ArrayList<>();
        sharedCells.add(shared);
        sharedCells.add(shared);
        if (!com.deltanexus.system.grid.core.GridIntegrity.sharesInstance(sharedCells)) {
            helper.fail("同一实例出现在两格必须被识别为引用共享");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------
    // 0.3.0Beta 第二阶段：布局推导（唯一的几何真相）
    // ------------------------------------------------------------------

    /** 布局推导：2x2 物品产生一个覆盖 4 格的 placement（足迹格为空，无需占位物）。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void layoutDerivesFootprintAndOwner(GameTestHelper helper) {
        int size = 27;
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 1));
        GridContext context = ctx(9, size);
        var result = com.deltanexus.system.grid.core.GridLayout.derive(context,
                snapshots(cells, 9, new GridDim(2, 2), 0));
        if (!result.consistent()) {
            helper.fail("足迹格为空时布局应一致，实际 " + result.describe());
        }
        if (result.placements().size() != 1) {
            helper.fail("应恰好推导出 1 个落位物品，实际 " + result.placements().size());
        }
        var placement = result.placements().get(0);
        if (placement.span() != 4 || placement.degraded()) {
            helper.fail("2x2 应覆盖 4 格且不退化，实际 span=" + placement.span());
        }
        for (int cell : new int[]{0, 1, 9, 10}) {
            if (result.owner()[cell] != 0) {
                helper.fail("格 " + cell + " 应归属锚点 0，实际 " + result.owner()[cell]);
            }
        }
        helper.succeed();
    }

    /** 布局推导：口袋区放大件退化为 1x1 并记录违规（不再出现“2x2 占进 1x1 空间”）。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void layoutDegradesIllegalPlacement(GameTestHelper helper) {
        int size = 27;
        List<ItemStack> cells = emptyCells(size);
        set(cells, 0, new ItemStack(Items.DIAMOND, 1));
        boolean[] zone = new boolean[size];
        zone[0] = true;
        GridContext context = GridContext.builder(9, size)
                .usable(com.deltanexus.system.grid.core.UsableMask.all(size))
                .hotbarZone(zone)
                .build();
        var result = com.deltanexus.system.grid.core.GridLayout.derive(context,
                snapshots(cells, 9, new GridDim(2, 2), 0));
        if (result.consistent()) {
            helper.fail("口袋区放大件必须被判定为不一致");
        }
        var placement = result.placements().get(0);
        if (!placement.degraded() || placement.span() != 1) {
            helper.fail("非法落位应退化为 1x1（保留物品、不覆盖别人），实际 span=" + placement.span());
        }
        if (result.owner()[1] != -1 || result.owner()[9] != -1 || result.owner()[10] != -1) {
            helper.fail("退化后不得占用其它格");
        }
        helper.succeed();
    }
}
