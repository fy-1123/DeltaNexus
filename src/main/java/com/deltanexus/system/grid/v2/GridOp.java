package com.deltanexus.system.grid.v2;

import com.deltanexus.system.grid.core.GridDim;

/**
 * 网格操作（重写版内核，v2）：<b>改动网格的唯一入口</b>。
 *
 * <p>所有变更（手动放置、快捷移动、交换、丢弃、旋转、整理、交易交付、指令与 Web 写入）都必须表达成
 * 一个 {@code GridOp} 交给 {@link GridInventory#apply}；内核负责：影子推演 → 不变式校验 → 守恒校验 →
 * 提交（或整体拒绝）。没有第二条写格子的路径，因此不存在“半写/互相覆盖/凭空造物品”。</p>
 */
public sealed interface GridOp {

    /** 落位：把 {@code entry} 放到锚点 {@code cell}。 */
    record Place(int cell, GridEntry entry) implements GridOp {
    }

    /** 取出锚点上的整件物品（返回给调用方）。 */
    record Take(int cell) implements GridOp {
    }

    /** 把锚点 {@code from} 的条目整体移到 {@code to}（可顺带换姿态）。 */
    record Move(int from, int to, Boolean rotated) implements GridOp {
    }

    /** 就地旋转锚点 {@code cell} 的条目。 */
    record Rotate(int cell) implements GridOp {
    }

    /** 显式整理：按面积降序重排（唯一允许“全局移动”的入口，且必须由玩家/管理员显式触发）。 */
    record Compact() implements GridOp {
    }

    /** 语义校验用的最小足迹尺寸（用于日志与错误信息）。 */
    default GridDim hintDim() {
        return GridDim.ONE;
    }
}
