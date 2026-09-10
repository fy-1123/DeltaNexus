package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.client.TradeSellIndex;
import com.deltanexus.system.common.FormatUtil;
import com.deltanexus.system.menu.WarehouseMenu;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2STradeSellPacket;
import com.deltanexus.system.network.packet.C2SWarehouseScrollPacket;
import com.deltanexus.system.network.packet.SyncSafeBoxPacket;
import com.deltanexus.system.network.packet.SyncWarehousePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 仓库 GUI（2.0.8 UI 重构：三列布局 + 原位滚动）。
 *
 * <p>布局遵循 UI 设计规范（{@code UI.html}，{@link PlayerLayout}）：
 * 左列 + 中列 = 玩家背包界面（盔甲/快捷栏列/副手 + 口袋/背包/安全箱），
 * 右列 = 仓库视口（12 行 x 9 列，滚轮滚动起始行）。</p>
 *
 * <p>2.0.8 滚动手感优化：滚动改为服务端原位替换视口槽位（{@code WarehouseMenu#scrollTo}），
 * 不再重建菜单/界面——光标物品不掉落、鼠标指针与悬停状态不重置、无闪烁。</p>
 */
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu> {

    private static final ResourceLocation SLOT = ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "textures/gui/slot.png");

    /** 服务端同步数据（由 SyncWarehousePacket 填充，屏幕构造时消费；特勤处同样订阅）。 */
    public static volatile SyncWarehousePacket lastSync;

    private SyncWarehousePacket sync;
    /** 滚轮滚动节流（2.0.1）。 */
    private int pendingScroll = 0;
    private long lastScrollSent = 0;

    // ---- 交易行卖出（0.2.0Beta）：出售模式 / 多选 / 二次确认 ----
    /** 出售模式开关（开启后点击仓库格为选中而非拖拽）。 */
    private boolean sellMode = false;
    /** 已点「出售」，等待「确认」。 */
    private boolean sellConfirm = false;
    /** 选中项：来源槽位 -> 选中信息（数量 + 记录时单价，滚动后预估仍准确）。 */
    private final java.util.Map<SellKey, SellSel> sellSelection = new java.util.LinkedHashMap<>();
    /** 每格匹配结果缓存（按 目录修订号 + 同步修订号 失效）。 */
    private final java.util.Map<SellKey, TradeSellIndex.Match> sellMatchCache = new java.util.HashMap<>();
    private long sellCacheKey = Long.MIN_VALUE;
    /** 仓库同步修订号（每次 SyncWarehousePacket 自增，用于失效匹配缓存）。 */
    private long syncRevision = 0;

    public WarehouseScreen(WarehouseMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.sync = lastSync;
    }

    /** 同步包到达：实时刷新（升级后无需重开 GUI）。 */
    public void onSync(SyncWarehousePacket packet) {
        this.sync = packet;
        // 视口/解锁变化后失效匹配缓存（选中项按全局索引保留，确认时服务端会重新校验）
        this.syncRevision++;
        this.sellMatchCache.clear();
    }

    /** 当前视口起始行（以同步包为准：原位滚动后 menu.scrollRow 不再重建）。 */
    private int scrollRow() {
        return sync != null ? sync.scrollRow : menu.scrollRow();
    }

    private int totalRows() {
        return sync != null ? Math.max(1, sync.totalRows) : 1;
    }

    /** 可滚动到的最大起始行（2.0.9：限定在已解锁行内——未解锁行不再可滚动）。 */
    private int maxScrollRow() {
        int rows = unlockedRows();
        return Math.max(0, Math.min(rows, totalRows()) - WarehouseMenu.WAREHOUSE_ROWS);
    }

    /**
     * 视口格子是否已解锁（按同步位图逐位判定）。
     * 位图缺失/越界时回退按解锁行数推导（宁可少画，不误画锁定格）。
     */
    private boolean isWarehouseSlotUnlocked(int globalIndex) {
        long[] bits = sync != null ? sync.unlocked : null;
        if (bits != null && bits.length > 0) {
            int word = globalIndex / 64;
            if (word < bits.length) {
                return (bits[word] & (1L << (globalIndex % 64))) != 0;
            }
            return false;
        }
        // 位图未就绪：按最高已解锁行推导（totalRows 含未解锁行，前 unlockedRows 行已解锁）
        return globalIndex < unlockedRows() * WarehouseMenu.WAREHOUSE_COLS;
    }

    /** 已解锁行数（位图缺失时回退 1，避免误判全部解锁）。 */
    private int unlockedRows() {
        long[] bits = sync != null ? sync.unlocked : null;
        if (bits == null || bits.length == 0) {
            return 1;
        }
        // 解锁为前缀式（unlockUpTo），最高置位行即已解锁行数
        int highest = -1;
        for (int w = 0; w < bits.length; w++) {
            if (bits[w] == 0L) continue;
            for (int b = 63; b >= 0; b--) {
                if ((bits[w] & (1L << b)) != 0) {
                    highest = Math.max(highest, w * 64 + b);
                    break;
                }
            }
        }
        return Math.max(1, highest / WarehouseMenu.WAREHOUSE_COLS + 1);
    }

    @Override
    protected void init() {
        // 全屏：GUI 覆盖整个屏幕（背景由 renderBackground 提供，面板自行绘制）
        this.imageWidth = this.width;
        this.imageHeight = this.height;
        this.leftPos = 0;
        this.topPos = 0;
        super.init();
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        PlayerLayout L = PlayerLayout.compute(width, height, true);
        // 右列：仓库面板（标题条 22 + 视口 + 行信息 18 + 底边距）
        // 2.0.10：面板高度按“玩家已解锁行数”而非总行数/视口行数——未解锁行完全不渲染
        int whPanelRows = Math.min(unlockedRows(), WarehouseMenu.WAREHOUSE_ROWS);
        DnTheme.drawPanel(gg, L.whX - 8, L.whY - 22, 162 + 16, whPanelRows * 18 + 22 + 18 + 8);
        // 左列：玩家面板（盔甲 + 快捷栏列 + 副手；2.0.9 移除 Curios，副手即底端）
        int leftBottom = L.offhandY + PlayerLayout.SLOT + 8;
        DnTheme.drawPanel(gg, L.leftX - 8, L.baseY - 22, PlayerLayout.SLOT + 16, leftBottom - L.baseY + 22 + 8);
        // 中列：口袋 + 背包 + 安全箱（整体一块面板，组标题见 renderLabels）
        // 2.0.9：面板高度按菜单实际安全箱槽位推算（按解锁数渲染，不再固定 3 行）
        int safeRows = Math.max(1, (menu.safeCount() + menu.safeW - 1) / menu.safeW);
        int midBottom = L.safeY + safeRows * PlayerLayout.SLOT + 8;
        DnTheme.drawPanel(gg, L.midX - 8, L.baseY - 22, 9 * PlayerLayout.SLOT + 16, midBottom - L.baseY + 22 + 8);
        // 视口槽位底图（2.0.9：未解锁格完全不渲染——按同步位图逐格判定）
        for (int i = 0; i < WarehouseMenu.WAREHOUSE_SLOTS && i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (!isWarehouseSlotUnlocked(slot.getSlotIndex())) {
                continue;
            }
            gg.blit(SLOT, slot.x, slot.y, 0, 0, 18, 18, 18, 18);
        }
        // 玩家区槽位（背包/快捷栏/盔甲/副手/安全箱）按菜单槽位坐标补底图
        // 2.1：安全箱被禁用时不渲染任何安全箱格子（标题提示见 renderLabels）
        boolean safeOk = SafeBoxOverlay.safeAllowed(SafeBoxOverlay.lastState());
        int safeStart = Math.min(menu.safeStart, menu.slots.size());
        for (int i = WarehouseMenu.PLAYER_START; i < menu.slots.size(); i++) {
            if (i >= safeStart && !safeOk) {
                continue; // 安全箱区域且被禁用：跳过格子
            }
            var slot = menu.slots.get(i);
            gg.blit(SLOT, slot.x, slot.y, 0, 0, 18, 18, 18, 18);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        PlayerLayout L = PlayerLayout.compute(width, height, true);
        int lw = PlayerLayout.SLOT + 16;
        int mw = 9 * PlayerLayout.SLOT + 16;
        // 左列组标题
        drawTitle(gg, L.leftX - 8, L.armorY - 22, lw, "gui.dn.armor", DnTheme.TEXT_MAIN);
        drawTitle(gg, L.leftX - 8, L.hotbarColY - 22, lw, "gui.dn.hotbar", DnTheme.TEXT_MAIN);
        drawTitle(gg, L.leftX - 8, L.offhandY - 22, lw, "gui.dn.offhand", DnTheme.TEXT_MAIN);
        // 中列组标题
        drawTitle(gg, L.midX - 8, L.pocketY - 22, 5 * PlayerLayout.SLOT + 16, "gui.dn.pocket", DnTheme.TEXT_MAIN);
        drawTitle(gg, L.midX - 8, L.invY - 22, mw, "container.inventory", DnTheme.TEXT_MAIN);
        // 右列：仓库标题 + 货币余额
        drawTitle(gg, L.whX - 8, L.whY - 22, mw, "gui.dn.warehouse", DnTheme.TEXT_MAIN);
        if (sync != null && sync.currencyUsable) {
            String money = currencyLabel() + ": " + FormatUtil.compact(sync.currencyCount);
            gg.drawString(font, money, L.whX + 162 - font.width(money), L.whY - 17, DnTheme.GOLD);
        }
        // 安全箱标题（中列背包下方；2.1：被禁用时红色「安全箱被禁用」提示）
        int safeW = Math.max(1, Math.min(3, menu.safeW));
        SyncSafeBoxPacket safeSt = SafeBoxOverlay.lastState();
        String safeTitle = safeSt != null
                ? SafeBoxOverlay.safeTitle(safeSt)
                : Component.translatable("gui.dn.safe_box").getString()
                        + (sync != null ? " Lv" + sync.safeLevel : "");
        DnTheme.drawTitle(gg, font, L.midX - 8, L.safeY - 22, safeW * 18 + 16, safeTitle,
                safeSt != null ? SafeBoxOverlay.safeTitleColor(safeSt) : DnTheme.GOLD);
        // 行信息：起始行-结束行 / 已解锁行数（2.0.10：看玩家解锁了多少，而非仓库总行数）
        int unlocked = Math.max(0, unlockedRows());
        int endRow = Math.min(scrollRow() + WarehouseMenu.WAREHOUSE_ROWS, unlocked);
        String rowInfo = (scrollRow() + 1) + " - " + Math.max(scrollRow() + 1, endRow) + " / " + unlocked;
        // 0.2.0Beta：出售按钮（左） + 右侧显示“预计总额”或行信息
        drawSellButton(gg, mouseX, mouseY);
        String rightText = !sellSelection.isEmpty()
                ? Component.translatable("gui.dn.trade.sell.estimate", FormatUtil.compact(estimateTotal())).getString()
                : rowInfo;
        gg.drawString(font, rightText, L.whX + 162 - font.width(rightText), L.whY + 218, DnTheme.ACCENT);
    }

    private void drawTitle(GuiGraphics gg, int x, int y, int w, String key, int color) {
        DnTheme.drawTitle(gg, font, x, y, w, Component.translatable(key).getString(), color);
    }

    /** 货币类型显示名（vault/playerpoints/计分板 等非物品货币用；item 走图标无需标签）。 */
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

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // 2.0.8 修复：节流期间累积的滚动量在停止滚动后补发（否则快速滚动会丢最后一格）
        if (pendingScroll != 0 && System.currentTimeMillis() - lastScrollSent >= 120) {
            sendScroll();
            lastScrollSent = System.currentTimeMillis();
        }
        renderBackground(gg);
        super.render(gg, mouseX, mouseY, partialTick);
        renderSellOverlay(gg);
        renderTooltip(gg, mouseX, mouseY);
    }

    /** 2.0.10：未解锁的仓库槽位不显示 tooltip（防御服务端残留数据穿透；
     *  物品与高亮的隐藏由 LockedAwareSlot.isActive() 实现）。 */
    @Override
    protected void renderTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        if (this.hoveredSlot != null
                && this.hoveredSlot.index < WarehouseMenu.WAREHOUSE_SLOTS
                && !isWarehouseSlotUnlocked(this.hoveredSlot.getSlotIndex())) {
            return;
        }
        // 出售模式：为悬停槽位（仓库/背包/安全箱）追加回收信息（仅悬停格懒计算）
        if (sellMode && this.hoveredSlot != null && !this.hoveredSlot.getItem().isEmpty()) {
            SellKey key = sellKeyOf(this.hoveredSlot);
            if (key != null) {
                java.util.List<Component> lines = new java.util.ArrayList<>(
                        this.getTooltipFromItem(this.minecraft, this.hoveredSlot.getItem()));
                TradeSellIndex.Match m = matchAt(key);
                if (m != null) {
                    lines.add(Component.translatable("gui.dn.trade.sell.tooltip",
                            m.displayName, FormatUtil.compact(m.unitPrice)));
                    SellSel sel = sellSelection.get(key);
                    if (sel != null) {
                        lines.add(Component.translatable("gui.dn.trade.sell.selected", sel.qty));
                    }
                } else {
                    lines.add(Component.translatable("gui.dn.trade.sell.unsellable"));
                }
                java.util.List<net.minecraft.util.FormattedCharSequence> fcs =
                        lines.stream().map(Component::getVisualOrderText).toList();
                gg.renderTooltip(font, fcs, mouseX, mouseY);
                return;
            }
        }
        super.renderTooltip(gg, mouseX, mouseY);
    }

    /** 仓库区滚轮滚动（2.0.1 动态向下渲染；2.0.8 原位滚动，界面不重建）。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return handleScroll(mouseX, mouseY, delta);
    }

    /** 处理一次滚轮（仓库行滚动）；返回是否已消费。 */
    public boolean handleScroll(double mouseX, double mouseY, double delta) {
        if (delta != 0) {
            pendingScroll += delta > 0 ? -1 : 1;
            long now = System.currentTimeMillis();
            // 节流：至少 120ms 或累积 4 格才发送，避免滚轮高频发包
            if (now - lastScrollSent >= 120 || Math.abs(pendingScroll) >= 4) {
                sendScroll();
                lastScrollSent = now;
            }
            return true;
        }
        return false;
    }

    /** 发送滚动请求（起始行 clamp 到已解锁范围）。 */
    private void sendScroll() {
        if (pendingScroll == 0) {
            return;
        }
        int target = net.minecraft.util.Mth.clamp(scrollRow() + pendingScroll, 0, maxScrollRow());
        pendingScroll = 0;
        if (target != scrollRow()) {
            PacketHandler.sendToServer(new C2SWarehouseScrollPacket(target));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 抢在 vanilla 鼠标居中逻辑之前消费滚轮事件（2.0.2）：
     * vanilla 在容器界面「光标持有物品 + 滚轮」时会强制把滚轮坐标视为屏幕中心，
     * 此处以真实鼠标位置滚动仓库，避免指针行为异常。
     */
    // ==================================================================
    // 交易行卖出（0.2.0Beta）：多选 → 出售 → 确认
    // ==================================================================

    /** 出售按钮热区（行信息条左侧）。 */
    private BtnRect sellBtnRect() {
        PlayerLayout L = PlayerLayout.compute(width, height, true);
        return new BtnRect(L.whX - 4, L.whY + 214, 54, 17);
    }

    private boolean isWarehouseViewSlot(int menuIndex) {
        return menuIndex >= 0 && menuIndex < WarehouseMenu.WAREHOUSE_SLOTS;
    }

    /** 槽位来源：0=仓库 1=背包/快捷栏 2=安全箱；-1=不参与回收（盔甲/副手/其他）。 */
    private int sellSourceOf(net.minecraft.world.inventory.Slot slot) {
        if (slot == null) {
            return -1;
        }
        if (isWarehouseViewSlot(slot.index)) {
            return C2STradeSellPacket.SOURCE_WAREHOUSE;
        }
        if (slot.container instanceof Inventory && slot.getContainerSlot() < 36) {
            return C2STradeSellPacket.SOURCE_INVENTORY;
        }
        int safeStart = menu.safeStart;
        if (slot.index >= safeStart && slot.index < safeStart + Math.max(0, menu.safeCount())) {
            return C2STradeSellPacket.SOURCE_SAFE_BOX;
        }
        return -1;
    }

    /** 槽位在来源内的索引。 */
    private int sellIndexOf(net.minecraft.world.inventory.Slot slot, int source) {
        return source == C2STradeSellPacket.SOURCE_WAREHOUSE ? slot.getSlotIndex() : slot.getContainerSlot();
    }

    private SellKey sellKeyOf(net.minecraft.world.inventory.Slot slot) {
        int source = sellSourceOf(slot);
        return source < 0 ? null : new SellKey(source, sellIndexOf(slot, source));
    }

    /** 按（来源, 索引）取当前菜单内的物品。 */
    private ItemStack stackAt(SellKey key) {
        for (var slot : menu.slots) {
            int source = sellSourceOf(slot);
            if (source == key.source && sellIndexOf(slot, source) == key.index) {
                return slot.getItem();
            }
        }
        return ItemStack.EMPTY;
    }

    /** 该槽位匹配的回收商品（按目录/同步修订号缓存；覆盖仓库/背包/安全箱）。 */
    private TradeSellIndex.Match matchAt(SellKey key) {
        TradeSellIndex idx = TradeSellIndex.get();
        long cacheKey = syncRevision * 1_000_003L + idx.revision();
        if (cacheKey != sellCacheKey) {
            sellMatchCache.clear();
            sellCacheKey = cacheKey;
        }
        if (sellMatchCache.containsKey(key)) {
            return sellMatchCache.get(key);
        }
        ItemStack st = stackAt(key);
        TradeSellIndex.Match m = st.isEmpty() ? null : idx.match(st);
        sellMatchCache.put(key, m);
        return m;
    }

    /** 左键：选中/取消整堆；右键：整堆 ⇄ 仅 1 个。 */
    private void toggleSellSelection(SellKey key, boolean rightClick, int stackCount) {
        TradeSellIndex.Match m = matchAt(key);
        if (m == null) {
            return;
        }
        SellSel cur = sellSelection.get(key);
        if (rightClick) {
            if (cur == null) {
                sellSelection.put(key, new SellSel(1, m.unitPrice, m.goodId));
            } else if (cur.qty > 1) {
                sellSelection.put(key, new SellSel(1, m.unitPrice, m.goodId));
            } else {
                sellSelection.put(key, new SellSel(stackCount, m.unitPrice, m.goodId));
            }
            return;
        }
        if (cur == null) {
            sellSelection.put(key, new SellSel(stackCount, m.unitPrice, m.goodId));
        } else {
            sellSelection.remove(key);
        }
    }

    private long estimateTotal() {
        long sum = 0L;
        for (SellSel s : sellSelection.values()) {
            sum += s.unitPrice * (long) s.qty;
        }
        return sum;
    }

    private void sendSell() {
        java.util.List<C2STradeSellPacket.Entry> list = new java.util.ArrayList<>();
        sellSelection.forEach((key, sel) ->
                list.add(new C2STradeSellPacket.Entry(key.source, key.index, sel.qty)));
        if (!list.isEmpty()) {
            PacketHandler.sendToServer(new C2STradeSellPacket(list));
        }
        sellSelection.clear();
        sellConfirm = false;
    }

    private void clearSellState() {
        sellMode = false;
        sellConfirm = false;
        sellSelection.clear();
        sellMatchCache.clear();
    }

    private void drawSellButton(GuiGraphics gg, int mouseX, int mouseY) {
        BtnRect r = sellBtnRect();
        boolean hover = r.contains(mouseX, mouseY);
        String label = sellConfirm
                ? Component.translatable("gui.dn.trade.sell.confirm").getString()
                : (sellSelection.isEmpty()
                        ? Component.translatable("gui.dn.trade.sell.button").getString()
                        : Component.translatable("gui.dn.trade.sell.button_count", sellSelection.size()).getString());
        int top = sellConfirm ? (hover ? 0xFFB23A3A : 0xFF8E2B2B) : (hover ? 0xFF3A4655 : 0xFF2A3040);
        int bot = sellConfirm ? (hover ? 0xFF8E2B2B : 0xFF6E1F1F) : (hover ? 0xFF2E3748 : 0xFF20242F);
        gg.fillGradient(r.x, r.y, r.x + r.w, r.y + r.h, top, bot);
        gg.renderOutline(r.x, r.y, r.w, r.h, sellConfirm ? 0xFFFF6B6B : DnTheme.PANEL_BORDER_IN);
        gg.drawCenteredString(font, label, r.x + r.w / 2, r.y + (r.h - 8) / 2,
                sellConfirm ? 0xFFFFFFFF : DnTheme.TEXT_MAIN);
    }

    /** 选中（金框 + 数量角标）与可回收（绿框）高亮；覆盖仓库、背包/快捷栏、安全箱。 */
    private void renderSellOverlay(GuiGraphics gg) {
        if (!sellMode) {
            return;
        }
        boolean safeOk = SafeBoxOverlay.safeAllowed(SafeBoxOverlay.lastState());
        for (var slot : menu.slots) {
            int source = sellSourceOf(slot);
            if (source < 0 || slot.getItem().isEmpty()) {
                continue;
            }
            if (source == C2STradeSellPacket.SOURCE_WAREHOUSE && !isWarehouseSlotUnlocked(slot.getSlotIndex())) {
                continue;
            }
            if (source == C2STradeSellPacket.SOURCE_SAFE_BOX && !safeOk) {
                continue;
            }
            SellKey key = new SellKey(source, sellIndexOf(slot, source));
            TradeSellIndex.Match m = matchAt(key);
            if (m == null) {
                continue;
            }
            SellSel sel = sellSelection.get(key);
            if (sel != null) {
                gg.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x55FFD700);
                gg.renderOutline(slot.x - 1, slot.y - 1, 18, 18, 0xFFFFD700);
                gg.renderOutline(slot.x, slot.y, 16, 16, 0xFFFFD700);
                String badge = sel.qty >= slot.getItem().getCount() ? "堆" : String.valueOf(sel.qty);
                gg.pose().pushPose();
                gg.pose().translate(slot.x + 1, slot.y + 9, 200);
                gg.pose().scale(0.5f, 0.5f, 1f);
                gg.drawString(font, badge, 0, 0, 0xFFFFF0A0, true);
                gg.pose().popPose();
            } else {
                gg.renderOutline(slot.x - 1, slot.y - 1, 18, 18, 0x806BD47A);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        BtnRect btn = sellBtnRect();
        if (btn.contains(mx, my)) {
            if (!sellMode) {
                // 进入出售模式
                sellMode = true;
                sellConfirm = false;
                sellSelection.clear();
            } else if (button == 1) {
                // 右键按钮：退出出售模式
                clearSellState();
            } else if (sellConfirm) {
                sendSell();
            } else if (sellSelection.isEmpty()) {
                clearSellState();
            } else {
                sellConfirm = true;
            }
            return true;
        }
        if (sellMode) {
            if (sellConfirm) {
                sellConfirm = false;
                return true;
            }
            var slot = this.getSlotUnderMouse();
            if (slot != null) {
                SellKey key = sellKeyOf(slot);
                if (key != null && !slot.getItem().isEmpty() && matchAt(key) != null) {
                    toggleSellSelection(key, button == 1, slot.getItem().getCount());
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (sellMode && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (sellConfirm) {
                sellConfirm = false;
            } else {
                clearSellState();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 选中信息（数量 + 记录时单价，便于滚动后预估）。 */
    private record SellSel(int qty, long unitPrice, String goodId) {
    }

    /** 回收来源槽位标识（source：0=仓库 1=背包 2=安全箱）。 */
    private record SellKey(int source, int index) {
    }

    /** 整数矩形热区。 */
    private record BtnRect(int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(
            modid = DeltaNexus.MODID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
    public static final class ScrollInterceptor {

        private ScrollInterceptor() {
        }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onMouseScroll(net.minecraftforge.client.event.InputEvent.MouseScrollingEvent event) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof WarehouseScreen s) {
                double gx = event.getMouseX() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
                double gy = event.getMouseY() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
                s.handleScroll(gx, gy, event.getScrollDelta());
                event.setCanceled(true);
            }
        }
    }
}
