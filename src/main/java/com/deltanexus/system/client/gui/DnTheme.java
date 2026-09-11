package com.deltanexus.system.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * dn 深色主题（2.0.8Alpha 抽取）：仓库/背包/安全箱面板共用配色与绘制，
 * 避免三个界面各自维护一份重复常量。
 */
public final class DnTheme {

    public static final int PANEL_BG_TOP = 0xF410131A;     // 面板渐变顶
    public static final int PANEL_BG_BOTTOM = 0xF40B0D12;  // 面板渐变底
    public static final int PANEL_BORDER_OUT = 0xFF252B38; // 外描边（暗）
    public static final int PANEL_BORDER_IN = 0xFF46536B;  // 内描边（亮）
    public static final int TITLE_LINE = 0xFF3E5F7A;       // 标题条底部分隔线
    public static final int ACCENT = 0xFF66CCFF;           // 强调青
    public static final int GOLD = 0xFFE0A0;               // 货币金
    public static final int TEXT_DIM = 0xFF9AA3B2;         // 次级文本
    public static final int TEXT_MAIN = 0xFFE0E6F0;        // 主文本

    private DnTheme() {
    }

    /** 渐变面板 + 双层描边 + 顶部高光。 */
    public static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        gg.fillGradient(x, y, x + w, y + h, PANEL_BG_TOP, PANEL_BG_BOTTOM);
        gg.renderOutline(x, y, w, h, PANEL_BORDER_OUT);
        gg.renderOutline(x + 1, y + 1, w - 2, h - 2, PANEL_BORDER_IN);
        gg.fill(x + 2, y + 2, x + w - 2, y + 3, 0x30FFFFFF);
    }

    /** 面板标题条（标题居中 + 底部亮线）。 */
    public static void drawTitle(GuiGraphics gg, Font font, int x, int y, int w, String title, int color) {
        gg.drawCenteredString(font, title, x + w / 2, y + 5, color);
        gg.fill(x + 8, y + 17, x + w - 8, y + 18, TITLE_LINE);
    }
}
