package com.deltanexus.system.network.packet;

import com.deltanexus.system.client.gui.WorkbenchScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：工作台总览全量同步（任务二全屏 UI 数据源）。
 *
 * <p>一次同步所有工作台 + 全部配方（含输入/输出物品明细，图标去 NBT），
 * 客户端本地零请求渲染三竖列。</p>
 */
public class SyncWorkbenchDataPacket {

    /** 工作台条目。 */
    public static class WorkbenchInfo {
        public final String id;
        public final String display;

        public WorkbenchInfo(String id, String display) {
            this.id = id;
            this.display = display;
        }
    }

    /** 配方详情（输入/输出物品明细 + 按钮三状态所需的任务信息）。 */
    public static class RecipeDetail {
        public final String workbenchId;
        public final String recipeId;
        public final ItemStack icon;
        public final long baseDuration;
        public final int requiredLevel;
        public final List<ItemStack> inputs;
        public final List<ItemStack> outputs;
        /** 同时制作上限。 */
        public final int maxParallel;
        /** 该配方进行中任务数。 */
        public final int runningCount;
        /** 是否有可领取的完成品。 */
        public final boolean hasDone;
        /** 可领取任务的 id（无则 -1）。 */
        public final int claimTaskId;
        /** 第一个进行中任务进度 0..1（无则 -1）。 */
        public final float progress01;
        /** 第一个进行中任务 id（用于取消，无则 -1）。 */
        public final int runningTaskId;
        /** 配方显示名（未设置时服务端已回退 recipeId）。 */
        public final String displayName;

        public RecipeDetail(String workbenchId, String recipeId, ItemStack icon, long baseDuration,
                            int requiredLevel, List<ItemStack> inputs, List<ItemStack> outputs,
                            int maxParallel, int runningCount, boolean hasDone, int claimTaskId,
                            float progress01, int runningTaskId, String displayName) {
            this.workbenchId = workbenchId;
            this.recipeId = recipeId;
            this.icon = icon;
            this.baseDuration = baseDuration;
            this.requiredLevel = requiredLevel;
            this.inputs = inputs;
            this.outputs = outputs;
            this.maxParallel = maxParallel;
            this.runningCount = runningCount;
            this.hasDone = hasDone;
            this.claimTaskId = claimTaskId;
            this.progress01 = progress01;
            this.runningTaskId = runningTaskId;
            this.displayName = displayName;
        }
    }

    public final List<WorkbenchInfo> workbenches;
    public final List<RecipeDetail> recipes;

    public SyncWorkbenchDataPacket(List<WorkbenchInfo> workbenches, List<RecipeDetail> recipes) {
        this.workbenches = workbenches;
        this.recipes = recipes;
    }

    public static void encode(SyncWorkbenchDataPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.workbenches.size());
        for (WorkbenchInfo w : msg.workbenches) {
            buf.writeUtf(w.id);
            buf.writeUtf(w.display);
        }
        buf.writeVarInt(msg.recipes.size());
        for (RecipeDetail r : msg.recipes) {
            buf.writeUtf(r.workbenchId);
            buf.writeUtf(r.recipeId);
            buf.writeItem(r.icon);
            buf.writeVarLong(r.baseDuration);
            buf.writeVarInt(r.requiredLevel);
            buf.writeVarInt(r.inputs.size());
            for (ItemStack s : r.inputs) {
                buf.writeItem(s);
            }
            buf.writeVarInt(r.outputs.size());
            for (ItemStack s : r.outputs) {
                buf.writeItem(s);
            }
            buf.writeVarInt(r.maxParallel);
            buf.writeVarInt(r.runningCount);
            buf.writeBoolean(r.hasDone);
            buf.writeVarInt(r.claimTaskId);
            buf.writeFloat(r.progress01);
            buf.writeVarInt(r.runningTaskId);
            buf.writeUtf(r.displayName);
        }
    }

    public static SyncWorkbenchDataPacket decode(FriendlyByteBuf buf) {
        int wbCount = buf.readVarInt();
        List<WorkbenchInfo> wbs = new ArrayList<>(wbCount);
        for (int i = 0; i < wbCount; i++) {
            wbs.add(new WorkbenchInfo(buf.readUtf(), buf.readUtf()));
        }
        int recipeCount = buf.readVarInt();
        List<RecipeDetail> recipes = new ArrayList<>(recipeCount);
        for (int i = 0; i < recipeCount; i++) {
            String workbenchId = buf.readUtf();
            String recipeId = buf.readUtf();
            ItemStack icon = buf.readItem();
            long baseDuration = buf.readVarLong();
            int requiredLevel = buf.readVarInt();
            int inCount = buf.readVarInt();
            List<ItemStack> inputs = new ArrayList<>(inCount);
            for (int j = 0; j < inCount; j++) {
                inputs.add(buf.readItem());
            }
            int outCount = buf.readVarInt();
            List<ItemStack> outputs = new ArrayList<>(outCount);
            for (int j = 0; j < outCount; j++) {
                outputs.add(buf.readItem());
            }
            int maxParallel = buf.readVarInt();
            int runningCount = buf.readVarInt();
            boolean hasDone = buf.readBoolean();
            int claimTaskId = buf.readVarInt();
            float progress01 = buf.readFloat();
            int runningTaskId = buf.readVarInt();
            String displayName = buf.readUtf();
            recipes.add(new RecipeDetail(workbenchId, recipeId, icon, baseDuration, requiredLevel,
                    inputs, outputs, maxParallel, runningCount, hasDone, claimTaskId, progress01, runningTaskId,
                    displayName));
        }
        return new SyncWorkbenchDataPacket(wbs, recipes);
    }

    public static void handle(SyncWorkbenchDataPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.handle(msg)));
        context.setPacketHandled(true);
    }

    /** 客户端处理（2.0.7Alpha 拆分：专用服务器不加载本类）。 */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static class ClientHandler {
        static void handle(SyncWorkbenchDataPacket msg) {
            com.deltanexus.system.client.gui.WorkbenchScreen.receiveData(msg);
        }
    }
}
