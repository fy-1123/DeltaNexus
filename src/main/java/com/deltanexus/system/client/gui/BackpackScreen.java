package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.ClientUiConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * dn 背包界面（0.5.0Beta 类塔克夫三列布局）：非白名单时替换原版背包界面（E 键）。
 *
 * <p>无右列，只有左列（盔甲 / 快捷栏 / 副手）与中列（口袋 / 胸挂 / 背包 / 安全箱）；
 * 布局、皮肤与交互全部来自 {@link DnInventoryScreen}。</p>
 *
 * <p>原版 27 格主背包已被胸挂 / 背包取代：合成格与被取代的 22 格移到屏外隐藏
 * （这些槽位另由 {@code SlotMixin} / {@code MenuMixin} 封死，屏外只是一种呈现）。</p>
 */
public class BackpackScreen extends DnInventoryScreen {

    public BackpackScreen(Player player) {
        // InventoryMenu 常驻（容器 id 0），直接复用，交互走标准容器协议
        // 注意：1.20.1 的 AbstractContainerMenu 无 getTitle()（1.20.3+ 才有），用 Inventory.getDisplayName()
        super(player.inventoryMenu, player.getInventory(), player.getInventory().getDisplayName());
    }

    /**
     * 背包界面替换拦截器：原版 InventoryScreen 打开时替换为本界面。
     *
     * <p>跳过条件（保持原版）：白名单包含 InventoryScreen、配置关闭替换、
     * 无玩家上下文（登录/断线等界面切换时序，NPE 预防）、创造模式玩家。</p>
     *
     * <p>创造模式修复：MC 1.20.1 按 E 统一打开 InventoryScreen
     * （{@code handleKeybinds} 无创造分支），其 {@code containerTick} 检测
     * {@code hasInfiniteItems()}（创造模式）后在一帧内自动切换为
     * CreativeModeInventoryScreen——InventoryScreen 只是创造界面的「跳板」。
     * 若在跳板上替换为 BackpackScreen，创造界面将永远无法出现。
     * 故创造模式玩家放行原版跳板，保持原版创造物品栏。</p>
     */
    @Mod.EventBusSubscriber(modid = DeltaNexus.MODID, value = Dist.CLIENT)
    public static final class Opener {

        private Opener() {
        }

        @SubscribeEvent
        public static void onScreenOpen(ScreenEvent.Opening event) {
            // 精确类匹配：CreativeModeInventoryScreen 继承 InventoryScreen，
            // instanceof 会误拦截创造模式界面（创造界面必须保持原版）
            if (event.getNewScreen() == null
                    || event.getNewScreen().getClass() != InventoryScreen.class) {
                return;
            }
            String reason = null;
            // 玩家功能被禁用（featuresEnabled=false）→ 所有界面恢复原版
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