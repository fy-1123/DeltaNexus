package com.deltanexus.system.grid;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 网格容器内核（0.5.0Beta 重写）——<b>格子背包唯一的一等公民存储模型</b>。
 *
 * <p>四条结构性保证：</p>
 * <ol>
 *   <li><b>只存锚点</b>：{@code entries: 锚点格号 → GridEntry}。足迹内的其它格不存在任何数据，就是空；
 *       没有占位物、没有派生标记、没有需要「协调」的副本。</li>
 *   <li><b>占用是算出来的</b>：{@link #footprint(int)} / {@link #occupancy()} 每次现算，
 *       不入库、不同步，因此不可能与物品失配。</li>
 *   <li><b>唯一写入口</b>：{@link #place}/{@link #take}/{@link #move}/{@link #rotate}/{@link #compact}
 *       统一走「影子推演 → 不变式校验 → 守恒校验 → 提交，任一不过即整体放弃」，
 *       任何失败都不会留下半写状态。</li>
 *   <li><b>不变式可判定</b>：{@link #validate()} 任何时候都能回答「这个容器现在合法吗」并给出格号与原因。</li>
 * </ol>
 *
 * <p>本层<b>不</b>碰玩家/菜单/网络/配置，也<b>不</b>自动重排：整体移动只允许显式 {@link #compact()}。
 * 解锁语义由 {@code usable} 掩码承载：未解锁格既不可落位、也不可被跨格物品跨越。</p>
 *
 * <p>线程约束：与旧内核一致，<b>只在主线程访问</b>，不提供内部锁。</p>
 */
public final class GridStore {

    /** 操作结果。{@code reason} 为人类可读的拒绝原因，可直接进日志或提示玩家。 */
    public record Result(boolean ok, String reason, long revision, ItemStack taken) {

        static Result ok(long revision) {
            return new Result(true, null, revision, null);
        }

        static Result ok(long revision, ItemStack taken) {
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
    /** 锚点格号 → 条目（保持插入序，便于稳定推导与可复现的整理结果）。 */
    private final Map<Integer, GridEntry> entries = new LinkedHashMap<>();
    private long revision;

    public GridStore(int width, int rows) {
        this(width, rows, null);
    }

    /** {@code usableMask} 为 null 时全部解锁；长度不足的部分按「已解锁」补齐。 */
    public GridStore(int width, int rows, boolean[] usableMask) {
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
    // 只读查询
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

    /** 解锁格掩码的拷贝（存档/同步用）。 */
    public boolean[] usableMask() {
        return usable.clone();
    }

    public int unlockedCount() {
        int n = 0;
        for (boolean b : usable) {
            if (b) {
                n++;
            }
        }
        return n;
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
    public int anchorAt(int cell) {
        return anchorIn(entries, cell);
    }

    /** 锚点的足迹格号（含锚点自身，按「行优先、逐行向右」展开）。 */
    public int[] footprint(int anchor) {
        GridEntry entry = entries.get(anchor);
        return entry == null ? new int[0] : footprintOf(anchor, entry.size());
    }

    /** 每格归属（派生）：下标 = 格号，值 = 锚点格号，-1 = 空。长度 = {@link #size()}。 */
    public int[] occupancy() {
        int[] owner = new int[size];
        Arrays.fill(owner, -1);
        for (Map.Entry<Integer, GridEntry> e : entries.entrySet()) {
            for (int cell : footprintOf(e.getKey(), e.getValue().size())) {
                if (cell >= 0 && cell < size) {
                    owner[cell] = e.getKey();
                }
            }
        }
        return owner;
    }

    /** 容器内物品总量（守恒校验与统计用）。 */
    public long totalCount() {
        long sum = 0;
        for (GridEntry e : entries.values()) {
            sum += e.count();
        }
        return sum;
    }

    /** 内容指纹（仓库/安全箱「外部改动」检测用；与插入顺序无关，只与最终内容有关）。 */
    public long contentHash() {
        long hash = 1125899906842597L;
        for (Map.Entry<Integer, GridEntry> e : new TreeMap<>(entries).entrySet()) {
            GridEntry entry = e.getValue();
            hash = 31 * hash + e.getKey();
            hash = 31 * hash + entry.stack().getItem().hashCode();
            hash = 31 * hash + entry.count();
            hash = 31 * hash + (entry.rotated() ? 1 : 0);
            hash = 31 * hash + Objects.hashCode(entry.stack().getTag());
        }
        return hash;
    }

    // ------------------------------------------------------------------
    // 落位可行性
    // ------------------------------------------------------------------

    /** 落位可行性；返回 {@code null} = 可放，否则为拒绝原因（可直接进日志/提示）。 */
    public String canPlace(int cell, GridSize dim) {
        if (cell < 0 || cell >= size) {
            return "格号越界";
        }
        GridSize d = dim == null ? GridSize.SINGLE : dim;
        if (d.single()) {
            if (entries.containsKey(cell) || anchorIn(entries, cell) >= 0) {
                return "该格已被占用";
            }
            return usable[cell] ? null : "该格未解锁";
        }
        return regionFreeIn(entries, cell, d) ? null : "足迹越界、未解锁或已被占用";
    }

    /** 「把 {@code cell} 腾空之后」{@code dim} 能否落在该格（纯查询）。
     *  用于交换语义的前置判定：原版交换是「先读走本格 → 再写光标物品」，
     *  写入失败就会丢物品，因此必须先问「腾空这一格后放得下吗」。 */
    public boolean canPlaceAfterRemoving(int cell, GridSize dim) {
        if (cell < 0 || cell >= size) {
            return false;
        }
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        int owner = anchorIn(candidate, cell);
        if (owner >= 0) {
            candidate.remove(owner);
        }
        return regionFreeIn(candidate, cell, dim == null ? GridSize.SINGLE : dim);
    }

    /** 行优先找可容纳 {@code dim} 的首个空位（-1 = 无）。 */
    public int findFreeSpot(GridSize dim) {
        return firstFreeSpotIn(entries, dim == null ? GridSize.SINGLE : dim);
    }

    // ------------------------------------------------------------------
    // 唯一写入口
    // ------------------------------------------------------------------

    /** 解锁/锁定某一格（管理员扩缩容用）；锁定前该格必须已经没有任何条目占用它。 */
    public Result setUsable(int cell, boolean value) {
        if (cell < 0 || cell >= size) {
            return Result.fail("格号越界: " + cell, revision);
        }
        if (!value && anchorIn(entries, cell) >= 0) {
            return Result.fail("该格正被条目占用，无法锁定: " + cell, revision);
        }
        if (usable[cell] == value) {
            return Result.ok(revision);
        }
        usable[cell] = value;
        revision++;
        return Result.ok(revision);
    }

    /** 落位（物品来自容器之外，不校验守恒）。目标足迹必须完整空闲且已解锁。 */
    public Result place(int cell, GridEntry entry) {
        if (entry == null || entry.isEmpty()) {
            return Result.fail("空条目", revision);
        }
        String deny = canPlace(cell, entry.size());
        if (deny != null) {
            return Result.fail(deny, revision);
        }
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        candidate.put(cell, entry);
        return commit(candidate, false, "落位");
    }

    /**
     * 自动落位（物品来自容器之外）：先并入同类未满堆叠，再找空位新落一件。
     *
     * @return 未能放入的剩余数量（0 = 全部放入）
     */
    public int insert(GridEntry entry) {
        if (entry == null || entry.isEmpty()) {
            return 0;
        }
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        int rest = attemptMerge(candidate, entry);
        if (rest > 0) {
            GridEntry remaining = entry.withCount(rest);
            int spot = firstFreeSpotIn(candidate, remaining.size());
            if (spot >= 0) {
                candidate.put(spot, remaining);
                rest = 0;
            }
        }
        commit(candidate, false, "放入");
        return rest;
    }

    /** 取走锚点整件物品（物品离开容器，不校验守恒）。 */
    public Result take(int cell) {
        int anchor = anchorIn(entries, cell);
        if (anchor < 0) {
            return Result.fail("该格没有物品", revision);
        }
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        GridEntry removed = candidate.remove(anchor);
        Result committed = commit(candidate, false, "取出");
        return committed.ok() ? Result.ok(revision, removed.stackForWrite()) : committed;
    }

    /** 移动整件物品到新锚点（守恒），可顺带换姿态。 */
    public Result move(int from, int to, Boolean rotated) {
        int anchor = anchorIn(entries, from);
        if (anchor < 0) {
            return Result.fail("源格没有物品", revision);
        }
        GridEntry entry = entries.get(anchor);
        GridEntry moved = rotated == null ? entry : entry.withRotated(rotated);
        if (to < 0 || to >= size) {
            return Result.fail("目标格越界", revision);
        }
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        candidate.remove(anchor);
        for (int cell : footprintOf(to, moved.size())) {
            if (cell < 0 || cell >= size) {
                return Result.fail("目标足迹越界", revision);
            }
            if (!usable[cell]) {
                return Result.fail("目标足迹压在未解锁格上", revision);
            }
            if (candidate.containsKey(cell) || anchorIn(candidate, cell) >= 0) {
                return Result.fail("目标位置已被占用", revision);
            }
        }
        candidate.put(to, moved);
        return commit(candidate, true, "移动");
    }

    /** 就地旋转（守恒）。 */
    public Result rotate(int cell) {
        int anchor = anchorIn(entries, cell);
        if (anchor < 0) {
            return Result.fail("该格没有物品", revision);
        }
        return move(anchor, anchor, !entries.get(anchor).rotated());
    }

    /** 显式整理（守恒）：按面积降序、行优先重新落位；任何一件放不下就保持原状并整体回退。 */
    public Result compact() {
        List<GridEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort((a, b) -> Integer.compare(b.area(), a.area()));
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>();
        for (GridEntry entry : sorted) {
            int spot = firstFreeSpotIn(candidate, entry.size());
            if (spot < 0) {
                return Result.fail("整理失败：空间不足以容纳全部物品（保持原状）", revision);
            }
            candidate.put(spot, entry);
        }
        return commit(candidate, true, "整理");
    }

    /** 以一组条目整体替换（读档/迁移用，不校验守恒）；放不下时整体失败，绝不丢弃。 */
    public Result replaceAll(List<GridEntry> newEntries) {
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>();
        for (GridEntry entry : newEntries == null ? List.<GridEntry>of() : newEntries) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            int spot = firstFreeSpotIn(candidate, entry.size());
            if (spot < 0) {
                return Result.fail("整体替换失败：空间不足以容纳全部条目（保持原状）", revision);
            }
            candidate.put(spot, entry);
        }
        return commit(candidate, false, "整体替换");
    }

    /** 强制落位（忽略解锁状态，仍校验越界/重叠）：只用于迁移旧存档与「绝不丢物品」兜底。 */
    public Result placeForced(int cell, GridEntry entry) {
        if (entry == null || entry.isEmpty()) {
            return Result.fail("空条目", revision);
        }
        Map<Integer, GridEntry> candidate = new LinkedHashMap<>(entries);
        int existing = anchorIn(candidate, cell);
        if (existing >= 0 && existing != cell) {
            GridEntry other = candidate.remove(existing);
            int spot = firstFreeSpotIn(candidate, other.size());
            if (spot < 0) {
                spot = firstFreeSpotIn(candidate, GridSize.SINGLE);
                if (spot >= 0) {
                    other = GridEntry.of(other.stackForWrite(), GridSize.SINGLE, false);
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

    public Result clear() {
        return commit(new LinkedHashMap<>(), false, "清空");
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /** 全容器不变式校验；{@code null} = 合法。
     *
     *  <p>注意：<b>「未解锁格上存在既有物品」不判为违规</b>——管理员缩容或旧存档可能留下这种状态，
     *  把它当违规会导致读档时物品被回滚丢弃。越界、重叠、空条目仍然会被拦下。</p> */
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
            GridSize dim = entry.size();
            if (col + dim.w() > width || row + dim.h() > rows) {
                return "条目足迹越界: 锚点 " + anchor + " 尺寸 " + dim;
            }
            for (int cell : footprintOf(anchor, dim)) {
                if (owner[cell] != -1 && owner[cell] != anchor) {
                    return "条目足迹重叠: 格 " + cell + "（锚点 " + owner[cell] + " 与 " + anchor + "）";
                }
                owner[cell] = anchor;
            }
        }
        return null;
    }

    /** 落在未解锁格上的条目数量（诊断用；读旧档后可能非 0）。 */
    public int entriesOnLockedCells() {
        int n = 0;
        for (Map.Entry<Integer, GridEntry> e : entries.entrySet()) {
            for (int cell : footprintOf(e.getKey(), e.getValue().size())) {
                if (!usable(cell)) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** 只读调试输出（日志/自检用）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder("GridStore(").append(width).append('x').append(rows)
                .append(", entries=").append(entries.size()).append(", rev=").append(revision).append(')');
        for (Map.Entry<Integer, GridEntry> e : entries.entrySet()) {
            sb.append('\n').append("  #").append(e.getKey()).append(" → ").append(e.getValue())
                    .append(" 足迹=").append(Arrays.toString(footprintOf(e.getKey(), e.getValue().size())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 提交：先校验数量守恒（可选）与不变式，全部通过才改状态，否则整体回退。 */
    private Result commit(Map<Integer, GridEntry> candidate, boolean mustConserve, String label) {
        long before = totalCount();
        long after = 0;
        for (Map.Entry<Integer, GridEntry> e : candidate.entrySet()) {
            Integer cell = e.getKey();
            GridEntry entry = e.getValue();
            if (cell == null || cell < 0 || cell >= size || entry == null || entry.isEmpty()) {
                return Result.fail(label + "失败：条目非法（空/越界/未落位）", revision);
            }
            after += entry.count();
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

    /** 把 {@code entry} 尽量并入 map 中同类未满堆叠，返回剩余数量（不写状态，只改 candidate）。 */
    private int attemptMerge(Map<Integer, GridEntry> candidate, GridEntry entry) {
        int rest = entry.count();
        for (Map.Entry<Integer, GridEntry> e : candidate.entrySet()) {
            if (rest <= 0) {
                break;
            }
            GridEntry target = e.getValue();
            if (!target.sameKind(entry)) {
                continue;
            }
            int space = target.maxStackSize() - target.count();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, rest);
            e.setValue(target.withCount(target.count() + moved));
            rest -= moved;
        }
        return rest;
    }

    private int firstFreeSpotIn(Map<Integer, GridEntry> map, GridSize dim) {
        GridSize d = dim == null ? GridSize.SINGLE : dim;
        for (int cell = 0; cell < size; cell++) {
            if (regionFreeIn(map, cell, d)) {
                return cell;
            }
        }
        return -1;
    }

    /** 目标足迹在该 map 上是否空闲（纯函数，不改状态）。 */
    private boolean regionFreeIn(Map<Integer, GridEntry> map, int cell, GridSize dim) {
        if (cell < 0 || cell >= size || dim == null) {
            return false;
        }
        int col = cell % width;
        int row = cell / width;
        if (col + dim.w() > width || row + dim.h() > rows) {
            return false;
        }
        for (int target : footprintOf(cell, dim)) {
            if (!usable[target] || map.containsKey(target) || anchorIn(map, target) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** 该 map 中覆盖 {@code cell} 的锚点（纯函数；-1 = 空）。 */
    private int anchorIn(Map<Integer, GridEntry> map, int cell) {
        if (cell < 0 || cell >= size) {
            return -1;
        }
        if (map.containsKey(cell)) {
            return cell;
        }
        int col = cell % width;
        int row = cell / width;
        for (Map.Entry<Integer, GridEntry> e : map.entrySet()) {
            int anchor = e.getKey();
            GridSize dim = e.getValue().size();
            int aCol = anchor % width;
            int aRow = anchor / width;
            if (col >= aCol && col < aCol + dim.w() && row >= aRow && row < aRow + dim.h()) {
                return anchor;
            }
        }
        return -1;
    }

    /** 由锚点与尺寸展开足迹格号。 */
    private int[] footprintOf(int anchor, GridSize dim) {
        GridSize d = dim == null ? GridSize.SINGLE : dim;
        int[] cells = new int[d.w() * d.h()];
        int col = anchor % width;
        int row = anchor / width;
        int k = 0;
        for (int dy = 0; dy < d.h(); dy++) {
            for (int dx = 0; dx < d.w(); dx++) {
                cells[k++] = (row + dy) * width + col + dx;
            }
        }
        return cells;
    }
}