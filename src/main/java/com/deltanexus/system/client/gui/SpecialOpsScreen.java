package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.common.FormatUtil;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SOpenSpecialOpsPacket;
import com.deltanexus.system.network.packet.C2SUpgradeSafeBoxPacket;
import com.deltanexus.system.network.packet.C2SUpgradeWarehousePacket;
import com.deltanexus.system.network.packet.SyncWarehousePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 特勤处（2.0.2Alpha）：仓库/安全箱升级独立界面（从仓库 GUI 中分离）。
 *
 * <p>全屏深色主题，左右两张升级卡片：左侧仓库升级、右侧安全箱升级；
 * 每张卡片含等级进度头、货币行、材料网格（滚轮滚动）、升级按钮；
 * 数据来自 {@link SyncWarehousePacket}（打开时请求，升级后服务端回包实时刷新）。</p>
 */
public class SpecialOpsScreen extends Screen {

    private static final ResourceLocation SLOT = ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "textures/gui/slot.png");

    // ---------------- 主题配色（与仓库界面一致） ----------------
    private static final int PANEL_BG_TOP = 0xF410131A;
    private static final int PANEL_BG_BOTTOM = 0xF40B0D12;
    private static final int PANEL_BORDER_OUT = 0xFF252B38;
    private static final int PANEL_BORDER_IN = 0xFF46536B;
    private static final int TITLE_LINE = 0xFF3E5F7A;
    private static final int ACCENT = 0xFF66CCFF;
    private static final int GOLD = 0xFFE0A0;
    private static final int TEXT_DIM = 0xFF9AA3B2;
    private static final int CELL_BG = 0x26FFFFFF;
    private static final int CELL_HOVER = 0x40FFFFFF;
    private static final int MAT_CELL_W = 58;
    private static final int MAT_CELL_H = 22;

    private SyncWarehousePacket sync;
    /** 卡片区域（init 时计算）。 */
    private int cardX, cardY, cardW, cardH;
    private int matCols, matRows;
    private int whScroll = 0;
    private int safeScroll = 0;
    /** 升级按钮热区（左右卡片各一个）。 */
    private int whBtnX, whBtnY, safeBtnX, safeBtnY, btnW, btnH = 20;

    /** 悬停物品 tooltip（渲染时填充，render 末尾绘制）。 */
    private ItemStack hoverStack = ItemStack.EMPTY;
    private int hoverX, hoverY;
    private List<Component> hoverLines;

    public SpecialOpsScreen() {
        super(Component.translatable("gui.dn.special.title"));
    }

    /** 打开特勤处（仓库界面按钮 / /dn open special）：本地打开 + 向服务端请求数据。 */
    public static void open() {
        Minecraft.getInstance().setScreen(new SpecialOpsScreen());
        PacketHandler.sendToServer(new C2SOpenSpecialOpsPacket());
    }

    /** 同步包到达：实时刷新（升级后无需重开）。 */
    public void onSync(SyncWarehousePacket packet) {
        this.sync = packet;
    }

    @Override
    protected void init() {
        super.init();
        this.sync = WarehouseScreen.lastSync;

        // 两张升级卡片并排居中（每张宽 300）
        cardW = 300;
        cardH = Math.max(220, height - 60);
        cardX = Math.max(8, (width - cardW * 2 - 24) / 2);
        cardY = Math.max(8, (height - cardH) / 2 - 8);
        matCols = Math.max(3, (cardW - 20) / MAT_CELL_W);
        matRows = Math.max(2, (cardH - 130) / MAT_CELL_H);
        btnW = cardW - 20;
        whBtnX = cardX + 10;
        safeBtnX = cardX + cardW + 24 + 10;
        whBtnY = cardY + cardH - 30;
        safeBtnY = whBtnY;
    }

    // ==================================================================
    // 主题绘制辅助（与仓库界面一致）
    // ==================================================================

    private void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        gg.fillGradient(x, y, x + w, y + h, PANEL_BG_TOP, PANEL_BG_BOTTOM);
        gg.renderOutline(x, y, w, h, PANEL_BORDER_OUT);
        gg.renderOutline(x + 1, y + 1, w - 2, h - 2, PANEL_BORDER_IN);
        gg.fill(x + 2, y + 2, x + w - 2, y + 3, 0x30FFFFFF);
    }

    private void drawTitle(GuiGraphics gg, int x, int y, int w, String title, int color) {
        gg.drawCenteredString(font, title, x + w / 2, y + 5, color);
        gg.fill(x + 8, y + 17, x + w - 8, y + 18, TITLE_LINE);
    }

    private void drawButton(GuiGraphics gg, int x, int y, int w, int h,
                            String label, boolean hovered, boolean active, int textColor) {
        int bgTop = !active ? 0xFF1A1D26 : hovered ? 0xFF3A4655 : 0xFF2A3040;
        int bgBot = !active ? 0xFF14161E : hovered ? 0xFF2E3748 : 0xFF20242F;
        int border = !active ? 0xFF303648 : hovered ? ACCENT : PANEL_BORDER_IN;
        gg.fillGradient(x, y, x + w, y + h, bgTop, bgBot);
        gg.renderOutline(x, y, w, h, border);
        gg.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, !active ? TEXT_DIM : textColor);
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        // 标题
        gg.drawCenteredString(font, Component.translatable("gui.dn.special.title").getString(),
                width / 2, 10, GOLD);
        gg.fill(width / 2 - 60, 22, width / 2 + 60, 23, TITLE_LINE);

        if (sync == null) {
            gg.drawCenteredString(font, Component.translatable("gui.dn.workbench_ui.loading").getString(),
                    width / 2, height / 2, TEXT_DIM);
            return;
        }

        // 左侧：仓库升级卡片
        drawPanel(gg, cardX, cardY, cardW, cardH);
        drawTitle(gg, cardX, cardY, cardW,
                Component.translatable("gui.dn.special.warehouse").getString() + "  Lv" + sync.warehouseLevel,
                GOLD);
        renderCard(gg, cardX, cardY, mouseX, mouseY,
                sync.nextLevel, sync.nextCostMoney, sync.nextMaterials,
                whScroll, true, whBtnX, whBtnY);

        // 右侧：安全箱升级卡片
        int sx = cardX + cardW + 24;
        drawPanel(gg, sx, cardY, cardW, cardH);
        drawTitle(gg, sx, cardY, cardW,
                Component.translatable("gui.dn.special.safe").getString()
                        + "  Lv" + sync.safeLevel + "  " + sync.safeHeight + "x" + sync.safeWidth,
                GOLD);
        renderCard(gg, sx, cardY, mouseX, mouseY,
                sync.safeNextLevel, sync.safeNextCostMoney, sync.safeNextMaterials,
                safeScroll, false, safeBtnX, safeBtnY);

        // 悬停物品 tooltip
        if (hoverStack != null && !hoverStack.isEmpty() && hoverLines != null) {
            List<net.minecraft.util.FormattedCharSequence> lines = hoverLines.stream()
                    .map(Component::getVisualOrderText)
                    .toList();
            gg.renderTooltip(font, lines, hoverX, hoverY);
        }
        hoverStack = ItemStack.EMPTY;
        hoverLines = null;

        super.render(gg, mouseX, mouseY, partialTick);
    }

    /** 渲染一张升级卡片（等级头 + 货币行 + 材料网格 + 升级按钮）。 */
    private void renderCard(GuiGraphics gg, int x, int y, int mouseX, int mouseY,
                            int nextLevel, int nextCost, List<SyncWarehousePacket.Material> materials,
                            int scroll, boolean warehouse, int btnX, int btnY) {
        if (nextLevel <= 0) {
            gg.drawCenteredString(font, Component.translatable("gui.dn.warehouse.maxed").getString(),
                    x + cardW / 2, y + 40, TEXT_DIM);
            return;
        }
        String lv = "§7Lv" + (nextLevel - 1) + "§r ➜ §eLv" + nextLevel;
        gg.drawCenteredString(font, lv, x + cardW / 2, y + 26, 0xFFE0E6F0);

        // 货币行
        int cy = y + 44;
        if (sync.currencyUsable) {
            if ("item".equals(sync.currencyType)) {
                ItemStack money = itemStackOf(sync.currencyItem, "");
                if (!money.isEmpty()) {
                    gg.renderItem(money, x + 12, cy);
                }
                String text = FormatUtil.compactNeed(sync.currencyCount, nextCost);
                gg.drawString(font, text, x + 34, cy + 4, needColor(sync.currencyCount, nextCost));
                if (!money.isEmpty() && isHovered(x + 12, cy, 16, 16, mouseX, mouseY)) {
                    setHover(money, x + 12, cy,
                            Component.literal("§7" + FormatUtil.compactNeed(sync.currencyCount, nextCost)));
                }
            } else {
                String text = currencyLabel() + ": " + FormatUtil.compactNeed(sync.currencyCount, nextCost);
                gg.drawString(font, text, x + 14, cy + 4, needColor(sync.currencyCount, nextCost));
            }
        } else {
            gg.drawString(font, Component.translatable("gui.dn.currency.unavailable").getString(),
                    x + 14, cy + 4, 0xFF8888);
        }

        // 材料网格
        int gy = cy + 24;
        int perPage = matCols * matRows;
        int maxScroll = Math.max(0, materials.size() - perPage);
        int s = Math.max(0, Math.min(maxScroll, scroll));
        String matTitle = Component.translatable("gui.dn.warehouse.materials").getString()
                + " " + (materials.size() > perPage ? (s + 1) + "-" + Math.min(materials.size(), s + perPage) + "/" : "") + materials.size();
        gg.drawString(font, matTitle, x + 12, gy, GOLD);
        gy += 12;

        int gridBottom = gy + matRows * MAT_CELL_H;
        for (int i = 0; i < perPage; i++) {
            int idx = s + i;
            if (idx >= materials.size()) {
                break;
            }
            SyncWarehousePacket.Material m = materials.get(idx);
            int col = i % matCols;
            int row = i / matCols;
            int mx = x + 10 + col * MAT_CELL_W;
            int my = gy + row * MAT_CELL_H;
            boolean hov = isHovered(mx, my, MAT_CELL_W - 2, MAT_CELL_H - 2, mouseX, mouseY);
            gg.fill(mx, my, mx + MAT_CELL_W - 2, my + MAT_CELL_H - 2, hov ? CELL_HOVER : CELL_BG);
            gg.renderOutline(mx, my, MAT_CELL_W - 2, MAT_CELL_H - 2, PANEL_BORDER_OUT);
            ItemStack stack = itemStackOf(m.itemId, m.nbt);
            if (!stack.isEmpty()) {
                gg.renderItem(stack, mx + 2, my + 2);
            }
            gg.drawString(font, FormatUtil.compactNeed(m.held, m.needed), mx + 21, my + 6,
                    needColor(m.held, m.needed));
            if (!stack.isEmpty() && hov) {
                setHover(stack, mx + 2, my + 2,
                        Component.literal("§7" + Component.translatable("gui.dn.warehouse.material_need",
                                FormatUtil.compact(m.held), FormatUtil.compact(m.needed)).getString()
                                + (m.nbt != null && !m.nbt.isBlank()
                                ? "\n§7" + Component.translatable("gui.dn.warehouse.material_nbt", m.matchType).getString() : "")));
            }
        }
        if (materials.size() > perPage) {
            gg.drawCenteredString(font, Component.translatable("gui.dn.warehouse.material_scroll").getString(),
                    x + cardW / 2, gridBottom + 2, TEXT_DIM);
        }

        // 升级按钮
        boolean hovBtn = isHovered(btnX, btnY, btnW, btnH, mouseX, mouseY);
        drawButton(gg, btnX, btnY, btnW, btnH,
                Component.translatable(warehouse ? "gui.dn.warehouse.upgrade" : "gui.dn.safe.upgrade").getString(),
                hovBtn, true, ACCENT);
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (sync != null) {
            if (sync.nextLevel > 0 && isHovered(whBtnX, whBtnY, btnW, btnH, mouseX, mouseY)) {
                PacketHandler.sendToServer(new C2SUpgradeWarehousePacket());
                return true;
            }
            if (sync.safeNextLevel > 0 && isHovered(safeBtnX, safeBtnY, btnW, btnH, mouseX, mouseY)) {
                PacketHandler.sendToServer(new C2SUpgradeSafeBoxPacket());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int step = delta > 0 ? -1 : 1;
        int perPage = matCols * matRows;
        // 材料区滚轮（按鼠标所在卡片）
        if (mouseX >= cardX && mouseX < cardX + cardW) {
            int max = Math.max(0, sync == null ? 0 : sync.nextMaterials.size() - perPage);
            whScroll = Math.max(0, Math.min(max, whScroll + step));
            return true;
        }
        if (sync != null && mouseX >= safeBtnX - 10 && mouseX < safeBtnX - 10 + cardW) {
            int max = Math.max(0, sync.safeNextMaterials.size() - perPage);
            safeScroll = Math.max(0, Math.min(max, safeScroll + step));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private void setHover(ItemStack stack, int x, int y, Component extra) {
        hoverStack = stack;
        hoverX = x;
        hoverY = y;
        List<Component> lines = new ArrayList<>(stack.getTooltipLines(
                Minecraft.getInstance().player, TooltipFlag.Default.NORMAL));
        if (extra != null) {
            for (String line : extra.getString().split("\n")) {
                lines.add(Component.literal(line));
            }
        }
        hoverLines = lines;
    }

    private String currencyLabel() {
        if (sync == null) {
            return "";
        }
        String type = sync.currencyType;
        if ("scoreboard".equals(type) || "vault".equals(type) || "playerpoints".equals(type)) {
            return Component.translatable("gui.dn.currency." + type).getString();
        }
        return Component.translatable("gui.dn.currency.item").getString();
    }

    private static ItemStack itemStackOf(String itemId, String nbt) {
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        if (nbt != null && !nbt.isBlank()) {
            net.minecraft.nbt.CompoundTag tag = com.deltanexus.system.common.NbtMatcher.parseTag(nbt);
            if (tag != null) {
                stack.setTag(tag);
            }
        }
        return stack;
    }

    private static int needColor(long held, long needed) {
        return held >= needed ? 0x88FF88 : 0xFF8888;
    }

    private static boolean isHovered(int x, int y, int w, int h, double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
