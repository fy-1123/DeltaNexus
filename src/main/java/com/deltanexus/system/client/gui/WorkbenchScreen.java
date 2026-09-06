package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SCancelTaskPacket;
import com.deltanexus.system.network.packet.C2SClaimTaskPacket;
import com.deltanexus.system.network.packet.C2SOpenWarehousePacket;
import com.deltanexus.system.network.packet.C2SRequestWorkbenchDataPacket;
import com.deltanexus.system.network.packet.C2SStartTaskPacket;
import com.deltanexus.system.network.packet.SyncWorkbenchDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作台总览 UI（任务二）：占全屏，分成三部分竖列。
 *
 * <ul>
 *   <li>最左侧竖列：所有类型工作台（防具台/枪械台/…），滚轮滚动；</li>
 *   <li>中间偏左竖列：所选工作台的全部配方，滚轮滚动；</li>
 *   <li>右侧竖列：所选配方详细信息（输入/输出物品图标与数量展示）+ 三状态按钮（制造/制造中/领取）。</li>
 * </ul>
 *
 * <p>滚轮分区：只有鼠标所在竖列才滚动。</p>
 */
public class WorkbenchScreen extends Screen {

    /** 服务端总览数据（到达时缓存；屏幕已打开则直接刷新）。 */
    private static volatile SyncWorkbenchDataPacket data;

    /** 左列宽度（工作台列表）。 */
    private static final int LEFT_W = 170;
    /** 中列宽度（配方列表）。 */
    private static final int MID_W = 210;
    private static final int GAP = 6;
    private static final int ROW_H = 22;

    private int workbenchScroll = 0;
    private int recipeScroll = 0;
    private String selectedWorkbench = null;
    private String selectedRecipe = null;
    private Button startButton;
    private int refreshTimer = 0;  // 总览自动刷新计时（每秒请求一次，任务完成状态自动更新）

    public WorkbenchScreen() {
        super(Component.translatable("gui.dn.workbench_ui"));
    }

    /** 打开工作台总览（按键触发）：本地打开 + 向服务端请求数据。 */
    public static void open() {
        Minecraft.getInstance().setScreen(new WorkbenchScreen());
        PacketHandler.sendToServer(new C2SRequestWorkbenchDataPacket());
    }

    /** 服务端数据到达。 */
    public static void receiveData(SyncWorkbenchDataPacket packet) {
        data = packet;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof WorkbenchScreen s) {
            s.onData();
        }
    }

    private void onData() {
        if (data == null || data.workbenches.isEmpty()) {
            return;
        }
        // 默认选中第一个工作台 / 第一个配方
        if (selectedWorkbench == null) {
            selectedWorkbench = data.workbenches.get(0).id;
            List<SyncWorkbenchDataPacket.RecipeDetail> recipes = recipesOf(selectedWorkbench);
            if (!recipes.isEmpty()) {
                selectedRecipe = recipes.get(0).recipeId;
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        // 每 20 tick（1秒）请求一次总览数据：服务端 sendWorkbenchData 会先 updateTasks，
        // 任务完成后自动把「制作中」切到「领取」状态，无需重新打开 GUI。
        if (++refreshTimer >= 20) {
            refreshTimer = 0;
            PacketHandler.sendToServer(new C2SRequestWorkbenchDataPacket());
        }
    }

    private List<SyncWorkbenchDataPacket.RecipeDetail> recipesOf(String workbenchId) {
        List<SyncWorkbenchDataPacket.RecipeDetail> list = new ArrayList<>();
        if (data == null) {
            return list;
        }
        for (SyncWorkbenchDataPacket.RecipeDetail r : data.recipes) {
            if (r.workbenchId.equals(workbenchId)) {
                list.add(r);
            }
        }
        return list;
    }

    private SyncWorkbenchDataPacket.RecipeDetail selectedDetail() {
        if (data == null || selectedRecipe == null) {
            return null;
        }
        for (SyncWorkbenchDataPacket.RecipeDetail r : data.recipes) {
            if (r.recipeId.equals(selectedRecipe)) {
                return r;
            }
        }
        return null;
    }

    private String workbenchDisplay(String id) {
        if (data == null) {
            return id;
        }
        for (SyncWorkbenchDataPacket.WorkbenchInfo w : data.workbenches) {
            if (w.id.equals(id)) {
                return w.display;
            }
        }
        return id;
    }

    @Override
    protected void init() {
        super.init();
        // 顶栏：标题 + 打开仓库按钮
        int midX = GAP + LEFT_W + GAP + MID_W + GAP;
        startButton = Button.builder(Component.translatable("gui.dn.recipe.craft"),
                        b -> {
                            SyncWorkbenchDataPacket.RecipeDetail detail = selectedDetail();
                            if (detail == null) {
                                return;
                            }
                            if (detail.hasDone) {
                                // 领取
                                PacketHandler.sendToServer(new C2SClaimTaskPacket(detail.workbenchId, detail.claimTaskId));
                            } else if (detail.runningCount > 0 && detail.runningTaskId >= 0) {
                                // 制造中 -> 取消（退还材料）
                                PacketHandler.sendToServer(new C2SCancelTaskPacket(detail.workbenchId, detail.runningTaskId));
                            } else {
                                // 制造
                                PacketHandler.sendToServer(new C2SStartTaskPacket(detail.workbenchId, detail.recipeId));
                            }
                        })
                .bounds(midX + 6, height - 40, 100, 18)
                .build();
        addRenderableWidget(startButton);
        addRenderableWidget(Button.builder(Component.translatable("gui.dn.workbench_ui.warehouse"),
                        b -> PacketHandler.sendToServer(new C2SOpenWarehousePacket()))
                .bounds(GAP, height - 40, 110, 18)
                .build());
        onData();
    }

    /** 详情按钮三状态刷新：制造（绿）/ 制造中（灰禁）/ 领取（橙），制造中进度条在按钮上方。 */
    private void refreshStartButton() {
        if (startButton == null || data == null) {
            return;
        }
        SyncWorkbenchDataPacket.RecipeDetail detail = selectedDetail();
        if (detail == null) {
            startButton.active = false;
            startButton.setMessage(Component.translatable("gui.dn.recipe.craft"));
            return;
        }
        if (detail.hasDone) {
            // 领取
            startButton.active = true;
            startButton.setMessage(Component.translatable("gui.dn.claim"));
        } else if (detail.runningCount > 0) {
            // 制造中（可点击取消，悬浮提示「点击取消」）
            startButton.active = true;
            startButton.setMessage(Component.translatable("gui.dn.recipe.crafting"));
        } else {
            // 制造
            startButton.active = true;
            startButton.setMessage(Component.translatable("gui.dn.recipe.craft"));
        }
    }

    /** 详情按钮上方进度条（制造中时；连续条 + 渐变填充 + 百分比）。 */
    private void drawStartProgress(GuiGraphics gg, int rightX) {
        if (data == null) {
            return;
        }
        SyncWorkbenchDataPacket.RecipeDetail detail = selectedDetail();
        if (detail == null || detail.progress01 < 0F || detail.hasDone) {
            return;
        }
        int bx = rightX + 8;
        int by = height - 52;
        int bw = 100;
        int bh = 12;
        float p = Math.max(0F, Math.min(1F, detail.progress01));
        // 背景槽
        gg.fill(bx, by, bx + bw, by + bh, 0xFF2A2A30);
        // 边框
        gg.fill(bx, by, bx + bw, by + 1, 0xFF555560);
        gg.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFF555560);
        gg.fill(bx, by, bx + 1, by + bh, 0xFF555560);
        gg.fill(bx + bw - 1, by, bx + bw, by + bh, 0xFF555560);
        // 填充（进度推进色相：橙→黄→绿）
        int fillW = (int) (bw * p);
        if (fillW > 0) {
            int color = p >= 1F ? 0xFF66CC66 : (p >= 0.5F ? 0xFFFFCC44 : 0xFFFF8844);
            gg.fill(bx + 1, by + 1, bx + fillW, by + bh - 1, color);
        }
        // 百分比文字（居中）
        gg.drawCenteredString(font, (int) (p * 100) + "%", bx + bw / 2, by + 2, 0xFFFFFF);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        int midX = GAP + LEFT_W + GAP;
        int rightX = midX + MID_W + GAP;
        int colTop = 34;
        int colBottom = height - 40;
        // 悬停物品 tooltip（配方图标/详情输入输出，step3）
        ItemStack tip = ItemStack.EMPTY;
        int tipX = 0;
        int tipY = 0;

        // 顶栏
        gg.drawString(font, Component.translatable("gui.dn.workbench_ui"), GAP + 2, 8, 0xFFAA00);
        gg.drawString(font, Component.translatable("gui.dn.workbench_ui.hint"), GAP + 2, 20, 0x666666);

        // 左列：工作台
        drawPanel(gg, GAP, colTop, LEFT_W, colBottom - colTop);
        gg.drawString(font, Component.translatable("gui.dn.workbenches"), GAP + 4, colTop - 12, 0xFFAA00);
        if (data != null) {
            List<SyncWorkbenchDataPacket.WorkbenchInfo> wbs = data.workbenches;
            int visible = (colBottom - colTop) / ROW_H;
            int start = Math.max(0, Math.min(workbenchScroll, Math.max(0, wbs.size() - visible)));
            for (int i = 0; i < visible && start + i < wbs.size(); i++) {
                SyncWorkbenchDataPacket.WorkbenchInfo w = wbs.get(start + i);
                int y = colTop + i * ROW_H;
                boolean hover = inRegion(mouseX, mouseY, GAP + 2, y, LEFT_W - 4, ROW_H - 2);
                boolean selected = w.id.equals(selectedWorkbench);
                if (hover || selected) {
                    gg.fill(GAP + 2, y, GAP + LEFT_W - 2, y + ROW_H - 2, selected ? 0x66FF8800 : 0x33222222);
                }
                gg.drawString(font, truncate(w.display, 18), GAP + 8, y + 6, selected ? 0xFFAA33 : 0xFFFFFF);
            }
        }

        // 中列：配方
        drawPanel(gg, midX, colTop, MID_W, colBottom - colTop);
        String wbTitle = selectedWorkbench == null ? "" : workbenchDisplay(selectedWorkbench);
        gg.drawString(font, Component.translatable("gui.dn.workbench_ui.recipes", wbTitle), midX + 4, colTop - 12, 0xFFAA00);
        List<SyncWorkbenchDataPacket.RecipeDetail> recipes = selectedWorkbench == null ? List.of() : recipesOf(selectedWorkbench);
        int visible = (colBottom - colTop) / ROW_H;
        int start = Math.max(0, Math.min(recipeScroll, Math.max(0, recipes.size() - visible)));
        for (int i = 0; i < visible && start + i < recipes.size(); i++) {
            SyncWorkbenchDataPacket.RecipeDetail r = recipes.get(start + i);
            int y = colTop + i * ROW_H;
            boolean hover = inRegion(mouseX, mouseY, midX + 2, y, MID_W - 4, ROW_H - 2);
            boolean selected = r.recipeId.equals(selectedRecipe);
            if (hover || selected) {
                gg.fill(midX + 2, y, midX + MID_W - 2, y + ROW_H - 2, selected ? 0x66FF8800 : 0x33222222);
            }
            gg.renderItem(r.icon, midX + 4, y + 3);
            if (inRegion(mouseX, mouseY, midX + 4, y + 3, 16, 16)) {
                tip = r.icon;
                tipX = midX + 4;
                tipY = y + 3;
            }
            gg.drawString(font, truncate(r.displayName, 14), midX + 24, y + 3, selected ? 0xFFAA33 : 0xFFFFFF);
            String info = Component.translatable("gui.dn.recipe.info", r.baseDuration, r.requiredLevel).getString();
            gg.drawString(font, truncate(info, 10), midX + 24, y + 12, 0x88AAFF);
        }

        // 右列：详情
        drawPanel(gg, rightX, colTop, width - rightX - GAP, colBottom - colTop);
        gg.drawString(font, Component.translatable("gui.dn.workbench_ui.detail"), rightX + 4, colTop - 12, 0xFFAA00);
        SyncWorkbenchDataPacket.RecipeDetail detail = selectedDetail();
        if (detail != null) {
            int y = colTop + 6;
            gg.renderItem(detail.icon, rightX + 8, y);
            if (inRegion(mouseX, mouseY, rightX + 8, y, 16, 16)) {
                tip = detail.icon;
                tipX = rightX + 8;
                tipY = y;
            }
            gg.drawString(font, truncate(detail.displayName, 20), rightX + 30, y + 4, 0xFFFFFF);
            String info = Component.translatable("gui.dn.recipe.info", detail.baseDuration, detail.requiredLevel).getString();
            gg.drawString(font, truncate(info, 16), rightX + 30, y + 14, 0x88AAFF);
            y += 30;

            gg.drawString(font, Component.translatable("gui.dn.recipe.inputs"), rightX + 8, y, 0xFFAA00);
            y += 12;
            for (ItemStack s : detail.inputs) {
                gg.renderItem(s, rightX + 8, y);
                if (inRegion(mouseX, mouseY, rightX + 8, y, 16, 16)) {
                    tip = s;
                    tipX = rightX + 8;
                    tipY = y;
                }
                gg.drawString(font, "x" + s.getCount(), rightX + 28, y + 4, 0xFFFFFF);
                y += 20;
            }
            y += 6;
            gg.drawString(font, Component.translatable("gui.dn.recipe.outputs"), rightX + 8, y, 0xFFAA00);
            y += 12;
            for (ItemStack s : detail.outputs) {
                gg.renderItem(s, rightX + 8, y);
                if (inRegion(mouseX, mouseY, rightX + 8, y, 16, 16)) {
                    tip = s;
                    tipX = rightX + 8;
                    tipY = y;
                }
                gg.drawString(font, "x" + s.getCount(), rightX + 28, y + 4, 0xFFFFFF);
                y += 20;
            }
        } else if (data == null) {
            gg.drawString(font, Component.translatable("gui.dn.workbench_ui.loading"), rightX + 8, colTop + 10, 0x666666);
        }
        // 详情按钮进度条（制造中时显示在按钮上方）
        drawStartProgress(gg, rightX);
        refreshStartButton();
        super.render(gg, mouseX, mouseY, partialTick);
        // 制造中状态悬浮提示「点击取消」（复用上方已声明的 detail 变量）
        if (startButton != null && startButton.isHovered()
                && detail != null && !detail.hasDone && detail.runningCount > 0) {
            gg.renderTooltip(font, Component.translatable("gui.dn.recipe.cancel_hint"), mouseX, mouseY);
        }
        // 配方/物品图标悬停 tooltip（标准物品 tooltip：名称/数量/NBT）
        if (!tip.isEmpty()) {
            gg.renderTooltip(font, tip, tipX, tipY);
        }
    }

    /** 面板背景（深空灰半透明）。 */
    private void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, 0xCC14151C);
        gg.fill(x, y, x + w, y + 1, 0xFF3A3A4A);
        gg.fill(x, y + h - 1, x + w, y + h, 0xFF3A3A4A);
        gg.fill(x, y, x + 1, y + h, 0xFF3A3A4A);
        gg.fill(x + w - 1, y, x + w, y + h, 0xFF3A3A4A);
    }

    private static boolean inRegion(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 按钮优先（打开仓库 / 开始制造）：修复列点击区域误拦截按钮的问题
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            int midX = GAP + LEFT_W + GAP;
            int rightX = midX + MID_W + GAP;
            int colTop = 34;
            int colBottom = height - 40;
            // 左列：选工作台（按钮区 y=height-40 以下不拦截）
            if (data != null && mouseY < height - 40 && mouseX >= GAP && mouseX < GAP + LEFT_W) {
                List<SyncWorkbenchDataPacket.WorkbenchInfo> wbs = data.workbenches;
                int visible = (colBottom - colTop) / ROW_H;
                int start = Math.max(0, Math.min(workbenchScroll, Math.max(0, wbs.size() - visible)));
                for (int i = 0; i < visible && start + i < wbs.size(); i++) {
                    int y = colTop + i * ROW_H;
                    if (inRegion(mouseX, mouseY, GAP + 2, y, LEFT_W - 4, ROW_H - 2)) {
                        selectedWorkbench = wbs.get(start + i).id;
                        recipeScroll = 0;
                        List<SyncWorkbenchDataPacket.RecipeDetail> recipes = recipesOf(selectedWorkbench);
                        selectedRecipe = recipes.isEmpty() ? null : recipes.get(0).recipeId;
                        return true;
                    }
                }
                return true;
            }
            // 中列：选配方
            if (data != null && mouseY < height - 40 && mouseX >= midX && mouseX < midX + MID_W) {
                List<SyncWorkbenchDataPacket.RecipeDetail> recipes = selectedWorkbench == null ? List.of() : recipesOf(selectedWorkbench);
                int visible = (colBottom - colTop) / ROW_H;
                int start = Math.max(0, Math.min(recipeScroll, Math.max(0, recipes.size() - visible)));
                for (int i = 0; i < visible && start + i < recipes.size(); i++) {
                    int y = colTop + i * ROW_H;
                    if (inRegion(mouseX, mouseY, midX + 2, y, MID_W - 4, ROW_H - 2)) {
                        selectedRecipe = recipes.get(start + i).recipeId;
                        return true;
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int step = delta > 0 ? -1 : 1;
        int midX = GAP + LEFT_W + GAP;
        // 分区滚动：只有鼠标所在竖列才滚动
        if (mouseX >= GAP && mouseX < GAP + LEFT_W) {
            workbenchScroll = Math.max(0, workbenchScroll + step);
            return true;
        }
        if (mouseX >= midX && mouseX < midX + MID_W) {
            recipeScroll = Math.max(0, recipeScroll + step);
            return true;
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
