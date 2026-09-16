package com.deltanexus.system.grid.v2;

import com.deltanexus.system.grid.GridSizes;
import com.deltanexus.system.grid.core.GridDim;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 玩家背包派生视图（重写版内核 v2，第二阶段）。
 *
 * <p>玩家背包的存储仍然是原版 {@code Inventory}（死亡掉落、其他模组、盔甲/副手都依赖原版语义），
 * 因此这里不持有数据，而是<b>从槽位内容派生</b>网格占用——与 {@link GridInventory} 同一套判定口径：</p>
 * <ul>
 *   <li>占用派生：锚点 = 有物品的格；足迹 = 锚点 + 尺寸（尺寸来自配置表 + 物品 NBT 的旋转键）；</li>
 *   <li>合法性：足迹不越界、不跨行分区（背包区 / 口袋区）、不与其它足迹重叠；</li>
 *   <li>修复：只产出「把某件物品从 A 挪到 B」这类<b>只移动</b>的计划，绝不创建/删除物品。</li>
 * </ul>
 *
 * <p>本类纯读、无副作用、不写任何容器——写入由调用方按计划执行（菜单层）。</p>
 */
public final class PlayerInventoryView {

    /** 一次合法位移（只移动，不改变物品数量）。 */
    public record Move(int from, int to) {
    }

    /** 派生结果。 */
    public record Report(int[] owner, String violation, List<Move> moves, List<Integer> stuck) {

        public boolean consistent() {
            return violation == null;
        }
    }

    private PlayerInventoryView() {
    }

    /**
     * 派生 + 体检 + 位移计划。
     *
     * @param cells   与容器对齐的槽位内容（null/空 = 空格）
     * @param dims    每格实际尺寸（已含便捷栏规则折算；空格可为 1x1）
     * @param zone    每格所属分区（分区内的足迹不允许跨越；null = 不分分区）
     * @param usable  每格是否可用（锁定格不可落位、不可被跨越；null = 全可用）
     */
    public static Report analyse(List<ItemStack> cells, List<GridDim> dims, int width, int rows,
                                 int[] zone, boolean[] usable) {
        int size = Math.max(0, cells == null ? 0 : cells.size());
        int w = Math.max(1, width);
        int r = Math.max(1, rows);
        int[] owner = new int[size];
        Arrays.fill(owner, -1);
        if (size == 0) {
            return new Report(owner, null, List.of(), List.of());
        }

        List<Integer> movers = new ArrayList<>();
        String violation = null;
        for (int i = 0; i < size; i++) {
            ItemStack stack = cells.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            GridDim dim = i < dims.size() && dims.get(i) != null ? dims.get(i) : GridDim.ONE;
            String deny = footprintDeny(i, dim, w, r, zone, usable, owner);
            if (deny != null) {
                if (violation == null) {
                    violation = deny + "（格 " + i + "）";
                }
                movers.add(i);
                continue;
            }
            for (int cell : footprint(i, dim, w)) {
                if (cell >= 0 && cell < size) {
                    owner[cell] = i;
                }
            }
        }

        // 位移计划：把非法/重叠者挪到首个空闲位（只移动，绝不丢弃）
        List<Move> moves = new ArrayList<>();
        List<Integer> stuck = new ArrayList<>();
        boolean[] taken = new boolean[size];
        for (int cell = 0; cell < size; cell++) {
            if (owner[cell] >= 0) {
                taken[cell] = true;
            }
        }
        for (int from : movers) {
            ItemStack stack = cells.get(from);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            GridDim dim = from < dims.size() && dims.get(from) != null ? dims.get(from) : GridDim.ONE;
            taken[from] = false; // 自身腾出来
            int spot = firstFree(dim, w, r, zone, usable, owner, taken);
            if (spot < 0) {
                // 退化 1x1 再找
                dim = GridDim.ONE;
                spot = firstFree(dim, w, r, zone, usable, owner, taken);
            }
            if (spot < 0) {
                stuck.add(from);
                continue;
            }
            moves.add(new Move(from, spot));
            for (int cell : footprint(spot, dim, w)) {
                if (cell >= 0 && cell < size) {
                    taken[cell] = true;
                }
            }
        }
        return new Report(owner, violation, List.copyOf(moves), List.copyOf(stuck));
    }

    /** 便捷构造：按物品内容填尺寸与分区（背包 27 格 + 快捷栏 9 格，clamp 到 9 列）。 */
    public static Report analyseInventory(List<ItemStack> cells, int width, int rows, int[] zone, boolean[] usable) {
        List<GridDim> dims = new ArrayList<>();
        for (ItemStack stack : cells) {
            dims.add(stack == null || stack.isEmpty() ? GridDim.ONE : GridSizes.baseDim(stack));
        }
        return analyse(cells, dims, width, rows, zone, usable);
    }

    // ------------------------------------------------------------------

    private static String footprintDeny(int anchor, GridDim dim, int width, int rows,
                                        int[] zone, boolean[] usable, int[] owner) {
        int col = anchor % width;
        int row = anchor / width;
        if (col + dim.w() > width || row + dim.h() > rows) {
            return "足迹超出容器";
        }
        int myZone = zone == null || anchor >= zone.length ? 0 : zone[anchor];
        for (int cell : footprint(anchor, dim, width)) {
            if (cell < 0 || cell >= owner.length) {
                return "足迹超出容器";
            }
            if (usable != null && cell < usable.length && !usable[cell]) {
                return "足迹压在锁定格上";
            }
            if (zone != null && cell < zone.length && zone[cell] != myZone) {
                return "足迹跨越分区";
            }
            if (owner[cell] != -1 && owner[cell] != anchor) {
                return "与另一件物品的足迹重叠";
            }
        }
        return null;
    }

    private static int firstFree(GridDim dim, int width, int rows, int[] zone, boolean[] usable,
                                 int[] owner, boolean[] taken) {
        int size = owner.length;
        for (int cell = 0; cell < size; cell++) {
            int col = cell % width;
            int row = cell / width;
            if (col + dim.w() > width || row + dim.h() > rows) {
                continue;
            }
            int myZone = zone == null || cell >= zone.length ? 0 : zone[cell];
            boolean ok = true;
            for (int c : footprint(cell, dim, width)) {
                if (c < 0 || c >= size || taken[c] || owner[c] != -1
                        || (usable != null && c < usable.length && !usable[c])
                        || (zone != null && c < zone.length && zone[c] != myZone)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return cell;
            }
        }
        return -1;
    }

    private static int[] footprint(int anchor, GridDim dim, int width) {
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
}
