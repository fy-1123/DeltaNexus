package com.deltanexus.system.client;

import com.deltanexus.system.client.gui.DnUiLayout;
import com.deltanexus.system.grid.GearConfig;
import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.grid.GridEntry;
import com.deltanexus.system.grid.GridNbt;
import com.deltanexus.system.grid.GridStore;
import com.deltanexus.system.grid.StoreContainer;
import com.deltanexus.system.network.packet.C2SOpenGearWindowPacket;
import com.deltanexus.system.network.packet.SyncGearWindowPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 客户端装备窗口缓存（0.5.0Beta 嵌套补丁）——只保存服务端下发的窗口真相。
 *
 * <p>每个窗口由 {@code (rootType, rootRef, path)} 定位，内容由 {@link SyncGearWindowPacket}
 * 整份替换（内核锚点格式，一次 {@link GridNbt#read} 还原）。窗口位置（{@link Window#x} /
 * {@link Window#y}）由客户端自己维护，服务端不参与。</p>
 *
 * <p>关闭是级联的：收起某一层窗口时，它的所有下级窗口一并移除（路径失效）。</p>
 */
public final class GearWindowState {

    /** 标题栏高（像素）。 */
    public static final int TITLE_H = 16;
    /** 内容内边距（像素）。 */
    public static final int PAD = 6;
    /** 网格步距（与内嵌网格一致）。 */
    public static final int PITCH = DnUiLayout.SLOT_PITCH;

    /** 一个窗口。 */
    public static final class Window {

        public final int rootType;
        public final int rootRef;
        public final int[] path;
        public final StoreContainer container;
        public ItemStack gear;
        /** 窗口左上角（屏幕 GUI 坐标，拖动改这里）。 */
        public int x;
        public int y;

        Window(int rootType, int rootRef, int[] path, StoreContainer container, ItemStack gear, int x, int y) {
            this.rootType = rootType;
            this.rootRef = rootRef;
            this.path = path;
            this.container = container;
            this.gear = gear;
            this.x = x;
            this.y = y;
        }
    }

    private static final List<Window> WINDOWS = new ArrayList<>();
    /** 层叠偏移计数（新窗口交错排列，避免完全重叠）。 */
    private static int cascade;

    private GearWindowState() {
    }

    public static List<Window> windows() {
        return WINDOWS;
    }

    /** 窗口内容宽（像素）。 */
    public static int width(Window w) {
        return PAD * 2 + Math.max(1, w.container.store().width()) * PITCH + 1;
    }

    /** 窗口内容高（像素）。 */
    public static int height(Window w) {
        return TITLE_H + PAD * 2 + Math.max(1, w.container.store().rows()) * PITCH;
    }

    /** 网格区左上角 x。 */
    public static int gridX(Window w) {
        return w.x + PAD;
    }

    /** 网格区左上角 y。 */
    public static int gridY(Window w) {
        return w.y + TITLE_H + PAD;
    }

    /** 收到窗口同步：close = 收起该窗口及其下级；否则新建或整份刷新。 */
    public static void receive(SyncGearWindowPacket msg) {
        if (msg.close) {
            remove(msg.rootType, msg.rootRef, msg.path, true);
            return;
        }
        GridStore store = GridNbt.read(msg.grid, msg.width, msg.height, null);
        if (store == null) {
            store = new GridStore(msg.width, msg.height);
        }
        Window existing = find(msg.rootType, msg.rootRef, msg.path);
        if (existing != null) {
            existing.container.reset(store);
            existing.gear = msg.gear;
        } else {
            int[] pos = defaultPosition(store.width(), store.rows());
            WINDOWS.add(new Window(msg.rootType, msg.rootRef, msg.path.clone(),
                    new StoreContainer(store), msg.gear, pos[0], pos[1]));
        }
        // 刷新之后级联校验：父窗口里那件装备一旦被取走，其所有下级窗口立即失效
        validateAll();
    }

    /** 移除窗口（{@code pruneChildren} = 同时移除其所有下级）。 */
    public static void remove(int rootType, int rootRef, int[] path, boolean pruneChildren) {
        for (int i = WINDOWS.size() - 1; i >= 0; i--) {
            Window w = WINDOWS.get(i);
            if (w.rootType != rootType || w.rootRef != rootRef) {
                continue;
            }
            if (Arrays.equals(w.path, path) || (pruneChildren && isDescendant(w.path, path))) {
                WINDOWS.remove(i);
            }
        }
    }

    /**
     * 鼠标是否落在某个浮动窗口内（提示层据此避免在下层界面再画一次槽位提示）。
     */
    public static boolean isOverWindow(double mx, double my) {
        for (Window w : WINDOWS) {
            if (mx >= w.x && mx < w.x + width(w) && my >= w.y && my < w.y + height(w)) {
                return true;
            }
        }
        return false;
    }

    /** 已装备几何刷新后校验窗口是否仍然有效（装备卸下 / 下层装备被取走则收起）。 */
    public static void onGearSync(GearKind kind) {
        validateAll();
    }

    /**
     * 级联校验全部窗口：<b>逐层</b>确认「上级容器里仍然放着这件装备」。
     *
     * <p>0.5.0Beta 修复：原实现只看路径第一层，导致「套好的包里把胸挂/背包拿出来后，
     * 该胸挂/背包的窗口不会自动关闭」——窗口停留在界面上，里面的数据也不再有效。</p>
     *
     * <p>判定所需数据全部在客户端：第 0 层是已装备装备的内容（{@link GearClientState}），
     * 第 k 层（k ≥ 1）是路径长度为 k 的那个窗口的内容。任何一层解析不出装备，
     * 该窗口及其所有下级窗口一并收起。</p>
     */
    public static void validateAll() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = WINDOWS.size() - 1; i >= 0; i--) {
                if (!resolvable(WINDOWS.get(i))) {
                    Window w = WINDOWS.get(i);
                    remove(w.rootType, w.rootRef, w.path, true);
                    changed = true;
                }
            }
        }
    }

    /** 该窗口的定位链路是否仍然成立（见 {@link #validateAll()}）。 */
    private static boolean resolvable(Window w) {
        GridStore store = rootStore(w);
        if (store == null) {
            return false;
        }
        for (int depth = 0; depth < w.path.length; depth++) {
            int cell = w.path[depth];
            if (cell < 0 || cell >= store.size()) {
                return false;
            }
            int anchor = store.anchorAt(cell);
            GridEntry entry = anchor < 0 ? null : store.entryAt(anchor);
            if (entry == null || entry.isEmpty() || !GearConfig.isGear(entry.stack())) {
                return false;
            }
            if (depth == w.path.length - 1) {
                return true;
            }
            // 下一层的存储 = 路径为「前缀」的那个窗口的内容；该窗口不在（已被收起）时本级失效
            Window child = find(w.rootType, w.rootRef, Arrays.copyOf(w.path, depth + 1));
            if (child == null) {
                return false;
            }
            store = child.container.store();
        }
        return true;
    }

    /** 窗口根所在容器的内容（已装备装备的内容网格；菜单槽根暂不支持）。 */
    private static GridStore rootStore(Window w) {
        if (w.rootType != C2SOpenGearWindowPacket.ROOT_EQUIPPED) {
            return null;
        }
        GearKind kind = kindOf(w.rootRef);
        if (kind == null || GearClientState.equipped(kind).isEmpty()) {
            return null;
        }
        return GearClientState.container(kind).store();
    }

    private static GearKind kindOf(int ref) {
        GearKind[] values = GearKind.values();
        return ref >= 0 && ref < values.length ? values[ref] : null;
    }

    /** 界面切换时清空全部窗口。 */
    public static void clear() {
        WINDOWS.clear();
        cascade = 0;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Window find(int rootType, int rootRef, int[] path) {
        for (Window w : WINDOWS) {
            if (w.rootType == rootType && w.rootRef == rootRef && Arrays.equals(w.path, path)) {
                return w;
            }
        }
        return null;
    }

    /** 下级窗口：路径以 {@code parent} 为前缀且更长。 */
    private static boolean isDescendant(int[] child, int[] parent) {
        if (child.length <= parent.length) {
            return false;
        }
        for (int i = 0; i < parent.length; i++) {
            if (child[i] != parent[i]) {
                return false;
            }
        }
        return true;
    }

    /** 默认位置：屏幕居中 + 逐窗口交错偏移。 */
    private static int[] defaultPosition(int cols, int rows) {
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int w = PAD * 2 + Math.max(1, cols) * PITCH + 1;
        int h = TITLE_H + PAD * 2 + Math.max(1, rows) * PITCH;
        int off = (cascade++ % 6) * 16;
        int x = Math.max(4, Math.min(sw - w - 4, (sw - w) / 2 + 40 + off));
        int y = Math.max(4, Math.min(sh - h - 4, (sh - h) / 2 - 40 + off));
        return new int[]{x, y};
    }
}
