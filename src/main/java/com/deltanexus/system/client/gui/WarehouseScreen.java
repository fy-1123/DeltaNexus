package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.common.FormatUtil;
import com.deltanexus.system.menu.WarehouseMenu;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SWarehouseScrollPacket;
import com.deltanexus.system.network.packet.SyncSafeBoxPacket;
import com.deltanexus.system.network.packet.SyncWarehousePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

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

    public WarehouseScreen(WarehouseMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.sync = lastSync;
    }

    /** 同步包到达：实时刷新（升级后无需重开 GUI）。 */
    public void onSync(SyncWarehousePacket packet) {
        this.sync = packet;
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
        gg.drawCenteredString(font, rowInfo, L.whX + 81, L.whY + 218, DnTheme.ACCENT);
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
