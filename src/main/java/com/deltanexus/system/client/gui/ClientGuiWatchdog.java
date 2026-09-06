package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.ClientUiConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端界面替换看门狗（2.0.10）：Tick 级兜底，与 Opener（ScreenEvent.Opening）
 * 构成双保险。
 *
 * <p>背景：ScreenEvent.Opening 在部分 Forge 补丁版本上存在未触发/触发异常的
 * 情形（日志证实 Opener 注册成功却从未收到事件）。看门狗在每个客户端 tick
 * 结束时检查当前 Screen：若为应替换的原版界面而实际未被替换，则强制替换。
 * 代价最多是一帧闪现，换来替换逻辑 100% 可靠。</p>
 *
 * <p>幂等性：替换后 Screen 不再匹配目标类，天然不会重复触发。
 * 创造模式跳板（InventoryScreen.containerTick 自动切创造界面）先于本事件
 * 执行，但仍保留 hasInfiniteItems 判定防御切换延迟。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID, value = Dist.CLIENT)
public final class ClientGuiWatchdog {

    private ClientGuiWatchdog() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        if (s == null || mc.player == null) {
            return;
        }
        try {
            // 2.1：玩家功能被禁用（featuresEnabled=false）→ 所有界面恢复原版，不做替换
            if (!ClientUiConfig.featuresEnabled()) {
                return;
            }
            // 背包兜底：原版背包实际显示且未被替换 → 强制替换（精确匹配排除创造子类）
            if (s.getClass() == InventoryScreen.class
                    && ClientUiConfig.replaceInventoryScreen()
                    && !ClientUiConfig.isVanillaUi(InventoryScreen.class)
                    && (mc.gameMode == null || !mc.gameMode.hasInfiniteItems())) {
                mc.setScreen(new BackpackScreen(mc.player));
                return;
            }
            // 容器兜底：原版纯槽位容器实际显示且未被替换 → 强制替换
            if (s instanceof ContainerScreen || s instanceof ShulkerBoxScreen
                    || s instanceof DispenserScreen || s instanceof HopperScreen) {
                if (!ClientUiConfig.replaceContainerScreen()
                        || ClientUiConfig.isVanillaUi(s.getClass())) {
                    return;
                }
                if (s instanceof AbstractContainerScreen<?> acs) {
                    mc.setScreen(new DnContainerScreen(acs.getMenu(), mc.player, s.getTitle()));
                }
            }
        } catch (Exception e) {
            // 预防：兜底替换异常时保持原版界面，不阻断游戏
            DeltaNexus.LOGGER.warn("[DN] 界面兜底替换失败，保持原版: {}", e.toString());
        }
    }
}
