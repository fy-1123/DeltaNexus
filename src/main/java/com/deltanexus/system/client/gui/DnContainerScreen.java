package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.ClientUiConfig;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SSafeBoxClickPacket;
import com.deltanexus.system.network.packet.SyncSafeBoxPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * dn 通用容器界面（2.0.10）：箱子/木桶/潜影盒/发射器/漏斗等纯槽位容器的 dn 风格皮肤。
 *
 * <p>核心思路：完全复用原版容器 Menu（服务端逻辑、容器 id、点击协议零改动），
 * 仅重映射槽位坐标到三列布局并全屏自绘面板——与 {@link BackpackScreen} 同一模式：
 * <ul>
 *   <li>左列 —— 快捷栏 1-4 号格竖排（容器菜单无盔甲/副手槽，不凭空造槽位）；</li>
 *   <li>中列 —— 口袋（快捷栏 5-9）/ 背包 3x9 / 安全箱（客户端渲染 + 服务端权威交互）；</li>
 *   <li>右列 —— 容器槽位网格（列数从原版槽位坐标自动推断，箱子 9 列/发射器 3 列/漏斗 5 列）。</li>
 * </ul></p>
 *
 * <p>白名单（双端并集）命中的界面类保持原版；{@code replaceContainerScreen=false} 全局关闭。
 * 创造模式玩家开箱同样替换（容器界面无 InventoryScreen 跳板问题）。</p>
 */
public class DnContainerScreen extends AbstractContainerScreen<AbstractContainerMenu> {

    private static final ResourceLocation SLOT_TEX =
            ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "textures/gui/slot.png");

    /** 槽位原坐标快照（removed/白名单热切换时恢复；resize 重入时网格推断以快照为准）。 */
    private int[] savedX;
    private int[] savedY;
    /** 容器网格列数/行数（快照坐标推断）。 */
    private int cols = 9;
    private int rows = 3;

    public DnContainerScreen(AbstractContainerMenu menu, Player player, Component title) {
        super(menu, player.getInventory(), title);
    }

    @Override
    protected void init() {
        this.imageWidth = this.width;
        this.imageHeight = this.height;
        this.leftPos = 0;
        this.topPos = 0;
        super.init();
        // 2.0.10：口袋槽尺寸守卫（客户端预测——塞大件到口袋直接回光标，避免一闪再回弹）
        InventoryGridHandler.ensurePocketGuards(this.menu, Minecraft.getInstance().player);
        snapshotAndDetectGrid();
        remapSlots();
    }

    /**
     * 首次进入时快照原版槽位坐标并推断容器网格：
     * 容器槽（非玩家 Inventory 槽）中不同 x 坐标数 = 列数、不同 y 数 = 行数。
     * 幂等：resize 重入不覆盖快照（此时坐标已被改写，重推断会出错）。
     */
    private void snapshotAndDetectGrid() {
        AbstractContainerMenu menu = this.menu;
        if (menu == null || menu.slots.isEmpty()) {
            return;
        }
        if (savedX == null || savedX.length != menu.slots.size()) {
            savedX = new int[menu.slots.size()];
            savedY = new int[menu.slots.size()];
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot s = menu.slots.get(i);
                savedX[i] = s.x;
                savedY[i] = s.y;
            }
        }
        // 以快照坐标推断网格（容器槽 = container 非玩家 Inventory）
        TreeSet<Integer> xs = new TreeSet<>();
        TreeSet<Integer> ys = new TreeSet<>();
        List<Integer> ctnIdx = containerSlotIndices();
        for (int idx : ctnIdx) {
            xs.add(savedX[idx]);
            ys.add(savedY[idx]);
        }
        if (!xs.isEmpty() && !ys.isEmpty()) {
            cols = Math.max(1, xs.size());
            rows = Math.max(1, ys.size());
        }
    }

    /** 容器槽索引列表（container 非玩家 Inventory 的槽位）。 */
    private List<Integer> containerSlotIndices() {
        List<Integer> list = new ArrayList<>();
        Player player = Minecraft.getInstance().player;
        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);
            // NPE 预防：无玩家上下文时按「非 Inventory 容器」判定可能误判，跳过处理
            if (s == null || player == null) {
                continue;
            }
            if (s.container != player.getInventory()) {
                list.add(i);
            }
        }
        return list;
    }

    /** 重映射槽位：容器槽 → 右列网格；玩家槽按 Inventory 索引分区（快捷栏/口袋/背包）。 */
    private void remapSlots() {
        AbstractContainerMenu menu = this.menu;
        if (menu == null || savedX == null) {
            return;
        }
        PlayerLayout L = PlayerLayout.computeContainer(this.width, this.height, cols, rows);
        // 容器槽：按菜单顺序行优先排入右列（原版顺序即行优先）
        List<Integer> ctn = containerSlotIndices();
        for (int n = 0; n < ctn.size(); n++) {
            Slot s = menu.slots.get(ctn.get(n));
            move(s, L.whX + (n % cols) * PlayerLayout.SLOT, L.whY + (n / cols) * PlayerLayout.SLOT);
        }
        // 玩家槽：containerSlot = Inventory 索引（0-8 快捷栏，9-35 背包）
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s == null) {
                continue;
            }
            Player player = Minecraft.getInstance().player;
            if (player == null || s.container != player.getInventory()) {
                continue;
            }
            int ci = s.getContainerSlot();
            if (ci >= 0 && ci <= 3) {
                // 快捷栏 1-4 号格：左列竖排
                move(s, L.leftX, L.hotbarColY + ci * PlayerLayout.SLOT);
            } else if (ci >= 4 && ci <= 8) {
                // 快捷栏 5-9 号格：中列口袋横排
                move(s, L.midX + (ci - 4) * PlayerLayout.SLOT, L.pocketY);
            } else if (ci >= 9 && ci <= 35) {
                // 背包 3x9
                move(s, L.midX + (ci - 9) % 9 * PlayerLayout.SLOT,
                        L.invY + (ci - 9) / 9 * PlayerLayout.SLOT);
            }
            // 其余（盔甲/副手等容器菜单不存在）保持快照坐标不处理
        }
    }

    private static void move(Slot slot, int x, int y) {
        if (slot == null) {
            return;
        }
        slot.x = x;
        slot.y = y;
    }

    /** 恢复原版槽位坐标（防御：其他模组复用同一菜单实例时不受污染）。 */
    @Override
    public void removed() {
        AbstractContainerMenu menu = this.menu;
        if (menu != null && savedX != null && savedX.length == menu.slots.size()) {
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot s = menu.slots.get(i);
                s.x = savedX[i];
                s.y = savedY[i];
            }
        }
        super.removed();
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        PlayerLayout L = PlayerLayout.computeContainer(this.width, this.height, cols, rows);
        // 左列面板（快捷栏 1-4 竖排）
        int leftBottom = L.hotbarColY + 4 * PlayerLayout.SLOT + 8;
        DnTheme.drawPanel(gg, L.leftX - 8, L.baseY - 22, PlayerLayout.SLOT + 16,
                leftBottom - L.baseY + 22 + 8);
        // 中列整块面板（口袋 + 背包 + 安全箱，与背包界面一致）
        int safeH = SafeBoxOverlay.safeHeight(SafeBoxOverlay.lastState());
        int midBottom = L.safeY + safeH * PlayerLayout.SLOT + 8;
        DnTheme.drawPanel(gg, L.midX - 8, L.baseY - 22, 9 * PlayerLayout.SLOT + 16,
                midBottom - L.baseY + 22 + 8);
        // 右列容器面板（标题 + 网格）
        int ctnBottom = L.whY + rows * PlayerLayout.SLOT + 8;
        DnTheme.drawPanel(gg, L.whX - 8, L.baseY - 22, cols * PlayerLayout.SLOT + 16,
                Math.max(ctnBottom, midBottom) - L.baseY + 22 + 8);
        // 槽位底图（容器菜单槽位 0 起即容器槽；快照坐标为负的异常槽跳过）
        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);
            if (s == null || s.x < 0 || s.y < 0) {
                continue;
            }
            gg.blit(SLOT_TEX, s.x, s.y, 0, 0, 18, 18, 18, 18);
        }
        // 安全箱底图 + 标题（物品在 render 阶段叠加）
        renderSafeBoxPanel(gg, L);
    }

    /** 安全箱区底图 + 标题（同背包界面：中列背包下方）。
     *  2.1：被禁用时仅显示红色「安全箱被禁用」提示，不渲染任何安全箱格子。 */
    private void renderSafeBoxPanel(GuiGraphics gg, PlayerLayout L) {
        SyncSafeBoxPacket st = SafeBoxOverlay.lastState();
        int w = SafeBoxOverlay.safeWidth(st);
        int h = SafeBoxOverlay.safeHeight(st);
        String title = SafeBoxOverlay.safeTitle(st);
        DnTheme.drawTitle(gg, this.font, L.midX - 8, L.safeY - 22,
                w * PlayerLayout.SLOT + 16, title, SafeBoxOverlay.safeTitleColor(st));
        if (!SafeBoxOverlay.safeAllowed(st)) {
            return; // 2.1：被禁用 → 不渲染安全箱格子
        }
        for (int i = 0; i < w * h; i++) {
            gg.blit(SLOT_TEX, L.midX + (i % w) * PlayerLayout.SLOT,
                    L.safeY + (i / w) * PlayerLayout.SLOT, 0, 0, 18, 18, 18, 18);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        PlayerLayout L = PlayerLayout.computeContainer(this.width, this.height, cols, rows);
        // 左列组标题
        drawGroupTitle(gg, L.leftX - 8, L.hotbarColY - 22, PlayerLayout.SLOT + 16, "gui.dn.hotbar");
        // 中列组标题
        drawGroupTitle(gg, L.midX - 8, L.pocketY - 22, 5 * PlayerLayout.SLOT + 16, "gui.dn.pocket");
        drawGroupTitle(gg, L.midX - 8, L.invY - 22, 9 * PlayerLayout.SLOT + 16, "container.inventory");
        // 右列容器名（原版界面标题，如「箱子」/自定义名）
        DnTheme.drawTitle(gg, this.font, L.whX - 8, L.whY - 22, cols * PlayerLayout.SLOT + 16,
                this.title.getString(), DnTheme.TEXT_MAIN);
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
            PlayerLayout L = PlayerLayout.computeContainer(this.width, this.height, cols, rows);
            SafeBoxOverlay.renderSafeBoxGrid(gg, L.midX, L.safeY,
                    SafeBoxOverlay.safeWidth(st), SafeBoxOverlay.safeHeight(st), mouseX, mouseY);
        }
    }

    // ==================================================================
    // 交互（安全箱服务端权威，同背包界面）
    // ==================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        SyncSafeBoxPacket st = SafeBoxOverlay.lastState();
        if (st != null && st.allowed && button == 0) {
            PlayerLayout L = PlayerLayout.computeContainer(this.width, this.height, cols, rows);
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
        // E 键关闭（与原版容器界面一致；matches 支持玩家自定义按键）
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
     * 容器界面替换拦截器（2.0.10）：原版纯槽位容器界面打开时替换为 dn 三列布局。
     *
     * <p>替换范围（1.20.1 类名）：箱子/木桶（ContainerScreen，含大箱子——与单箱同类
     * 无法区分）/潜影盒（ShulkerBoxScreen，直接继承 AbstractContainerScreen，
     * 非 ContainerScreen 子类，2.0.10 补拦）/发射器与投掷器（DispenserScreen）/
     * 漏斗（HopperScreen）——均为纯槽位容器，复用原版 Menu 后全部交互
     * （点击/shift/拖拽/数字键）走标准协议。熔炉等工作台类界面有进度条等特殊渲染，不替换。</p>
     */
    @Mod.EventBusSubscriber(modid = DeltaNexus.MODID, value = Dist.CLIENT)
    public static final class Opener {

        private Opener() {
        }

        @SubscribeEvent
        public static void onScreenOpen(ScreenEvent.Opening event) {
            Screen s = event.getNewScreen();
            if (!(s instanceof ContainerScreen || s instanceof ShulkerBoxScreen
                    || s instanceof DispenserScreen || s instanceof HopperScreen)) {
                return;
            }
            String reason = null;
            // 2.1：玩家功能被禁用（featuresEnabled=false）→ 所有界面恢复原版
            if (!ClientUiConfig.featuresEnabled()) {
                reason = "功能被禁用";
            } else if (ClientUiConfig.isVanillaUi(s.getClass())) {
                reason = "白名单命中";                        // 大小箱子同为 ContainerScreen，白名单同时影响两者
            } else if (!ClientUiConfig.replaceContainerScreen()) {
                reason = "replaceContainerScreen=false";
            } else {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) {
                    reason = "无玩家上下文";
                } else if (!(s instanceof AbstractContainerScreen<?> acs)) {
                    reason = "非容器界面";
                } else {
                    try {
                        event.setNewScreen(new DnContainerScreen(acs.getMenu(), mc.player, s.getTitle()));
                        // 诊断日志：确认拦截生效（含大箱子/潜影盒的实际类名）
                        DeltaNexus.LOGGER.info("[DN] 容器界面已替换: {} -> DnContainerScreen",
                                s.getClass().getSimpleName());
                        return;
                    } catch (Exception e) {
                        DeltaNexus.LOGGER.warn("[DN] 容器界面替换失败，保持原版: {}", e.toString());
                        return;
                    }
                }
            }
            // 诊断日志：记录跳过原因（白名单/开关/上下文），便于排查「该替换未替换」类问题
            DeltaNexus.LOGGER.info("[DN] 容器界面保持原版：{}，{}",
                    s.getClass().getSimpleName(), reason);
        }
    }
}
