package com.deltanexus.system.client.gui;

import com.deltanexus.system.client.TradeClientState;
import com.deltanexus.system.common.FormatUtil;
import com.deltanexus.system.common.NbtMatcher;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2STradeBuyPacket;
import com.deltanexus.system.network.packet.SyncTradeCatalogPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易行界面（0.2.0Beta）。参考 UI参考/交易行一级界面.html 与 二级界面.html。
 *
 * <p>一级：左侧分类栏（全部 + 各分类，滚轮滚动）+ 右侧 3×5 商品格（图标/名称/买入价/库存，
 * 商品多时滚轮滚动而非翻页）；二级：大图标 + 4 行信息（当前数量 / 当前价格 / 当前卖出价格 /
 * 当前买入价格）+ 数量输入（EditBox + 步进）+ 合计与买入按钮。</p>
 *
 * <p>数据源 = {@link TradeClientState}（服务端 SyncTradeCatalogPacket 目录快照，
 * 成交/补货后自动刷新；重开界面读取最新）。买入动作发 {@link C2STradeBuyPacket}
 * 由服务端权威结算（价格/货币/仓库空间/库存校验），失败经热栏文案提示。</p>
 */
public class TradeScreen extends Screen {

    // ---------------- 主题 ----------------
    private static final int PANEL_BG_TOP = 0xF410131A;
    private static final int PANEL_BG_BOTTOM = 0xF40B0D12;
    private static final int PANEL_BORDER_OUT = 0xFF252B38;
    private static final int PANEL_BORDER_IN = 0xFF46536B;
    private static final int TITLE_LINE = 0xFF3E5F7A;
    private static final int ACCENT = 0xFF66CCFF;
    private static final int GOLD = 0xFFE0A0;
    private static final int TEXT_DIM = 0xFF9AA3B2;
    private static final int TEXT_MAIN = 0xFFE0E6F0;
    private static final int TEXT_RED = 0xFFFF6B6B;
    private static final int TEXT_GREEN = 0xFF6BD47A;
    private static final int CELL_BG = 0x26FFFFFF;
    private static final int CELL_HOVER = 0x40FFFFFF;

    private static final int GRID_COLS = 3;
    private static final int GRID_ROWS = 5;
    private static final int CAT_ROW_H = 22;

    /** 当前分类过滤（null = 全部）。 */
    private String categoryId;
    /** 商品区滚动行偏移（滚轮滚动，替代翻页）。 */
    private int goodsScrollRows = 0;
    /** 非 null = 二级详情模式。 */
    private String detailGoodId;
    /** 分类栏滚动偏移（像素；类别过多时滚轮滚动）。 */
    private int catScrollPx = 0;

    // 数量输入（二级）
    private EditBox qtyBox;
    private int qty = 1;

    public TradeScreen() {
        super(Component.translatable("gui.dn.trade.title"));
    }

    /** 由 OpenScreenPacket(SCREEN_TRADE) 打开（目录已由 SyncTradeCatalogPacket 先行送达）。 */
    public static void open() {
        Minecraft.getInstance().setScreen(new TradeScreen());
    }

    @Override
    protected void init() {
        super.init();
        this.categoryId = null;
        this.goodsScrollRows = 0;
        this.detailGoodId = null;
        // 数量输入框：位置按二级详情面板布局（面板随窗口尺寸固定，init 即定）
        Rect body = detailBodyRect();
        Rect boxRect = qtyBoxRect(body);
        this.qtyBox = new EditBox(this.font, boxRect.x, boxRect.y, boxRect.w, boxRect.h,
                Component.translatable("gui.dn.trade.qty"));
        this.qtyBox.setMaxLength(6);
        this.qtyBox.setFilter(s -> s.matches("\\d*"));
        this.qtyBox.setValue("1");
        this.qty = 1;
    }

    // ==================================================================
    // 输入
    // ==================================================================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (detailGoodId != null && qtyBox != null && qtyBox.isFocused()) {
            if (qtyBox.keyPressed(keyCode, scanCode, modifiers)) {
                syncQtyFromBox();
                return true;
            }
            return false;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (detailGoodId != null && qtyBox != null && qtyBox.isFocused()) {
            if (qtyBox.charTyped(codePoint, modifiers)) {
                syncQtyFromBox();
                return true;
            }
            return false;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (detailGoodId == null) {
            // 左侧分类栏滚动
            Rect side = sidebarRect();
            int listTop = side.y + 24;
            int listH = Math.max(CAT_ROW_H, side.h - 30);
            if (mx >= side.x && mx < side.x + side.w && my >= listTop && my < listTop + listH) {
                int contentH = (1 + TradeClientState.categories().size()) * CAT_ROW_H;
                int maxScroll = Math.max(0, contentH - listH);
                if (maxScroll > 0) {
                    catScrollPx = (int) Math.max(0, Math.min(maxScroll, catScrollPx - delta * CAT_ROW_H));
                    return true;
                }
            }
            // 右侧商品区滚动（替代翻页）
            Rect main = mainRect();
            Rect grid = gridRect(main);
            if (mx >= grid.x && mx < grid.x + grid.w && my >= grid.y && my < grid.y + grid.h) {
                int maxRows = maxGoodsScrollRows();
                if (maxRows > 0) {
                    goodsScrollRows = (int) Math.max(0, Math.min(maxRows, goodsScrollRows - Math.round(delta)));
                    return true;
                }
            }
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        int x = (int) mx;
        int y = (int) my;
        if (detailGoodId != null) {
            return clickDetail(x, y);
        }
        return clickList(x, y);
    }

    private boolean clickList(int x, int y) {
        Rect side = sidebarRect();
        Rect main = mainRect();
        // 分类栏（含“全部”）；类别多时按滚动偏移命中
        if (side.contains(x, y)) {
            List<String> ids = new ArrayList<>();
            ids.add(null);
            for (SyncTradeCatalogPacket.Category c : TradeClientState.categories()) {
                ids.add(c.id);
            }
            int listTop = side.y + 24;
            int listH = Math.max(CAT_ROW_H, side.h - 30);
            if (y >= listTop && y < listTop + listH) {
                int index = (y - listTop + catScrollPx) / CAT_ROW_H;
                if (index >= 0 && index < ids.size()) {
                    String target = ids.get(index);
                    if (!java.util.Objects.equals(target, categoryId)) {
                        categoryId = target;
                        goodsScrollRows = 0;
                    }
                }
            }
            return true;
        }
        if (main.contains(x, y)) {
            Rect grid = gridRect(main);
            if (grid.contains(x, y)) {
                int cellW = cellW(main);
                int cellH = cellH(main);
                if (cellW > 6 && cellH > 10) {
                    int col = (x - grid.x) / (cellW + 8);
                    int row = (y - grid.y) / (cellH + 8);
                    if (col >= 0 && col < GRID_COLS && row >= 0 && row < GRID_ROWS) {
                        List<SyncTradeCatalogPacket.Good> goods = filteredGoods();
                        int idx = (goodsScrollRows + row) * GRID_COLS + col;
                        if (idx >= 0 && idx < goods.size()) {
                            enterDetail(goods.get(idx).id);
                            return true;
                        }
                    }
                }
            }
        }
        return super.mouseClicked(x, y, 0);
    }

    private boolean clickDetail(int x, int y) {
        SyncTradeCatalogPacket.Good g = currentDetailGood();
        if (backRect().contains(x, y)) {
            detailGoodId = null;
            if (qtyBox != null) {
                qtyBox.setFocused(false);
            }
            return true;
        }
        Rect body = detailBodyRect();
        if (g != null && body.contains(x, y)) {
            if (minusRect(body).contains(x, y)) {
                qty = Math.max(1, qty - 1);
                syncBoxFromQty();
                return true;
            }
            if (plusRect(body).contains(x, y)) {
                qty = Math.min(Math.max(1, currentBuyLimit()), qty + 1);
                syncBoxFromQty();
                return true;
            }
            if (buyRect(body).contains(x, y)) {
                doBuy(g);
                return true;
            }
            if (qtyBoxRect(body).contains(x, y)) {
                qtyBox.setFocused(true);
                return true;
            }
            qtyBox.setFocused(false);
        }
        return super.mouseClicked(x, y, 0);
    }

    private void doBuy(SyncTradeCatalogPacket.Good g) {
        if (g == null || g.buyCode != 0) {
            return;
        }
        int want = Math.max(1, Math.min(qty, Math.max(1, currentBuyLimit())));
        PacketHandler.sendToServer(new C2STradeBuyPacket(g.id, want));
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        gg.drawCenteredString(font, Component.translatable("gui.dn.trade.title").getString(),
                width / 2, 6, GOLD);
        gg.fill(width / 2 - 60, 17, width / 2 + 60, 18, TITLE_LINE);

        if (detailGoodId != null) {
            renderDetail(gg, mouseX, mouseY, partialTick);
        } else {
            renderList(gg, mouseX, mouseY);
        }
        super.render(gg, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphics gg, int mouseX, int mouseY) {
        Rect side = sidebarRect();
        Rect main = mainRect();
        drawPanel(gg, side.x, side.y, side.w, side.h);
        drawTitle(gg, side.x, side.y, side.w,
                Component.translatable("gui.dn.trade.category").getString(), ACCENT);

        // 分类按钮（全部 + 各分类，单列每 22px；超出高度滚轮滚动 + 右侧滚动条）
        List<String> ids = new ArrayList<>();
        ids.add(null);
        for (SyncTradeCatalogPacket.Category c : TradeClientState.categories()) {
            ids.add(c.id);
        }
        int listTop = side.y + 24;
        int listH = Math.max(CAT_ROW_H, side.h - 30);
        int contentH = ids.size() * CAT_ROW_H;
        int maxScroll = Math.max(0, contentH - listH);
        catScrollPx = Math.max(0, Math.min(maxScroll, catScrollPx));
        gg.enableScissor(side.x + 2, listTop, side.x + side.w - 2, listTop + listH);
        int by = listTop - catScrollPx;
        for (int i = 0; i < ids.size(); i++) {
            if (by + 18 < listTop) {
                by += CAT_ROW_H;
                continue;
            }
            if (by > listTop + listH) {
                break;
            }
            String id = ids.get(i);
            boolean selected = java.util.Objects.equals(id, categoryId);
            String label = id == null
                    ? Component.translatable("gui.dn.trade.category.all").getString()
                    : categoryName(id);
            boolean hover = mouseX >= side.x + 6 && mouseX < side.x + side.w - 6
                    && mouseY >= by && mouseY < by + 18 && mouseY >= listTop && mouseY < listTop + listH;
            int bg = selected ? 0xFF2563EB : hover ? CELL_HOVER : CELL_BG;
            gg.fill(side.x + 4, by, side.x + side.w - 4, by + 18, bg);
            gg.drawCenteredString(font, trunc(label, side.w - 20), side.x + side.w / 2, by + 5,
                    selected ? 0xFFFFFFFF : TEXT_MAIN);
            by += CAT_ROW_H;
        }
        gg.disableScissor();
        if (maxScroll > 0) {
            gg.fill(side.x + side.w - 4, listTop, side.x + side.w - 3, listTop + listH, 0x50000000);
            int thumbH = Math.max(16, listH * listH / contentH);
            int thumbY = listTop + (int) ((listH - thumbH) * (catScrollPx / (float) maxScroll));
            gg.fill(side.x + side.w - 4, thumbY, side.x + side.w - 3, thumbY + thumbH, ACCENT);
        }

        // 商品主区
        drawPanel(gg, main.x, main.y, main.w, main.h);
        drawTitle(gg, main.x, main.y, main.w,
                Component.translatable("gui.dn.trade.goods").getString(), ACCENT);

        List<SyncTradeCatalogPacket.Good> goods = filteredGoods();
        if (goods.isEmpty()) {
            boolean loading = !TradeClientState.hasCatalog();
            gg.drawCenteredString(font,
                    Component.translatable(loading ? "gui.dn.trade.loading" : "gui.dn.trade.empty").getString(),
                    main.x + main.w / 2, main.y + main.h / 2, TEXT_DIM);
            return;
        }

        Rect grid = gridRect(main);
        int cellW = cellW(main);
        int cellH = cellH(main);
        int maxRows = maxGoodsScrollRows();
        goodsScrollRows = Math.max(0, Math.min(maxRows, goodsScrollRows));

        gg.enableScissor(grid.x - 2, grid.y - 2, grid.x + grid.w + 2, grid.y + grid.h + 2);
        for (int row = 0; row < GRID_ROWS; row++) {
            int srcRow = goodsScrollRows + row;
            if (srcRow * GRID_COLS >= goods.size()) {
                break;
            }
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = srcRow * GRID_COLS + col;
                if (idx >= goods.size()) {
                    break;
                }
                SyncTradeCatalogPacket.Good g = goods.get(idx);
                int cx = grid.x + col * (cellW + 8);
                int cy = grid.y + row * (cellH + 8);
                boolean hover = mouseX >= cx && mouseX < cx + cellW && mouseY >= cy && mouseY < cy + cellH
                        && mouseX >= grid.x && mouseX < grid.x + grid.w
                        && mouseY >= grid.y && mouseY < grid.y + grid.h;
                gg.fill(cx, cy, cx + cellW, cy + cellH, hover ? CELL_HOVER : CELL_BG);
                gg.renderOutline(cx, cy, cellW, cellH, PANEL_BORDER_IN);

                int iconBox = Math.max(16, Math.min(30, cellH - 20));
                ItemStack iconStack = itemStackOf(g);
                int iconBoxX = cx + 6;
                int iconBoxY = cy + Math.max(4, (cellH - iconBox) / 2);
                if (!iconStack.isEmpty()) {
                    renderIconInBox(gg, iconStack, iconBoxX, iconBoxY, iconBox);
                }
                int tx = iconBoxX + iconBox + 8;
                gg.drawString(font, trunc(g.displayName, cx + cellW - 8 - tx), tx, cy + 6, TEXT_MAIN);

                int priceY = cy + cellH - 14;
                String priceText;
                int color;
                if (g.buyCode == 0) {
                    priceText = Component.translatable("gui.dn.trade.buy_price_short",
                            FormatUtil.plain(g.buyPrice)).getString();
                    color = GOLD;
                } else {
                    priceText = buyStateText(g);
                    color = g.buyCode == 3 ? TEXT_RED : TEXT_DIM;
                }
                gg.drawString(font, trunc(priceText, cellW - 10), cx + 6, priceY, color);
                gg.drawString(font, stockShort(g),
                        cx + cellW - 8 - font.width(stockShort(g)), priceY, TEXT_DIM);
            }
        }
        gg.disableScissor();

        // 商品区滚动条（替代翻页）
        if (maxRows > 0) {
            int trackH = grid.h;
            int totalRows = totalGoodsRows();
            gg.fill(main.x + main.w - 6, grid.y, main.x + main.w - 5, grid.y + trackH, 0x50000000);
            int thumbH = Math.max(16, trackH * GRID_ROWS / Math.max(1, totalRows));
            int thumbY = grid.y + (int) ((trackH - thumbH) * (goodsScrollRows / (float) maxRows));
            gg.fill(main.x + main.w - 6, thumbY, main.x + main.w - 5, thumbY + thumbH, ACCENT);
        }
        // 底部统计
        gg.drawString(font, Component.translatable("gui.dn.trade.count", goods.size()).getString(),
                main.x + 10, main.y + main.h - 12, TEXT_DIM);
    }

    private void renderDetail(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        Rect backR = backRect();
        drawMiniButton(gg, backR.x, backR.y, backR.w, backR.h,
                Component.translatable("gui.dn.trade.back").getString(), TEXT_MAIN, true, mouseX, mouseY);

        SyncTradeCatalogPacket.Good g = currentDetailGood();
        Rect body = detailBodyRect();
        drawPanel(gg, body.x, body.y, body.w, body.h);
        if (g == null) {
            gg.drawCenteredString(font,
                    Component.translatable("gui.dn.trade.gone").getString(),
                    body.x + body.w / 2, body.y + body.h / 2, TEXT_DIM);
            return;
        }

        // 大图标（左上角固定框，图标居中于框内）
        int iconFrameX = body.x + 24;
        int iconFrameY = body.y + 24;
        gg.renderOutline(iconFrameX, iconFrameY, 72, 72, PANEL_BORDER_IN);
        gg.fill(iconFrameX, iconFrameY, iconFrameX + 72, iconFrameY + 72, 0xFF10131A);
        ItemStack iconStack = itemStackOf(g);
        if (!iconStack.isEmpty()) {
            renderIconInBox(gg, iconStack, iconFrameX + 4, iconFrameY + 4, 64);
        }

        // 信息行（当前数量/当前价格/卖出价/买入价）
        int lx = body.x + 116;
        int ly = body.y + 26;
        int rowH = 20;
        infoRow(gg, lx, ly, Component.translatable("gui.dn.trade.stock").getString(),
                FormatUtil.compact(g.stock), TEXT_MAIN);
        long market = g.marketCode == 0 ? g.marketPrice : -1;
        infoRow(gg, lx, ly + rowH,
                Component.translatable("gui.dn.trade.market_price").getString(),
                market >= 0 ? FormatUtil.plain(market) : "—", TEXT_MAIN);
        infoRow(gg, lx, ly + rowH * 2,
                Component.translatable("gui.dn.trade.sell_price").getString(),
                (g.sellCode == 0 ? FormatUtil.plain(g.sellPrice) : "—"), TEXT_GREEN);
        infoRow(gg, lx, ly + rowH * 3,
                Component.translatable("gui.dn.trade.buy_price").getString(),
                (g.buyCode == 0 ? FormatUtil.plain(g.buyPrice)
                        : Component.translatable("gui.dn.trade.price_na").getString()), TEXT_RED);
        int noteY = ly + rowH * 4;
        if (g.unitCount > 1) {
            gg.drawString(font, Component.translatable("gui.dn.trade.per_unit",
                    g.unitCount).getString(), lx, noteY, TEXT_DIM);
            noteY += 12;
        }
        if (g.buyCode != 0) {
            gg.drawString(font, buyStatusLine(g), lx, noteY, g.buyCode == 3 ? TEXT_RED : TEXT_DIM);
        }

        // 底部两行：第一行数量输入，第二行合计 + 买入（互不重叠）
        Rect boxR = qtyBoxRect(body);
        String qtyLabel = Component.translatable("gui.dn.trade.qty").getString();
        gg.drawString(font, qtyLabel, body.x + 24, boxR.y + 4, TEXT_MAIN);
        Rect minus = minusRect(body);
        Rect plus = plusRect(body);
        // 实时可购上限（成交/补货后目录刷新，底部“最多 N”随之更新）
        int limit = currentBuyLimit();
        int maxStep = Math.max(1, limit);
        if (qty > maxStep) {
            qty = maxStep;
            syncBoxFromQty();
        }
        drawMiniButton(gg, minus.x, minus.y, minus.w, minus.h, "-", TEXT_MAIN, true, mouseX, mouseY);
        qtyBox.render(gg, mouseX, mouseY, partialTick);
        drawMiniButton(gg, plus.x, plus.y, plus.w, plus.h, "+", TEXT_MAIN,
                qty < maxStep, mouseX, mouseY);
        Rect buyR = buyRect(body);
        String maxTxt = Component.translatable("gui.dn.trade.max_qty", limit).getString();
        int maxSpace = buyR.x - (plus.x + plus.w + 10);
        if (maxSpace >= 24) {
            gg.drawString(font, trunc(maxTxt, maxSpace), plus.x + plus.w + 10,
                    boxR.y + 4, TEXT_DIM);
        }

        int total = Math.max(1, qty);
        long unit = g.buyCode == 0 ? g.buyPrice : 0;
        String totalText = Component.translatable("gui.dn.trade.total_price",
                FormatUtil.plain(unit * total)).getString();
        gg.drawString(font, trunc(totalText, buyR.x - (body.x + 24) - 10),
                body.x + 24, buyR.y + 7, GOLD);
        boolean canBuy = g.buyCode == 0;
        drawMiniButton(gg, buyR.x, buyR.y, buyR.w, buyR.h,
                Component.translatable("gui.dn.trade.buy").getString(), 0xFFFFFFFF, canBuy, mouseX, mouseY);
    }

    private void infoRow(GuiGraphics gg, int x, int y, String label, String value, int valueColor) {
        gg.drawString(font, label, x, y + 4, TEXT_DIM);
        gg.drawString(font, value, x + 150, y + 4, valueColor);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private SyncTradeCatalogPacket.Good currentDetailGood() {
        return detailGoodId == null ? null : TradeClientState.good(detailGoodId);
    }

    /** 当前商品的实时可购上限（每帧从最新目录读取，成交/补货后自动刷新）。 */
    private int currentBuyLimit() {
        SyncTradeCatalogPacket.Good g = currentDetailGood();
        return g == null ? 0 : Math.max(0, g.buyLimit);
    }

    private void enterDetail(String goodId) {
        detailGoodId = goodId;
        qty = 1;
        if (qtyBox != null) {
            qtyBox.setValue("1");
            qtyBox.setFocused(false);
        }
    }

    private void syncQtyFromBox() {
        try {
            qty = Integer.parseInt(qtyBox.getValue().isEmpty() ? "0" : qtyBox.getValue());
        } catch (NumberFormatException e) {
            qty = 0;
        }
        qty = Math.max(1, Math.min(Math.max(1, currentBuyLimit()), qty));
        syncBoxFromQty();
    }

    private void syncBoxFromQty() {
        if (qtyBox != null) {
            qtyBox.setValue(Integer.toString(qty));
        }
    }

    /** 当前分类过滤后的商品（不截断；滚动在渲染/命中时处理）。 */
    private List<SyncTradeCatalogPacket.Good> filteredGoods() {
        List<SyncTradeCatalogPacket.Good> out = new ArrayList<>();
        for (SyncTradeCatalogPacket.Good g : TradeClientState.goods()) {
            if (categoryId == null || categoryId.equals(g.categoryId)) {
                out.add(g);
            }
        }
        return out;
    }

    private int totalGoodsRows() {
        return Math.max(1, (filteredGoods().size() + GRID_COLS - 1) / GRID_COLS);
    }

    private int maxGoodsScrollRows() {
        return Math.max(0, totalGoodsRows() - GRID_ROWS);
    }

    private String categoryName(String id) {
        for (SyncTradeCatalogPacket.Category c : TradeClientState.categories()) {
            if (c.id.equals(id)) {
                return c.name;
            }
        }
        return id;
    }

    private ItemStack itemStackOf(SyncTradeCatalogPacket.Good g) {
        ItemStack stack = ItemStack.EMPTY;
        if (g != null && g.itemId != null && !g.itemId.isEmpty()) {
            var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(g.itemId));
            if (item != null) {
                stack = new ItemStack(item, 1);
                if (g.nbt != null && !g.nbt.isEmpty()) {
                    var tag = NbtMatcher.parseTag(g.nbt);
                    if (tag != null) {
                        stack.setTag(tag);
                    }
                }
            }
        }
        return stack;
    }

    /** 在指定方框内居中绘制物品图标（按整数倍缩放，绝不越出方框）。 */
    private void renderIconInBox(GuiGraphics gg, ItemStack stack, int boxX, int boxY, int boxSize) {
        if (stack.isEmpty() || boxSize <= 0) {
            return;
        }
        int scale = Math.max(1, boxSize / 16);
        int drawn = 16 * scale;
        int ox = boxX + (boxSize - drawn) / 2;
        int oy = boxY + (boxSize - drawn) / 2;
        gg.pose().pushPose();
        gg.pose().translate(ox, oy, 0);
        gg.pose().scale(scale, scale, 1);
        gg.renderItem(stack, 0, 0);
        gg.pose().popPose();
    }

    private String stockShort(SyncTradeCatalogPacket.Good g) {
        return Component.translatable("gui.dn.trade.stock_short", FormatUtil.compact(g.stock)).getString();
    }

    private String buyStateText(SyncTradeCatalogPacket.Good g) {
        return switch (g.buyCode) {
            case 1 -> Component.translatable("gui.dn.trade.state.out_of_stock").getString();
            case 2 -> Component.translatable("gui.dn.trade.state.low_stock", g.stockMin).getString();
            case 3 -> Component.translatable("gui.dn.trade.state.price_na").getString();
            default -> Component.translatable("gui.dn.trade.state.not_buyable").getString();
        };
    }

    /** 详情页状态行（限购/缺货原因）。 */
    private String buyStatusLine(SyncTradeCatalogPacket.Good g) {
        return buyStateText(g);
    }

    private String trunc(String s, int maxPx) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return font.width(s) <= maxPx ? s : font.plainSubstrByWidth(s, Math.max(0, maxPx - 4)) + "…";
    }

    private void drawMiniButton(GuiGraphics gg, int x, int y, int w, int h, String label,
                                int textColor, boolean active, int mouseX, int mouseY) {
        boolean hover = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bgTop = !active ? 0xFF1A1D26 : hover ? 0xFF3A4655 : 0xFF2A3040;
        int bgBot = !active ? 0xFF14161E : hover ? 0xFF2E3748 : 0xFF20242F;
        int border = !active ? 0xFF303648 : hover ? ACCENT : PANEL_BORDER_IN;
        gg.fillGradient(x, y, x + w, y + h, bgTop, bgBot);
        gg.renderOutline(x, y, w, h, border);
        gg.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, !active ? TEXT_DIM : textColor);
    }

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

    // ==================================================================
    // 区域布局
    // ==================================================================

    private Rect sidebarRect() {
        return new Rect(8, 26, 120, height - 36);
    }

    private Rect mainRect() {
        return new Rect(136, 26, width - 144, height - 36);
    }

    private Rect gridRect(Rect main) {
        int cellW = cellW(main);
        int cellH = cellH(main);
        int w = GRID_COLS * cellW + (GRID_COLS - 1) * 8;
        int h = GRID_ROWS * cellH + (GRID_ROWS - 1) * 8;
        return new Rect(main.x + 10, main.y + 30, w, h);
    }

    private int cellW(Rect main) {
        return (main.w - 28 - (GRID_COLS - 1) * 8) / GRID_COLS;
    }

    private int cellH(Rect main) {
        return (main.h - 48 - (GRID_ROWS - 1) * 8) / GRID_ROWS;
    }

    private Rect detailBodyRect() {
        int w = Math.min(520, width - 40);
        int h = Math.min(300, height - 40);
        return new Rect(Math.max(8, (width - w) / 2), Math.max(28, (height - h) / 2), w, h);
    }

    private Rect backRect() {
        return new Rect(8, 8, 60, 16);
    }

    private Rect qtyBoxRect(Rect body) {
        // 第一行布局（互不重叠）：标签 x+24 → 减号 x+94 → 输入框 x+122(宽70) → 加号 x+198
        return new Rect(body.x + 122, body.y + body.h - 58, 70, 16);
    }

    private Rect minusRect(Rect body) {
        Rect box = qtyBoxRect(body);
        return new Rect(box.x - 28, box.y - 1, 22, 18);
    }

    private Rect plusRect(Rect body) {
        Rect box = qtyBoxRect(body);
        return new Rect(box.x + box.w + 6, box.y - 1, 22, 18);
    }

    private Rect buyRect(Rect body) {
        return new Rect(body.x + body.w - 124, body.y + body.h - 32, 100, 22);
    }

    /** 简单整数矩形。 */
    private record Rect(int x, int y, int w, int h) {
        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
