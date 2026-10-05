package com.deltanexus.system.grid.adapter;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.deltanexus.system.client.gui.DnUiLayout;
import com.deltanexus.system.grid.GridClassConfig;
import com.deltanexus.system.grid.GridSizes;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.grid.core.GridTags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * 网格渲染适配器（0.3.0Beta）：跨格物品与类色背景的绘制，<b>不反向依赖任何具体屏幕类</b>。
 *
 * <p>相比 0.2.x 的渲染遍历修正了两处不严谨：</p>
 * <ul>
 *   <li>跳过 {@code !slot.isActive()} 的槽位——解锁位图收缩后残留在隐藏槽里的占位物不再被画成白墙；</li>
 *   <li>去重键改为「容器身份 + 容器内索引」，不再用「菜单索引 + 9 列偏移」——
 *       旧键会让仓库视口最后一行的跨格物品把背包槽位误判为已渲染（偶发少画两格）。</li>
 * </ul>
 */
public final class GridRenderAdapter {

    private GridRenderAdapter() {
    }

    /** 渲染一件跨格物品：背景墙（按所属类着色）+ 缩放图标 + 数量角标。 */
    public static void renderGridStack(GuiGraphics gui, ItemStack stack, int x, int y,
                                       InventoryGridHandler.ItemDim dim, boolean rotated) {
        renderGridStack(gui, stack, x, y, dim, rotated, GridClassConfig.bgOf(stack));
    }

    /**
     * 渲染一件跨格物品（自定义背景色）。
     *
     * <p>坐标约定与 {@code DnUiTheme.drawRaritySlot} / 槽位命中测试一致：
     * {@code (x, y)} 是 16×16 物品内容原点，18×18 格框为 {@code [x-1, x+17]}。
     * 因此墙体从 {@code (x-1, y-1)} 起画，图标中心落在格框正中心。</p>
     */
    public static void renderGridStack(GuiGraphics gui, ItemStack stack, int x, int y,
                                       InventoryGridHandler.ItemDim dim, boolean rotated, int[] bg) {
        int cw = (dim.w() - 1) * DnUiLayout.SLOT_PITCH + DnUiLayout.SLOT_SIZE;
        int ch = (dim.h() - 1) * DnUiLayout.SLOT_PITCH + DnUiLayout.SLOT_SIZE;
        int gx = x - 1;
        int gy = y - 1;

        gui.pose().pushPose();
        gui.pose().translate(0, 0, 360);
        gui.fill(gx, gy, gx + cw, gy + ch, bg[1]);                       // 外衬
        gui.fill(gx + 1, gy + 1, gx + cw - 1, gy + ch - 1, bg[0]);       // 主墙
        int b = bg[2];
        gui.fill(gx, gy, gx + cw, gy + 1, b);                            // 上边框
        gui.fill(gx, gy + ch - 1, gx + cw, gy + ch, b);                  // 下边框
        gui.fill(gx, gy + 1, gx + 1, gy + ch - 1, b);                    // 左边框
        gui.fill(gx + cw - 1, gy + 1, gx + cw, gy + ch - 1, b);          // 右边框
        gui.pose().popPose();

        gui.pose().pushPose();
        gui.pose().translate(gx + cw / 2.0f, gy + ch / 2.0f, 400);
        float s = dim.is1x1() ? 1.0f : Math.min(cw / 16.0f, ch / 16.0f) * 0.85f;
        gui.pose().scale(s, s, 1.0f);
        if (rotated) {
            gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90));
        }
        gui.pose().translate(-8, -8, 0);
        RenderSystem.enableBlend();
        Lighting.setupForFlatItems();
        gui.renderFakeItem(stack, 0, 0);
        Lighting.setupFor3DItems();
        gui.pose().popPose();

        if (stack.getCount() > 1) {
            String txt = String.valueOf(stack.getCount());
            gui.pose().pushPose();
            gui.pose().translate(0, 0, 700);
            gui.drawString(Minecraft.getInstance().font, txt,
                    gx + cw - Minecraft.getInstance().font.width(txt) - 1, gy + ch - 9, 0xFFFFFFFF, true);
            gui.pose().popPose();
        }
    }

    /** 容器界面网格渲染（跨格物品与占位物白墙）。 */
    public static void renderSlots(GuiGraphics gui, AbstractContainerScreen<?> screen) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        boolean isCreative = screen instanceof CreativeModeInventoryScreen;
        Set<Long> rendered = new HashSet<>();

        // 0.3.0Beta 第二阶段：优先按服务端下发的布局渲染（唯一几何真相；覆盖格跳过逐槽绘制）
        Set<Integer> covered = java.util.Set.of();
        var anchors = com.deltanexus.system.client.GridLayoutClient.anchors();
        if (!anchors.isEmpty()) {
            covered = com.deltanexus.system.client.GridLayoutClient.coveredSlots();
            for (var entry : anchors.entrySet()) {
                int anchorSlot = entry.getKey();
                if (anchorSlot < 0 || anchorSlot >= screen.getMenu().slots.size()) {
                    continue;
                }
                Slot anchor = screen.getMenu().slots.get(anchorSlot);
                if (anchor == null || anchor.x < -500 || anchor.y < -500 || !anchor.isActive()) {
                    continue;
                }
                ItemStack stack = anchor.getItem();
                if (stack.isEmpty()) {
                    continue; // 布局与内容短暂不一致：等下一份布局/槽位同步
                }
                var layout = entry.getValue();
                renderGridStack(gui, stack, screen.getGuiLeft() + anchor.x, screen.getGuiTop() + anchor.y,
                        new InventoryGridHandler.ItemDim(layout.w(), layout.h()), layout.rotated());
            }
        }

        for (Slot slot : screen.getMenu().slots) {
            if (slot.x < -500 || slot.y < -500 || !slot.isActive()) {
                continue;
            }
            if (covered.contains(slot.index)) {
                continue; // 已由布局绘制
            }
            if (slot.container instanceof Inventory && slot.getContainerSlot() >= 36) {
                continue; // 盔甲/副手不参与网格
            }
            if (isCreative && !(slot.container instanceof Inventory)) {
                continue; // 创造模式物品标签页（非玩家存储）不做网格渲染
            }
            Object container = InventoryGridHandler.gridContainerOf(slot);
            long key = ((long) System.identityHashCode(container) << 20) + slot.getSlotIndex();
            if (!rendered.add(key)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            int x = screen.getGuiLeft() + slot.x;
            int y = screen.getGuiTop() + slot.y;
            if (GridTags.isSlave(stack)) {
                // 占位物：画白色半透明墙（铺满 18px 格框，主格会覆盖绘制完整大图标）
                gui.fill(x - 1, y - 1, x + 17, y + 17, 0xAAFFFFFF);
                continue;
            }
            InventoryGridHandler.ItemDim dim = InventoryGridHandler.getActualDim(stack, slot, false, player);
            // 问题3修复：只要服务端布局已到达，非“布局条目覆盖格”一律按 1x1 绘制——
            // 大图标只能来自服务端布局（否则“足迹放不下被降级为 1x1”的物品会被本地按配置尺寸画成 2x2，
            // 看起来就像几个小物品被“组合”成了一个大物品）。
            if (com.deltanexus.system.client.GridLayoutClient.hasLayout()) {
                dim = new InventoryGridHandler.ItemDim(1, 1);
            } else {
                int gw = InventoryGridHandler.gridWidth(player, container);
                if (gw > 0 && slot.getContainerSlot() % gw + dim.w() > gw) {
                    dim = new InventoryGridHandler.ItemDim(1, 1);
                }
            }
            boolean rotated = GridSizes.isRotated(stack);
            if (!dim.is1x1()) {
                renderGridStack(gui, stack, x, y, dim, rotated);
            } else if (GridClassConfig.isClassed(stack)) {
                // 1x1 物品走网格渲染（类色墙 + 图标 + 数量角标）
                renderGridStack(gui, stack, x, y, new InventoryGridHandler.ItemDim(1, 1), rotated,
                        GridClassConfig.bgOf(stack));
            }
        }
    }
}
