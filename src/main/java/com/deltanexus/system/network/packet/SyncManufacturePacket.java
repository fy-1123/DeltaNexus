package com.deltanexus.system.network.packet;

import com.deltanexus.system.client.gui.ManufactureScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：制造台全量同步。
 *
 * <p>仅发送「任务唯一 ID + 剩余秒数 + 配方图标基础信息」；进度由客户端
 * 结合首次同步时间戳本地假进度渲染，服务端不主动推送每秒更新。</p>
 *
 * <p>体验优化：配方完整 JSON 仅在管理员时发送（普通玩家不携带，包体大幅缩小）；
 * 配方图标不带 NBT（图标展示无需完整数据）。</p>
 */
public class SyncManufacturePacket {

    /** 任务行信息（增量同步：只发 taskId + 剩余秒数）。 */
    public static class TaskInfo {
        public final int taskId;
        public final String recipeId;
        public final int remainingSeconds;
        public final int totalSeconds;
        public final boolean completed;

        public TaskInfo(int taskId, String recipeId, int remainingSeconds, int totalSeconds, boolean completed) {
            this.taskId = taskId;
            this.recipeId = recipeId;
            this.remainingSeconds = remainingSeconds;
            this.totalSeconds = totalSeconds;
            this.completed = completed;
        }
    }

    /** 配方目录行信息（图标基础信息）。 */
    public static class RecipeInfo {
        public final String recipeId;
        public final String type;
        public final ItemStack icon;
        public final long baseDuration;
        public final int requiredLevel;
        public final int inputCount;
        /** 同时制作上限（与按钮三状态联动）。 */
        public final int maxParallel;
        /** 配方显示名（未设置时服务端已回退 recipeId）。 */
        public final String displayName;

        public RecipeInfo(String recipeId, String type, ItemStack icon, long baseDuration,
                          int requiredLevel, int inputCount, int maxParallel, String displayName) {
            this.recipeId = recipeId;
            this.type = type;
            this.icon = icon;
            this.baseDuration = baseDuration;
            this.requiredLevel = requiredLevel;
            this.inputCount = inputCount;
            this.maxParallel = maxParallel;
            this.displayName = displayName;
        }
    }

    public final String workbenchId;
    public final String workbenchDisplay;
    public final boolean isAdmin;
    public final int warehouseLevel;
    public final int queueSize;
    public final int maxQueueSize;
    public final List<TaskInfo> tasks;
    public final List<RecipeInfo> recipes;

    public SyncManufacturePacket(String workbenchId, String workbenchDisplay, boolean isAdmin, int warehouseLevel,
                                 int queueSize, int maxQueueSize,
                                 List<TaskInfo> tasks, List<RecipeInfo> recipes) {
        this.workbenchId = workbenchId;
        this.workbenchDisplay = workbenchDisplay;
        this.isAdmin = isAdmin;
        this.warehouseLevel = warehouseLevel;
        this.queueSize = queueSize;
        this.maxQueueSize = maxQueueSize;
        this.tasks = tasks;
        this.recipes = recipes;
    }

    public static void encode(SyncManufacturePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.workbenchId);
        buf.writeUtf(msg.workbenchDisplay);
        buf.writeBoolean(msg.isAdmin);
        buf.writeVarInt(msg.warehouseLevel);
        buf.writeVarInt(msg.queueSize);
        buf.writeVarInt(msg.maxQueueSize);

        buf.writeVarInt(msg.tasks.size());
        for (TaskInfo t : msg.tasks) {
            buf.writeVarInt(t.taskId);
            buf.writeUtf(t.recipeId);
            buf.writeVarInt(t.remainingSeconds);
            buf.writeVarInt(t.totalSeconds);
            buf.writeBoolean(t.completed);
        }
        buf.writeVarInt(msg.recipes.size());
        for (RecipeInfo r : msg.recipes) {
            buf.writeUtf(r.recipeId);
            buf.writeUtf(r.type);
            buf.writeItem(r.icon);
            buf.writeVarLong(r.baseDuration);
            buf.writeVarInt(r.requiredLevel);
            buf.writeVarInt(r.inputCount);
            buf.writeVarInt(r.maxParallel);
            buf.writeUtf(r.displayName);
        }
    }

    public static SyncManufacturePacket decode(FriendlyByteBuf buf) {
        String workbenchId = buf.readUtf();
        String workbenchDisplay = buf.readUtf();
        boolean isAdmin = buf.readBoolean();
        int warehouseLevel = buf.readVarInt();
        int queueSize = buf.readVarInt();
        int maxQueueSize = buf.readVarInt();

        int taskCount = buf.readVarInt();
        List<TaskInfo> tasks = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            tasks.add(new TaskInfo(buf.readVarInt(), buf.readUtf(),
                    buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        }
        int recipeCount = buf.readVarInt();
        List<RecipeInfo> recipes = new ArrayList<>(recipeCount);
        for (int i = 0; i < recipeCount; i++) {
            recipes.add(new RecipeInfo(buf.readUtf(), buf.readUtf(), buf.readItem(),
                    buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf()));
        }
        return new SyncManufacturePacket(workbenchId, workbenchDisplay, isAdmin, warehouseLevel,
                queueSize, maxQueueSize, tasks, recipes);
    }

    public static void handle(SyncManufacturePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.handle(msg)));
        context.setPacketHandled(true);
    }

    /** 客户端处理（2.0.7Alpha 拆分：专用服务器不加载本类）。 */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static class ClientHandler {
        static void handle(SyncManufacturePacket msg) {
            com.deltanexus.system.client.gui.ManufactureScreen.receiveSync(msg);
        }
    }
}
