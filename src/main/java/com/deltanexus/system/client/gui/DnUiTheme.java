package com.deltanexus.system.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * 类塔克夫界面主题（0.5.0Beta）。
 *
 * <p>配色与绘制方式保持二进制一致（含半透明通道）：
 * 半透明深色战术面板 + 1px 立体边、分组标签条 + 行尾延伸线、稀有度着色槽位、
 * 空槽底、金色悬停框、缺失装备提示块、生命 / 饥饿 / 护甲迷你指示。</p>
 */
public final class DnUiTheme {

    /** 世界背景遮罩（未配置背景图时的纯色底）。 */
    public static final int WORLD_BACKGROUND = 1342572812;
    /** 面板底。 */
    public static final int PANEL_BACKGROUND = 1695553824;
    /** 标题条 / 标签条底。 */
    public static final int HEADER_BACKGROUND = 1882208314;
    /** 槽位底（常规）。 */
    public static final int SLOT_BACKGROUND = 1477449752;

    /** 主文本。 */
    public static final int TEXT_PRIMARY = 15790840;
    /** 次级文本。 */
    public static final int TEXT_MUTED = 10988984;
    /** 警告文本。 */
    public static final int TEXT_WARNING = 10959675;
    /** 分组标签文本。 */
    public static final int SECTION_TEXT = 15527668;
    /** 战术标签文本。 */
    public static final int TACTICAL_TEXT = -3090478;
    /** 文字投影。 */
    private static final int TEXT_SHADOW = -15724268;
    /** 槽位悬停边框（金）。 */
    private static final int HOVER_BORDER = -1853869;

    private DnUiTheme() {
    }

    /** 世界遮罩色：保留传入色的 RGB，统一压到 0x50 透明度。 */
    public static int worldColor(int argb) {
        return 0x50000000 | argb & 0xFFFFFF;
    }

    // ------------------------------------------------------------------
    // 面板
    // ------------------------------------------------------------------

    /** 战术面板外壳：半透明底 + 上/左亮边 + 下/右暗边。 */
    public static void drawPanelShell(GuiGraphics gg, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        gg.fill(x, y, x + w, y + h, PANEL_BACKGROUND);
        gg.fill(x, y, x + w, y + 1, 1431264609);
        gg.fill(x, y, x + 1, y + h, 1431264609);
        gg.fill(x + w - 1, y, x + w, y + h, 809322829);
        gg.fill(x, y + h - 1, x + w, y + h, 809322829);
    }

    /** 面板 + 标题条强调块 + 标题文字（带标题的面板统一入口）。 */
    public static void drawPanel(GuiGraphics gg, Font font, int x, int y, int w, int h, Component title) {
        drawPanel(gg, font, x, y, w, h, title, TEXT_PRIMARY);
    }

    /** 面板 + 标题条强调块 + 标题文字（可指定标题颜色，如安全箱禁用时红色警示）。 */
    public static void drawPanel(GuiGraphics gg, Font font, int x, int y, int w, int h,
                                Component title, int titleColor) {
        drawPanelShell(gg, x, y, w, h);
        gg.fill(x + 3, y + 3, x + w - 3, y + 24, HEADER_BACKGROUND);
        gg.fill(x + 3, y + 24, x + w - 3, y + 25, -15263459);
        gg.fill(x + 6, y + 6, x + w - 6, y + 10, 0x18FFFFFF);
        gg.fill(x + 6, y + 20, x + w - 6, y + 21, -12631220);
        drawStringWithShadow(gg, font, title, x + 11, y + 8, titleColor);
    }

    // ------------------------------------------------------------------
    // 战术标签 / 缺失装备 / 空块
    // ------------------------------------------------------------------

    /** 战术标签条：左端强调块 + 圆角底 + 投影文字。 */
    public static void drawTacticalLabel(GuiGraphics gg, Font font, Component text, int x, int y, int w) {
        String label = font.substrByWidth(text, Math.max(1, w - 12)).getString();
        int width = Math.min(w, Math.max(44, font.width(label) + 12));
        gg.fill(x, y - 3, x + width, y + 11, HEADER_BACKGROUND);
        gg.fill(x, y - 3, x + 1, y + 11, -1280065606);
        drawStringWithShadow(gg, font, label, x + 5, y, TACTICAL_TEXT);
    }

    /** 缺失装备提示块。 */
    public static void drawMissingGear(GuiGraphics gg, Font font, Component text, int x, int y, int w) {
        String label = font.substrByWidth(text, Math.max(1, w - 34)).getString();
        int blockW = Math.min(w - 4, Math.max(112, font.width(label) + 30));
        int px = x + (w - blockW) / 2;
        int py = y + 13;
        gg.fill(px, py, px + blockW, py + 26, -2010445560);
        gg.fill(px, py, px + blockW, py + 1, -1886432);
        gg.fill(px, py + 25, px + blockW, py + 26, -1886432);
        gg.fill(px, py, px + 1, py + 26, -1886432);
        gg.fill(px + blockW - 1, py, px + blockW, py + 26, -1886432);
        drawStringWithShadow(gg, font, label, px + 14, py + 8, -1085344);
    }

    /** 空块提示：浅底 + 上下暗边 + 居中文字。 */
    public static void drawEmptyBlock(GuiGraphics gg, Font font, Component text, int x, int y, int w) {
        String label = text.getString();
        int blockW = Math.min(w - 18, Math.max(112, font.width(label) + 24));
        int h = 18;
        gg.fill(x, y, x + blockW, y + h, 1108019981);
        gg.fill(x, y, x + blockW, y + 1, -6344656);
        gg.fill(x, y + h - 1, x + blockW, y + h, -8773086);
        int tx = x + (blockW - font.width(label)) / 2;
        drawStringWithShadow(gg, font, label, tx, y + 5, -2069396);
    }

    /** 迷你状态指示：3x3 影 + 3x3 彩块。 */
    public static void drawMiniIndicator(GuiGraphics gg, int x, int y, int color) {
        gg.fill(x, y, x + 3, y + 3, TEXT_SHADOW);
        gg.fill(x + 1, y + 1, x + 4, y + 4, color);
    }

    /** 左面板生命 / 饥饿 / 护甲读数（仓库/背包/容器共用）。 */
    public static void drawPlayerStatus(GuiGraphics gg, Font font, int panelX, int panelY) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return;
        }
        String[] texts = {
                Component.translatable("gui.dn.status.health",
                        (int) Math.ceil(p.getHealth()),
                        (int) Math.ceil(p.getMaxHealth())).getString(),
                Component.translatable("gui.dn.status.hunger",
                        p.getFoodData().getFoodLevel(), 20).getString(),
                Component.translatable("gui.dn.status.armor",
                        p.getArmorValue(), 20).getString()};
        int[] colors = {-5253032, -1844046, -8864556};
        int x = panelX + 5;
        int y0 = panelY + 222;
        for (int i = 0; i < texts.length; i++) {
            int y = y0 + i * 10;
            drawMiniIndicator(gg, x, y + 3, colors[i]);
            gg.drawString(font, texts[i], x + 6, y, colors[i], false);
        }
    }

    /**
     * 左面板人物预览。
     *
     * <p><b>深度缓冲清理（修复「人物预览压在装备窗口/提示框上」）</b>：原版
     * {@code renderEntityInInventory} 把角色模型画在 {@code z=50} 附近（模型自身在 z 轴跨越
     * ±尺寸），因此模型会往深度缓冲写入<b>比 GUI 平面更靠前</b>的深度值。GUI 的绘制顺序是
     * 「先画人物、后画其它」，但深度测试（LEQUAL）会让后画的面板/装备窗口/悬停提示在被模型
     * 覆盖处被判为「在模型后面」而丢弃——看起来就是人物预览盖住了窗口。</p>
     *
     * <p>人物预览是本帧 GUI 中唯一需要 3D 深度的内容，画完即清一次深度缓冲，
     * 之后绘制的一切 GUI（面板、槽位物品、装备窗口、提示框）就不再被模型遮挡。</p>
     */
    public static void drawPlayerPreview(GuiGraphics gg, int panelX, int panelY, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.getEntityRenderDispatcher() == null) {
            return;
        }
        int px = panelX + 65;
        int py = panelY + 207;
        InventoryScreen.renderEntityInInventoryFollowsMouse(gg, px, py, 58,
                (float) (px - mouseX), (float) ((py - 90) - mouseY), mc.player);
        // 先把已排队的 GUI 批次刷出去（原版实体渲染后已 flush，这里只做保险），再清深度：
        // 之后绘制的任何 GUI 都不会再被角色模型挡住。
        gg.flush();
        RenderSystem.clear(256, Minecraft.ON_OSX);
    }

    // ------------------------------------------------------------------
    // 槽位
    // ------------------------------------------------------------------

    /** 槽位框：外亮边 → 暗环 → 内亮环 → 内部底色（4 层内缩填充）。 */
    public static void drawSlotFrame(GuiGraphics gg, int x, int y) {
        int x0 = x - 1;
        int y0 = y - 1;
        int x1 = x0 + DnUiLayout.SLOT_SIZE;
        int y1 = y0 + DnUiLayout.SLOT_SIZE;
        gg.fill(x0, y0, x1, y1, -9670532);
        gg.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, -14013131);
        gg.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, -11381148);
        gg.fill(x0 + 3, y0 + 3, x1 - 3, y1 - 3, -11841700);
    }

    /** 槽位悬停高亮。 */
    public static void drawSlotHover(GuiGraphics gg, int x, int y) {
        gg.fill(x - 1, y - 1, x + 17, y, HOVER_BORDER);
        gg.fill(x - 1, y + 16, x + 17, y + 17, HOVER_BORDER);
        gg.fill(x - 1, y, x, y + 16, HOVER_BORDER);
        gg.fill(x + 16, y, x + 17, y + 16, HOVER_BORDER);
    }

    /** 按物品稀有度着色的槽位底：物品为空时用常规底色。 */
    public static void drawRaritySlot(GuiGraphics gg, int x, int y, ItemStack stack) {
        int bg = SLOT_BACKGROUND;
        if (stack != null && !stack.isEmpty()) {
            bg = switch (stack.getRarity()) {
                case UNCOMMON -> 1884964882;
                case RARE -> 1881160036;
                case EPIC -> 1884299874;
                default -> SLOT_BACKGROUND;
            };
        }
        gg.fill(x, y, x + 16, y + 16, bg);
        gg.fill(x - 1, y - 1, x + 17, y, -2142612140);
        gg.fill(x - 1, y + 16, x + 17, y + 17, -2142612140);
        gg.fill(x - 1, y, x, y + 16, -2142612140);
        gg.fill(x + 16, y, x + 17, y + 16, -2142612140);
    }

    /** 槽位底（16x16 内填充 + 1px 深色描边）。 */
    public static void drawSlotBackdrop(GuiGraphics gg, int x, int y) {
        drawRaritySlot(gg, x, y, ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------
    // 分组标签 / 文本
    // ------------------------------------------------------------------

    /** 分组标签条：标题块 + 行尾延伸线（保证至少 minW 宽）。
     *  用 {@code getString()} 取纯文本，避免组件自带样式覆盖 textColor。 */
    public static void drawSectionLabel(GuiGraphics gg, Font font, Component text,
                                       int x, int y, int minW, int textColor) {
        String label = text.getString();
        int top = y - 4;
        int bottom = top + 18;
        int width = Math.max(font.width(label) + 22, minW);
        gg.fill(x - 3, top, x - 3 + width, bottom, HEADER_BACKGROUND);
        gg.fill(x - 3, bottom - 1, x - 3 + width, bottom, -15000286);
        drawStringWithShadow(gg, font, label, x + 5, y, textColor);
        // 行尾延伸线（首段 x+6+文本宽+10 起，最长 96）
        int lineX = x + 6 + font.width(label) + 10;
        int lineW = Mth.clamp(x - 3 + width - 6 - lineX, 0, 96);
        if (lineW > 0) {
            gg.fill(lineX, y + 4, lineX + lineW, y + 5, -15000286);
        }
    }

    /**
     * 重绘光标上的物品（在网格渲染之后的提示层里调用）。
     *
     * <p>原版把光标物品画在 z≈382，而网格里的跨格大图标在 z≈550——拖动物品经过这类格子时，
     * 光标物品会被大图标盖住。调用方已把 z 抬到网格内容之上，这里直接按光标位置重绘一次即可
     * （随后绘制的提示框仍在它之上，与手感一致）。</p>
     */
    public static void redrawCarriedItem(GuiGraphics gg, Font font, ItemStack carried, int mouseX, int mouseY) {
        if (carried == null || carried.isEmpty()) {
            return;
        }
        gg.renderItem(carried, mouseX - 8, mouseY - 8);
        gg.renderItemDecorations(font, carried, mouseX - 8, mouseY - 8);
    }

    /** 带 1px 偏移投影的文字（硬投影）。 */
    public static void drawStringWithShadow(GuiGraphics gg, Font font, Component text, int x, int y, int color) {
        drawStringWithShadow(gg, font, text.getString(), x, y, color);
    }

    /** 带 1px 偏移投影的纯文本。 */
    public static void drawStringWithShadow(GuiGraphics gg, Font font, String text, int x, int y, int color) {
        gg.drawString(font, text, x + 1, y + 1, TEXT_SHADOW, false);
        gg.drawString(font, text, x, y, color, false);
    }
}
