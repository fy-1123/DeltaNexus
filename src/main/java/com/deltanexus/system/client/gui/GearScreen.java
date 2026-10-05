package com.deltanexus.system.client.gui;

import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.menu.GearMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 装备容器界面（0.5.0Beta 照抄 sakura 单面板排列）：胸挂 / 背包网格 + 口袋 + 快捷栏。
 *
 * <p>排列与 sakura 的装备界面一致：单块 176 宽面板，顶部标题条，其下是装备网格，
 * 网格之后是口袋 5 格与快捷栏 9 格（各行内水平排列）。</p>
 *
 * <p>槽位坐标由 {@link GearMenu} 按菜单局部坐标给出，本界面只负责把面板居中、
 * 缩放适配并绘制皮肤；物品本体仍由原版槽位渲染（含网格引擎的跨格物品叠加）。</p>
 */
public class GearScreen extends AbstractContainerScreen<GearMenu> implements DnOverlayTooltips {

    /** 面板宽度（与 sakura 一致）。 */
    private static final int PANEL_W = 176;
    /** 标题条左内缩。 */
    private static final int LABEL_X = 8;
    /** 统一提示层的 z 抬升量（网格大图标 z≈550，见 {@link DnOverlayTooltips}）。 */
    private static final int TOOLTIP_Z = 1000;

    private int panelH;
    private float uiScale = 1f;

    public GearScreen(GearMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    @Override
    protected void init() {
        this.panelH = 22 + this.menu.storageHeight() + 24 + 21 + 24;
        this.imageWidth = PANEL_W;
        this.imageHeight = panelH;
        super.init();
        this.uiScale = Math.min(1f, Math.min(
                this.width / (float) (PANEL_W + 12),
                this.height / (float) (panelH + 12)));
        if (this.uiScale <= 0.1f) {
            this.uiScale = 0.1f;
        }
        this.leftPos = (int) ((this.width / this.uiScale - PANEL_W) / 2);
        this.topPos = (int) ((this.height / this.uiScale - panelH) / 2);
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        // 面板 + 左侧高光 + 顶部标题条（sakura 装备界面画法）
        gg.fill(x, y, x + PANEL_W, y + panelH, 1695553824);
        gg.fill(x, y, x + 1, y + panelH, 1431264609);
        gg.fill(x + 4, y + 4, x + PANEL_W - 4, y + 18, 1882208314);
        gg.fill(x + 4, y + 4, x + 5, y + 18, -1280065606);
        // 槽位底
        for (Slot slot : this.menu.slots) {
            if (slot == null) {
                continue;
            }
            DnUiTheme.drawSlotBackdrop(gg, x + slot.x, y + slot.y);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        GearKind kind = menu.kind();
        DnUiTheme.drawStringWithShadow(gg, this.font,
                Component.translatable("gui.dn.gear." + kind.id()), LABEL_X, 8, DnUiTheme.TEXT_PRIMARY);
        DnUiTheme.drawSectionLabel(gg, this.font, Component.translatable("gui.dn.pocket"),
                LABEL_X, 22 + menu.storageHeight() + 12, 120, DnUiTheme.SECTION_TEXT);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // 世界遮罩（照抄 sakura：纯色压暗）
        gg.fill(0, 0, this.width, this.height, DnUiTheme.worldColor(DnUiTheme.WORLD_BACKGROUND));
        gg.pose().pushPose();
        gg.pose().scale(uiScale, uiScale, 1f);
        int sx = (int) (mouseX / uiScale);
        int sy = (int) (mouseY / uiScale);
        super.render(gg, sx, sy, partialTick);
        // 悬停金框（sakura 装备界面画法）
        if (this.hoveredSlot != null && this.hoveredSlot.x > -1000) {
            DnUiTheme.drawSlotHover(gg, this.leftPos + this.hoveredSlot.x, this.topPos + this.hoveredSlot.y);
        }
        // 提示不在这里画：网格大图标（z≈550）在更晚的 Render.Post 绘制，会盖住提示
        gg.pose().popPose();
    }

    /** 原版时机的提示绘制被接管（见 {@link DnOverlayTooltips}）。 */
    @Override
    protected void renderTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        // 故意留空
    }

    /** 统一提示层：所有网格绘制之后画悬停提示（z 抬到网格内容之上）。 */
    @Override
    public void dnRenderTooltips(GuiGraphics gg, int mouseX, int mouseY) {
        if (com.deltanexus.system.client.GearWindowState.isOverWindow(mouseX, mouseY)) {
            return;
        }
        gg.pose().pushPose();
        gg.pose().scale(uiScale, uiScale, 1f);
        gg.pose().translate(0, 0, TOOLTIP_Z);
        int sx = (int) (mouseX / uiScale);
        int sy = (int) (mouseY / uiScale);
        DnUiTheme.redrawCarriedItem(gg, this.font, this.menu.getCarried(), sx, sy);
        super.renderTooltip(gg, sx, sy);
        gg.pose().popPose();
    }

    // ==================================================================
    // 交互（坐标为缩放前空间）
    // ==================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX / uiScale, mouseY / uiScale, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX / uiScale, mouseY / uiScale, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX / uiScale, mouseY / uiScale, button, dragX / uiScale, dragY / uiScale);
    }
}