package com.deltanexus.system.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 「提示层」契约（0.5.0Beta 修复「物品栏物品的提示框有时空白」）。
 *
 * <p>格子界面里有三类绘制者会互相遮挡：</p>
 * <ol>
 *   <li>原版槽位在 {@code AbstractContainerScreen#render} 末尾就地画悬停提示（z≈400）；</li>
 *   <li>网格渲染（{@code GridRenderAdapter}，{@code ScreenEvent.Render.Post} 最高优先级）
 *       在提示<b>之后</b>绘制跨格物品：类色背景墙 z≈360、放大图标 z≈550；
 *   </li>
 *   <li>格子界面自绘的胸挂 / 背包 / 安全箱网格（z≈550）。</li>
 * </ol>
 *
 * <p>于是「提示框被物品图标盖住 → 看起来提示是空的」。修法是统一的：界面<b>不再</b>在
 * 原版时机画提示，而是实现本接口，由 {@code DnTooltipPass} 在<b>所有网格绘制之后</b>
 * （{@code ScreenEvent.Render.Post} 最低优先级）调用一次，并把 z 抬到所有网格内容之上。</p>
 */
public interface DnOverlayTooltips {

    /**
     * 绘制本界面全部悬停提示（必须在所有网格物品绘制之后调用）。
     *
     * @param mouseX 屏幕 GUI 坐标（未经界面缩放）
     * @param mouseY 屏幕 GUI 坐标（未经界面缩放）
     */
    void dnRenderTooltips(GuiGraphics gg, int mouseX, int mouseY);
}
