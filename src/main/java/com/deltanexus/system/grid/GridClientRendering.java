package com.deltanexus.system.grid;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.grid.adapter.GridRenderAdapter;
import com.deltanexus.system.grid.adapter.InputGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 格式背包客户端渲染与输入（0.3.0Beta：改为薄壳，逻辑在 {@code grid.adapter}）。
 *
 * <p>本类以 {@code value = Dist.CLIENT} 限定，专用服务器（含 Mohist）完全不加载；
 * 具体渲染见 {@link GridRenderAdapter}，交互门闸见 {@link InputGate}——
 * grid 包不再引用任何具体屏幕类（不再反向依赖 {@code WarehouseScreen}）。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID, value = Dist.CLIENT)
public final class GridClientRendering {

    private GridClientRendering() {
    }

    /** 渲染一件跨格物品（容器界面与安全箱覆盖层共用）。 */
    public static void renderGridStack(GuiGraphics gui, ItemStack stack, int x, int y,
                                       InventoryGridHandler.ItemDim dim, boolean rotated) {
        GridRenderAdapter.renderGridStack(gui, stack, x, y, dim, rotated);
    }

    /** 渲染一件跨格物品（自定义背景色）。 */
    public static void renderGridStack(GuiGraphics gui, ItemStack stack, int x, int y,
                                       InventoryGridHandler.ItemDim dim, boolean rotated, int[] bg) {
        GridRenderAdapter.renderGridStack(gui, stack, x, y, dim, rotated, bg);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!InputGate.clientGridInteractive(screen)) {
            return;
        }
        // 创造模式按 E 时 InventoryScreen 是跳板帧（随后切 CreativeModeInventoryScreen），跳板帧不渲染
        Minecraft mc = Minecraft.getInstance();
        if (screen.getClass() == net.minecraft.client.gui.screens.inventory.InventoryScreen.class
                && mc.gameMode != null && mc.gameMode.hasInfiniteItems()) {
            return;
        }
        GridRenderAdapter.renderSlots(event.getGuiGraphics(), screen);
        // 问题5修复：出售高亮画在网格渲染<b>之后</b>，否则会被跨格物品的 class 背景墙盖住
        if (screen instanceof com.deltanexus.system.client.gui.WarehouseScreen ws) {
            ws.renderSellHighlight(event.getGuiGraphics());
        }
    }

    /**
     * R 键旋转光标物品（可绑定按键）。
     *
     * <p>0.3.0Beta：占位物格的点击不再由客户端发包（改由菜单/屏幕入口统一重定向到主格），
     * 因此原来的 {@code onMousePress} 拦截已删除；这里只保留旋转。</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!InputGate.clientGridInteractive(screen)) {
            return;
        }
        // 出售模式等冻结状态：物品完全冻结（含旋转）
        if (InputGate.clientFrozen()) {
            return;
        }
        if (!com.deltanexus.system.client.KeyBindings.ROTATE_ITEM.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }
        ItemStack carried = screen.getMenu().getCarried();
        if (!carried.isEmpty()) {
            com.deltanexus.system.network.PacketHandler.sendToServer(
                    new com.deltanexus.system.grid.network.RotationPacket());
            Minecraft.getInstance().player.playSound(SoundEvents.UI_BUTTON_CLICK.get(), 0.4f, 1.1f);
            event.setCanceled(true);
        }
    }
}
