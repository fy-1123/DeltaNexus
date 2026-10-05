package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.client.GearClientState;
import com.deltanexus.system.client.TradeSellIndex;
import com.deltanexus.system.common.FormatUtil;
import com.deltanexus.system.grid.GearConfig;
import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.grid.GridClassConfig;
import com.deltanexus.system.grid.GridClientRendering;
import com.deltanexus.system.grid.GridEntry;
import com.deltanexus.system.grid.GridSize;
import com.deltanexus.system.grid.GridStore;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.grid.StoreContainer;
import com.deltanexus.system.menu.WarehouseMenu;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SOpenGearPacket;
import com.deltanexus.system.network.packet.C2SOpenGearWindowPacket;
import com.deltanexus.system.network.packet.C2SOverlayClickPacket;
import com.deltanexus.system.network.packet.C2STradeSellPacket;
import com.deltanexus.system.network.packet.C2SWarehouseScrollPacket;
import com.deltanexus.system.network.packet.SyncSafeBoxPacket;
import com.deltanexus.system.network.packet.SyncWarehousePacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 仓库 GUI（0.5.0Beta 格子背包装备化：三列布局 + 原位滚动）。
 *
 * <p>布局遵循 {@link PlayerLayout}：左列 + 中列 = 玩家区，右列 = 仓库视口（12 行 x 9 列，滚轮滚动起始行）。
 * 左列 = 装备（胸挂/背包，非菜单槽：自绘 + 自处理点击）/ 盔甲 4 / 副手 1；
 * 中列 = 口袋（inv 9-13）/ 快捷栏 9（inv 0-8）/ 安全箱。
 * 被胸挂/背包取代的 22 格（inv 14-35）移到屏外（菜单索引不变）。</p>
 *
 * <p>2.0.8Alpha 滚动手感优化：滚动改为服务端原位替换视口槽位（{@code WarehouseMenu#scrollTo}），
 * 不再重建菜单/界面——光标物品不掉落、鼠标指针与悬停状态不重置、无闪烁。</p>
 */
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu>
        implements com.deltanexus.system.grid.adapter.InputGate.SellModeSource, DnOverlayTooltips {

    /** 屏外坐标（被装备取代的 22 格隐藏用：不渲染不可点击）。 */
    private static final int OFFSCREEN = -1000;

    /** 统一提示层的 z 抬升量（提示框自身 z≈400、网格大图标 z≈550，见 {@link DnOverlayTooltips}）。 */
    private static final int TOOLTIP_Z = 1000;

    /** 服务端同步数据（由 SyncWarehousePacket 填充，屏幕构造时消费；特勤处同样订阅）。 */
    public static volatile SyncWarehousePacket lastSync;

    private SyncWarehousePacket sync;
    /** 滚轮滚动节流（2.0.1Alpha）。 */
    private int pendingScroll = 0;
    private long lastScrollSent = 0;

    // ---- 主面板物品区（口袋 / 胸挂 / 背包 / 安全箱）：与 E 键界面同一套 sakura 几何 ----
    /** 主面板内容视口（照抄 DnInventoryScreen：超出滑动 + 裁剪）。 */
    private final DnInventoryScreen.Viewport itemViewport = new DnInventoryScreen.Viewport();
    /** 背包标签 y（面板顶相对值）。 */
    private int backpackLabelY;
    /** 安全箱标签 y（面板顶相对值）。 */
    private int safeBoxLabelY;
    private boolean draggingScrollbar;
    private double scrollbarGrab;

    /** 布局缓存（几何固定，仅随窗口尺寸变化）。 */
    private PlayerLayout cachedLayout;
    private int cachedW = -1;
    private int cachedH = -1;

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

    /** 可滚动到的最大起始行（2.0.9Alpha：限定在已解锁行内——未解锁行不再可滚动）。 */
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
        // 出售模式来源：注册给 grid 的统一交互门闸（0.3.0Beta；grid 包不再引用本类）
        com.deltanexus.system.grid.adapter.InputGate.setSellModeSource(this);
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    /** 本帧布局（几何固定，按窗口尺寸缓存）。 */
    private PlayerLayout layout() {
        if (cachedLayout == null || cachedW != width || cachedH != height) {
            int safeRows = Math.max(1, (menu.safeCount() + Math.max(1, menu.safeW) - 1) / Math.max(1, menu.safeW));
            cachedLayout = PlayerLayout.compute(width, height, safeRows,
                    Math.min(unlockedRows(), WarehouseMenu.WAREHOUSE_ROWS));
            cachedW = width;
            cachedH = height;
        }
        return cachedLayout;
    }

    // ==================================================================
    // 主面板物品区（口袋 / 胸挂 / 背包 / 安全箱）：照抄 DnInventoryScreen
    // ==================================================================

    private static int gearRows(GearKind kind) {
        return Math.max(1, GearClientState.container(kind).store().rows());
    }

    /** 安全箱行数（按已解锁槽位与列数）。 */
    private int mainSafeRows() {
        int w = Math.max(1, menu.safeW);
        return Math.max(1, (menu.safeCount() + w - 1) / w);
    }

    /** 主面板第 n 列槽位 x。 */
    private int mainSlotX(int n) {
        return layout().midX + n * PlayerLayout.PITCH;
    }

    /** 主面板内容 y（含滚动偏移）。 */
    private int contentY(int n) {
        return layout().topY + n - itemViewport.offset();
    }

    private int viewportTop() {
        return layout().topY + 4;
    }

    private int viewportBottom() {
        return layout().hotbarLabelY - 8;
    }

    private int clipLeft() {
        return layout().midPanelX + 3;
    }

    private int clipRight() {
        return layout().midPanelX + PlayerLayout.MAIN_W - 8;
    }

    private int sectionBarWidth() {
        return 168;
    }

    /** 重算主面板内容位置与视口，并重排口袋 / 安全箱菜单槽（滚动时逐帧刷新）。 */
    private void refreshMainLayout() {
        boolean rigEmpty = GearClientState.equipped(GearKind.RIG).isEmpty();
        boolean bagEmpty = GearClientState.equipped(GearKind.BACKPACK).isEmpty();
        int rigH = gearRows(GearKind.RIG) * PlayerLayout.PITCH;
        int bagH = gearRows(GearKind.BACKPACK) * PlayerLayout.PITCH;
        this.backpackLabelY = 64 + (rigEmpty ? 52 : rigH) + 14;
        this.safeBoxLabelY = this.backpackLabelY + 14 + (bagEmpty ? 52 : bagH) + 14;
        boolean safe = SafeBoxOverlay.safeAllowed(SafeBoxOverlay.lastState());
        int contentBottom = safe
                ? this.safeBoxLabelY + 18 + Math.max(30, mainSafeRows() * PlayerLayout.PITCH) + 30
                : this.safeBoxLabelY;
        this.itemViewport.configure(viewportTop(), viewportBottom(), contentBottom - 4);

        // 盔甲（头盔 39 / 胸甲 38）：左面板盔甲列
        PlayerLayout L = layout();
        int[] armorInv = {39, 38};
        for (int i = 0; i < armorInv.length; i++) {
            Slot s = playerSlot(armorInv[i]);
            if (s == null) {
                continue;
            }
            s.x = L.leftX;
            s.y = L.armorY + i * PlayerLayout.GEAR_STEP;
        }

        // 口袋（containerSlot 9..13）
        int py = contentY(22);
        for (int n = 0; n < 5; n++) {
            Slot s = playerSlot(9 + n);
            if (s == null) {
                continue;
            }
            if (this.itemViewport.fullyVisible(py - 1, PlayerLayout.SLOT)) {
                s.x = mainSlotX(n);
                s.y = py;
            } else {
                s.x = OFFSCREEN;
                s.y = OFFSCREEN;
            }
        }
        // 安全箱（菜单槽）
        int safeW = Math.max(1, menu.safeW);
        int safeY0 = contentY(this.safeBoxLabelY + 14);
        for (int i = 0; i < menu.safeCount(); i++) {
            int idx = menu.safeStart + i;
            if (idx < 0 || idx >= menu.slots.size()) {
                continue;
            }
            Slot s = menu.slots.get(idx);
            int y = safeY0 + (i / safeW) * PlayerLayout.PITCH;
            if (safe && this.itemViewport.fullyVisible(y - 1, PlayerLayout.SLOT)) {
                s.x = mainSlotX(i % safeW);
                s.y = y;
            } else {
                s.x = OFFSCREEN;
                s.y = OFFSCREEN;
            }
        }
    }

    /** 按容器内下标找玩家背包菜单槽（口袋 / 快捷栏）。 */
    private Slot playerSlot(int containerSlot) {
        for (Slot s : menu.slots) {
            if (s.container instanceof Inventory && s.getContainerSlot() == containerSlot) {
                return s;
            }
        }
        return null;
    }

    private void beginItemClip(GuiGraphics gg) {
        gg.enableScissor(clipLeft(), itemViewport.top(), clipRight(), itemViewport.bottom());
    }

    private void drawScrollbar(GuiGraphics gg) {
        if (itemViewport.maximum() == 0) {
            return;
        }
        int x = clipRight();
        gg.fill(x, itemViewport.top(), x + 4, itemViewport.bottom(), -2010105284);
        gg.fill(x, itemViewport.thumbY(), x + 4, itemViewport.thumbY() + itemViewport.thumbHeight(),
                draggingScrollbar ? -1853869 : -6978976);
    }

    /** 左面板生命 / 饥饿 / 护甲读数。 */
    private void drawLeftStatus(GuiGraphics gg) {
        PlayerLayout L = layout();
        DnUiTheme.drawPlayerStatus(gg, this.font, L.leftPanelX, L.topY);
    }

    /** 玩家预览。 */
    private void drawPlayerPreview(GuiGraphics gg, int mouseX, int mouseY) {
        PlayerLayout L = layout();
        DnUiTheme.drawPlayerPreview(gg, L.leftPanelX, L.topY, mouseX, mouseY);
    }

    /** 胸挂 / 背包网格底框（未装备时画缺失块）。 */
    private void drawStorageSection(GuiGraphics gg, GearKind kind, int offsetY) {
        if (GearClientState.equipped(kind).isEmpty()) {
            DnUiTheme.drawMissingGear(gg, font,
                    Component.translatable(kind == GearKind.RIG
                            ? "gui.dn.gear.empty_rig" : "gui.dn.gear.empty_backpack"),
                    mainSlotX(0), contentY(offsetY), sectionBarWidth());
            return;
        }
        StoreContainer box = GearClientState.container(kind);
        GridStore store = box.store();
        int cols = Math.max(1, store.width());
        for (int cell = 0; cell < store.size(); cell++) {
            if (box.isCovered(cell)) {
                continue;
            }
            int x = mainSlotX(cell % cols);
            int y = contentY(offsetY + (cell / cols) * PlayerLayout.PITCH);
            GridEntry entry = store.entryAt(cell);
            if (entry != null && !entry.isEmpty()) {
                DnUiTheme.drawRaritySlot(gg, x, y, entry.stack());
            } else {
                DnUiTheme.drawSlotBackdrop(gg, x, y);
            }
        }
    }

    /** 胸挂 / 背包网格物品本体。 */
    private void drawStorageItems(GuiGraphics gg, GearKind kind, int offsetY) {
        if (GearClientState.equipped(kind).isEmpty()) {
            return;
        }
        GridStore store = GearClientState.container(kind).store();
        int cols = Math.max(1, store.width());
        for (java.util.Map.Entry<Integer, GridEntry> e : store.entries().entrySet()) {
            GridEntry entry = e.getValue();
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            int anchor = e.getKey();
            int x = mainSlotX(anchor % cols);
            int y = contentY(offsetY + (anchor / cols) * PlayerLayout.PITCH);
            GridSize size = entry.size();
            InventoryGridHandler.ItemDim dim = new InventoryGridHandler.ItemDim(size.w(), size.h());
            if (dim.is1x1()) {
                if (GridClassConfig.isClassed(entry.stack())) {
                    GridClientRendering.renderGridStack(gg, entry.stack(), x, y, dim, false,
                            GridClassConfig.bgOf(entry.stack()));
                } else {
                    gg.renderItem(entry.stack(), x, y);
                    gg.renderItemDecorations(font, entry.stack(), x, y);
                }
            } else {
                GridClientRendering.renderGridStack(gg, entry.stack(), x, y, dim, entry.rotated());
            }
        }
    }

    private int storageCellAt(GearKind kind, int offsetY, double mouseX, double mouseY) {
        if (GearClientState.equipped(kind).isEmpty()) {
            return -1;
        }
        GridStore store = GearClientState.container(kind).store();
        int cols = Math.max(1, store.width());
        for (int cell = 0; cell < store.size(); cell++) {
            int x = mainSlotX(cell % cols);
            int y = contentY(offsetY + (cell / cols) * PlayerLayout.PITCH);
            if (hit(mouseX, mouseY, x, y, PlayerLayout.PITCH, PlayerLayout.PITCH)) {
                return cell;
            }
        }
        return -1;
    }

    private void drawStorageHover(GuiGraphics gg, GearKind kind, int offsetY, int mouseX, int mouseY) {
        int cell = storageCellAt(kind, offsetY, mouseX, mouseY);
        if (cell < 0) {
            return;
        }
        GridStore store = GearClientState.container(kind).store();
        int cols = Math.max(1, store.width());
        int anchor = store.anchorAt(cell);
        int show = anchor >= 0 ? anchor : cell;
        DnUiTheme.drawSlotHover(gg, mainSlotX(show % cols), contentY(offsetY + (show / cols) * PlayerLayout.PITCH));
    }

    private void drawStorageTooltip(GuiGraphics gg, GearKind kind, int offsetY, int mouseX, int mouseY) {
        int cell = storageCellAt(kind, offsetY, mouseX, mouseY);
        if (cell < 0) {
            return;
        }
        GridStore store = GearClientState.container(kind).store();
        int anchor = store.anchorAt(cell);
        int show = anchor >= 0 ? anchor : cell;
        GridEntry entry = store.entryAt(show);
        if (entry != null && !entry.isEmpty()) {
            gg.renderTooltip(font, entry.stack(), mouseX, mouseY);
        }
    }

    /** 客户端侧把足迹格重定向到锚点格后发送（服务端另有权威归一）；右键点击装备物品请求打开其窗口。 */
    private void clickGear(int target, int cell, int button, boolean shift) {
        GearKind kind = target == C2SOverlayClickPacket.TARGET_RIG ? GearKind.RIG : GearKind.BACKPACK;
        GridStore store = GearClientState.container(kind).store();
        int anchor = store.anchorAt(cell);
        int at = anchor >= 0 ? anchor : cell;
        GridEntry entry = store.entryAt(at);
        if (button == 1 && !shift && entry != null && !entry.isEmpty() && GearConfig.isGear(entry.stack())) {
            PacketHandler.sendToServer(new C2SOpenGearWindowPacket(
                    C2SOpenGearWindowPacket.ROOT_EQUIPPED, kind.ordinal(), new int[]{at}));
            return;
        }
        PacketHandler.sendToServer(new C2SOverlayClickPacket(target, at, button, shift));
    }

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        PlayerLayout L = layout();
        refreshMainLayout();
        // 三列面板外壳（与背包/容器界面同一套皮肤）
        DnUiTheme.drawPanelShell(gg, L.leftPanelX, L.topY, PlayerLayout.EQUIP_W, L.panelH);
        DnUiTheme.drawPanelShell(gg, L.midPanelX, L.topY, PlayerLayout.MAIN_W, L.panelH);
        DnUiTheme.drawPanelShell(gg, L.whPanelX, L.topY, PlayerLayout.RIGHT_W, L.panelH);
        // 仓库视口槽位底（2.0.9Alpha：未解锁格完全不渲染——按同步位图逐格判定）
        for (int i = 0; i < WarehouseMenu.WAREHOUSE_SLOTS && i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (!isWarehouseSlotUnlocked(slot.getSlotIndex())) {
                continue;
            }
            DnUiTheme.drawSlotBackdrop(gg, slot.x, slot.y);
        }
        // ---- 主面板物品区（口袋 / 胸挂 / 背包 / 安全箱；超出视口裁剪）----
        boolean safeOk = SafeBoxOverlay.safeAllowed(SafeBoxOverlay.lastState());
        beginItemClip(gg);
        DnUiTheme.drawTacticalLabel(gg, font, Component.translatable("gui.dn.pocket"),
                mainSlotX(0) - 1, contentY(8), sectionBarWidth());
        DnUiTheme.drawTacticalLabel(gg, font, Component.translatable("gui.dn.gear.rig"),
                mainSlotX(0) - 1, contentY(50), sectionBarWidth());
        DnUiTheme.drawTacticalLabel(gg, font, Component.translatable("gui.dn.gear.backpack"),
                mainSlotX(0) - 1, contentY(backpackLabelY), sectionBarWidth());
        drawStorageSection(gg, GearKind.RIG, 64);
        drawStorageSection(gg, GearKind.BACKPACK, backpackLabelY + 14);
        // 安全箱标签（与胸挂 / 背包同列下方）
        SyncSafeBoxPacket safeSt = SafeBoxOverlay.lastState();
        String safeTitle = safeSt != null
                ? SafeBoxOverlay.safeTitle(safeSt)
                : Component.translatable("gui.dn.safe_box").getString();
        DnUiTheme.drawTacticalLabel(gg, font, Component.literal(safeTitle),
                mainSlotX(0) - 1, contentY(safeBoxLabelY), sectionBarWidth());
        // 口袋 / 安全箱菜单槽底（快捷栏在裁剪区外单独绘制）
        int safeStart = Math.min(menu.safeStart, menu.slots.size());
        for (int i = WarehouseMenu.PLAYER_START; i < menu.slots.size(); i++) {
            if (i >= safeStart && !safeOk) {
                continue; // 安全箱区域且被禁用：跳过格子
            }
            var slot = menu.slots.get(i);
            if (slot.x <= OFFSCREEN / 2 || slot.y <= OFFSCREEN / 2) {
                continue; // 屏外（被取代的 22 格 / 隐藏盔甲 / 副手 / 视口外）
            }
            if (slot.container instanceof Inventory) {
                int ci = slot.getContainerSlot();
                if (ci < 9 || ci == 38 || ci == 39) {
                    continue; // 快捷栏 / 盔甲：裁剪区外或自绘
                }
            }
            DnUiTheme.drawSlotBackdrop(gg, slot.x, slot.y);
        }
        gg.disableScissor();
        // 快捷栏（钉在面板底，不参与滚动）
        DnUiTheme.drawTacticalLabel(gg, font, Component.translatable("gui.dn.hotbar"),
                mainSlotX(0) - 1, L.hotbarLabelY, sectionBarWidth());
        for (int n = 0; n < 9; n++) {
            Slot s = playerSlot(n);
            if (s != null && s.x > OFFSCREEN / 2 && s.y > OFFSCREEN / 2) {
                DnUiTheme.drawSlotBackdrop(gg, s.x, s.y);
            }
        }
        drawScrollbar(gg);
        // 左面板状态与人物预览
        drawLeftStatus(gg);
        drawPlayerPreview(gg, mouseX, mouseY);
        // 盔甲底图（头盔 / 胸甲）：物品由 super.render 画（真实菜单槽）
        int[] armorInv = {39, 38};
        for (int i = 0; i < armorInv.length; i++) {
            int ax = L.leftX;
            int ay = L.armorY + i * PlayerLayout.GEAR_STEP;
            Slot s = playerSlot(armorInv[i]);
            ItemStack armor = (s != null) ? s.getItem() : ItemStack.EMPTY;
            DnUiTheme.drawRaritySlot(gg, ax, ay, armor);
            if (hit(mouseX, mouseY, ax, ay, PlayerLayout.PITCH, PlayerLayout.PITCH)) {
                DnUiTheme.drawSlotHover(gg, ax, ay);
            }
        }
        // 装备槽（胸挂/背包：物品本体来自 GearClientState；非菜单槽，自绘）
        for (GearKind kind : GearKind.values()) {
            drawGearSlot(gg, kind, L.gearX, gearY(L, kind), mouseX, mouseY);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        PlayerLayout L = layout();
        // 左面板：装备标题（主面板标签在 renderBg 内随视口滚动绘制）
        section(gg, "gui.dn.gear", L.leftPanelX, L.gearLabelY, PlayerLayout.EQUIP_W, DnUiTheme.SECTION_TEXT);
        // 右列：仓库标题 + 货币余额
        section(gg, "gui.dn.warehouse", L.whPanelX, L.whLabelY, PlayerLayout.RIGHT_W, DnUiTheme.SECTION_TEXT);
        if (sync != null && sync.currencyUsable) {
            String money = currencyLabel() + ": " + FormatUtil.compact(sync.currencyCount);
            gg.drawString(font, money, L.whPanelX + PlayerLayout.RIGHT_W - 26 - font.width(money),
                    L.whLabelY + 5, 0xFFE0A0);
        }
        // 行信息：起始行-结束行 / 已解锁行数（2.0.10Alpha：看玩家解锁了多少，而非仓库总行数）
        int unlocked = Math.max(0, unlockedRows());
        int endRow = Math.min(scrollRow() + WarehouseMenu.WAREHOUSE_ROWS, unlocked);
        String rowInfo = (scrollRow() + 1) + " - " + Math.max(scrollRow() + 1, endRow) + " / " + unlocked;
        // 0.2.0Beta：出售按钮（左） + 右侧显示“预计总额”或行信息
        drawSellButton(gg, mouseX, mouseY);
        String rightText = !sellSelection.isEmpty()
                ? Component.translatable("gui.dn.trade.sell.estimate", FormatUtil.compact(estimateTotal())).getString()
                : rowInfo;
        gg.drawString(font, rightText, L.whPanelX + PlayerLayout.RIGHT_W - 26 - font.width(rightText),
                infoStripY() + 5, DnUiTheme.SECTION_TEXT);
    }

    /** 分组标签（列内左对齐，整列宽度）。 */
    private void section(GuiGraphics gg, String key, int panelX, int labelY, int panelW, int color) {
        DnUiTheme.drawSectionLabel(gg, font, Component.translatable(key), panelX + 4, labelY,
                panelW - PlayerLayout.PANEL_PAD, color);
    }

    // ==================================================================
    // 装备槽（胸挂 / 背包：非菜单槽，自绘自处理点击）
    // ==================================================================

    /** 装备槽 y 坐标（布局按帧计算，故显式传入）。 */
    private static int gearY(PlayerLayout L, GearKind kind) {
        return kind == GearKind.RIG ? L.gearRigY : L.gearBagY;
    }

    /** 命中测试：返回被点中的装备种类（未命中 = null）。 */
    private GearKind gearAt(double mx, double my) {
        PlayerLayout L = PlayerLayout.compute(width, height);
        for (GearKind kind : GearKind.values()) {
            if (hit(mx, my, L.gearX, gearY(L, kind), PlayerLayout.PITCH, PlayerLayout.PITCH)) {
                return kind;
            }
        }
        return null;
    }

    /** 装备槽：底格 + 悬停金框 + 已装备物品图标（空槽只画底格）。 */
    private void drawGearSlot(GuiGraphics gg, GearKind kind, int x, int y, int mouseX, int mouseY) {
        DnUiTheme.drawSlotBackdrop(gg, x, y);
        ItemStack stack = GearClientState.equipped(kind);
        if (!stack.isEmpty()) {
            // x,y 已是 16×16 内容原点：直接画即居中于 18×18 格框 [x-1, x+17]
            gg.renderItem(stack, x, y);
            gg.renderItemDecorations(font, stack, x, y);
        }
        if (hit(mouseX, mouseY, x, y, PlayerLayout.PITCH, PlayerLayout.PITCH)) {
            DnUiTheme.drawSlotHover(gg, x, y);
        }
    }

    /** 装备槽悬停提示：空槽提示「未装备」，有装备则显示名称与打开提示。 */
    private void renderGearTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        GearKind kind = gearAt(mouseX, mouseY);
        if (kind == null) {
            return;
        }
        ItemStack stack = GearClientState.equipped(kind);
        if (stack.isEmpty()) {
            gg.renderTooltip(font, Component.translatable("gui.dn.gear.empty_" + kind.id()), mouseX, mouseY);
            return;
        }
        gg.renderTooltip(font, stack, mouseX, mouseY);
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
        // 2.0.8Alpha 修复：节流期间累积的滚动量在停止滚动后补发（否则快速滚动会丢最后一格）
        if (pendingScroll != 0 && System.currentTimeMillis() - lastScrollSent >= 120) {
            sendScroll();
            lastScrollSent = System.currentTimeMillis();
        }
        refreshMainLayout();
        renderBackground(gg);
        super.render(gg, mouseX, mouseY, partialTick);
        // 胸挂 / 背包网格物品（裁剪在视口内）
        beginItemClip(gg);
        drawStorageItems(gg, GearKind.RIG, 64);
        drawStorageItems(gg, GearKind.BACKPACK, backpackLabelY + 14);
        drawStorageHover(gg, GearKind.RIG, 64, mouseX, mouseY);
        drawStorageHover(gg, GearKind.BACKPACK, backpackLabelY + 14, mouseX, mouseY);
        gg.disableScissor();
        // 出售高亮改由 GridClientRendering 在网格渲染之后调用 renderSellHighlight（否则被 class 背景墙盖住）
        // 提示不在这里画：原版时机早于网格渲染，会被跨格物品的大图标盖住（见 dnRenderTooltips）
    }

    /**
     * 原版时机的提示绘制被接管（见 {@link DnOverlayTooltips} 与 {@link #dnRenderTooltips}）：
     * 网格渲染在更晚的 {@code ScreenEvent.Render.Post} 且大图标 z≈550，就地画会被盖住。
     */
    @Override
    protected void renderTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        // 故意留空
    }

    /** 统一提示层：所有网格绘制之后一次画完本界面的全部悬停提示（z 抬到网格内容之上）。 */
    @Override
    public void dnRenderTooltips(GuiGraphics gg, int mouseX, int mouseY) {
        if (com.deltanexus.system.client.GearWindowState.isOverWindow(mouseX, mouseY)) {
            return; // 光标在浮动装备窗口上：由窗口自己画提示
        }
        gg.pose().pushPose();
        gg.pose().translate(0, 0, TOOLTIP_Z);
        DnUiTheme.redrawCarriedItem(gg, this.font, this.menu.getCarried(), mouseX, mouseY);
        renderSlotTooltip(gg, mouseX, mouseY);
        renderGearTooltip(gg, mouseX, mouseY);
        drawStorageTooltip(gg, GearKind.RIG, 64, mouseX, mouseY);
        drawStorageTooltip(gg, GearKind.BACKPACK, backpackLabelY + 14, mouseX, mouseY);
        gg.pose().popPose();
    }

    /** 2.0.10Alpha：未解锁的仓库槽位不显示 tooltip（防御服务端残留数据穿透；
     *  物品与高亮的隐藏由 LockedAwareSlot.isActive() 实现）。 */
    private void renderSlotTooltip(GuiGraphics gg, int mouseX, int mouseY) {
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

    /** 仓库区滚轮滚动（2.0.1Alpha 动态向下渲染；2.0.8Alpha 原位滚动，界面不重建）。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return handleScroll(mouseX, mouseY, delta);
    }

    /** 处理一次滚轮；返回是否已消费。主面板物品区滚动内容，其余滚动仓库行。 */
    public boolean handleScroll(double mouseX, double mouseY, double delta) {
        if (delta == 0) {
            return false;
        }
        // 主面板物品区：滚动胸挂 / 背包 / 安全箱内容
        if (mouseX >= clipLeft() && mouseX < clipRight() + 8 && itemViewport.contains(mouseY)) {
            refreshMainLayout();
            itemViewport.scroll(delta);
            refreshMainLayout();
            return true;
        }
        pendingScroll += delta > 0 ? -1 : 1;
        long now = System.currentTimeMillis();
        // 节流：至少 120ms 或累积 4 格才发送，避免滚轮高频发包
        if (now - lastScrollSent >= 120 || Math.abs(pendingScroll) >= 4) {
            sendScroll();
            lastScrollSent = now;
        }
        return true;
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
     * 抢在 vanilla 鼠标居中逻辑之前消费滚轮事件（2.0.2Alpha）：
     * vanilla 在容器界面「光标持有物品 + 滚轮」时会强制把滚轮坐标视为屏幕中心，
     * 此处以真实鼠标位置滚动仓库，避免指针行为异常。
     */
    // ==================================================================
    // 交易行卖出（0.2.0Beta 起；0.2.1Beta：取消语义 / 按钮贴行信息条 / 物品冻结）
    // ==================================================================

    /**
     * 仓库行信息条顶边（= 实际绘制出的网格底沿）。
     *
     * <p>0.2.1Beta：面板高度按「玩家已解锁行数」而非固定 12 行绘制，因此按钮与行信息不能再用固定偏移，
     * 否则低等级玩家（解锁行数少、面板更短）会看到按钮悬在面板下方的空白处。</p>
     */
    private int infoStripY() {
        PlayerLayout L = PlayerLayout.compute(width, height);
        int rows = Math.max(1, Math.min(unlockedRows(), WarehouseMenu.WAREHOUSE_ROWS));
        return L.whY + rows * PlayerLayout.PITCH;
    }

    /**
     * 出售按钮热区（行信息条内）。
     *
     * <p>0.2.1Beta 修复：槽位物品区是 16×16，而槽位底图（含边框）是 18×18 且向右下各多出 2px，
     * 原按钮按物品区定位，导致其右下压住仓库最后一行的格子 UI。现按 18×18 外框对齐：
     * 自网格底沿下 1px 起、垂直居中于 18px 行信息条；左侧与网格首列取齐（原为网格左外 4px）。</p>
     */
    private BtnRect sellBtnRect() {
        PlayerLayout L = PlayerLayout.compute(width, height);
        return new BtnRect(L.whX + 2, infoStripY() + 1, 54, 17);
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
        boolean wasSellMode = sellMode;
        sellMode = false;
        sellConfirm = false;
        sellSelection.clear();
        sellMatchCache.clear();
        if (wasSellMode) {
            sendSellMode(false);
        }
    }

    /**
     * 通知服务端出售模式开关（0.3.0Beta：出售模式服务端权威化，协议 dn3）。
     *
     * <p>服务端据此冻结菜单内的一切物品移动；客户端自身的冻结只负责体验一致。</p>
     */
    private void sendSellMode(boolean active) {
        PacketHandler.sendToServer(new com.deltanexus.system.network.packet.C2SSellModePacket(active));
    }

    /**
     * 出售诊断日志（排查「点进出售模式却什么都识别不出来」看这一行）。
     *
     * <p>输出：目录商品数 / 其中带可用卖出价的数量 / 本界面可回收的槽位数。
     * 后两项为 0 时问题在目录或价格（服务端侧），不在网格。</p>
     */
    private void logSellDiagnostics() {
        try {
            int goods = com.deltanexus.system.client.TradeClientState.goods().size();
            int sellable = com.deltanexus.system.client.TradeSellIndex.get().debugEntryCount();
            int matched = 0;
            for (var slot : menu.slots) {
                SellKey key = sellKeyOf(slot);
                if (key != null && !slot.getItem().isEmpty() && matchAt(key) != null) {
                    matched++;
                }
            }
            com.deltanexus.system.DeltaNexus.LOGGER.info(
                    "[DN] 出售模式：目录商品 {} 件（带卖出价 {} 件），本界面可回收槽位 {} 个",
                    goods, sellable, matched);
        } catch (Throwable t) {
            com.deltanexus.system.DeltaNexus.LOGGER.warn("[DN] 出售诊断日志失败: {}", t.toString());
        }
    }

    private void drawSellButton(GuiGraphics gg, int mouseX, int mouseY) {
        BtnRect r = sellBtnRect();
        boolean hover = r.contains(mouseX, mouseY);
        // 0.2.1Beta：出售模式但未选中任何物品 → 显示「取消」，点击即退回正常存储功能
        String label;
        if (sellConfirm) {
            label = Component.translatable("gui.dn.trade.sell.confirm").getString();
        } else if (!sellMode) {
            label = Component.translatable("gui.dn.trade.sell.button").getString();
        } else if (sellSelection.isEmpty()) {
            label = Component.translatable("gui.dn.trade.sell.cancel").getString();
        } else {
            label = Component.translatable("gui.dn.trade.sell.button_count", sellSelection.size()).getString();
        }
        int top = sellConfirm ? (hover ? 0xFFB23A3A : 0xFF8E2B2B) : (hover ? 0xFF3A4655 : 0xFF2A3040);
        int bot = sellConfirm ? (hover ? 0xFF8E2B2B : 0xFF6E1F1F) : (hover ? 0xFF2E3748 : 0xFF20242F);
        gg.fillGradient(r.x, r.y, r.x + r.w, r.y + r.h, top, bot);
        gg.renderOutline(r.x, r.y, r.w, r.h, sellConfirm ? 0xFFFF6B6B : 0xFF46536B);
        gg.drawCenteredString(font, label, r.x + r.w / 2, r.y + (r.h - 8) / 2,
                sellConfirm ? 0xFFFFFFFF : 0xFFE0E6F0);
    }

    /**
     * 出售高亮（公开给网格渲染事件在<b>网格绘制之后</b>调用，避免被 class 背景墙盖住）。
     *
     * <p>0.3.0Beta 修复两点：①绘制时机移到网格渲染之后；②跨格物品的<b>每个覆盖格</b>都画边框
     * （以前只有锚点格有边框，因为覆盖格是空的、被跳过了）。</p>
     */
    public void renderSellHighlight(GuiGraphics gg) {
        // GUI 里 z 越大越靠前：跨格物品的 class 背景墙画在 z=360、大图标 z=400，
        // 因此高亮必须画在更高的 z 上，否则会出现「边框被背景挡住 / 边框在物品后面」。
        gg.pose().pushPose();
        gg.pose().translate(0, 0, 450);
        renderSellOverlay(gg);
        gg.pose().popPose();
    }

    /** 覆盖格（非锚点）的高亮：按锚点物品的可回收/选中状态，逐格画边框与底色。 */
    private void drawSellCellOutline(GuiGraphics gg, net.minecraft.world.inventory.Slot cell, int anchorSlot, int source) {
        var anchor = menu.slots.get(anchorSlot);
        if (anchor.getItem().isEmpty()) {
            return;
        }
        SellKey key = new SellKey(source, sellIndexOf(anchor, source));
        TradeSellIndex.Match m = matchAt(key);
        if (m == null) {
            return;
        }
        boolean selected = sellSelection.containsKey(key);
        if (selected) {
            gg.fill(cell.x, cell.y, cell.x + 16, cell.y + 16, 0x55FFD700);
            gg.renderOutline(cell.x - 1, cell.y - 1, 18, 18, 0xFFFFD700);
        } else {
            gg.renderOutline(cell.x - 1, cell.y - 1, 18, 18, 0x806BD47A);
        }
    }

    /** 选中（金框 + 数量角标）与可回收（绿框）高亮；覆盖仓库、背包/快捷栏、安全箱。 */
    private void renderSellOverlay(GuiGraphics gg) {
        if (!sellMode) {
            return;
        }
        boolean safeOk = SafeBoxOverlay.safeAllowed(SafeBoxOverlay.lastState());
        for (var slot : menu.slots) {
            int source = sellSourceOf(slot);
            if (source < 0) {
                continue;
            }
            if (source == C2STradeSellPacket.SOURCE_WAREHOUSE && !isWarehouseSlotUnlocked(slot.getSlotIndex())) {
                continue;
            }
            if (source == C2STradeSellPacket.SOURCE_SAFE_BOX && !safeOk) {
                continue;
            }
            // 覆盖格（非锚点）也要画：归属查服务端布局，锚点物品才是真正被选中的那件
            int anchorSlot = com.deltanexus.system.client.GridLayoutClient.anchorOf(slot.index);
            if (anchorSlot >= 0 && anchorSlot != slot.index && anchorSlot < menu.slots.size()) {
                drawSellCellOutline(gg, slot, anchorSlot, source);
                continue;
            }
            if (slot.getItem().isEmpty()) {
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
        // 装备槽（胸挂 / 背包本体）：左键打开（空槽且光标持同类装备时装备）；
        // 点击 = 卸下；出售模式下吞掉点击
        GearKind gear = gearAt(mx, my);
        if (gear != null) {
            if (sellMode) {
                return true;
            }
            boolean shift = hasShiftDown();
            if (button == 1 || shift) {
                int target = gear == GearKind.RIG
                        ? C2SOverlayClickPacket.TARGET_RIG : C2SOverlayClickPacket.TARGET_BACKPACK;
                PacketHandler.sendToServer(new C2SOverlayClickPacket(target, -1, button, shift));
            } else if (button == 0) {
                // 左键：装上去（若光标持有同类装备）——不打开独立界面
                PacketHandler.sendToServer(new C2SOpenGearPacket(gear));
            }
            return true;
        }
        BtnRect btn = sellBtnRect();
        if (btn.contains(mx, my)) {
            if (!sellMode) {
                // 进入出售模式（0.3.0Beta：同步告知服务端 → 服务端冻结物品移动）
                sellMode = true;
                sellConfirm = false;
                sellSelection.clear();
                sendSellMode(true);
                logSellDiagnostics();
            } else if (button == 1) {
                // 右键按钮：退出出售模式
                clearSellState();
            } else if (sellConfirm) {
                sendSell();
            } else if (sellSelection.isEmpty()) {
                // 0.2.1Beta：未选中任何物品时按钮显示「取消」，点击退回正常存储功能
                clearSellState();
            } else {
                sellConfirm = true;
            }
            return true;
        }
        if (sellMode) {
            // 2Beta：出售模式下界面内一切点击都不移动物品
            if (sellConfirm) {
                sellConfirm = false;      // 点别处 = 取消确认，但绝不触碰物品
                return true;
            }
            var slot = this.getSlotUnderMouse();
            if (slot != null) {
                // 问题4修复：点到的可能是跨格物品的覆盖格（那里是空的）——先按服务端布局重定向到锚点，
                // 用锚点槽位记录选中，这样 >1x1 的物品点任意一格都能被识别与回收。
                int anchorSlotIndex = com.deltanexus.system.client.GridLayoutClient.anchorOf(slot.index);
                if (anchorSlotIndex >= 0 && anchorSlotIndex != slot.index && anchorSlotIndex < menu.slots.size()) {
                    slot = menu.slots.get(anchorSlotIndex);
                }
                SellKey key = sellKeyOf(slot);
                if (key != null && !slot.getItem().isEmpty() && matchAt(key) != null) {
                    toggleSellSelection(key, button == 1, slot.getItem().getCount());
                }
                // 不可回收的物品 / 盔甲副手等来源：点击同样被吞掉，不改动任何槽位
                return true;
            }
            // 槽位之外的点击（原版此处会把光标物品丢到世界里）：出售模式下一并吞掉
            return true;
        }
        // 主面板物品区：滚动条拖动 / 胸挂 / 背包网格点击
        refreshMainLayout();
        if (button == 0 && itemViewport.maximum() > 0 && itemViewport.contains(my)
                && mx >= clipRight() - 2 && mx < clipRight() + 6) {
            draggingScrollbar = true;
            scrollbarGrab = (my >= itemViewport.thumbY() && my < itemViewport.thumbY() + itemViewport.thumbHeight())
                    ? my - itemViewport.thumbY() : itemViewport.thumbHeight() / 2.0;
            itemViewport.dragThumb(my, scrollbarGrab);
            refreshMainLayout();
            return true;
        }
        int rig = storageCellAt(GearKind.RIG, 64, mx, my);
        if (rig >= 0) {
            clickGear(C2SOverlayClickPacket.TARGET_RIG, rig, button, hasShiftDown());
            return true;
        }
        int bag = storageCellAt(GearKind.BACKPACK, backpackLabelY + 14, mx, my);
        if (bag >= 0) {
            clickGear(C2SOverlayClickPacket.TARGET_BACKPACK, bag, button, hasShiftDown());
            return true;
        }
        // 点击“界面之外”的空白处丢出光标物品（原版在全屏自绘界面下判定不出“之外”）。
        // 只有落在**所有面板之外**才算丢——否则面板内的格间缝隙/行信息条会把物品误丢进世界（看起来像“消失”）。
        boolean handled = super.mouseClicked(mx, my, button);
        if (!handled && this.getSlotUnderMouse() == null
                && !this.getMenu().getCarried().isEmpty()
                && !insideAnyPanel(mx, my)
                && this.minecraft != null && this.minecraft.gameMode != null && this.minecraft.player != null) {
            this.minecraft.gameMode.handleInventoryMouseClick(this.getMenu().containerId, -999, button,
                    net.minecraft.world.inventory.ClickType.PICKUP, this.minecraft.player);
            return true;
        }
        return handled;
    }

    /**
     * 是否落在任意面板内（矩形与 renderBg 完全一致）。
     *
     * <p>用于“点界面之外丢物品”的判定：面板**内部**的空白（格间缝隙、行信息条、标题条）不算“界面之外”，
     * 否则拿着物品点到缝隙就会把物品丢进世界——看起来就是“物品消失”。</p>
     */
    private boolean insideAnyPanel(double mx, double my) {
        PlayerLayout L = layout();
        return hit(mx, my, L.leftPanelX, L.topY, PlayerLayout.EQUIP_W, L.panelH)
                || hit(mx, my, L.midPanelX, L.topY, PlayerLayout.MAIN_W, L.panelH)
                || hit(mx, my, L.whPanelX, L.topY, PlayerLayout.RIGHT_W, L.panelH)
                || sellBtnRect().contains(mx, my);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            itemViewport.dragThumb(my, scrollbarGrab);
            refreshMainLayout();
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(mx, my, button);
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
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

    /** 出售模式是否激活（0.2.1Beta：供全局网格交互拦截判断用）。 */
    public boolean isSellMode() {
        return sellMode;
    }

    /**
     * 0.2.1Beta：出售模式下<b>冻结物品</b>——任何槽位交互一律不生效，无论该物品是否可回收。
     *
     * <p>0.3.0Beta：客户端同时把点击<b>重定向到主格</b>（占位物格 → 主格），
     * 让左/右键、Shift、数字键、Q、拖拽全部与点击主格走同一条路径；
     * 服务端由 {@code GridAwareMenu#clicked} 再做一次权威重定向。</p>
     */
    @Override
    protected void slotClicked(net.minecraft.world.inventory.Slot slot, int slotId, int mouseButton,
                              net.minecraft.world.inventory.ClickType type) {
        if (sellMode) {
            return;
        }
        net.minecraft.world.inventory.Slot master =
                com.deltanexus.system.client.GridLayoutClient.masterSlot(this.menu, slot);
        if (master != null && master != slot) {
            super.slotClicked(master, master.index, mouseButton, type);
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, type);
    }

    /** 屏幕移除：退出出售模式（服务端同步解锁）+ 注销出售模式来源。 */
    @Override
    public void removed() {
        if (sellMode) {
            sendSellMode(false);
        }
        super.removed();
        com.deltanexus.system.grid.adapter.InputGate.setSellModeSource(null);
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
