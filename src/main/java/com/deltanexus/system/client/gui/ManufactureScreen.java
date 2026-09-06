package com.deltanexus.system.client.gui;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SClaimTaskPacket;
import com.deltanexus.system.network.packet.C2SRefreshTasksPacket;
import com.deltanexus.system.network.packet.C2SStartTaskPacket;
import com.deltanexus.system.network.packet.SyncManufacturePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 制造台 GUI（阶段 3，工作台动态化；256 宽布局：左配方区 / 右任务区，按钮完整显示）。
 *
 * <p>被动计算 + 本地假进度：服务端仅同步「taskId + 剩余秒数 + 总秒数」，
 * 客户端用 {@code remaining = syncedRemainingMs - (now - syncTime)} 本地插值渲染；
 * 进度条为 5 段「分段亮起」效果（每段 20%，阶段 5）。</p>
 */
public class ManufactureScreen extends Screen {

    private static final int GUI_WIDTH = 256;
    private static final int GUI_HEIGHT = 240;
    /** 左侧配方区宽度（右侧任务区从 x=130 起）。 */
    private static final int RECIPE_AREA = 122;

    private static final ResourceLocation BG = ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "textures/gui/manufacture_gui.png");
    private static final ResourceLocation PROGRESS_ON = ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "textures/gui/progress_on.png");
    private static final ResourceLocation PROGRESS_OFF = ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "textures/gui/progress_off.png");
    private static final ResourceLocation BTN = ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "textures/gui/button.png");

    /** 待打开的制造台数据（SyncManufacturePacket 先于 OpenScreenPacket 到达时暂存）。 */
    private static final Map<String, SyncManufacturePacket> PENDING = new HashMap<>();

    /** 客户端本地任务进度（插值用，按钮状态自动切换依赖本地时间）。 */
    private static class LocalTask {
        final int taskId;
        final String recipeId;
        final boolean completed;
        final long localEndTimeMs;
        final long totalMs;

        LocalTask(int taskId, String recipeId, boolean completed, int remainingSeconds, int totalSeconds) {
            this.taskId = taskId;
            this.recipeId = recipeId;
            this.completed = completed;
            long now = System.currentTimeMillis();
            this.localEndTimeMs = now + remainingSeconds * 1000L;
            this.totalMs = Math.max(1L, totalSeconds * 1000L);
        }

        long remainingMs() {
            return Math.max(0, localEndTimeMs - System.currentTimeMillis());
        }

        float progress() {
            return 1.0F - (float) remainingMs() / (float) totalMs;
        }

        /** 本地判定完成：服务端标记完成或本地时间已到（按钮自动切到「领取」，服务端校验兜底）。 */
        boolean isDone() {
            return completed || remainingMs() <= 0;
        }
    }

    private final String workbenchId;
    private final String workbenchDisplay;
    private final boolean isAdmin;
    private SyncManufacturePacket sync;
    private final List<LocalTask> localTasks = new ArrayList<>();
    private int recipeScroll = 0;
    private int taskScroll = 0;
    private Button refreshButton;

    private ManufactureScreen(String workbenchId, String workbenchDisplay, SyncManufacturePacket initial, boolean isAdmin) {
        super(Component.literal(workbenchDisplay == null || workbenchDisplay.isEmpty() ? workbenchId : workbenchDisplay));
        this.workbenchId = workbenchId;
        this.workbenchDisplay = workbenchDisplay;
        this.sync = initial;
        this.isAdmin = isAdmin;
    }

    /** 收到同步包：屏幕已打开则直接刷新，否则暂存待打开。 */
    public static void receiveSync(SyncManufacturePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ManufactureScreen s && s.workbenchId.equals(packet.workbenchId)) {
            s.applySync(packet);
        } else {
            PENDING.put(packet.workbenchId, packet);
        }
    }

    /** 打开制造台（OpenScreenPacket 处理）。 */
    public static void open(String workbenchId, String workbenchDisplay, boolean isAdmin) {
        SyncManufacturePacket pending = PENDING.remove(workbenchId);
        Minecraft.getInstance().setScreen(new ManufactureScreen(workbenchId, workbenchDisplay, pending, isAdmin));
    }

    private void applySync(SyncManufacturePacket packet) {
        this.sync = packet;
        this.localTasks.clear();
        for (SyncManufacturePacket.TaskInfo t : packet.tasks) {
            localTasks.add(new LocalTask(t.taskId, t.recipeId, t.completed, t.remainingSeconds, t.totalSeconds));
        }
    }

    private int guiLeft() {
        return (width - GUI_WIDTH) / 2;
    }

    private int guiTop() {
        return (height - GUI_HEIGHT) / 2;
    }

    @Override
    protected void init() {
        super.init();
        refreshButton = Button.builder(Component.translatable("gui.dn.refresh"),
                        b -> PacketHandler.sendToServer(new C2SRefreshTasksPacket(workbenchId)))
                .bounds(guiLeft() + GUI_WIDTH - 44, 4, 36, 12)
                .build();
        addRenderableWidget(refreshButton);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        int left = guiLeft();
        int top = guiTop();
        gg.blit(BG, left, top, 0, 0, GUI_WIDTH, GUI_HEIGHT, 256, 256);

        // 标题（工作台显示名）+ 队列状态
        gg.drawString(font, Component.literal(workbenchDisplay == null || workbenchDisplay.isEmpty() ? workbenchId : workbenchDisplay),
                left + 8, top + 6, 0xE0E0E0);
        if (sync != null) {
            gg.drawString(font, Component.translatable("gui.dn.queue_status", sync.queueSize, sync.maxQueueSize),
                    left + 70, top + 6, 0x88AAFF);
        }

        // 配方列表（左侧区，行高 28：制造中时顶部显示进度条，按钮在行右侧）
        gg.drawString(font, Component.translatable("gui.dn.recipes"), left + 8, top + 22, 0xFFAA00);
        List<SyncManufacturePacket.RecipeInfo> recipes = sync != null ? sync.recipes : List.of();
        int visibleRecipes = 6;
        int rStart = Math.max(0, Math.min(recipeScroll, Math.max(0, recipes.size() - visibleRecipes)));
        for (int i = 0; i < visibleRecipes; i++) {
            int idx = rStart + i;
            if (idx >= recipes.size()) {
                break;
            }
            SyncManufacturePacket.RecipeInfo r = recipes.get(idx);
            int rowY = top + 34 + i * 28;
            drawRecipeRow(gg, r, left + 8, rowY, mouseX, mouseY);
        }

        // 任务队列（右侧区，x 130-248）
        gg.drawString(font, Component.translatable("gui.dn.tasks"), left + 130, top + 22, 0xFFAA00);
        int visibleTasks = 5;
        int tStart = Math.max(0, Math.min(taskScroll, Math.max(0, localTasks.size() - visibleTasks)));
        for (int i = 0; i < visibleTasks; i++) {
            int idx = tStart + i;
            if (idx >= localTasks.size()) {
                break;
            }
            LocalTask t = localTasks.get(idx);
            int rowY = top + 34 + i * 36;
            drawTaskRow(gg, t, left + 130, rowY, mouseX, mouseY);
        }

        // 底部信息
        if (sync != null) {
            gg.drawString(font, Component.translatable("gui.dn.warehouse_level", sync.warehouseLevel),
                    left + 8, top + 226, 0x88AAFF);
        }
        super.render(gg, mouseX, mouseY, partialTick);
    }

    /** 配方行按钮三状态：制造（绿）/ 制造中（灰，禁点，顶部进度条）/ 领取（橙）。
     *  状态与 max_parallel 联动：进行中数量达到上限才禁点；本地时间到自动切「领取」。 */
    private void drawRecipeRow(GuiGraphics gg, SyncManufacturePacket.RecipeInfo r, int x, int y,
                               int mouseX, int mouseY) {
        gg.renderItem(r.icon, x, y + 4);
        // 图标悬停：标准物品 tooltip（名称/数量/NBT）
        if (isHovered(x, y + 4, 16, 16, mouseX, mouseY) && !r.icon.isEmpty()) {
            gg.renderTooltip(font, r.icon, x, y + 4);
        }
        gg.drawString(font, truncate(r.displayName, 9), x + 20, y + 5, 0xFFFFFF);
        String info = Component.translatable("gui.dn.recipe.info", r.baseDuration, r.requiredLevel).getString();
        gg.drawString(font, truncate(info, 11), x + 20, y + 14, 0x88AAFF);

        int bx = x + 80;
        int by = y + 6;
        int bw = 32;
        int bh = 14;
        LocalTask running = firstRunning(r.recipeId);
        LocalTask done = firstDone(r.recipeId);
        int runningCount = runningCountOf(r.recipeId);
        boolean hover = isHovered(bx, by, bw, bh, mouseX, mouseY);

        if (done != null) {
            // 领取（橙）——优先展示
            gg.blit(BTN, bx, by, 0, 0, bw, bh, 162, 18);
            gg.fill(bx, by, bx + bw, by + bh, hover ? 0xAAFF8800 : 0x88CC6600);
            gg.drawString(font, Component.translatable("gui.dn.claim"),
                    bx + 5, by + 3, hover ? 0x111111 : 0x222222);
        } else if (running != null && runningCount >= r.maxParallel) {
            // 制造中（灰禁点）：进度条在按钮上方（本地插值实时推进）
            int lit = Math.max(0, Math.min(5, (int) Math.ceil(running.progress() * 5)));
            for (int seg = 0; seg < 5; seg++) {
                ResourceLocation tex = seg < lit ? PROGRESS_ON : PROGRESS_OFF;
                gg.blit(tex, bx - 2 + seg * 11, y - 3, 0, 0, 10, 10, 10, 10);
            }
            gg.blit(BTN, bx, by, 0, 0, bw, bh, 162, 18);
            gg.fill(bx, by, bx + bw, by + bh, 0xAA555555);
            gg.drawString(font, Component.translatable("gui.dn.recipe.crafting"),
                    bx + 4, by + 3, 0xAAAAAA);
        } else {
            // 制造（绿）：进行中未满上限时仍可继续制造
            if (running != null) {
                // 已有进行中任务：进度条提示
                int lit = Math.max(0, Math.min(5, (int) Math.ceil(running.progress() * 5)));
                for (int seg = 0; seg < 5; seg++) {
                    ResourceLocation tex = seg < lit ? PROGRESS_ON : PROGRESS_OFF;
                    gg.blit(tex, bx - 2 + seg * 11, y - 3, 0, 0, 10, 10, 10, 10);
                }
            }
            gg.blit(BTN, bx, by, 0, 0, bw, bh, 162, 18);
            gg.fill(bx, by, bx + bw, by + bh, hover ? 0xAA66CC66 : 0x88449944);
            gg.drawString(font, Component.translatable("gui.dn.recipe.craft"),
                    bx + 4, by + 3, hover ? 0x111111 : 0x222222);
        }
    }

    /** 该配方第一个进行中任务（本地插值进度）。 */
    private LocalTask firstRunning(String recipeId) {
        for (LocalTask t : localTasks) {
            if (t.recipeId.equals(recipeId) && !t.isDone()) {
                return t;
            }
        }
        return null;
    }

    /** 该配方第一个已完成任务。 */
    private LocalTask firstDone(String recipeId) {
        for (LocalTask t : localTasks) {
            if (t.recipeId.equals(recipeId) && t.isDone()) {
                return t;
            }
        }
        return null;
    }

    /** 该配方进行中任务数（与 max_parallel 联动）。 */
    private int runningCountOf(String recipeId) {
        int count = 0;
        for (LocalTask t : localTasks) {
            if (t.recipeId.equals(recipeId) && !t.isDone()) {
                count++;
            }
        }
        return count;
    }

    /** 任务行显示名：从同步的配方列表按 id 查找（未找到回退 recipeId）。 */
    private String taskDisplayName(String recipeId) {
        if (sync != null) {
            for (SyncManufacturePacket.RecipeInfo r : sync.recipes) {
                if (r.recipeId.equals(recipeId)) {
                    return r.displayName;
                }
            }
        }
        return recipeId;
    }

    private void drawTaskRow(GuiGraphics gg, LocalTask t, int x, int y, int mouseX, int mouseY) {
        gg.drawString(font, truncate(taskDisplayName(t.recipeId), 11), x, y, t.completed ? 0x88FF88 : 0xFFFFFF);
        // 5 段分段亮起（每段 20%）
        int lit = Math.max(0, Math.min(5, (int) Math.ceil(t.progress() * 5)));
        for (int seg = 0; seg < 5; seg++) {
            ResourceLocation tex = seg < lit ? PROGRESS_ON : PROGRESS_OFF;
            gg.blit(tex, x + seg * 11, y + 12, 0, 0, 10, 10, 10, 10);
        }
        if (t.completed) {
            // 完整领取按钮（右区 118 宽，按钮完整显示）
            boolean hover = isHovered(x + 58, y + 11, 56, 12, mouseX, mouseY);
            gg.blit(BTN, x + 58, y + 11, 0, 0, 56, 12, 162, 18);
            gg.drawString(font, Component.translatable("gui.dn.claim"),
                    x + 72, y + 13, hover ? 0x111111 : 0x222222);
        } else {
            gg.drawString(font, Component.translatable("gui.dn.remaining", t.remainingMs() / 1000 + 1),
                    x + 58, y + 13, 0xAAAAAA);
        }
    }

    private static boolean isHovered(int x, int y, int w, int h, double mx, double my) {
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
        int left = guiLeft();
        int top = guiTop();
        if (button == 0 && sync != null) {
            // 配方行按钮（三状态：制造 / 制造中禁点 / 领取）
            List<SyncManufacturePacket.RecipeInfo> recipes = sync.recipes;
            int visible = 6;
            int rStart = Math.max(0, Math.min(recipeScroll, Math.max(0, recipes.size() - visible)));
            for (int i = 0; i < visible; i++) {
                int idx = rStart + i;
                if (idx >= recipes.size()) {
                    break;
                }
                SyncManufacturePacket.RecipeInfo r = recipes.get(idx);
                int rowY = top + 34 + i * 28;
                int bx = left + 8 + 80;
                int by = rowY + 6;
                if (isHovered(bx, by, 32, 14, mouseX, mouseY)) {
                    LocalTask done = firstDone(r.recipeId);
                    LocalTask running = firstRunning(r.recipeId);
                    if (done != null) {
                        // 领取
                        PacketHandler.sendToServer(new C2SClaimTaskPacket(workbenchId, done.taskId));
                    } else if (running == null || runningCountOf(r.recipeId) < r.maxParallel) {
                        // 制造（未达上限）
                        PacketHandler.sendToServer(new C2SStartTaskPacket(workbenchId, r.recipeId));
                    }
                    // 已达上限：不响应
                    return true;
                }
            }
            // 任务行点击 -> 领取（与渲染循环一致：仅可见行 + 滚动偏移，避免误触不可见任务）
            int visibleTasks = 5;
            int tStart = Math.max(0, Math.min(taskScroll, Math.max(0, localTasks.size() - visibleTasks)));
            for (int i = 0; i < visibleTasks; i++) {
                int idx = tStart + i;
                if (idx >= localTasks.size()) {
                    break;
                }
                LocalTask t = localTasks.get(idx);
                int rowY = top + 34 + i * 36;
                if (t.completed && isHovered(left + 130 + 58, rowY + 11, 56, 12, mouseX, mouseY)) {
                    PacketHandler.sendToServer(new C2SClaimTaskPacket(workbenchId, t.taskId));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int left = guiLeft();
        int step = delta > 0 ? -1 : 1;
        if (mouseX < left + RECIPE_AREA) {
            recipeScroll = Math.max(0, recipeScroll + step);
        } else {
            taskScroll = Math.max(0, taskScroll + step);
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
