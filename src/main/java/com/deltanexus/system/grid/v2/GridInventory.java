package com.deltanexus.system.grid.v2;

import com.deltanexus.system.grid.core.GridDim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网格容器（重写版内核，v2）：<b>一等公民的存储模型</b>。
 *
 * <p>核心思想（与旧实现的根本差别）：</p>
 * <ol>
 *   <li><b>只存锚点</b>：{@code entries: cell → GridEntry}，足迹内的非锚点格不存在任何数据，就是空；
 *       没有占位物、没有派生标记、没有需要“协调”的东西。</li>
 *   <li><b>占用是算出来的</b>：{@link #occupancy()} / {@link #footprint(int)} 现算，不入库、不同步、不可能失配。</li>
 *   <li><b>唯一入口</b>：{@link #apply(GridOp)} —— 影子推演 → 不变式校验 → 守恒校验 → 提交或整体拒绝。
 *       任何失败都不会留下半写状态。</li>
 *   <li><b>不变式可判定</b>：{@link #validate()} 在任何时刻都能回答“这个容器现在合法吗”，
 *       违规时给出格号与原因（便于日志与自检）。</li>
 * </ol>
 *
 * <p>本层不依赖玩家/菜单/网络，也不做“自动重排”：全局移动只允许显式 {@link GridOp.Compact}。</p>
 */
public final class GridInventory {

    /** 操作结果。 */
    public record Result(boolean ok, String reason, long revision, GridEntry taken) {

        static Result ok(long revision) {
            return new Result(true, null, revision, null);
        }

        static Result ok(long revision, GridEntry taken) {
            return new Result(true, null, revision, taken);
        }

        static Result fail(String reason, long revision) {
            return new Result(false, reason, revision, null);
        }

        public boolean failed() {
            return !ok;
        }
    }

    private final int width;
    private final int rows;
    private final int size;
    private final boolean[] usable;
    /** 锚点 → 条目（只含锚点；顺序保持插入序，便于稳定推导）。 */
    private final Map<Integer, GridEntry> entries = new LinkedHashMap<>();
    private long revision;

    public GridInventory(int width, int rows, boolean[] usableMask) {
        this.width = Math.max(1, width);
        this.rows = Math.max(1, rows);
        this.size = this.width * this.rows;
        this.usable = new boolean[this.size];
        Arrays.fill(this.usable, true);
        if (usableMask != null) {
            System.arraycopy(usableMask, 0, this.usable, 0, Math.min(usableMask.length, this.size));
        }
    }

    // ------------------------------------------------------------------
    // 查询（全部只读、无副作用）
    // ------------------------------------------------------------------

    public int width() {
        return width;
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return size;
    }

    public long revision() {
        return revision;
    }

    public boolean usable(int cell) {
        return cell >= 0 && cell < size && usable[cell];
    }

    /** 解锁/锁定某一格（管理员缩格/扩格用；锁定前必须已经没有条目占用它）。 */
    public Result setUsable(int cell, boolean value) {
        if (cell < 0 || cell >= size) {
            return Result.fail("格号越界: " + cell, revision);
        }
        if (!value && anchorCovering(cell) >= 0) {
            return Result.fail("该格正被条目占用，无法锁定: " + cell, revision);
        }
        usable[cell] = value;
        revision++;
        return Result.ok(revision);
    }

    /** 锚点表（只读拷贝）。 */
    public Map<Integer, GridEntry> entries() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public int entryCount() {
        return entries.size();
    }

    /** 该格是否为锚点，是则返回条目。 */
    public GridEntry entryAt(int cell) {
        return entries.get(cell);
    }

    /** 覆盖该格的锚点格号（-1 = 空格）。 */
    public int anchorCovering(int cell) {
        if (cell < 0 || cell >= size) {
            return -1;
        }
        Integer direct = entries.containsKey(cell) ? cell : null;
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<Integer, GridEntry> e : entries.entrySet()) {
            if (covers(e.getKey(), e.getValue(), cell)) {
                return e.getKey();
            }
        }
        return -1;
    }

    /** 每格归属（派生）：cell → 锚点格号，-1 = 空。长度 = size。 */
    public int[] occupancy() {
        int[] owner = new int[size];
        Arrays.fill(owner, -1);
        for (Map.Entry<Integer, GridEntry> e : entries.entrySet()) {
            for (int cell : footprint(e.getKey())) {
                if (cell >= 0 && cell < size) {
                    owner[cell] = e.getKey();
                }
            }
        }
        return owner;
    }

    /** 锚点的足迹格号（含锚点自身）；锚点无效/放不下时退化为只含自身的 1x1。 */
    public int[] footprint(int anchor) {
        GridEntry entry = entries.get(anchor);
        if (entry == null || anchor < 0 || anchor >= size) {
            return new int[0];
        }
        GridDim dim = effectiveDim(anchor, entry);
        int[] cells = new int[dim.w() * dim.h()];
        int col = anchor % width;
        int row = anchor / width;
        int k = 0;
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                cells[k++] = (row + dy) * width + col + dx;
            }
        }
        return cells;
    }

    /**
     * 实际生效尺寸（<b>绝不查询其它条目</b>，只做边界与可用格检查）：足迹越界或压在锁定格上时退化为 1x1。
     *
     * <p>⚠️ 这里以前调用 {@code regionLegal(..., ignoreEntry)}，而后者会回调 {@link #anchorCovering(int)} →
     * {@code covers} → {@code effectiveDim}，形成<b>无限递归</b>（放置多格物品时直接 StackOverflow，
     * 玩家数据加载失败 → 游戏报「无效的玩家数据」）。占用冲突由 {@link #validate()} 与
     * {@link #canPlace(int, GridDim)} 判定，这里只回答“这条目自己能不能摆得下”。</p>
     */
    private GridDim effectiveDim(int anchor, GridEntry entry) {
        return effectiveDimIn(entries, anchor, entry);
    }

    /** 容器内物品总量（守恒校验用）。 */
    public long totalCount() {
        long sum = 0;
        for (GridEntry e : entries.values()) {
            sum += e.count();
        }
        return sum;
    }

    /**
     * “把 {@code cell} 这一格腾空之后”，{@code dim} 能否落在该格（纯查询，不改任何状态）。
     *
     * <p>用于交换语义的前置判定：原版交换的顺序是「先读走本格 → 再写入光标物品」，
     * 若写入失败，被读走的物品已在光标、而光标物品无处安放 → 物品凭空消失。
     * 因此必须<b>先问</b>“腾空这一格后放得下吗”，放不下就通过 {@code mayPlace} 直接拦住交换。</p>
     */
    public boolean canPlaceAfterRemoving(int cell, GridDim dim) {
        if (cell < 0 || cell >= size || dim == null) {
            return false;
        }
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        int owner = anchorCoveringIn(candidate, cell);
        if (owner >= 0) {
            candidate.remove(owner); // 该格本身或其主格腾空
        }
        return regionFreeIn(candidate, cell, dim);
    }
    /** 行优先找可容纳 {@code dim} 的空位（-1 = 无）。 */
    public int findFreeSpot(GridDim dim) {
        GridDim d = dim == null ? GridDim.ONE : dim;
        for (int cell = 0; cell < size; cell++) {
            // 与 canPlace 完全同一判据：越界 / 锁定格 / 锚点 / 他人足迹 全部排除
            if (d.is1x1() ? (usable[cell] && !entries.containsKey(cell) && anchorCovering(cell) < 0)
                    : regionFreeIn(entries, cell, d)) {
                return cell;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /** 落位可行性；返回 null = 可放，否则为拒绝原因（人类可读，直接可用于日志）。 */
    public String canPlace(int cell, GridDim dim) {
        if (cell < 0 || cell >= size) {
            return "格号越界";
        }
        if (dim == null || dim.is1x1()) {
            // 1x1：只要求该格本身空闲（不是锚点、也不属于别人足迹）
            if (entries.containsKey(cell) || anchorCovering(cell) >= 0) {
                return "该格已被占用";
            }
            return usable[cell] ? null : "该格未解锁";
        }
        // 多格：整块足迹必须空闲（越界/锁定格/锚点/他人足迹都不行）——
        // 之前这里用的是 regionLegal(-1)，它<b>不做占用检查</b>，导致 findFreeSpot 返回“假空位”，
        // 随后 place 在 validate() 处被重叠拒绝 → 交换已取走原物品 → 物品丢失。
        if (!regionFreeIn(entries, cell, dim)) {
            return "足迹越界或已被占用";
        }
        return null;
    }

    /** 全容器不变式校验；返回 null = 合法。
     *
     *  <p>注意：<b>“锁定格上有物品”不视为违规</b>——旧版本允许这种状态（管理员降级/默认尺寸变小、
     *  旧存档遗留），把它当违规会导致读取存档时物品被回滚丢弃。合法占用（越界/重叠/空条目/锁定格上的
     *  非法尺寸）仍然会被拦下。</p>
     */
    public String validate() {
        int[] owner = new int[size];
        Arrays.fill(owner, -1);
        for (Map.Entry<Integer, GridEntry> e : entries.entrySet()) {
            int anchor = e.getKey();
            GridEntry entry = e.getValue();
            if (anchor < 0 || anchor >= size) {
                return "锚点越界: " + anchor;
            }
            if (entry == null || entry.isEmpty()) {
                return "锚点上存在空条目: " + anchor;
            }
            int col = anchor % width;
            int row = anchor / width;
            GridDim dim = entry.dim();
            if (col + dim.w() > width || row + dim.h() > rows) {
                return "条目足迹越界: 锚点 " + anchor + " 尺寸 " + dim;
            }
            for (int dy = 0; dy < dim.h(); dy++) {
                for (int dx = 0; dx < dim.w(); dx++) {
                    int cell = (row + dy) * width + col + dx;
                    if (owner[cell] != -1 && owner[cell] != anchor) {
                        return "条目足迹重叠: 格 " + cell + "（锚点 " + owner[cell] + " 与 " + anchor + "）";
                    }
                    owner[cell] = anchor;
                }
            }
        }
        return null;
    }

    /** 统计“落在锁定格上的条目”数量（诊断/UI 用；读取旧存档后可能非 0）。 */
    public int entriesOnLockedCells() {
        int n = 0;
        for (Map.Entry<Integer, GridEntry> e : entries.entrySet()) {
            boolean locked = false;
            for (int cell : footprint(e.getKey())) {
                if (!usable(cell)) {
                    locked = true;
                    break;
                }
            }
            if (locked) {
                n++;
            }
        }
        return n;
    }

    /**
     * 强制落位（忽略解锁状态，仍校验越界/重叠）：<b>只用于迁移旧存档与“绝不丢物品”兜底</b>。
     * 正常游戏路径不得使用（玩家放置继续走 {@link #place}，会受锁定格限制）。
     */
    public Result placeForced(int cell, GridEntry entry) {
        if (entry == null || entry.isEmpty()) {
            return Result.fail("空条目", revision);
        }
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        int existing = anchorCovering(cell);
        if (existing >= 0 && existing != cell) {
            // 与既有足迹冲突：先把对方腾到别处（保持其物品）
            GridEntry other = candidate.remove(existing);
            int spot = firstFreeSpotIn(candidate, other.dim());
            if (spot < 0) {
                spot = firstFreeSpotIn(candidate, GridDim.ONE);
                if (spot >= 0) {
                    other = GridEntry.of(other.stackForWrite(), GridDim.ONE, false);
                }
            }
            if (spot < 0) {
                return Result.fail("强制落位失败：无处安置冲突条目", revision);
            }
            candidate.put(spot, other);
        }
        candidate.put(cell, entry);
        return commit(candidate, false, "强制落位");
    }

    private boolean regionLegal(int anchor, GridDim dim, int ignoreEntry) {
        if (dim == null || anchor < 0 || anchor >= size) {
            return false;
        }
        int col = anchor % width;
        int row = anchor / width;
        if (col + dim.w() > width || row + dim.h() > rows) {
            return false;
        }
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                int cell = (row + dy) * width + col + dx;
                if (!usable[cell]) {
                    return false;
                }
                if (ignoreEntry >= 0 && entries.containsKey(cell) && cell != ignoreEntry) {
                    return false;
                }
                if (ignoreEntry >= 0 && !entries.containsKey(cell)) {
                    int owner = anchorCovering(cell);
                    if (owner >= 0 && owner != ignoreEntry) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean covers(int anchor, GridEntry entry, int cell) {
        GridDim dim = effectiveDim(anchor, entry);
        int col = anchor % width;
        int row = anchor / width;
        int targetCol = cell % width;
        int targetRow = cell / width;
        return targetCol >= col && targetCol < col + dim.w()
                && targetRow >= row && targetRow < row + dim.h();
    }

    // ------------------------------------------------------------------
    // 唯一入口
    // ------------------------------------------------------------------

    /** 应用一个操作（影子推演 → 校验 → 提交或整体拒绝）。 */
    public Result apply(GridOp op) {
        if (op == null) {
            return Result.fail("空操作", revision);
        }
        if (op instanceof GridOp.Place place) {
            return place(place.cell(), place.entry());
        }
        if (op instanceof GridOp.Take take) {
            return take(take.cell());
        }
        if (op instanceof GridOp.Move move) {
            return move(move.from(), move.to(), move.rotated());
        }
        if (op instanceof GridOp.Rotate rotate) {
            return rotate(rotate.cell());
        }
        if (op instanceof GridOp.Compact) {
            return compact();
        }
        return Result.fail("未知操作: " + op.getClass().getSimpleName(), revision);
    }

    /** 落位（不守恒：物品来自容器之外）。 */
    public Result place(int cell, GridEntry entry) {
        if (entry == null || entry.isEmpty()) {
            return Result.fail("空条目", revision);
        }
        String deny = canPlace(cell, entry.dim());
        if (deny != null) {
            return Result.fail(deny, revision);
        }
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        int existing = anchorCovering(cell);
        if (existing >= 0) {
            if (existing != cell) {
                return Result.fail("该格属于另一件跨格物品", revision);
            }
            // 同格叠放：仅同种且未满时合并（数量由调用方决定传入多少）
            GridEntry cur = candidate.get(cell);
            int space = cur.maxStackSize() - cur.count();
            if (!cur.sameKind(entry.withCount(1)) || space <= 0) {
                return Result.fail("该格已被占用", revision);
            }
        }
        // 先把可能与之冲突的“旧足迹”清掉（同锚点重放/换尺寸）
        candidate.put(cell, entry);
        return commit(candidate, false, "落位");
    }

    /** 取走锚点整件物品（不守恒：物品离开容器）。 */
    public Result take(int cell) {
        int anchor = entries.containsKey(cell) ? cell : anchorCovering(cell);
        if (anchor < 0) {
            return Result.fail("该格没有物品", revision);
        }
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        GridEntry removed = candidate.remove(anchor);
        Result committed = commit(candidate, false, "取出");
        return committed.ok() ? Result.ok(revision, removed) : committed;
    }

    /** 移动（守恒）：整件搬到新锚点，可换姿态。 */
    public Result move(int from, int to, Boolean rotated) {
        int anchor = entries.containsKey(from) ? from : anchorCovering(from);
        if (anchor < 0) {
            return Result.fail("源格没有物品", revision);
        }
        GridEntry entry = entries.get(anchor);
        GridEntry moved = rotated == null ? entry : entry.withRotated(rotated);
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        candidate.remove(anchor);
        if (to < 0 || to >= size) {
            return Result.fail("目标格越界", revision);
        }
        for (int cell : footprintIn(anchor, entry)) {
            if (cell != anchor && candidate.containsKey(cell)) {
                return Result.fail("目标位置被占用", revision);
            }
        }
        for (int cell : footprintIn(to, moved)) {
            if (cell < 0 || cell >= size || !usable[cell]) {
                return Result.fail("目标足迹超出容器或压在锁定格上", revision);
            }
            Integer occupant = candidate.containsKey(cell) ? cell : null;
            if (occupant != null) {
                return Result.fail("目标位置被占用", revision);
            }
            int owner = anchorCoveringIn(candidate, cell);
            if (owner >= 0) {
                return Result.fail("目标位置被另一件跨格物品占用", revision);
            }
        }
        candidate.put(to, moved);
        return commit(candidate, true, "移动");
    }

    /** 就地旋转（守恒）。 */
    public Result rotate(int cell) {
        int anchor = entries.containsKey(cell) ? cell : anchorCovering(cell);
        if (anchor < 0) {
            return Result.fail("该格没有物品", revision);
        }
        GridEntry entry = entries.get(anchor);
        return move(anchor, anchor, !entry.rotated());
    }

    /** 显式整理（守恒）：按面积降序、行优先重新落位；任何一件放不下就保持原位并整体回退。 */
    public Result compact() {
        List<GridEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort((a, b) -> Integer.compare(b.area(), a.area()));
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>();
        for (GridEntry entry : sorted) {
            int spot = firstFreeSpotIn(candidate, entry.dim());
            if (spot < 0) {
                return Result.fail("整理失败：空间不足以容纳全部物品（保持原状）", revision);
            }
            candidate.put(spot, entry);
        }
        return commit(candidate, true, "整理");
    }

    /** 用一组条目整体替换（迁移/读取存档用；不守恒）。放不下时整体失败，绝不丢弃。 */
    public Result replaceAll(List<GridEntry> newEntries) {
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>();
        for (GridEntry entry : newEntries == null ? List.<GridEntry>of() : newEntries) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            int spot = firstFreeSpotIn(candidate, entry.dim());
            if (spot < 0) {
                return Result.fail("整体替换失败：空间不足以容纳全部条目（保持原状）", revision);
            }
            candidate.put(spot, entry);
        }
        return commit(candidate, false, "整体替换");
    }

    public Result clear() {
        Map<Integer, GridEntry> empty = new LinkedHashMap<>();
        return commit(empty, false, "清空");
    }

    // ------------------------------------------------------------------
    // 提交：校验后才改状态
    // ------------------------------------------------------------------

    private Result commit(Map<Integer, GridEntry> candidate, boolean mustConserve, String label) {
        long before = totalCount();
        long after = 0;
        for (Map.Entry<Integer, GridEntry> e : candidate.entrySet()) {
            if (e.getKey() == null || e.getKey() < 0 || e.getValue() == null || e.getValue().isEmpty()) {
                return Result.fail(label + "失败：条目非法（空/未落位）", revision);
            }
            after += e.getValue().count();
        }
        if (mustConserve && before != after) {
            return Result.fail(label + "失败：物品数量不守恒（" + before + " → " + after + "）", revision);
        }
        Map<Integer, GridEntry> backup = new LinkedHashMap<>(entries);
        long backupRevision = revision;
        entries.clear();
        entries.putAll(candidate);
        String violation = validate();
        if (violation != null) {
            entries.clear();
            entries.putAll(backup);
            revision = backupRevision;
            return Result.fail(label + "失败：" + violation, revision);
        }
        revision++;
        return Result.ok(revision);
    }

    private int firstFreeSpotIn(Map<Integer, GridEntry> map, GridDim dim) {
        GridDim d = dim == null ? GridDim.ONE : dim;
        for (int cell = 0; cell < size; cell++) {
            if (regionFreeIn(map, cell, d)) {
                return cell;
            }
        }
        return -1;
    }

    /** 目标足迹在该 map 上是否空闲（纯函数，不改状态）。 */
    private boolean regionFreeIn(Map<Integer, GridEntry> map, int cell, GridDim dim) {
        if (cell < 0 || cell >= size) {
            return false;
        }
        int col = cell % width;
        int row = cell / width;
        if (col + dim.w() > width || row + dim.h() > rows) {
            return false;
        }
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                int target = (row + dy) * width + col + dx;
                if (!usable[target]) {
                    return false;
                }
                if (map.containsKey(target)) {
                    return false;
                }
                if (anchorCoveringIn(map, target) >= 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 该 map 中覆盖 {@code cell} 的锚点（纯函数）。 */
    private int anchorCoveringIn(Map<Integer, GridEntry> map, int cell) {
        if (cell < 0 || cell >= size) {
            return -1;
        }
        if (map.containsKey(cell)) {
            return cell;
        }
        for (Map.Entry<Integer, GridEntry> e : map.entrySet()) {
            GridDim dim = effectiveDimIn(map, e.getKey(), e.getValue());
            int col = e.getKey() % width;
            int row = e.getKey() / width;
            int targetCol = cell % width;
            int targetRow = cell / width;
            if (targetCol >= col && targetCol < col + dim.w()
                    && targetRow >= row && targetRow < row + dim.h()) {
                return e.getKey();
            }
        }
        return -1;
    }

    /** 该 map 中某条目的“实际生效尺寸”（足迹不合法则退化 1x1）。 */
    private GridDim effectiveDimIn(Map<Integer, GridEntry> map, int anchor, GridEntry entry) {
        GridDim dim = entry.dim();
        if (dim.is1x1()) {
            return GridDim.ONE;
        }
        int col = anchor % width;
        int row = anchor / width;
        if (col + dim.w() > width || row + dim.h() > rows) {
            return GridDim.ONE;
        }
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                int cell = (row + dy) * width + col + dx;
                if (!usable[cell]) {
                    return GridDim.ONE;
                }
                if (map.containsKey(cell) && cell != anchor) {
                    return GridDim.ONE;
                }
            }
        }
        return dim;
    }

    private int[] footprintIn(int anchor, GridEntry entry) {
        GridDim dim = entry.dim();
        int col = anchor % width;
        int row = anchor / width;
        int[] cells = new int[dim.w() * dim.h()];
        int k = 0;
        for (int dy = 0; dy < dim.h(); dy++) {
            for (int dx = 0; dx < dim.w(); dx++) {
                cells[k++] = (row + dy) * width + col + dx;
            }
        }
        return cells;
    }

    /** 只读调试输出（日志/自检用）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder("GridInventory(").append(width).append('x').append(rows)
                .append(", entries=").append(entries.size()).append(", rev=").append(revision).append(')');
        for (Map.Entry<Integer, GridEntry> e : entries.entrySet()) {
            sb.append('\n').append("  #").append(e.getKey()).append(" → ").append(e.getValue())
                    .append(" 足迹=").append(Arrays.toString(footprint(e.getKey())));
        }
        return sb.toString();
    }
}
