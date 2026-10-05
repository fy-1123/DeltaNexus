package com.deltanexus.system.grid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 网格存档读写（0.5.0Beta 重写）——只写事实、读档绝不丢物品。
 *
 * <p>落盘格式（沿用并兼容 0.3.0Beta 的锚点格式，便于旧存档直接读取）：</p>
 * <pre>
 * { format_version: 1,
 *   entries: [ { cell: <锚点格号>, item: &lt;ItemStack NBT&gt;, w: n, h: m, rotated: 0b/1b } ] }
 * </pre>
 *
 * <p><b>只写锚点</b>：足迹内的非锚点格没有任何数据。物品上的网格姿态键在此剥离——
 * 容器存档里 {@code rotated} 才是唯一真相，避免「条目姿态」与「物品 NBT 姿态」两份真相打架。</p>
 *
 * <p>读档分两条路：锚点格式直接读；老平铺格式（玩家存档里的原版槽位列表）走
 * {@link #read(CompoundTag, int, int, boolean[], List, String)} 的迁移路径。两条路都使用
 * {@link #placeTolerant} 容错安置：<b>原位 → 首个空位 → 强制原位 → 退化 1x1</b>，
 * 只移动不丢弃，全部放不下才 ERROR 上报。</p>
 */
public final class GridNbt {

    /** 锚点格式版本。 */
    public static final int FORMAT_VERSION = 1;

    private static final String KEY_FORMAT = "format_version";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_CELL = "cell";
    private static final String KEY_ITEM = "item";
    private static final String KEY_W = "w";
    private static final String KEY_H = "h";
    private static final String KEY_ROTATED = "rotated";

    private GridNbt() {
    }

    /** 是否为当前锚点格式。 */
    public static boolean isAnchorFormat(CompoundTag tag) {
        return tag != null && tag.contains(KEY_FORMAT) && tag.getInt(KEY_FORMAT) == FORMAT_VERSION;
    }

    /** 写出（只写锚点；物品上的网格姿态键被剥离）。 */
    public static CompoundTag write(GridStore store) {
        CompoundTag root = new CompoundTag();
        root.putInt(KEY_FORMAT, FORMAT_VERSION);
        ListTag list = new ListTag();
        if (store != null) {
            for (var e : store.entries().entrySet()) {
                GridEntry entry = e.getValue();
                CompoundTag tag = new CompoundTag();
                tag.putInt(KEY_CELL, e.getKey());
                ItemStack stack = entry.stackForWrite();
                GridMarker.strip(stack.getTag());
                tag.put(KEY_ITEM, stack.save(new CompoundTag()));
                tag.putInt(KEY_W, entry.size().w());
                tag.putInt(KEY_H, entry.size().h());
                tag.putBoolean(KEY_ROTATED, entry.rotated());
                list.add(tag);
            }
        }
        root.put(KEY_ENTRIES, list);
        return root;
    }

    /** 读取：不是锚点格式或读取异常时返回 {@code null}（调用方据此决定是否走迁移）。 */
    public static GridStore read(CompoundTag tag, int width, int rows, boolean[] usableMask) {
        if (!isAnchorFormat(tag)) {
            return null;
        }
        GridStore store = new GridStore(width, rows, usableMask);
        ListTag list = tag.getList(KEY_ENTRIES, 10);
        List<Integer> cells = new ArrayList<>(list.size());
        List<GridEntry> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (!entryTag.contains(KEY_ITEM, 10)) {
                continue;
            }
            ItemStack stack = ItemStack.of(entryTag.getCompound(KEY_ITEM));
            if (stack.isEmpty()) {
                continue;
            }
            GridMarker.strip(stack.getTag());
            int w = Math.max(1, entryTag.getInt(KEY_W));
            int h = Math.max(1, entryTag.getInt(KEY_H));
            boolean rotated = entryTag.getBoolean(KEY_ROTATED);
            cells.add(entryTag.contains(KEY_CELL) ? entryTag.getInt(KEY_CELL) : -1);
            entries.add(new GridEntry(stack, new GridSize(w, h), rotated));
        }
        placeTolerant(store, cells, entries, "读取存档");
        return store;
    }

    /**
     * 读取并兼容旧平铺格式：锚点格式优先；否则把 {@code legacyFlat}（下标 = 格号）迁移为锚点格式。
     *
     * <p>迁移规则（只移动不丢弃）：跳过历史占位物、按面积降序（大件优先）、
     * 姿态从物品 NBT 读取并剥离、容错安置。</p>
     *
     * @param legacyFlat 旧平铺槽位（可为 null）；元素下标即原格号
     * @param label      日志标签（如「仓库」「安全箱」），便于排障定位
     */
    public static GridStore read(CompoundTag tag, int width, int rows, boolean[] usableMask,
                                 List<ItemStack> legacyFlat, String label) {
        GridStore anchor = read(tag, width, rows, usableMask);
        if (anchor != null) {
            return anchor;
        }
        GridStore store = new GridStore(width, rows, usableMask);
        if (legacyFlat == null || legacyFlat.isEmpty()) {
            return store;
        }
        record Candidate(int cell, GridEntry entry) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int cell = 0; cell < legacyFlat.size(); cell++) {
            ItemStack stack = legacyFlat.get(cell);
            if (stack == null || stack.isEmpty() || GridMarker.isLegacyPlaceholder(stack)) {
                continue;
            }
            boolean rotated = GridMarker.isRotated(stack);
            ItemStack clean = GridMarker.stripped(stack);
            candidates.add(new Candidate(cell, GridEntry.of(clean, rotated)));
        }
        if (candidates.isEmpty()) {
            return store;
        }
        candidates.sort((a, b) -> {
            int byArea = Integer.compare(b.entry().area(), a.entry().area());
            return byArea != 0 ? byArea : Integer.compare(a.cell(), b.cell());
        });
        List<Integer> cells = new ArrayList<>(candidates.size());
        List<GridEntry> entries = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            cells.add(c.cell());
            entries.add(c.entry());
        }
        int[] stats = placeTolerant(store, cells, entries, "迁移");
        com.deltanexus.system.DeltaNexus.LOGGER.info(
                "[DN] {}网格迁移完成：条目 {} 件（原位保留 {}，移位 {}，退化 1x1 {}），容器 {}x{}",
                label, candidates.size(), candidates.size() - stats[0] - stats[1], stats[0], stats[1], width, rows);
        return store;
    }

    /**
     * 容错安置（读档与迁移共用）：<b>绝不静默丢弃物品</b>。
     *
     * <p>顺序：原位 → 首个空位 → 强制原位（可能落在未解锁格上）→ 退化 1x1（优先原位）→ 任意 1x1 空位。
     * 全部失败才 ERROR 上报（例如容器被缩到零可用格）。</p>
     *
     * @return {@code {移位数量, 退化数量}}
     */
    private static int[] placeTolerant(GridStore store, List<Integer> cells, List<GridEntry> entries, String label) {
        int moved = 0;
        int degraded = 0;
        for (int i = 0; i < entries.size(); i++) {
            GridEntry entry = entries.get(i);
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            int cell = i < cells.size() ? cells.get(i) : -1;
            if (cell >= 0 && store.place(cell, entry).ok()) {
                continue;
            }
            int spot = store.findFreeSpot(entry.size());
            if (spot >= 0 && store.place(spot, entry).ok()) {
                moved++;
                continue;
            }
            if (cell >= 0 && store.placeForced(cell, entry).ok()) {
                degraded++;
                continue;
            }
            GridEntry single = GridEntry.of(entry.stackForWrite(), GridSize.SINGLE, false);
            if (cell >= 0 && (store.place(cell, single).ok() || store.placeForced(cell, single).ok())) {
                degraded++;
                continue;
            }
            int anywhere = store.findFreeSpot(GridSize.SINGLE);
            if (anywhere >= 0 && store.place(anywhere, single).ok()) {
                degraded++;
                continue;
            }
            com.deltanexus.system.DeltaNexus.LOGGER.error(
                    "[DN] 网格{}无可用位置，物品暂未安置（槽位 {}：{}）——请扩大容器后重登",
                    label, cell, entry.stackForWrite().getItem());
        }
        return new int[]{moved, degraded};
    }
}