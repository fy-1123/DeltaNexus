package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：取消制造中任务。
 *
 * <p>用于工作台总览 UI「制造中」按钮状态：点击取消后服务端移除任务并退还输入材料。</p>
 */
public class C2SCancelTaskPacket {

    public final String workbenchId;
    public final int taskId;

    public C2SCancelTaskPacket(String workbenchId, int taskId) {
        this.workbenchId = workbenchId;
        this.taskId = taskId;
    }

    public static void encode(C2SCancelTaskPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.workbenchId);
        buf.writeVarInt(msg.taskId);
    }

    public static C2SCancelTaskPacket decode(FriendlyByteBuf buf) {
        return new C2SCancelTaskPacket(buf.readUtf(), buf.readVarInt());
    }

    public static void handle(C2SCancelTaskPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ManufacturingService.cancelTask(player, msg.workbenchId, msg.taskId);
        });
        context.setPacketHandled(true);
    }
}
