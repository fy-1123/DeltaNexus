package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.ClientUiConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;
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
 * dn 通用容器界面（0.5.0Beta 类塔克夫三列布局）：箱子/木桶/潜影盒/发射器/漏斗的皮肤。
 *
 * <p>完全复用原版容器 Menu（服务端逻辑、容器 id、点击协议零改动），只重排槽位坐标：</p>
 * <ul>
 *   <li>左列 —— 盔甲 4 / 快捷栏 9 / 副手 1（本模式菜单里没有盔甲与副手槽，
 *       因此自绘并走覆盖槽通道，与原版箱子界面相比是纯增益）；</li>
 *   <li>中列 —— 口袋 5 / 胸挂 / 背包 / 安全箱；</li>
 *   <li>右列 —— 容器网格（列数由原版槽位坐标自动推断：箱子 9 / 发射器 3 / 漏斗 5）。</li>
 * </ul>
 *
 * <p>白名单（双端并集）命中的界面类保持原版；{@code replaceContainerScreen=false} 全局关闭。
 * 创造模式玩家开箱同样替换（容器界面无 InventoryScreen 跳板问题）。</p>
 */
public class DnContainerScreen extends DnInventoryScreen {

    private final int columns;

    public DnContainerScreen(AbstractContainerMenu menu, Player player, Component title, int columns) {
        super(menu, player.getInventory(), title);
        new Throwable("[DN] DnContainerScreen 构造").printStackTrace();
        this.columns = Math.max(1, columns);
    }

    /** 由原版容器界面构造替换界面（拦截器与兜底替换共用；列数自动推断）。 */
    public static DnContainerScreen of(AbstractContainerScreen<?> source, Player player) {
        new Throwable("[DN] 谁替换了容器界面").printStackTrace();
        AbstractContainerMenu menu = source.getMenu();
        return new DnContainerScreen(menu, player, source.getTitle(), detectColumns(menu, player));
    }

    /** 容器列数：原版槽位坐标里不同 x 的个数（箱子 9 / 发射器 3 / 漏斗 5）。 */
    private static int detectColumns(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null) {
            return 9;
        }
        TreeSet<Integer> xs = new TreeSet<>();
        for (Slot slot : menu.slots) {
            if (slot != null && slot.container != player.getInventory()) {
                xs.add(slot.x);
            }
        }
        return xs.isEmpty() ? 9 : xs.size();
    }

    /** 容器槽位 = 不属于玩家背包的菜单槽（原版容器菜单把它们排在最前面）。 */
    @Override
    protected List<Integer> rightColumnSlots() {
        AbstractContainerMenu m = this.menu;
        if (m == null) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < m.slots.size(); i++) {
            Slot s = m.slots.get(i);
            if (s != null && s.container != playerInv) {
                out.add(i);
            }
        }
        return out;
    }

    @Override
    protected int rightColumnColumns() {
        return columns;
    }

    /**
     * 容器界面替换拦截器：原版纯槽位容器界面打开时替换为 dn 三列布局。
     *
     * <p>替换范围（1.20.1 类名）：箱子/木桶（ContainerScreen，含大箱子——与单箱同类
     * 无法区分）/潜影盒（ShulkerBoxScreen，直接继承 AbstractContainerScreen，
     * 非 ContainerScreen 子类）/发射器与投掷器（DispenserScreen）/
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
            // 玩家功能被禁用（featuresEnabled=false）→ 所有界面恢复原版
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
                } else if (mc.gameMode != null && mc.gameMode.hasInfiniteItems()) {
                    // 创造模式：与原版 containerTick 判定一致（hasInfiniteItems），
                    // 放行原版容器界面
                    reason = "创造模式，保持原版容器界面";
                } else if (!(s instanceof AbstractContainerScreen<?> acs)) {
                    reason = "非容器界面";
                } else {
                    try {
                        event.setNewScreen(DnContainerScreen.of(acs, mc.player));
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