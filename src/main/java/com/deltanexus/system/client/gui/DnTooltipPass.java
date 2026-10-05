package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 统一提示层（0.5.0Beta）——在所有网格渲染之后画悬停提示，避免被跨格物品的大图标盖住。
 *
 * <p>绘制顺序（同一帧内）：</p>
 * <ol>
 *   <li>{@code GridClientRendering.onRenderPost}（HIGHEST）→ 网格背景墙与大图标；</li>
 *   <li>{@code GearWindowOverlay.onRender}（默认）→ 浮动装备窗口；</li>
 *   <li><b>本类（LOWEST）→ 提示框</b>。</li>
 * </ol>
 *
 * <p>提示本身还会在自己的 z 上再抬 1000（见各界面实现），因此即使某条链路仍在提示之后
 * 绘制，也不会反过来盖住提示。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID, value = Dist.CLIENT)
public final class DnTooltipPass {

    private DnTooltipPass() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        // 只有实现了提示层契约的界面才会走到这里（格子背包的类塔克夫界面）；
        // 不做功能开关/白名单判定——这些界面本来就不会在功能被禁用时出现，
        // 而多判一次只会让提示在极端配置下整片消失。
        if (event.getScreen() instanceof DnOverlayTooltips screen) {
            screen.dnRenderTooltips(event.getGuiGraphics(),
                    (int) event.getMouseX(), (int) event.getMouseY());
        }
    }
}
