package com.deltanexus.system.grid;

import com.deltanexus.system.DeltaNexus;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;

/**
 * 格式背包客户端渲染与输入（2.0.7Alpha 拆分）。
 *
 * <p>原 {@link InventoryGridHandler} 将服务端网格事件与客户端渲染事件混在同一
 * {@code @Mod.EventBusSubscriber} 类中，参数类型（{@code ScreenEvent.*} 传递依赖
 * {@code Screen}）导致 Mod 在专用服务器（含 Mohist 混合服务端）加载时触发
 * "Attempted to load class ... for invalid dist DEDICATED_SERVER" 崩溃。
 * 本类以 {@code value = Dist.CLIENT} 限定，服务器侧完全不加载。</p>
 *
 * <p>职责：跨格物品/类色背景渲染（{@link #renderGridStack}）、容器界面网格渲染
 * （{@link #onRenderPost}）、占位物格点击捡起（{@link #onMousePress}）、
 * R 键旋转光标物品（{@link #onKey}）。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID, value = Dist.CLIENT)
public final class GridClientRendering {

    private GridClientRendering() {
    }

    /** 渲染一件跨格物品：背景墙（按所属类着色，2.0.3Alpha）+ 缩放图标 + 数量角标（容器界面与安全箱覆盖层共用）。 */
    public static void renderGridStack(GuiGraphics gui, ItemStack stack, int x, int y,
                                       InventoryGridHandler.ItemDim dim, boolean rotated) {
        int[] bg = GridClassConfig.bgOf(stack);
        renderGridStack(gui, stack, x, y, dim, rotated, bg);
    }

    /** 渲染一件跨格物品（自定义背景色）。 */
    public static void renderGridStack(GuiGraphics gui, ItemStack stack, int x, int y,
                                       InventoryGridHandler.ItemDim dim, boolean rotated, int[] bg) {
        // 2.0.10Alpha：背景墙覆盖完整格足迹（每格 18px），与槽位底图严格对齐——
        // 旧实现 tw=16+(w-1)*18 少算了 2px，再整体左移 1px，导致右/下边缘出现 1px 缝隙
        int cw = dim.w() * 18;
        int ch = dim.h() * 18;

        // 绘制一大坨背景墙（底色按物品所属类，2.0.3Alpha）
        gui.pose().pushPose(); gui.pose().translate(0, 0, 360);
        gui.fill(x, y, x + cw, y + ch, bg[1]);                       // 外衬
        gui.fill(x + 1, y + 1, x + cw - 1, y + ch - 1, bg[0]);       // 主墙
        int b = bg[2];
        gui.fill(x, y, x + cw, y + 1, b);             // 上边框
        gui.fill(x, y + ch - 1, x + cw, y + ch, b);   // 下边框
        gui.fill(x, y + 1, x + 1, y + ch - 1, b);     // 左边框
        gui.fill(x + cw - 1, y + 1, x + cw, y + ch - 1, b);          // 右边框
        gui.pose().popPose();

        // 绘制超大图标（1x1 保持原尺寸，跨格物品才缩放 0.85，居中于完整格足迹）
        gui.pose().pushPose();
        gui.pose().translate(x + cw / 2.0f, y + ch / 2.0f, 400);
        float s = dim.is1x1() ? 1.0f : Math.min(cw / 16.0f, ch / 16.0f) * 0.85f;
        gui.pose().scale(s, s, 1.0f);
        if (rotated) gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90));
        gui.pose().translate(-8, -8, 0);
        RenderSystem.enableBlend(); Lighting.setupForFlatItems();
        gui.renderFakeItem(stack, 0, 0); Lighting.setupFor3DItems();
        gui.pose().popPose();

        // 数量角标（右下角，贴合完整格足迹边缘）
        if (stack.getCount() > 1) {
            String txt = String.valueOf(stack.getCount());
            gui.pose().pushPose(); gui.pose().translate(0, 0, 700);
            gui.drawString(Minecraft.getInstance().font, txt, x + cw - Minecraft.getInstance().font.width(txt) - 1, y + ch - 9, 0xFFFFFFFF, true);
            gui.pose().popPose();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        // 2.1Alpha：玩家功能被禁用 → 不做网格渲染（界面恢复原版）
        if (!com.deltanexus.system.config.ClientUiConfig.featuresEnabled()) return;
        // 白名单界面（2.0.8Alpha）：保持原版 GUI 样式与交互，不做网格渲染
        if (com.deltanexus.system.config.ClientUiConfig.isVanillaUi(screen.getClass())) return;
        // 2.0.10Alpha 创造模式修复：创造玩家按 E 时 InventoryScreen 是跳板帧
        // （containerTick 随后自动切换为 CreativeModeInventoryScreen），跳板帧不渲染网格，避免闪现；
        // 创造玩家打开的其他容器界面（仓库等）不受影响
        Minecraft mc0 = Minecraft.getInstance();
        if (screen.getClass() == net.minecraft.client.gui.screens.inventory.InventoryScreen.class
                && mc0.gameMode != null && mc0.gameMode.hasInfiniteItems()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        GuiGraphics gui = event.getGuiGraphics();
        boolean isCreative = screen instanceof CreativeModeInventoryScreen;
        Set<Integer> renderedArea = new HashSet<>();

        for (Slot slot : screen.getMenu().slots) {
            // 屏外隐藏槽位（2.0.8Alpha：背包界面 2x2 合成格/结果格移至屏外）不渲染
            if (slot.x < -500 || slot.y < -500) continue;
            if (renderedArea.contains(slot.index)) continue;
            if (isCreative || (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.getContainerSlot() >= 36)) continue;
            ItemStack stack = slot.getItem();
            int x = screen.getGuiLeft() + slot.x, y = screen.getGuiTop() + slot.y;
            if (InventoryGridHandler.isSlave(stack)) {
                renderedArea.add(slot.index);
                gui.pose().pushPose(); gui.pose().translate(0, 0, 350);
                gui.fill(x, y, x + 16, y + 16, 0xAAFFFFFF); gui.pose().popPose();
                continue;
            }

            if (!stack.isEmpty()) {
                InventoryGridHandler.ItemDim dim = InventoryGridHandler.getActualDim(stack, slot, false, player);
                boolean rotated = stack.hasTag() && stack.getTag().getBoolean(InventoryGridHandler.IS_ROTATED);

                // 如果物品太宽超过容器边框，回退到 1x1 渲染
                int gw = InventoryGridHandler.gridWidth(player, InventoryGridHandler.gridContainerOf(slot));
                if (slot.getContainerSlot() % gw + dim.w() > gw) dim = new InventoryGridHandler.ItemDim(1, 1);

                if (!dim.is1x1()) {
                    for (int dx = 0; dx < dim.w(); dx++) {
                        for (int dy = 0; dy < dim.h(); dy++) {
                            renderedArea.add(slot.index + dy * gw + dx);
                        }
                    }
                    renderGridStack(gui, stack, x, y, dim, rotated);
                } else {
                    // 2.0.5Alpha：1x1 物品统一走网格渲染（类色墙 + 图标 + 数量角标）
                    // 2.0.7Alpha：显式 isClassed 判定（不再依赖 bgOf 数组引用比较）
                    if (GridClassConfig.isClassed(stack)) {
                        renderGridStack(gui, stack, x, y, new InventoryGridHandler.ItemDim(1, 1), rotated, GridClassConfig.bgOf(stack));
                    }
                }
            }
        }
    }

    /**
     * 2.0.3Alpha：点击跨格物品的非左上角格（占位物格）时，拦截原版点击，
     * 发送 {@code C2SPickupGridStackPacket} 由服务端处理。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMousePress(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        // 0.2.1Beta：仓库出售模式冻结物品——跨格「非左上角捡起」也不得生效
        if (screen instanceof com.deltanexus.system.client.gui.WarehouseScreen ws && ws.isSellMode()) {
            return;
        }
        // 2.1Alpha：玩家功能被禁用 → 不拦截点击（界面恢复原版）
        if (!com.deltanexus.system.config.ClientUiConfig.featuresEnabled()) {
            return;
        }
        // 白名单界面（2.0.8Alpha）：保持原版交互逻辑
        if (com.deltanexus.system.config.ClientUiConfig.isVanillaUi(screen.getClass())) {
            return;
        }
        double mx = event.getMouseX();
        double my = event.getMouseY();
        for (Slot slot : screen.getMenu().slots) {
            // 屏外隐藏槽位不可点击（正常鼠标坐标不可能命中，防御性跳过）
            if (slot.x < -500 || slot.y < -500) continue;
            int x = screen.getGuiLeft() + slot.x;
            int y = screen.getGuiTop() + slot.y;
            if (mx >= x && mx < x + 18 && my >= y && my < y + 18) {
                if (InventoryGridHandler.isSlave(slot.getItem())) {
                    com.deltanexus.system.network.PacketHandler.sendToServer(
                            new com.deltanexus.system.network.packet.C2SPickupGridStackPacket(slot.index));
                    Minecraft.getInstance().player.playSound(SoundEvents.UI_BUTTON_CLICK.get(), 0.4f, 1.1f);
                    event.setCanceled(true);
                }
                return;
            }
        }
    }

    /**
     * 2.0.5Alpha：旋转光标物品（R 键，可绑定按键）。使用屏幕键盘事件 + KeyMapping 匹配，
     * 保证在容器界面内可靠触发（tick 通道在部分环境不可靠）。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        // 0.2.1Beta：仓库出售模式冻结物品——光标物品旋转也不得生效
        if (screen instanceof com.deltanexus.system.client.gui.WarehouseScreen ws && ws.isSellMode()) {
            return;
        }
        // 2.1Alpha：玩家功能被禁用 → 不拦截按键（界面恢复原版）
        if (!com.deltanexus.system.config.ClientUiConfig.featuresEnabled()) {
            return;
        }
        // 白名单界面（2.0.8Alpha）：保持原版交互逻辑（不拦截按键）
        if (com.deltanexus.system.config.ClientUiConfig.isVanillaUi(screen.getClass())) {
            return;
        }
        if (!com.deltanexus.system.client.KeyBindings.ROTATE_ITEM.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }
        ItemStack carried = screen.getMenu().getCarried();
        if (!carried.isEmpty()) {
            com.deltanexus.system.network.PacketHandler.sendToServer(new com.deltanexus.system.grid.network.RotationPacket());
            Minecraft.getInstance().player.playSound(SoundEvents.UI_BUTTON_CLICK.get(), 0.4f, 1.1f);
            event.setCanceled(true);
        }
    }
}
