package com.deltanexus.system.grid.v2;

import com.deltanexus.system.grid.GridSizes;
import com.deltanexus.system.grid.core.GridDim;
import com.deltanexus.system.grid.core.GridTags;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 网格存档（重写版内核，v2）：<b>只存锚点，绝不存派生态</b>。
 *
 * <p>新格式（{@code format_version = 1}）：</p>
 * <pre>
 * {
 *   format_version: 1,
 *   entries: [
 *     { cell: 0, item: {…ItemStack NBT…}, w: 2, h: 2, rotated: 0b }
 *   ]
 * }
 * </pre>
 *
 * <p>为什么这样存：旧格式把“谁的足迹覆盖了谁”写进物品 NBT（占位物），于是任何外部写入都能破坏它、
 * 每次修复都要重写格子（= 造物品）。新格式里足迹是读出来现算的，存档只有事实（哪件物品、多大、转没转），
 * 因此不存在“存档与内存不一致”这种状态。</p>
 *
 * <p>{@link #migrateFrom} 负责旧平铺格式 → 锚点格式，规则见方法注释：只移动不丢弃。</p>
 */
public final class GridStorage {

    public static final int FORMAT_VERSION = 1;
    private static final String KEY_FORMAT = "format_version";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_CELL = "cell";
    private static final String KEY_ITEM = "item";
    private static final String KEY_W = "w";
    private static final String KEY_H = "h";
    private static final String KEY_ROTATED = "rotated";

    private GridStorage() {
    }

    /** 写出（只写锚点）。 */
    public static CompoundTag write(GridInventory inv) {
        CompoundTag root = new CompoundTag();
        root.putInt(KEY_FORMAT, FORMAT_VERSION);
        ListTag list = new ListTag();
        if (inv != null) {
            for (var e : inv.entries().entrySet()) {
                GridEntry entry = e.getValue();
                CompoundTag tag = new CompoundTag();
                tag.putInt(KEY_CELL, e.getKey());
                tag.put(KEY_ITEM, e.getValue().stackForWrite().save(new CompoundTag()));
                tag.putInt(KEY_W, entry.dim().w());
                tag.putInt(KEY_H, entry.dim().h());
                tag.putBoolean(KEY_ROTATED, entry.rotated());
                list.add(tag);
            }
        }
        root.put(KEY_ENTRIES, list);
        return root;
    }

    /**
     * 读取。
     *
     * @return 读取成功返回容器；{@code null} = 不是新格式（调用方应走 {@link #migrateFrom}）
     */
    public static GridInventory read(CompoundTag tag, int width, int rows, boolean[] usableMask) {
        if (tag == null || !tag.contains(KEY_FORMAT) || tag.getInt(KEY_FORMAT) != FORMAT_VERSION) {
            return null;
        }
        GridInventory inv = new GridInventory(width, rows, usableMask);
        List<Integer> cells = new ArrayList<>();
        List<GridEntry> entries = new ArrayList<>();
        ListTag list = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            ItemStack stack = ItemStack.of(entryTag.getCompound(KEY_ITEM));
            if (stack.isEmpty()) {
                continue;
            }
            int w = Math.max(1, entryTag.getInt(KEY_W));
            int h = Math.max(1, entryTag.getInt(KEY_H));
            boolean rotated = entryTag.getBoolean(KEY_ROTATED);
            cells.add(entryTag.contains(KEY_CELL) ? entryTag.getInt(KEY_CELL) : -1);
            entries.add(new GridEntry(stack, new GridDim(w, h), rotated));
        }
        // 容错安置：绝不因为“放不下”而返回空容器（那会导致下次保存把仓库写成空的 = 物品永久消失）
        placeTolerant(inv, cells, entries, "读取存档");
        return inv;
    }

    /**
     * 旧平铺格式 → 锚点格式（一次性迁移）。
     *
     * <p>规则（只移动不丢弃，顺序固定、结果确定）：</p>
     * <ol>
     *   <li>逐格扫描，跳过错位/占位物（{@code is_slave}）；</li>
     *   <li>按面积降序处理（大件优先，减少碎片）；</li>
     *   <li>先试<b>原位</b> → 再找<b>首个空位</b> → 都不行则<b>退化 1x1 保留原位</b>；</li>
     *   <li>物品上残留的网格 NBT 键（旧的旋转/占位标记）在此剥离——姿态改由条目承载。</li>
     * </ol>
     */
    public static GridInventory migrateFrom(ItemStackHandler legacy, int width, int rows, boolean[] usableMask) {
        GridInventory inv = new GridInventory(width, rows, usableMask);
        if (legacy == null) {
            return inv;
        }
        record Candidate(int cell, GridEntry entry, boolean rotatedFromNbt) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int cell = 0; cell < legacy.getSlots(); cell++) {
            ItemStack stack = legacy.getStackInSlot(cell);
            if (stack.isEmpty() || GridTags.isSlave(stack)) {
                continue; // 占位物是历史派生态，直接丢弃（不迁移）
            }
            boolean rotated = GridTags.isRotated(stack);
            ItemStack clean = GridTags.stripped(stack); // 剥离旧网格 NBT（姿态改由条目承载）
            candidates.add(new Candidate(cell, GridEntry.of(clean, rotated), rotated));
        }
        candidates.sort((a, b) -> {
            int byArea = Integer.compare(b.entry().area(), a.entry().area());
            return byArea != 0 ? byArea : Integer.compare(a.cell(), b.cell());
        });

        int moved = 0;
        int degraded = 0;
        List<Integer> cells = new ArrayList<>(candidates.size());
        List<GridEntry> entries = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            cells.add(c.cell());
            entries.add(c.entry());
        }
        int[] stats = placeTolerant(inv, cells, entries, "迁移");
        moved = stats[0];
        degraded = stats[1];
        com.deltanexus.system.DeltaNexus.LOGGER.info(
                "[DN] 网格格式迁移完成：条目 {} 件（原位保留 {}，移位 {}，退化 1x1 {}），容器 {}x{}",
                candidates.size(), candidates.size() - moved - degraded, moved, degraded, width, rows);
        return inv;
    }

    /**
     * 容错安置（读取存档与旧格式迁移共用）：<b>绝不静默丢弃物品</b>。
     *
     * <p>顺序：原位 → 首个空位 → 强制原位（可能落在锁定格上）→ 退化 1x1（优先原位）→ 任意空位 1x1。
     * 全部失败才 ERROR 上报（容器被缩到零可用格这类极端情况）。</p>
     *
     * @return {移位数量, 退化数量}
     */
    private static int[] placeTolerant(GridInventory inv, List<Integer> cells, List<GridEntry> entries, String label) {
        int moved = 0;
        int degraded = 0;
        for (int i = 0; i < entries.size(); i++) {
            GridEntry entry = entries.get(i);
            int cell = i < cells.size() ? cells.get(i) : -1;
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            if (cell >= 0 && inv.place(cell, entry).ok()) {
                continue;
            }
            int spot = inv.findFreeSpot(entry.dim());
            if (spot >= 0 && inv.place(spot, entry).ok()) {
                moved++;
                continue;
            }
            if (cell >= 0 && inv.placeForced(cell, entry).ok()) {
                degraded++;
                continue;
            }
            GridEntry one = GridEntry.of(entry.stackForWrite(), GridDim.ONE, false);
            if ((cell >= 0 && (inv.place(cell, one).ok() || inv.placeForced(cell, one).ok()))) {
                degraded++;
                continue;
            }
            int anywhere = inv.findFreeSpot(GridDim.ONE);
            if (anywhere >= 0 && inv.place(anywhere, one).ok()) {
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
