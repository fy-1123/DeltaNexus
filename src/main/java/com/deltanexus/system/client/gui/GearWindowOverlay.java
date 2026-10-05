package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.client.GearWindowState;
import com.deltanexus.system.grid.GearConfig;
import com.deltanexus.system.grid.GridClassConfig;
import com.deltanexus.system.grid.GridClientRendering;
import com.deltanexus.system.grid.GridEntry;
import com.deltanexus.system.grid.GridSize;
import com.deltanexus.system.grid.GridStore;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SOpenGearWindowPacket;
import com.deltanexus.system.network.packet.C2SGearWindowClickPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 装备窗口覆盖层（0.5.0Beta 嵌套补丁）——Windows 风格的浮动窗口。
 *
 * <p>只在类塔克夫界面（背包 / 容器 / 仓库）之上叠加：按住标题栏拖动、右上角按钮关闭、
 * 窗口内网格可交互（左键取放、右键取半/放一、Shift 转移），服务端权威。<b>右键</b>点击
 * 窗口内的装备物品继续下沉打开下一层窗口。</p>
 *
 * <p>窗口以屏幕 GUI 坐标绘制（不随界面缩放），因此大小恒定，像桌面窗口一样浮动在界面上方。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID, value = Dist.CLIENT)
public final class GearWindowOverlay {

    /** 关闭按钮边长。 */
    private static final int CLOSE_SIZE = 10;
    /** 浮动窗口整体 z 偏移（盖住槽位物品 / 跨格大图标 / 提示框，见 {@link #renderWindow}）。 */
    private static final int WINDOW_Z = 900;

    private static GearWindowState.Window dragging;
    private static int dragOffsetX;
    private static int dragOffsetY;

    private GearWindowOverlay() {
    }

    /** 该界面是否承载窗口（类塔克夫容器界面）。 */
    private static boolean applies(Screen screen) {
        if (!com.deltanexus.system.config.ClientUiConfig.featuresEnabled()) {
            return false;
        }
        return screen instanceof BackpackScreen
                || screen instanceof DnContainerScreen
                || screen instanceof WarehouseScreen;
    }

    /** 界面打开 / 重排：清空旧窗口。 */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        GearWindowState.clear();
        dragging = null;
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!applies(event.getScreen()) || GearWindowState.windows().isEmpty()) {
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();
        double mx = event.getMouseX();
        double my = event.getMouseY();
        for (GearWindowState.Window w : GearWindowState.windows()) {
            renderWindow(gg, w, mx, my);
        }
    }

    private static void renderWindow(GuiGraphics gg, GearWindowState.Window w, double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        int x = w.x;
        int y = w.y;
        int width = GearWindowState.width(w);
        int height = GearWindowState.height(w);

        // 浮动窗口必须盖住下层界面的一切内容：槽位物品 z≈150、跨格大图标 z≈550、悬停提示 z≈400。
        // 整窗上抬 WINDOW_Z（窗口内的背景墙 / 大图标 / 提示框再各自叠加自己的 z），
        // 配合人物预览后的深度清理，窗口才真正「在最上面」。
        gg.pose().pushPose();
        gg.pose().translate(0, 0, WINDOW_Z);

        DnUiTheme.drawPanelShell(gg, x, y, width, height);
        gg.fill(x + 1, y + 1, x + width - 1, y + GearWindowState.TITLE_H, DnUiTheme.HEADER_BACKGROUND);
        String title = w.gear.isEmpty() ? "容器" : w.gear.getHoverName().getString();
        DnUiTheme.drawStringWithShadow(gg, mc.font, title, x + 6, y + 4, DnUiTheme.TEXT_PRIMARY);

        // 关闭按钮（右上角）
        int closeX = closeX(w);
        int closeY = closeY(w);
        boolean overClose = inRect(mx, my, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE);
        gg.fill(closeX, closeY, closeX + CLOSE_SIZE, closeY + CLOSE_SIZE,
                overClose ? DnUiTheme.TEXT_WARNING : DnUiTheme.PANEL_BACKGROUND);
        DnUiTheme.drawStringWithShadow(gg, mc.font, "x", closeX + 3, closeY + 1, DnUiTheme.TEXT_PRIMARY);

        // 网格底框与物品
        GridStore store = w.container.store();
        int cols = Math.max(1, store.width());
        int gx = GearWindowState.gridX(w);
        int gy = GearWindowState.gridY(w);
        for (int cell = 0; cell < store.size(); cell++) {
            if (w.container.isCovered(cell)) {
                continue;
            }
            int sx = gx + (cell % cols) * GearWindowState.PITCH;
            int sy = gy + (cell / cols) * GearWindowState.PITCH;
            GridEntry entry = store.entryAt(cell);
            if (entry != null && !entry.isEmpty()) {
                DnUiTheme.drawRaritySlot(gg, sx, sy, entry.stack());
            } else {
                DnUiTheme.drawSlotBackdrop(gg, sx, sy);
            }
        }
        for (Map.Entry<Integer, GridEntry> e : store.entries().entrySet()) {
            GridEntry entry = e.getValue();
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            int anchor = e.getKey();
            drawEntry(gg, entry, gx + (anchor % cols) * GearWindowState.PITCH,
                    gy + (anchor / cols) * GearWindowState.PITCH);
        }

        // 悬停高亮与提示
        int cell = cellAt(w, mx, my);
        if (cell >= 0) {
            int anchor = store.anchorAt(cell);
            int show = anchor >= 0 ? anchor : cell;
            DnUiTheme.drawSlotHover(gg, gx + (show % cols) * GearWindowState.PITCH,
                    gy + (show / cols) * GearWindowState.PITCH);
            GridEntry entry = store.entryAt(show);
            if (entry != null && !entry.isEmpty()) {
                gg.renderTooltip(mc.font, entry.stack(), (int) mx, (int) my);
            }
        }
        gg.pose().popPose();
    }

    /** 单件物品绘制（1x1 类色 / 跨格，与内嵌网格同口径）。 */
    private static void drawEntry(GuiGraphics gg, GridEntry entry, int x, int y) {
        GridSize size = entry.size();
        InventoryGridHandler.ItemDim dim = new InventoryGridHandler.ItemDim(size.w(), size.h());
        if (dim.is1x1()) {
            if (GridClassConfig.isClassed(entry.stack())) {
                GridClientRendering.renderGridStack(gg, entry.stack(), x, y, dim, false,
                        GridClassConfig.bgOf(entry.stack()));
            } else {
                gg.renderItem(entry.stack(), x, y);
                gg.renderItemDecorations(Minecraft.getInstance().font, entry.stack(), x, y);
            }
        } else {
            GridClientRendering.renderGridStack(gg, entry.stack(), x, y, dim, entry.rotated());
        }
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.isCanceled() || !applies(event.getScreen())) {
            return;
        }
        List<GearWindowState.Window> windows = GearWindowState.windows();
        for (int i = windows.size() - 1; i >= 0; i--) {
            GearWindowState.Window w = windows.get(i);
            int x = w.x;
            int y = w.y;
            int width = GearWindowState.width(w);
            int height = GearWindowState.height(w);
            if (!inRect(event.getMouseX(), event.getMouseY(), x, y, width, height)) {
                continue;
            }
            // 命中即置顶
            windows.remove(i);
            windows.add(w);

            if (inRect(event.getMouseX(), event.getMouseY(), closeX(w), closeY(w), CLOSE_SIZE, CLOSE_SIZE)) {
                if (event.getButton() == 0) {
                    GearWindowState.remove(w.rootType, w.rootRef, w.path, true);
                }
                event.setCanceled(true);
                return;
            }
            if (event.getButton() == 0 && event.getMouseY() < y + GearWindowState.TITLE_H) {
                dragging = w;
                dragOffsetX = (int) event.getMouseX() - x;
                dragOffsetY = (int) event.getMouseY() - y;
                event.setCanceled(true);
                return;
            }
            if (event.getButton() == 0 || event.getButton() == 1) {
                int cell = cellAt(w, event.getMouseX(), event.getMouseY());
                if (cell >= 0) {
                    handleCellPress(w, cell, event.getButton(), Screen.hasShiftDown());
                }
            }
            // 窗口范围内的其它点击一律吞掉，避免透传到下层界面
            event.setCanceled(true);
            return;
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (dragging == null) {
            return;
        }
        boolean wasDragging = event.getButton() == 0;
        dragging = null;
        if (wasDragging) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (dragging == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int w = GearWindowState.width(dragging);
        int h = GearWindowState.height(dragging);
        dragging.x = clamp((int) event.getMouseX() - dragOffsetX, 0, Math.max(0, sw - w));
        dragging.y = clamp((int) event.getMouseY() - dragOffsetY, 0, Math.max(0, sh - h));
        event.setCanceled(true);
    }

    /** 窗口内网格点击：右键装备物品下钻打开子窗口，其余即时派发（左键取放、右键取半、Shift 转移）。 */
    private static void handleCellPress(GearWindowState.Window w, int cell, int button, boolean shift) {
        GridStore store = w.container.store();
        int anchor = store.anchorAt(cell);
        int target = anchor >= 0 ? anchor : cell;
        GridEntry entry = store.entryAt(target);
        if (button == 1 && !shift && entry != null && !entry.isEmpty() && GearConfig.isGear(entry.stack())) {
            PacketHandler.sendToServer(new C2SOpenGearWindowPacket(
                    w.rootType, w.rootRef, append(w.path, target)));
            return;
        }
        PacketHandler.sendToServer(new C2SGearWindowClickPacket(
                w.rootType, w.rootRef, w.path, target, button, shift));
    }

    // ==================================================================
    // 几何
    // ==================================================================

    private static int closeX(GearWindowState.Window w) {
        return w.x + GearWindowState.width(w) - CLOSE_SIZE - 4;
    }

    private static int closeY(GearWindowState.Window w) {
        return w.y + 3;
    }

    /** 返回窗口网格内命中的格号（未命中 -1）。 */
    private static int cellAt(GearWindowState.Window w, double mx, double my) {
        GridStore store = w.container.store();
        int cols = Math.max(1, store.width());
        int rows = store.rows();
        int gx = GearWindowState.gridX(w);
        int gy = GearWindowState.gridY(w);
        int col = (int) Math.floor((mx - gx) / GearWindowState.PITCH);
        int row = (int) Math.floor((my - gy) / GearWindowState.PITCH);
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return -1;
        }
        return row * cols + col;
    }

    private static int[] append(int[] path, int cell) {
        int[] next = Arrays.copyOf(path, path.length + 1);
        next[path.length] = cell;
        return next;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
