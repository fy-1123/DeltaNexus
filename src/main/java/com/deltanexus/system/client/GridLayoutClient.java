package com.deltanexus.system.client;

import com.deltanexus.system.network.packet.SyncGridLayoutPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端网格布局缓存（0.3.0Beta 第二阶段）。
 *
 * <p>由 {@link SyncGridLayoutPacket} 填充：{@code 锚点菜单槽位 → 尺寸/旋转}，
 * 以及反查表 {@code 任意覆盖格 → 锚点菜单槽位}。客户端渲染与点击重定向都以它为准，
 * 不再靠占位物 NBT 或自行推导几何——这样服务端与客户端共享同一份几何真相。</p>
 *
 * <p>仅内存、随会话失效；没有布局信息时（旧服务端/未下发）调用方回退到占位物 NBT 判定。</p>
 */
public final class GridLayoutClient {

    /** 当前布局版本（单调递增；小版本号的包被忽略）。 */
    private static volatile long revision = -1L;
    private static volatile Map<Integer, SyncGridLayoutPacket.Entry> byAnchor = Map.of();
    /** 当前布局对应的菜单容器 id（切换菜单即失效）。 */
    private static volatile int currentContainer = Integer.MIN_VALUE;

    private GridLayoutClient() {
    }

    /** 应用服务端布局（仅接受更新的版本；切换菜单时自动重置）。 */
    public static void apply(int containerId, long newRevision, List<SyncGridLayoutPacket.Entry> entries) {
        if (containerId != currentContainer) {
            currentContainer = containerId;
            revision = -1L;
            byAnchor = Map.of();
        }
        if (newRevision < revision) {
            return;
        }
        Map<Integer, SyncGridLayoutPacket.Entry> anchors = new HashMap<>();
        if (entries != null) {
            for (SyncGridLayoutPacket.Entry e : entries) {
                if (e.w() <= 0 || e.h() <= 0 || e.rowStride() <= 0) {
                    continue;
                }
                anchors.put(e.anchorSlot(), e);
            }
        }
        revision = newRevision;
        byAnchor = Map.copyOf(anchors);
    }

    /** 清空（切换菜单/断线）。 */
    public static void reset() {
        revision = -1L;
        byAnchor = Map.of();
        currentContainer = Integer.MIN_VALUE;
    }

    public static long revision() {
        return revision;
    }

    public static boolean hasLayout() {
        return revision >= 0;
    }

    /** 该菜单槽位是否为跨格物品的锚点；返回其布局条目（非锚点返回 null）。 */
    public static SyncGridLayoutPacket.Entry anchorEntry(int menuSlot) {
        return byAnchor.get(menuSlot);
    }

    /** 当前布局中的全部锚点条目（渲染用）。 */
    public static Map<Integer, SyncGridLayoutPacket.Entry> anchors() {
        return byAnchor;
    }

    /** 当前布局覆盖的菜单槽位集合（含锚点自身；渲染时据此避免重复绘制）。 */
    public static java.util.Set<Integer> coveredSlots() {
        if (byAnchor.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<Integer> covered = new java.util.HashSet<>();
        for (Map.Entry<Integer, SyncGridLayoutPacket.Entry> e : byAnchor.entrySet()) {
            int anchor = e.getKey();
            SyncGridLayoutPacket.Entry entry = e.getValue();
            int stride = entry.rowStride();
            int anchorCol = anchor % stride;
            int anchorRow = anchor / stride;
            for (int dy = 0; dy < entry.h(); dy++) {
                for (int dx = 0; dx < entry.w(); dx++) {
                    covered.add((anchorRow + dy) * stride + anchorCol + dx);
                }
            }
        }
        return covered;
    }

    /**
     * 点击重定向（客户端）：优先用服务端布局把「覆盖格」指向锚点，无布局信息时回退到占位物 NBT 判定。
     *
     * <p>布局优先的意义：客户端不再依赖占位物是否恰好存在/正确，与服务端几何完全一致。</p>
     */
    public static net.minecraft.world.inventory.Slot masterSlot(
            net.minecraft.world.inventory.AbstractContainerMenu menu,
            net.minecraft.world.inventory.Slot clicked) {
        if (menu == null || clicked == null) {
            return clicked;
        }
        int anchor = anchorOf(clicked.index);
        if (anchor >= 0 && anchor != clicked.index && anchor < menu.slots.size()) {
            return menu.slots.get(anchor);
        }
        return com.deltanexus.system.grid.adapter.MenuGridAdapter.resolveMaster(menu, clicked);
    }

    /**
     * 该菜单槽位所属的锚点槽位；不属于任何跨格物品时返回 -1。
     *
     * <p>覆盖范围完全由服务端下发的 {@code anchor + w/h + rowStride} 决定，客户端不做任何几何假设。</p>
     */
    public static int anchorOf(int menuSlot) {
        if (byAnchor.isEmpty()) {
            return -1;
        }
        for (Map.Entry<Integer, SyncGridLayoutPacket.Entry> e : byAnchor.entrySet()) {
            int anchor = e.getKey();
            SyncGridLayoutPacket.Entry entry = e.getValue();
            int stride = entry.rowStride();
            int anchorCol = anchor % stride;
            int anchorRow = anchor / stride;
            int col = menuSlot % stride;
            int row = menuSlot / stride;
            if (col >= anchorCol && col < anchorCol + entry.w()
                    && row >= anchorRow && row < anchorRow + entry.h()) {
                return anchor;
            }
        }
        return -1;
    }
}
