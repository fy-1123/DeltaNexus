package com.deltanexus.system.client.gui;

import com.deltanexus.system.client.GearClientState;
import com.deltanexus.system.client.GearWindowState;
import com.deltanexus.system.grid.GearConfig;
import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.grid.GridClassConfig;
import com.deltanexus.system.grid.GridClientRendering;
import com.deltanexus.system.grid.GridEntry;
import com.deltanexus.system.grid.GridSize;
import com.deltanexus.system.grid.GridStore;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.grid.StoreContainer;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SOverlayClickPacket;
import com.deltanexus.system.network.packet.C2SSafeBoxClickPacket;
import com.deltanexus.system.network.packet.SyncSafeBoxPacket;
import com.deltanexus.system.network.packet.C2SOpenGearPacket;
import com.deltanexus.system.network.packet.C2SOpenGearWindowPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * 类塔克夫容器界面基类（0.5.0Beta）。
 *
 * <p>几何与绘制，布局语义逐行对齐：</p>
 * <ul>
 *   <li><b>左面板</b>（{@link DnUiLayout#LEFT_PANEL_W} = 128 宽）：装备标题 + 盔甲（仅头盔 / 胸甲）
 *   + 胸挂 / 背包本体槽 + 角色预览 + 生命 / 饥饿 / 护甲读数；</li>
 *   <li><b>主面板</b>（188 宽，固定高 300）：口袋 5 → 胸挂网格 → 背包网格 → 安全箱网格（可滚动裁剪），
 *       底部钉死快捷栏 9；</li>
 *   <li><b>容器面板</b>（188 宽）：仅容器 / 仓库模式存在。</li>
 * </ul>
 *
 * <p>纵向位置
 * {@code backpackLabelY = 64 + (胸挂未装备 ? 52 : 胸挂高) + 14}，
 * {@code safeBoxLabelY = backpackLabelY + 14 + (背包未装备 ? 52 : 背包高) + 14}；
 * 内容超出可视区时由 {@link Viewport}滚动。</p>
 *
 * <p>胸挂 / 背包网格直接内嵌于此（数据来自已同步的 {@link GearClientState}），
 * 鼠标交互走专用通道 {@link C2SOverlayClickPacket} / {@link C2SSafeBoxClickPacket}，
 * 服务端权威裁决；这些位置不在原版槽位表里。</p>
 */
public abstract class DnInventoryScreen extends AbstractContainerScreen<AbstractContainerMenu>
        implements DnOverlayTooltips {

    /** 屏外坐标（隐藏槽位用：不可渲染不可点击）。 */
    protected static final int OFFSCREEN = -20000;

    /**
     * 统一提示层的 z 抬升量：提示框自身还在 z≈400，网格大图标在 z≈550，
     * 因此抬 1000 后提示必定在最上层（见 {@link DnOverlayTooltips}）。
     */
    private static final int TOOLTIP_Z = 1000;

    private static final int LEFT_W = DnUiLayout.LEFT_PANEL_W;
    private static final int MAIN_W = DnUiLayout.MAIN_PANEL_W;
    private static final int CONTAINER_W = DnUiLayout.CONTAINER_PANEL_W;
    private static final int GAP = DnUiLayout.PANEL_GAP;
    private static final int PITCH = DnUiLayout.SLOT_PITCH;
    private static final int CELL = DnUiLayout.SLOT_SIZE;
    private static final int PANEL_H = DnUiLayout.MAIN_PANEL_H;

    /** 面板总宽（328 + 容器 ? 200 : 0）。 */
    private static final int TOTAL_W = LEFT_W + GAP + MAIN_W;
    private static final int TOTAL_W_CONTAINER = TOTAL_W + GAP + CONTAINER_W;

    /** 左面板盔甲列 x（相对面板左）。 */
    private static final int ARMOR_X = 10;
    /** 左面板盔甲 / 装备首行 y（相对面板顶）。 */
    private static final int GEAR_TOP_Y = 116;
    /** 左面板装备列 / 盔甲行步距。 */
    private static final int GEAR_STEP = 32;
    /** 左面板装备（胸挂 / 背包本体）列 x（相对面板左）。 */
    private static final int GEAR_X = 104;

    /** 盔甲格右移修正：格子以装备图标为中心（视觉居中）。 */
    private static final int ARMOR_NUDGE_X = 2;
    /** 装备（胸挂 / 背包本体）格右移 / 下移修正：格子以装备图标为中心。 */
    private static final int GEAR_NUDGE_X = 2;
    private static final int GEAR_NUDGE_Y = 2;

    /** 主面板内容内缩（getMainSlotX：+6）。 */
    private static final int CONTENT_INSET = 6;

    /** 玩家背包引用（原版 AbstractContainerScreen 不保存该字段，各子类各自保存，故自行持有）。 */
    protected final Inventory playerInv;

    /** 槽位原坐标快照（关闭时恢复；InventoryMenu 常驻实例，必须还原）。 */
    private int[] savedX;
    private int[] savedY;

    // ---- 面板几何----
    protected int leftPanelX;
    protected int mainPanelX;
    protected int containerPanelX;
    protected int panelsTopY;
    protected int mainPanelH = PANEL_H;
    protected float uiScale = 1f;

    /** 背包标签 y（面板顶相对值）。 */
    private int backpackLabelY;
    /** 安全箱标签 y（面板顶相对值）。 */
    private int safeBoxLabelY;

    /** 主面板物品视口。 */
    private final Viewport itemViewport = new Viewport();
    private boolean draggingScrollbar;
    private double scrollbarGrab;

    // ---- 菜单槽位解析（玩家口袋 / 快捷栏，模式无关）----
    private final int[] pocketSlots = new int[DnUiLayout.POCKET_COUNT];
    private final int[] hotbarSlots = new int[DnUiLayout.HOTBAR_COUNT];

    private int rightCols = 9;
    private int rightCount;

    protected DnInventoryScreen(AbstractContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.playerInv = inventory;
        // 全屏自绘：面板由 renderBg 绘制，槽位坐标全部为绝对坐标（leftPos / topPos = 0）
        this.imageWidth = 0;
        this.imageHeight = 0;
        this.leftPos = -2000;
        this.topPos = -2000;
        this.titleLabelX = -2000;
        this.titleLabelY = -2000;
        this.inventoryLabelX = -2000;
        this.inventoryLabelY = -2000;
    }

    /** 容器槽位（菜单下标，行优先顺序）；空 = 本界面没有容器列。 */
    protected List<Integer> rightColumnSlots() {
        return List.of();
    }

    /** 容器列列数。 */
    protected int rightColumnColumns() {
        return 9;
    }

    /** 是否为容器 / 仓库模式（有容器列）。 */
    protected boolean containerMode() {
        return rightCount > 0;
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    protected void init() {
        super.init();
        this.leftPos = 0;
        this.topPos = 0;
        this.imageWidth = this.width;
        this.imageHeight = this.height;
        // 口袋槽尺寸守卫（客户端预测——塞大件到口袋直接回光标，避免一闪再回弹）
        InventoryGridHandler.ensurePocketGuards(this.menu, Minecraft.getInstance().player);
        snapshotSlots();
        resolvePlayerSlots();
        this.rightCols = Math.max(1, rightColumnColumns());
        this.rightCount = rightColumnSlots().size();
        refreshLayout();
    }

    @Override
    public void removed() {
        restoreSlots();
        super.removed();
    }

    // ==================================================================
    // 布局（recalculateLayout / repositionSlots）
    // ==================================================================

    private static int gearRows(GearKind kind) {
        return Math.max(1, GearClientState.container(kind).store().rows());
    }

    /** refreshLayout：重算几何 → 重排槽位。每帧可安全调用（滚动偏移保留）。 */
    private void refreshLayout() {
        recalculateLayout();
        repositionSlots();
    }

    /** recalculateLayout（309-332）。 */
    private void recalculateLayout() {
        boolean container = containerMode();
        boolean rigEmpty = GearClientState.equipped(GearKind.RIG).isEmpty();
        boolean bagEmpty = GearClientState.equipped(GearKind.BACKPACK).isEmpty();
        int rigH = gearRows(GearKind.RIG) * PITCH;
        int bagH = gearRows(GearKind.BACKPACK) * PITCH;

        this.backpackLabelY = 64 + (rigEmpty ? 52 : rigH) + 14;
        this.safeBoxLabelY = this.backpackLabelY + 14 + (bagEmpty ? 52 : bagH) + 14;

        SyncSafeBoxPacket st = SafeBoxOverlay.lastState();
        boolean safe = SafeBoxOverlay.safeAllowed(st);
        int safeRows = safe ? SafeBoxOverlay.safeHeight(st) : 0;
        int contentBottom = safe
                ? this.safeBoxLabelY + 18 + Math.max(30, safeRows * PITCH) + 30
                : this.safeBoxLabelY;

        this.mainPanelH = PANEL_H;
        int totalW = container ? TOTAL_W_CONTAINER : TOTAL_W;
        this.uiScale = Math.max(0.1f, Math.min(1f, Math.min(
                this.width / (float) (totalW + 12),
                this.height / (float) (this.mainPanelH + 12))));
        int viewW = (int) (this.width / this.uiScale);
        int viewH = (int) (this.height / this.uiScale);
        this.imageWidth = viewW;
        this.imageHeight = viewH;

        int baseX = Math.max(6, (viewW - totalW) / 2);
        this.leftPanelX = baseX;
        this.mainPanelX = this.leftPanelX + LEFT_W + GAP;
        this.containerPanelX = this.mainPanelX + MAIN_W + GAP;
        this.panelsTopY = Math.max(6, (viewH - this.mainPanelH) / 2);
        this.itemViewport.configure(this.panelsTopY + 4, getHotbarLabelY() - 8, contentBottom - 4);
    }

    /** repositionSlots（342-364）。 */
    private void repositionSlots() {
        AbstractContainerMenu m = this.menu;
        if (m == null) {
            return;
        }
        for (int i = 0; i < m.slots.size(); i++) {
            move(i, OFFSCREEN, OFFSCREEN);
        }
        if (!containerMode()) {
            // 背包模式：头盔 5 / 胸甲 6 为真实菜单槽（护腿 / 靴子 / 副手隐藏）
            move(5, armorSlotX(), armorSlotY(0));
            move(6, armorSlotX(), armorSlotY(1));
        } else {
            List<Integer> ctn = rightColumnSlots();
            for (int n = 0; n < ctn.size(); n++) {
                move(ctn.get(n), containerPanelX + CONTENT_INSET + (n % 9) * PITCH,
                        panelsTopY + 26 + (n / 9) * PITCH);
            }
        }
        int py = contentY(22);
        for (int n = 0; n < pocketSlots.length; n++) {
            if (pocketSlots[n] >= 0 && itemViewport.fullyVisible(py - 1, CELL)) {
                move(pocketSlots[n], getMainSlotX(n), py);
            }
        }
        for (int n = 0; n < hotbarSlots.length; n++) {
            if (hotbarSlots[n] >= 0) {
                move(hotbarSlots[n], getMainSlotX(n), getHotbarSlotsY());
            }
        }
    }

    /** 主面板第 n 列槽位 x（getMainSlotX：mainPanelX + 6 + n * 17）。 */
    private int getMainSlotX(int n) {
        return mainPanelX + CONTENT_INSET + n * PITCH;
    }

    /** 主面板内容 y（含滚动偏移；contentY）。 */
    private int contentY(int n) {
        return panelsTopY + n - itemViewport.offset();
    }

    private int scrollbarX() {
        return mainPanelX + MAIN_W - 8;
    }

    private int getHotbarLabelY() {
        return panelsTopY + mainPanelH - 34;
    }

    private int getHotbarSlotsY() {
        return panelsTopY + mainPanelH - 20;
    }

    private int getSafeBoxLabelY() {
        return contentY(safeBoxLabelY);
    }

    private int sectionBarWidth() {
        return 168;
    }

    private boolean overItems(double x, double y) {
        return x >= mainPanelX + 3 && x < mainPanelX + MAIN_W - 3 && itemViewport.contains(y);
    }

    /** 解析玩家口袋 / 快捷栏菜单下标（按容器内下标，模式无关）。 */
    private void resolvePlayerSlots() {
        for (int i = 0; i < pocketSlots.length; i++) {
            pocketSlots[i] = -1;
        }
        for (int i = 0; i < hotbarSlots.length; i++) {
            hotbarSlots[i] = -1;
        }
        AbstractContainerMenu m = this.menu;
        if (m == null) {
            return;
        }
        for (int i = 0; i < m.slots.size(); i++) {
            Slot s = m.slots.get(i);
            if (s == null || s.container != playerInv) {
                continue;
            }
            int ci = s.getContainerSlot();
            if (ci >= 0 && ci < 9) {
                hotbarSlots[ci] = i;
            } else if (ci >= 9 && ci < 14) {
                pocketSlots[ci - 9] = i;
            }
        }
    }

    // ==================================================================
    // 槽位重映射
    // ==================================================================

    private void snapshotSlots() {
        AbstractContainerMenu m = this.menu;
        if (m == null || (savedX != null && savedX.length == m.slots.size())) {
            return;
        }
        savedX = new int[m.slots.size()];
        savedY = new int[m.slots.size()];
        for (int i = 0; i < m.slots.size(); i++) {
            Slot s = m.slots.get(i);
            savedX[i] = s.x;
            savedY[i] = s.y;
        }
    }

    private void restoreSlots() {
        AbstractContainerMenu m = this.menu;
        if (m == null || savedX == null || savedX.length != m.slots.size()) {
            return;
        }
        for (int i = 0; i < m.slots.size(); i++) {
            Slot s = m.slots.get(i);
            s.x = savedX[i];
            s.y = savedY[i];
        }
    }

    private void move(int index, int x, int y) {
        AbstractContainerMenu m = this.menu;
        if (m == null || index < 0 || index >= m.slots.size()) {
            return;
        }
        Slot s = m.slots.get(index);
        if (s == null) {
            return;
        }
        s.x = x;
        s.y = y;
    }

    private Slot slotAt(int index) {
        AbstractContainerMenu m = this.menu;
        if (m == null || index < 0 || index >= m.slots.size()) {
            return null;
        }
        return m.slots.get(index);
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        // 面板外壳
        DnUiTheme.drawPanelShell(gg, leftPanelX, panelsTopY, LEFT_W, mainPanelH);
        DnUiTheme.drawPanelShell(gg, mainPanelX, panelsTopY, MAIN_W, mainPanelH);
        if (containerMode()) {
            DnUiTheme.drawPanelShell(gg, containerPanelX, panelsTopY, CONTAINER_W, mainPanelH);
        }
        // 左面板装备标题
        DnUiTheme.drawTacticalLabel(gg, this.font, Component.translatable("gui.dn.gear"),
                leftPanelX + 6, panelsTopY + 8, 62);

        // ---- 物品视口（裁剪以下内容）----
        beginItemClip(gg);
        drawSection(gg, Component.translatable("gui.dn.pocket"), 8);
        drawSection(gg, Component.translatable("gui.dn.gear.rig"), 50);
        drawSection(gg, Component.translatable("gui.dn.gear.backpack"), backpackLabelY);
        drawMenuSlotFrames(gg, pocketSlots);
        drawStorageSection(gg, GearKind.RIG, 64);
        drawStorageSection(gg, GearKind.BACKPACK, backpackLabelY + 14);

        SyncSafeBoxPacket st = SafeBoxOverlay.lastState();
        if (SafeBoxOverlay.safeAllowed(st)) {
            int w = SafeBoxOverlay.safeWidth(st);
            int h = SafeBoxOverlay.safeHeight(st);
            DnUiTheme.drawTacticalLabel(gg, this.font, Component.literal(SafeBoxOverlay.safeTitle(st)),
                    getMainSlotX(0) - 1, getSafeBoxLabelY(), sectionBarWidth());
            for (int i = 0; i < w * h; i++) {
                DnUiTheme.drawSlotBackdrop(gg, getMainSlotX(i % w), getSafeBoxLabelY() + 16 + (i / w) * PITCH);
            }
        }
        gg.disableScissor();

        // 快捷栏（钉在面板底，不参与滚动）
        DnUiTheme.drawTacticalLabel(gg, this.font, Component.translatable("gui.dn.hotbar"),
                getMainSlotX(0) - 1, getHotbarLabelY(), sectionBarWidth());
        drawMenuSlotFrames(gg, hotbarSlots);
        drawEquipmentFrames(gg);

        // 容器面板
        if (containerMode()) {
            DnUiTheme.drawTacticalLabel(gg, this.font, this.title,
                    containerPanelX + 6, panelsTopY + 8, 176);
            for (int idx : rightColumnSlots()) {
                Slot s = slotAt(idx);
                if (s != null && s.x > -1000 && s.y > -1000) {
                    DnUiTheme.drawRaritySlot(gg, s.x, s.y, s.getItem());
                }
            }
        }

        drawScrollbar(gg);
        drawLeftStatus(gg);
        drawPlayerPreview(gg, mouseX, mouseY);

        // 装备本体图标 + 容器模式盔甲覆盖层（非菜单槽，叠加在容器渲染之后）
        drawEquippedGear(gg, mouseX, mouseY);
        drawArmorOverlay(gg, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        // 装备 / 安全箱同步可能在界面打开后一 tick 才到达：每帧重排，保证与面板同帧一致
        refreshLayout();
        // 世界遮罩（纯色压暗，不用原版模糊背景）
        gg.fill(0, 0, this.width, this.height, DnUiTheme.worldColor(DnUiTheme.WORLD_BACKGROUND));
        gg.pose().pushPose();
        gg.pose().scale(uiScale, uiScale, 1f);
        int sx = (int) (mouseX / uiScale);
        int sy = (int) (mouseY / uiScale);
        super.render(gg, sx, sy, partialTick);
        // 提示不在这里画：原版时机早于网格渲染，会被跨格物品的大图标盖住（见 dnRenderTooltips）
        // 内嵌网格物品（超出可视区被裁剪）
        beginItemClip(gg);
        drawStorageItems(gg, GearKind.RIG, 64);
        drawStorageItems(gg, GearKind.BACKPACK, backpackLabelY + 14);
        SyncSafeBoxPacket st = SafeBoxOverlay.lastState();
        if (SafeBoxOverlay.safeAllowed(st)) {
            SafeBoxOverlay.renderSafeBoxGrid(gg, getMainSlotX(0), getSafeBoxLabelY() + 16,
                    SafeBoxOverlay.safeWidth(st), SafeBoxOverlay.safeHeight(st), sx, sy);
        }
        // 悬停框（物品之上）
        drawStorageHover(gg, GearKind.RIG, 64, sx, sy);
        drawStorageHover(gg, GearKind.BACKPACK, backpackLabelY + 14, sx, sy);
        gg.disableScissor();
        gg.pose().popPose();
    }

    /**
     * 原版时机的提示绘制被接管（见 {@link DnOverlayTooltips} 与 {@link #dnRenderTooltips}）。
     *
     * <p>原版在 {@code AbstractContainerScreen#render} 末尾就地画槽位提示（z≈400），
     * 而网格渲染发生在 {@code ScreenEvent.Render.Post}（更晚）且大图标 z≈550，
     * 于是提示会被物品图标盖住——表现为「提示框在，但里面看不到内容」。这里让它空转，
     * 统一改到所有网格绘制之后绘制。</p>
     */
    @Override
    protected void renderTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        // 故意留空
    }

    /**
     * 统一提示层：所有网格物品画完之后，再一次画完本界面的全部悬停提示。
     *
     * <p>坐标系与 {@code render} 内一致（先按 {@link #uiScale} 缩放），z 再抬
     * {@link #TOOLTIP_Z}，于是提示永远在所有内容（槽位物品 z≈150、跨格大图标 z≈550、
     * 浮动装备窗口 z≈900）之上。</p>
     */
    @Override
    public void dnRenderTooltips(GuiGraphics gg, int mouseX, int mouseY) {
        if (GearWindowState.isOverWindow(mouseX, mouseY)) {
            return; // 光标在浮动窗口上：由窗口自己画提示，避免下层界面再画一次
        }
        gg.pose().pushPose();
        gg.pose().scale(uiScale, uiScale, 1f);
        gg.pose().translate(0, 0, TOOLTIP_Z);
        int sx = (int) (mouseX / uiScale);
        int sy = (int) (mouseY / uiScale);
        DnUiTheme.redrawCarriedItem(gg, this.font, this.menu.getCarried(), sx, sy);
        drawSlotTooltip(gg, sx, sy);
        drawEquippedGearTooltip(gg, sx, sy);
        drawArmorTooltip(gg, sx, sy);
        drawStorageTooltip(gg, GearKind.RIG, 64, sx, sy);
        drawStorageTooltip(gg, GearKind.BACKPACK, backpackLabelY + 14, sx, sy);
        SyncSafeBoxPacket st = SafeBoxOverlay.lastState();
        if (SafeBoxOverlay.safeAllowed(st)) {
            SafeBoxOverlay.renderSafeBoxTooltip(gg, getMainSlotX(0), getSafeBoxLabelY() + 16,
                    SafeBoxOverlay.safeWidth(st), SafeBoxOverlay.safeHeight(st), sx, sy);
        }
        gg.pose().popPose();
    }

    /** 原版槽位（口袋 / 快捷栏 / 容器 / 盔甲）物品提示：语义与
     *  {@code AbstractContainerScreen#renderTooltip} 一致（光标持物时不显示）。 */
    private void drawSlotTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        Slot slot = this.hoveredSlot;
        if (slot == null || !slot.hasItem() || !this.menu.getCarried().isEmpty()) {
            return;
        }
        ItemStack stack = slot.getItem();
        gg.renderTooltip(this.font, this.getTooltipFromContainerItem(stack),
                stack.getTooltipImage(), stack, mouseX, mouseY);
    }

    /** 标签条 + 行尾延伸线。 */
    private void drawSection(GuiGraphics gg, Component text, int offsetY) {
        DnUiTheme.drawTacticalLabel(gg, this.font, text, getMainSlotX(0) - 1, contentY(offsetY), sectionBarWidth());
    }

    /** 菜单槽位底框（跳过屏外槽位）。 */
    private void drawMenuSlotFrames(GuiGraphics gg, int[] indices) {
        for (int idx : indices) {
            Slot s = slotAt(idx);
            if (s != null && s.x > -1000 && s.y > -1000) {
                DnUiTheme.drawRaritySlot(gg, s.x, s.y, s.getItem());
            }
        }
    }

    /** 装备槽底框：自绘盔甲（头盔 / 胸甲覆盖层）+ 胸挂 / 背包本体。 */
    private void drawEquipmentFrames(GuiGraphics gg) {
        // 容器模式盔甲不是真实菜单槽，物品由 drawArmorOverlay 自绘；
        // 背包模式盔甲是真实菜单槽 5/6，物品由 super.render 渲染，这里只补底图。
        DnUiTheme.drawRaritySlot(gg, armorSlotX(), armorSlotY(0), armorStack(0));
        DnUiTheme.drawRaritySlot(gg, armorSlotX(), armorSlotY(1), armorStack(1));

        DnUiTheme.drawRaritySlot(gg, gearSlotX(), gearSlotY(GearKind.RIG),
                GearClientState.equipped(GearKind.RIG));
        DnUiTheme.drawRaritySlot(gg, gearSlotX(), gearSlotY(GearKind.BACKPACK),
                GearClientState.equipped(GearKind.BACKPACK));
    }

    /** 盔甲格 x（头盔 / 胸甲）。 */
    private int armorSlotX() {
        return leftPanelX + ARMOR_X + ARMOR_NUDGE_X;
    }

    /** 盔甲格 y（0 = 头盔，1 = 胸甲）。 */
    private int armorSlotY(int index) {
        return panelsTopY + GEAR_TOP_Y + index * GEAR_STEP;
    }

    /** 装备（胸挂 / 背包本体）格 x。 */
    private int gearSlotX() {
        return leftPanelX + GEAR_X + GEAR_NUDGE_X;
    }

    /** 装备（胸挂 / 背包本体）格 y。 */
    private int gearSlotY(GearKind kind) {
        return panelsTopY + GEAR_TOP_Y + GEAR_NUDGE_Y + (kind == GearKind.RIG ? 0 : GEAR_STEP);
    }

    /** 胸挂 / 背包网格：底框 + 物品（drawStorageSection；未装备时画缺失块）。 */
    private void drawStorageSection(GuiGraphics gg, GearKind kind, int offsetY) {
        if (GearClientState.equipped(kind).isEmpty()) {
            DnUiTheme.drawMissingGear(gg, this.font,
                    Component.translatable(kind == GearKind.RIG
                            ? "gui.dn.gear.empty_rig" : "gui.dn.gear.empty_backpack"),
                    getMainSlotX(0), contentY(offsetY), sectionBarWidth());
            return;
        }
        StoreContainer box = GearClientState.container(kind);
        GridStore store = box.store();
        int cols = Math.max(1, store.width());
        for (int cell = 0; cell < store.size(); cell++) {
            if (box.isCovered(cell)) {
                continue;
            }
            int x = getMainSlotX(cell % cols);
            int y = contentY(offsetY + (cell / cols) * PITCH);
            GridEntry entry = store.entryAt(cell);
            if (entry != null && !entry.isEmpty()) {
                DnUiTheme.drawRaritySlot(gg, x, y, entry.stack());
            } else {
                DnUiTheme.drawSlotBackdrop(gg, x, y);
            }
        }
    }

    /** 胸挂 / 背包网格物品本体（1x1 或 NxN 跨格）。 */
    private void drawStorageItems(GuiGraphics gg, GearKind kind, int offsetY) {
        if (GearClientState.equipped(kind).isEmpty()) {
            return;
        }
        GridStore store = GearClientState.container(kind).store();
        int cols = Math.max(1, store.width());
        for (Map.Entry<Integer, GridEntry> e : store.entries().entrySet()) {
            GridEntry entry = e.getValue();
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            int anchor = e.getKey();
            int x = getMainSlotX(anchor % cols);
            int y = contentY(offsetY + (anchor / cols) * PITCH);
            GridSize size = entry.size();
            InventoryGridHandler.ItemDim dim = new InventoryGridHandler.ItemDim(size.w(), size.h());
            if (dim.is1x1()) {
                if (GridClassConfig.isClassed(entry.stack())) {
                    GridClientRendering.renderGridStack(gg, entry.stack(), x, y, dim, false,
                            GridClassConfig.bgOf(entry.stack()));
                } else {
                    gg.renderItem(entry.stack(), x, y);
                    gg.renderItemDecorations(this.font, entry.stack(), x, y);
                }
            } else {
                GridClientRendering.renderGridStack(gg, entry.stack(), x, y, dim, entry.rotated());
            }
        }
    }

    /** 内嵌网格悬停金框（锚点格）。 */
    private void drawStorageHover(GuiGraphics gg, GearKind kind, int offsetY, int mouseX, int mouseY) {
        int cell = storageCellAt(kind, offsetY, mouseX, mouseY);
        if (cell < 0) {
            return;
        }
        GridStore store = GearClientState.container(kind).store();
        int cols = Math.max(1, store.width());
        int anchor = store.anchorAt(cell);
        int show = anchor >= 0 ? anchor : cell;
        DnUiTheme.drawSlotHover(gg, getMainSlotX(show % cols), contentY(offsetY + (show / cols) * PITCH));
    }

    /** 内嵌网格悬停提示。 */
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
            gg.renderTooltip(this.font, entry.stack(), mouseX, mouseY);
        }
    }

    /** 装备本体图标（胸挂 / 背包）+ 悬停金框；提示由 {@link #drawEquippedGearTooltip} 统一绘制。 */
    private void drawEquippedGear(GuiGraphics gg, int mouseX, int mouseY) {
        for (GearKind kind : GearKind.values()) {
            int x = gearSlotX();
            int y = gearSlotY(kind);
            ItemStack stack = GearClientState.equipped(kind);
            if (!stack.isEmpty()) {
                // x,y 已是 16×16 内容原点：直接画即居中于 18×18 格框 [x-1, x+17]
                gg.renderItem(stack, x, y);
                gg.renderItemDecorations(this.font, stack, x, y);
            }
            if (hit(mouseX, mouseY, x, y)) {
                DnUiTheme.drawSlotHover(gg, x, y);
            }
        }
    }

    /** 装备本体槽悬停提示（空槽提示「未装备」，有装备显示名称与卸下提示）。 */
    private void drawEquippedGearTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        for (GearKind kind : GearKind.values()) {
            if (!hit(mouseX, mouseY, gearSlotX(), gearSlotY(kind))) {
                continue;
            }
            ItemStack stack = GearClientState.equipped(kind);
            if (stack.isEmpty()) {
                gg.renderComponentTooltip(this.font, List.of(
                        Component.translatable("gui.dn.gear.empty_" + kind.id())), mouseX, mouseY);
            } else {
                gg.renderComponentTooltip(this.font, List.of(
                                stack.getHoverName(),
                                Component.translatable("gui.dn.gear.unequip_hint").withStyle(ChatFormatting.DARK_GRAY)),
                        mouseX, mouseY);
            }
            return;
        }
    }

    /** 容器模式盔甲覆盖层（头盔 0 / 胸甲 1；隐藏护腿 / 靴子 / 副手）。 */
    private void drawArmorOverlay(GuiGraphics gg, int mouseX, int mouseY) {
        for (int i = 0; i < DnUiLayout.ARMOR_COUNT; i++) {
            int x = armorSlotX();
            int y = armorSlotY(i);
            ItemStack stack = armorStack(i);

            // 容器模式：盔甲不是真实菜单槽，需自绘物品；
            // 背包模式：盔甲是真实菜单槽（5/6），物品由 super.render 画，避免重复。
            if (containerMode() && !stack.isEmpty()) {
                gg.renderItem(stack, x, y);
                gg.renderItemDecorations(this.font, stack, x, y);
            }

            if (hit(mouseX, mouseY, x, y)) {
                DnUiTheme.drawSlotHover(gg, x, y);
            }
        }
    }

    /** 容器模式盔甲覆盖层的悬停提示。 */
    private void drawArmorTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        if (!containerMode()) {
            return; // 背包模式盔甲是真实菜单槽，提示由 drawSlotTooltip 负责
        }
        for (int i = 0; i < DnUiLayout.ARMOR_COUNT; i++) {
            if (!hit(mouseX, mouseY, armorSlotX(), armorSlotY(i))) {
                continue;
            }
            ItemStack stack = armorStack(i);
            if (!stack.isEmpty()) {
                gg.renderTooltip(this.font, stack, mouseX, mouseY);
            }
            return;
        }
    }

    /**
     * 盔甲位：0 = 头盔（{@code Inventory} 的 39）、1 = 胸甲（38）；
     * 与 {@code GearGridService.armorClick} 的 {@code ARMOR_ORDER.length - 1 - index} 语义一致。
     */
    private ItemStack armorStack(int index) {
        int inv = index == 0 ? 39 : 38;
        ItemStack s = playerInv.getItem(inv);
        return s == null ? ItemStack.EMPTY : s;
    }

    private void beginItemClip(GuiGraphics gg) {
        gg.enableScissor(
                (int) Math.ceil((mainPanelX + 3) * uiScale),
                (int) Math.ceil(itemViewport.top() * uiScale),
                (int) Math.floor(scrollbarX() * uiScale),
                (int) Math.floor(itemViewport.bottom() * uiScale));
    }

    private void drawScrollbar(GuiGraphics gg) {
        if (itemViewport.maximum() == 0) {
            return;
        }
        int x = scrollbarX();
        gg.fill(x, itemViewport.top(), x + 4, itemViewport.bottom(), -2010105284);
        gg.fill(x, itemViewport.thumbY(), x + 4, itemViewport.thumbY() + itemViewport.thumbHeight(),
                draggingScrollbar ? -1853869 : -6978976);
    }

    /** 左面板生命 / 饥饿 / 护甲读数。 */
    private void drawLeftStatus(GuiGraphics gg) {
        DnUiTheme.drawPlayerStatus(gg, this.font, leftPanelX, panelsTopY);
    }

    /** 玩家预览。 */
    private void drawPlayerPreview(GuiGraphics gg, int mouseX, int mouseY) {
        DnUiTheme.drawPlayerPreview(gg, leftPanelX, panelsTopY, mouseX, mouseY);
    }

    /** 原版悬停高亮置空。 */
    public int getSlotColor(int index) {
        return 0;
    }

    // ==================================================================
    // 命中测试
    // ==================================================================

    private int storageCellAt(GearKind kind, int offsetY, double mouseX, double mouseY) {
        if (GearClientState.equipped(kind).isEmpty()) {
            return -1;
        }
        GridStore store = GearClientState.container(kind).store();
        int cols = Math.max(1, store.width());
        for (int cell = 0; cell < store.size(); cell++) {
            int x = getMainSlotX(cell % cols);
            int y = contentY(offsetY + (cell / cols) * PITCH);
            if (hit(mouseX, mouseY, x, y)) {
                return cell;
            }
        }
        return -1;
    }

    private GearKind gearBodyAt(double mouseX, double mouseY) {
        for (GearKind kind : GearKind.values()) {
            if (hit(mouseX, mouseY, gearSlotX(), gearSlotY(kind))) {
                return kind;
            }
        }
        return null;
    }

    /** 单位格命中：宽高取步距 17（相邻格框叠边 1px，用 18 会在交界处同时命中多格）。 */
    protected static boolean hit(double mx, double my, int x, int y) {
        return hit(mx, my, x, y, PITCH, PITCH);
    }

    protected static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        refreshLayout();
        double mx = mouseX / uiScale;
        double my = mouseY / uiScale;
        boolean shift = hasShiftDown();

        // 滚动条拖动
        if (button == 0 && itemViewport.maximum() > 0 && itemViewport.contains(my)
                && mx >= scrollbarX() - 2 && mx < scrollbarX() + 6) {
            draggingScrollbar = true;
            scrollbarGrab = (my >= itemViewport.thumbY() && my < itemViewport.thumbY() + itemViewport.thumbHeight())
                    ? my - itemViewport.thumbY() : itemViewport.thumbHeight() / 2.0;
            itemViewport.dragThumb(my, scrollbarGrab);
            this.hoveredSlot = null;
            return true;
        }
        // 装备本体（胸挂 / 背包）：左键打开（空槽且光标持同类装备时装备）；
        GearKind gearHit = gearBodyAt(mx, my);
        if (gearHit != null) {
            if (button == 1 || shift) {
                int target = gearHit == GearKind.RIG
                        ? C2SOverlayClickPacket.TARGET_RIG : C2SOverlayClickPacket.TARGET_BACKPACK;
                PacketHandler.sendToServer(new C2SOverlayClickPacket(target, -1, button, shift));
                return true;
            }
            if (button == 0) {
                // 左键：装上去（若光标持有同类装备）——不打开独立界面
                PacketHandler.sendToServer(new C2SOpenGearPacket(gearHit));
                return true;
            }
        }
        // 内嵌装备网格（胸挂 / 背包）
        int rig = storageCellAt(GearKind.RIG, 64, mx, my);
        if (rig >= 0) {
            clickGear(C2SOverlayClickPacket.TARGET_RIG, rig, button, shift);
            return true;
        }
        int bag = storageCellAt(GearKind.BACKPACK, backpackLabelY + 14, mx, my);
        if (bag >= 0) {
            clickGear(C2SOverlayClickPacket.TARGET_BACKPACK, bag, button, shift);
            return true;
        }
        // 安全箱（服务端权威）
        SyncSafeBoxPacket st = SafeBoxOverlay.lastState();
        if (SafeBoxOverlay.safeAllowed(st)) {
            int idx = SafeBoxOverlay.safeSlotAt(mx, my, getMainSlotX(0), getSafeBoxLabelY() + 16,
                    SafeBoxOverlay.safeWidth(st), SafeBoxOverlay.safeHeight(st));
            if (idx >= 0) {
                int action = shift ? C2SSafeBoxClickPacket.ACTION_SHIFT : C2SSafeBoxClickPacket.ACTION_CLICK;
                PacketHandler.sendToServer(new C2SSafeBoxClickPacket(idx, action));
                return true;
            }
        }
        // 容器模式盔甲覆盖层（头盔 0 / 胸甲 1）
        if (containerMode() && (button == 0 || button == 1)) {
            for (int i = 0; i < DnUiLayout.ARMOR_COUNT; i++) {
                if (hit(mx, my, armorSlotX(), armorSlotY(i))) {
                    PacketHandler.sendToServer(new C2SOverlayClickPacket(
                            C2SOverlayClickPacket.TARGET_ARMOR, i, button, shift));
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            itemViewport.dragThumb(mouseY / uiScale, scrollbarGrab);
            refreshLayout();
            this.hoveredSlot = null;
            return true;
        }
        return super.mouseDragged(mouseX / uiScale, mouseY / uiScale, button, dragX / uiScale, dragY / uiScale);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(mouseX / uiScale, mouseY / uiScale, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double mx = mouseX / uiScale;
        double my = mouseY / uiScale;
        if (!overItems(mx, my)) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        itemViewport.scroll(delta);
        refreshLayout();
        this.hoveredSlot = null;
        return true;
    }

    /**
     * 客户端侧把足迹格重定向到锚点格后发送（服务端另有权威归一）。
     *
     * <p><b>右键</b>点击胸挂 / 背包等装备物品改为请求打开其窗口；其余点击照常派发（拾取 / 放下 / 取半）。</p>
     */
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // E 键关闭（与原版一致；matches 支持玩家自定义按键）
        try {
            if (Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) {
                onClose();
                return true;
            }
        } catch (Exception ignored) {
            // options 未就绪时忽略，走默认按键处理
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================================================================
    // 视口
    // ==================================================================

    static final class Viewport {

        private int top;
        private int bottom = 1;
        private int contentHeight;
        private int offset;

        void configure(int top, int bottom, int contentHeight) {
            this.top = top;
            this.bottom = Math.max(top + 1, bottom);
            this.contentHeight = Math.max(0, contentHeight);
            scrollTo(this.offset);
        }

        int top() {
            return top;
        }

        int bottom() {
            return bottom;
        }

        int height() {
            return bottom - top;
        }

        int offset() {
            return offset;
        }

        int maximum() {
            return Math.max(0, contentHeight - height());
        }

        void scrollTo(int value) {
            this.offset = Math.max(0, Math.min(maximum(), value));
        }

        void scroll(double amount) {
            if (Double.isFinite(amount)) {
                scrollTo(offset - (int) Math.round(amount * 17.0 * 2.0));
            }
        }

        boolean contains(double y) {
            return y >= top && y < bottom;
        }

        boolean fullyVisible(int y, int h) {
            return y >= top && y + h <= bottom;
        }

        int thumbHeight() {
            return Math.min(height(), Math.max(18, height() * height() / Math.max(height(), contentHeight)));
        }

        int thumbY() {
            return top + (maximum() == 0 ? 0
                    : (int) Math.round((double) offset / (double) maximum() * (double) (height() - thumbHeight())));
        }

        void dragThumb(double mouseY, double grabOffset) {
            int travel = height() - thumbHeight();
            if (travel > 0 && Double.isFinite(mouseY)) {
                scrollTo((int) Math.round((mouseY - grabOffset - (double) top) / (double) travel * (double) maximum()));
            }
        }
    }
}
