package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SRequestSafeBoxPacket;
import com.deltanexus.system.network.packet.C2SSafeBoxClickPacket;
import com.deltanexus.system.network.packet.SyncSafeBoxPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 安全箱数据同步与渲染（1.1.0；2.0.8 UI 重构）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>数据同步：接收 {@link SyncSafeBoxPacket}，维护 {@link #lastState()}，
 *       并把影子容器注册为「客户端安全箱容器」供网格引擎识别；</li>
 *   <li>背包界面支持：dn 背包（{@link BackpackScreen}，中列安全箱面板）与
 *       原版背包（{@link InventoryScreen}，右侧覆盖层，白名单/关闭替换场景）
 *       打开时请求状态，安全箱物品渲染与点击命中判定共用本类的公共方法。</li>
 * </ul>
 *
 * <p>交互（服务端权威）：左键 = 光标与槽位交换/合并；Shift+左键 = 整体移入背包；
 * 未解锁槽位点击无效（服务端拒绝）。无权使用仓库的玩家收到 allowed=false，不渲染。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID, value = Dist.CLIENT)
public final class SafeBoxOverlay {

    private static final ResourceLocation SLOT =
            ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "textures/gui/slot.png");

    /** 服务端同步状态（allowed=false 或 null 时不渲染）。 */
    private static volatile SyncSafeBoxPacket state;

    /** 覆盖层安全箱影子容器（网格尺寸计算用；仅客户端）。 */
    private static final ItemStackHandler OVERLAY_SAFE = new ItemStackHandler(9);
    private static final Slot[] FAKE_SLOTS = new Slot[9];

    static {
        for (int i = 0; i < 9; i++) {
            FAKE_SLOTS[i] = new SlotItemHandler(OVERLAY_SAFE, i, 0, 0);
        }
    }

    private SafeBoxOverlay() {
    }

    /** 服务端状态到达：缓存；背包界面打开时同步光标栈（点击交互后立即反映）。 */
    public static void receive(SyncSafeBoxPacket packet) {
        state = packet;
        // 格式背包（2.0.0）：覆盖层影子容器注册为「客户端安全箱容器」供网格渲染识别
        InventoryGridHandler.CLIENT_SAFE_HANDLER = OVERLAY_SAFE;
        // 2.0.4：同步客户端安全箱宽度（修复 2x3 物品在覆盖层渲染异常：宽度残留旧值导致回退 1x1）
        InventoryGridHandler.CLIENT_SAFE_WIDTH = safeWidth(packet);
        Minecraft mc = Minecraft.getInstance();
        // 光标栈同步：原版背包与 dn 背包/容器界面均适用（NPE 预防：界面切换时序下判空）。
        // 精确匹配 InventoryScreen——CreativeModeInventoryScreen 是其子类，须排除（需求 1）。
        if (mc.player != null && mc.player.containerMenu != null
                && (isVanillaInventory(mc.screen) || mc.screen instanceof BackpackScreen
                        || mc.screen instanceof DnContainerScreen)) {
            mc.player.containerMenu.setCarried(packet.carried == null ? ItemStack.EMPTY : packet.carried);
        }
    }

    /**
     * 精确判定原版生存背包界面：CreativeModeInventoryScreen 继承 InventoryScreen，
     * instanceof 会误匹配创造模式（需求 1：创造界面必须保持原版，禁止覆盖层）。
     */
    private static boolean isVanillaInventory(net.minecraft.client.gui.screens.Screen screen) {
        return screen != null && screen.getClass() == InventoryScreen.class;
    }

    /** 覆盖层是否对该界面生效：原版背包未加入白名单时才渲染（白名单 = 完全原版体验）。
     *  2.1：玩家功能被禁用（featuresEnabled=false）时不渲染安全箱。 */
    private static boolean overlayEnabled(net.minecraft.client.gui.screens.Screen screen) {
        if (!com.deltanexus.system.config.ClientUiConfig.featuresEnabled()) {
            return false;
        }
        if (!isVanillaInventory(screen)) {
            return false;
        }
        // 2.0.10 创造模式修复：按 E 时 InventoryScreen 是创造界面的「跳板」
        // （containerTick 检测 hasInfiniteItems 后自动切换为 CreativeModeInventoryScreen），
        // 跳板帧内不渲染覆盖层，避免闪现
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null && mc.gameMode.hasInfiniteItems()) {
            return false;
        }
        return !com.deltanexus.system.config.ClientUiConfig.isVanillaUi(screen.getClass());
    }

    /** 最近一次安全箱状态（安全箱面板读取尺寸/等级用；可能为 null）。 */
    public static SyncSafeBoxPacket lastState() {
        return state;
    }

    /** 安全箱显示宽度（1~3，状态无效时回退 1，防御非法数据）。 */
    public static int safeWidth(SyncSafeBoxPacket st) {
        return st == null ? 1 : Math.max(1, Math.min(3, st.width));
    }

    /** 安全箱显示高度（1~3，状态无效时回退 1，防御非法数据）。 */
    public static int safeHeight(SyncSafeBoxPacket st) {
        return st == null ? 1 : Math.max(1, Math.min(3, st.height));
    }

    /** 安全箱标题文本（2.1：被禁用时显示「安全箱被禁用」提示）。 */
    public static String safeTitle(SyncSafeBoxPacket st) {
        if (st == null) {
            return Component.translatable("gui.dn.safe_box").getString();
        }
        if (!st.allowed) {
            return Component.translatable("gui.dn.safe_box_disabled").getString();
        }
        return Component.translatable("gui.dn.safe_box").getString() + " Lv" + st.safeLevel;
    }

    /** 安全箱标题颜色（2.1：禁用时红色警示，正常金色）。 */
    public static int safeTitleColor(SyncSafeBoxPacket st) {
        return st != null && !st.allowed ? 0xFFFF5555 : DnTheme.GOLD;
    }

    /** 安全箱是否可用（状态已同步且未被禁用；2.1：禁用时不渲染任何格子）。 */
    public static boolean safeAllowed(SyncSafeBoxPacket st) {
        return st != null && st.allowed;
    }

    /** 打开背包/容器/仓库界面（dn / 原版）：请求一次安全箱状态（精确匹配，排除创造模式子类）。
     *  2.1：玩家功能被禁用时不请求（界面已恢复原版且不渲染安全箱）。 */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!com.deltanexus.system.config.ClientUiConfig.featuresEnabled()) {
            return;
        }
        if (event.getScreen().getClass() == InventoryScreen.class
                || event.getScreen() instanceof BackpackScreen
                || event.getScreen() instanceof DnContainerScreen
                || event.getScreen() instanceof WarehouseScreen) {
            // 格式背包（2.0.0）：菜单关闭后重置客户端安全箱容器指向覆盖层影子容器
            InventoryGridHandler.CLIENT_SAFE_HANDLER = OVERLAY_SAFE;
            PacketHandler.sendToServer(new C2SRequestSafeBoxPacket());
        }
    }

    // ==================================================================
    // 公共渲染与命中判定（dn 背包中列面板 / 原版背包右侧覆盖层共用）
    // ==================================================================

    /**
     * 渲染安全箱网格物品与悬停 tooltip（调用方自画面板底图）。
     * NPE 预防：状态未同步/字段缺失时静默跳过。
     */
    public static void renderSafeBoxGrid(GuiGraphics gg, int x, int y, int w, int h, double mx, double my) {
        SyncSafeBoxPacket s = state;
        if (s == null || !s.allowed || s.items == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        for (int i = 0; i < w * h && i < FAKE_SLOTS.length; i++) {
            int sx = x + (i % w) * 18;
            int sy = y + (i / w) * 18;
            ItemStack stack = i < s.items.length ? s.items[i] : ItemStack.EMPTY;
            if (stack.isEmpty()) {
                continue;
            }
            if (InventoryGridHandler.isSlave(stack)) {
                // 占位物：白色半透明墙（与容器界面一致）
                gg.pose().pushPose();
                gg.pose().translate(0, 0, 350);
                gg.fill(sx, sy, sx + 16, sy + 16, 0xAAFFFFFF);
                gg.pose().popPose();
            } else {
                InventoryGridHandler.ItemDim dim = InventoryGridHandler.getActualDim(
                        stack, FAKE_SLOTS[i], false, mc.player);
                // 超宽回退 1x1（与容器界面一致）
                if ((i % w) + dim.w() > w) {
                    dim = new InventoryGridHandler.ItemDim(1, 1);
                }
                if (dim.is1x1()) {
                    // 2.0.5：1x1 物品同样按类着色（类色墙 + 图标）
                    if (com.deltanexus.system.grid.GridClassConfig.isClassed(stack)) {
                        com.deltanexus.system.grid.GridClientRendering.renderGridStack(gg, stack, sx, sy,
                                new InventoryGridHandler.ItemDim(1, 1), false,
                                com.deltanexus.system.grid.GridClassConfig.bgOf(stack));
                    } else {
                        gg.renderItem(stack, sx, sy);
                    }
                } else {
                    boolean rotated = stack.hasTag()
                            && stack.getTag().getBoolean(InventoryGridHandler.IS_ROTATED);
                    com.deltanexus.system.grid.GridClientRendering.renderGridStack(gg, stack, sx, sy, dim, rotated);
                }
            }
            if (!InventoryGridHandler.isSlave(stack)
                    && mx >= sx && mx < sx + 18 && my >= sy && my < sy + 18) {
                gg.renderTooltip(mc.font, stack, sx, sy);
            }
        }
    }

    /** 安全箱网格命中判定：返回槽位序号（0~8），未命中返回 -1。 */
    public static int safeSlotAt(double mx, double my, int x, int y, int w, int h) {
        for (int i = 0; i < w * h; i++) {
            int sx = x + (i % w) * 18;
            int sy = y + (i / w) * 18;
            if (mx >= sx && mx < sx + 18 && my >= sy && my < sy + 18) {
                return i;
            }
        }
        return -1;
    }

    // ==================================================================
    // 原版背包界面右侧覆盖层（白名单/关闭替换场景）
    // ==================================================================

    /** 覆盖层网格区域（屏幕坐标）：x, y, 宽, 高；状态未知返回 null。
     *  2.1：被禁用（allowed=false）时仍返回区域（用于渲染「安全箱被禁用」提示面板）。 */
    private static int[] boxRect() {
        SyncSafeBoxPacket s = state;
        if (s == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        int w = safeWidth(s);
        int h = safeHeight(s);
        int x = mc.getWindow().getGuiScaledWidth() - w * 18 - 14 - 72;
        int y = mc.getWindow().getGuiScaledHeight() / 2 - h * 18 / 2 - 6 + 27;
        return new int[]{x, y, w, h};
    }

    /** 渲染覆盖层（原版背包打开、未加白名单时；2.1：禁用时显示红色「安全箱被禁用」提示，
     *  不渲染任何格子；正常时渲染格子与物品）。 */
    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!overlayEnabled(event.getScreen())) {
            return;
        }
        int[] r = boxRect();
        if (r == null) {
            return;
        }
        SyncSafeBoxPacket s = state;
        GuiGraphics gg = event.getGuiGraphics();
        int x = r[0];
        int y = r[1];
        int w = r[2];
        int h = r[3];
        int pw = w * 18 + 16;
        int ph = h * 18 + 26 + 8;
        // 面板 + 标题（2.1：禁用时红色「安全箱被禁用」提示，位于安全箱文字处）
        DnTheme.drawPanel(gg, x - 8, y - 26, pw, ph);
        DnTheme.drawTitle(gg, Minecraft.getInstance().font, x - 8, y - 26, pw, safeTitle(s), safeTitleColor(s));
        if (s == null || !s.allowed) {
            // 2.1：被禁用 → 不渲染任何安全箱格子
            return;
        }
        // 槽位（仅已解锁行列）
        for (int i = 0; i < w * h; i++) {
            int sx = x + (i % w) * 18;
            int sy = y + (i / w) * 18;
            gg.blit(SLOT, sx, sy, 0, 0, 18, 18, 18, 18);
        }
        // 物品 + 悬停 tooltip（与 dn 背包共用）
        Minecraft mc = Minecraft.getInstance();
        double mx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double my = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        renderSafeBoxGrid(gg, x, y, w, h, mx, my);
    }

    /** 点击交互：仅拦截安全箱网格区域内的左键点击（禁用时不拦截），其余交给原版界面。 */
    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0) {
            return;
        }
        if (!overlayEnabled(Minecraft.getInstance().screen)) {
            return;
        }
        if (!safeAllowed(state)) {
            return; // 2.1：被禁用时不拦截点击（安全箱格子不渲染）
        }
        int[] r = boxRect();
        if (r == null) {
            return;
        }
        int idx = safeSlotAt(event.getMouseX(), event.getMouseY(), r[0], r[1], r[2], r[3]);
        if (idx >= 0) {
            int action = Screen.hasShiftDown()
                    ? C2SSafeBoxClickPacket.ACTION_SHIFT
                    : C2SSafeBoxClickPacket.ACTION_CLICK;
            PacketHandler.sendToServer(new C2SSafeBoxClickPacket(idx, action));
            event.setCanceled(true);
        }
    }
}
