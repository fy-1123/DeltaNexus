package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.ClientUiConfig;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SSafeBoxClickPacket;
import com.deltanexus.system.network.packet.SyncSafeBoxPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * dn 背包界面（2.0.8Alpha UI 重构）：非白名单时替换原版背包界面（E 键）。
 *
 * <p>布局遵循 UI 设计规范（{@code UI.html}）：
 * 左列 = 盔甲(4)/快捷栏后 4 格/副手 竖排；中列 = 口袋(快捷栏前 5 格)/背包(3x9)/安全箱(3x3)。</p>
 *
 * <p>配方书与 2x2 合成栏的隐藏机制：本界面不继承 RecipeBookScreen，
 * 配方书组件与按钮根本不存在（无空指针风险）；合成格/结果格槽位坐标移至屏外，
 * 屏外槽位不可渲染、不可点击，原版快捷移动（shift）也不会将物品移入合成格。
 * 槽位原坐标在打开时快照、关闭时恢复，保证再次打开原版界面（白名单场景）不串位。</p>
 *
 * <p>安全箱：中列背包下方，数据由 {@link SafeBoxOverlay} 同步（服务端权威交互）。</p>
 */
public class BackpackScreen extends AbstractContainerScreen<InventoryMenu> {

    private static final ResourceLocation SLOT_TEX =
            ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "textures/gui/slot.png");

    /** 屏外坐标（合成格/结果格隐藏用：不可渲染不可点击）。 */
    private static final int OFFSCREEN = -1000;

    /** 槽位原坐标快照（removed 时恢复；menu 为常驻 InventoryMenu，必须还原）。 */
    private int[] savedX;
    private int[] savedY;

    public BackpackScreen(Player player) {
        // InventoryMenu 常驻（容器 id 0），直接复用，交互走标准容器协议
        // 注意：1.20.1 的 AbstractContainerMenu 无 getTitle()（1.20.3+ 才有），用 Inventory.getDisplayName()
        super(player.inventoryMenu, player.getInventory(), player.getInventory().getDisplayName());
    }

    @Override
    protected void init() {
        // 全屏自绘（与仓库界面一致）：面板由 renderBg 绘制
        this.imageWidth = this.width;
        this.imageHeight = this.height;
        this.leftPos = 0;
        this.topPos = 0;
        super.init();
        // 2.0.10Alpha：口袋槽尺寸守卫（客户端预测——塞大件到口袋直接回光标，避免一闪再回弹）
        InventoryGridHandler.ensurePocketGuards(this.menu, Minecraft.getInstance().player);
        remapSlots();
    }

    /**
     * 重映射原版 InventoryMenu 槽位到三列布局。
     * 全程判空防御：菜单槽位数不足（其他模组改造）时保持原布局不处理。
     */
    private void remapSlots() {
        InventoryMenu menu = this.menu;
        // NPE 预防：资源加载/界面切换时序下菜单可能尚未就绪
        if (menu == null || menu.slots.size() < 46) {
            DeltaNexus.LOGGER.debug("[DN] 背包槽位重映射跳过：槽位数 {}", menu == null ? 0 : menu.slots.size());
            return;
        }
        snapshotSlots(menu);
        PlayerLayout L = PlayerLayout.compute(this.width, this.height, false);
        // 0 = 合成结果，1-4 = 2x2 合成格：移屏外隐藏（配方书本界面不存在）
        for (int i = 0; i <= 4; i++) {
            move(menu.getSlot(i), OFFSCREEN, OFFSCREEN);
        }
        // 5-8 盔甲（5=头盔 ... 8=靴子，竖排）
        for (int i = 0; i < 4; i++) {
            move(menu.getSlot(5 + i), L.leftX, L.armorY + i * PlayerLayout.SLOT);
        }
        // 9-35 背包 3x9
        for (int i = 9; i < 36; i++) {
            move(menu.getSlot(i), L.midX + (i - 9) % 9 * PlayerLayout.SLOT,
                    L.invY + (i - 9) / 9 * PlayerLayout.SLOT);
        }
        // 36-44 快捷栏（键位 1-9）：1-4 号格 = 左列竖排，5-9 号格 = 中列口袋（横排）
        for (int i = 36; i < 45; i++) {
            if (i < 40) {
                move(menu.getSlot(i), L.leftX, L.hotbarColY + (i - 36) * PlayerLayout.SLOT);
            } else {
                move(menu.getSlot(i), L.midX + (i - 40) * PlayerLayout.SLOT, L.pocketY);
            }
        }
        // 45 副手
        move(menu.getSlot(45), L.leftX, L.offhandY);
    }

    private static void move(Slot slot, int x, int y) {
        if (slot == null) {
            return;
        }
        slot.x = x;
        slot.y = y;
    }

    /** 首次重映射前快照原版槽位坐标（幂等：resize 重入不覆盖）。 */
    private void snapshotSlots(InventoryMenu menu) {
        if (savedX != null && savedX.length == menu.slots.size()) {
            return;
        }
        savedX = new int[menu.slots.size()];
        savedY = new int[menu.slots.size()];
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            savedX[i] = s.x;
            savedY[i] = s.y;
        }
    }

    /** 恢复原版槽位坐标（InventoryMenu 为常驻实例，关闭后原版界面仍会使用）。 */
    private void restoreSlots() {
        InventoryMenu menu = this.menu;
        if (menu == null || savedX == null || savedX.length != menu.slots.size()) {
            return;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            s.x = savedX[i];
            s.y = savedY[i];
        }
    }

    @Override
    public void removed() {
        restoreSlots();
        super.removed();
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        PlayerLayout L = PlayerLayout.compute(this.width, this.height, false);
        // 左列面板（盔甲 + 快捷栏列 + 副手）
        int leftBottom = L.offhandY + PlayerLayout.SLOT + 8;
        DnTheme.drawPanel(gg, L.leftX - 8, L.baseY - 22, PlayerLayout.SLOT + 16, leftBottom - L.baseY + 22 + 8);
        // 中列整块面板（口袋 + 背包 + 安全箱，2.0.9Alpha 与仓库界面中列面板统一）
        int safeH = SafeBoxOverlay.safeHeight(SafeBoxOverlay.lastState());
        int midBottom = L.safeY + safeH * PlayerLayout.SLOT + 8;
        DnTheme.drawPanel(gg, L.midX - 8, L.baseY - 22, 9 * PlayerLayout.SLOT + 16, midBottom - L.baseY + 22 + 8);
        // 槽位底图（屏外槽位跳过）
        for (int i = 5; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);
            if (s.x <= OFFSCREEN / 2) {
                continue;
            }
            gg.blit(SLOT_TEX, s.x, s.y, 0, 0, 18, 18, 18, 18);
        }
        // 安全箱区底图 + 标题（面板已并入中列整块，2.0.9Alpha）
        renderSafeBoxPanel(gg, L);
    }

    /** 安全箱区底图 + 标题（面板底图并入中列整块面板；物品渲染在 render 阶段叠加）。
     *  2.1Alpha：被禁用时仅显示红色「安全箱被禁用」提示，不渲染任何安全箱格子。 */
    private void renderSafeBoxPanel(GuiGraphics gg, PlayerLayout L) {
        SyncSafeBoxPacket st = SafeBoxOverlay.lastState();
        int w = SafeBoxOverlay.safeWidth(st);
        int h = SafeBoxOverlay.safeHeight(st);
        int pw = w * PlayerLayout.SLOT + 16;
        String title = SafeBoxOverlay.safeTitle(st);
        DnTheme.drawTitle(gg, this.font, L.midX - 8, L.safeY - 22, pw, title, SafeBoxOverlay.safeTitleColor(st));
        if (!SafeBoxOverlay.safeAllowed(st)) {
            return; // 2.1Alpha：被禁用 → 不渲染安全箱格子
        }
        for (int i = 0; i < w * h; i++) {
            int x = L.midX + (i % w) * PlayerLayout.SLOT;
            int y = L.safeY + (i / w) * PlayerLayout.SLOT;
            gg.blit(SLOT_TEX, x, y, 0, 0, 18, 18, 18, 18);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        PlayerLayout L = PlayerLayout.compute(this.width, this.height, false);
        int lw = PlayerLayout.SLOT + 16;
        // 左列组标题
        drawGroupTitle(gg, L.leftX - 8, L.armorY - 22, lw, "gui.dn.armor");
        drawGroupTitle(gg, L.leftX - 8, L.hotbarColY - 22, lw, "gui.dn.hotbar");
        drawGroupTitle(gg, L.leftX - 8, L.offhandY - 22, lw, "gui.dn.offhand");
        // 中列组标题
        drawGroupTitle(gg, L.midX - 8, L.pocketY - 22, 5 * PlayerLayout.SLOT + 16, "gui.dn.pocket");
        drawGroupTitle(gg, L.midX - 8, L.invY - 22, 9 * PlayerLayout.SLOT + 16, "container.inventory");
    }

    private void drawGroupTitle(GuiGraphics gg, int x, int y, int w, String key) {
        DnTheme.drawTitle(gg, this.font, x, y, w, Component.translatable(key).getString(), DnTheme.TEXT_MAIN);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        super.render(gg, mouseX, mouseY, partialTick);
        renderTooltip(gg, mouseX, mouseY);
        // 安全箱物品与 tooltip 在容器渲染之后叠加（坐标独立于菜单槽位）
        SyncSafeBoxPacket st = SafeBoxOverlay.lastState();
        if (st != null && st.allowed) {
            PlayerLayout L = PlayerLayout.compute(this.width, this.height, false);
            SafeBoxOverlay.renderSafeBoxGrid(gg, L.midX, L.safeY,
                    SafeBoxOverlay.safeWidth(st), SafeBoxOverlay.safeHeight(st), mouseX, mouseY);
        }
    }

    // ==================================================================
    // 安全箱交互（服务端权威，同原背包覆盖层）
    // ==================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        SyncSafeBoxPacket st = SafeBoxOverlay.lastState();
        // NPE 预防：状态未同步（刚打开/切界面）时不拦截，交给原版槽位逻辑
        if (st != null && st.allowed && button == 0) {
            PlayerLayout L = PlayerLayout.compute(this.width, this.height, false);
            int idx = SafeBoxOverlay.safeSlotAt(mouseX, mouseY, L.midX, L.safeY,
                    SafeBoxOverlay.safeWidth(st), SafeBoxOverlay.safeHeight(st));
            if (idx >= 0) {
                int action = hasShiftDown()
                        ? C2SSafeBoxClickPacket.ACTION_SHIFT
                        : C2SSafeBoxClickPacket.ACTION_CLICK;
                PacketHandler.sendToServer(new C2SSafeBoxClickPacket(idx, action));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // E 键关闭背包（与原版一致；matches 支持玩家自定义按键）
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

    /**
     * 背包界面替换拦截器：原版 InventoryScreen 打开时替换为本界面。
     *
     * <p>跳过条件（保持原版）：白名单包含 InventoryScreen、配置关闭替换、
     * 无玩家上下文（登录/断线等界面切换时序，NPE 预防）、创造模式玩家。</p>
     *
     * <p>2.0.10Alpha 创造模式修复：MC 1.20.1 按 E 统一打开 InventoryScreen
     * （{@code handleKeybinds} 无创造分支），其 {@code containerTick} 检测
     * {@code hasInfiniteItems()}（创造模式）后在一帧内自动切换为
     * CreativeModeInventoryScreen——InventoryScreen 只是创造界面的「跳板」。
     * 若在跳板上替换为 BackpackScreen，创造界面将永远无法出现。
     * 故创造模式玩家放行原版跳板，保持原版创造物品栏（需求 1）。</p>
     */
    @Mod.EventBusSubscriber(modid = DeltaNexus.MODID, value = Dist.CLIENT)
    public static final class Opener {

        private Opener() {
        }

        @SubscribeEvent
        public static void onScreenOpen(ScreenEvent.Opening event) {
            // 精确类匹配：CreativeModeInventoryScreen 继承 InventoryScreen，
            // instanceof 会误拦截创造模式界面（需求 1：创造界面必须保持原版）
            if (event.getNewScreen() == null
                    || event.getNewScreen().getClass() != InventoryScreen.class) {
                return;
            }
            String reason = null;
            // 2.1Alpha：玩家功能被禁用（featuresEnabled=false）→ 所有界面恢复原版
            if (!ClientUiConfig.featuresEnabled()) {
                reason = "功能被禁用";
            } else if (ClientUiConfig.isVanillaUi(InventoryScreen.class)) {
                reason = "白名单命中";
            } else if (!ClientUiConfig.replaceInventoryScreen()) {
                reason = "replaceInventoryScreen=false";
            } else {
                Minecraft mc = Minecraft.getInstance();
                // NPE 预防：无玩家（主菜单/加载界面）时不替换
                if (mc.player == null || mc.player.inventoryMenu == null) {
                    reason = "无玩家上下文";
                } else if (mc.gameMode != null && mc.gameMode.hasInfiniteItems()) {
                    // 创造模式修复：与原版 containerTick 判定一致（hasInfiniteItems），
                    // 放行跳板让原版自动切换到创造物品栏界面
                    reason = "创造模式，跳板放行原版界面";
                } else {
                    try {
                        event.setNewScreen(new BackpackScreen(mc.player));
                        return;
                    } catch (Exception e) {
                        // 预防：替换异常时保持原版界面，不阻断游戏
                        DeltaNexus.LOGGER.warn("[DN] 背包界面替换失败，保持原版: {}", e.toString());
                        return;
                    }
                }
            }
            // 诊断日志：记录跳过原因，便于排查「背包变原版」类问题
            DeltaNexus.LOGGER.info("[DN] 背包界面保持原版：{}", reason);
        }
    }
}
