package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：领取已完成任务。
 */
public class C2SClaimTaskPacket {

    public final String workbenchId;
    public final int taskId;

    public C2SClaimTaskPacket(String workbenchId, int taskId) {
        this.workbenchId = workbenchId;
        this.taskId = taskId;
    }

    public static void encode(C2SClaimTaskPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.workbenchId);
        buf.writeVarInt(msg.taskId);
    }

    public static C2SClaimTaskPacket decode(FriendlyByteBuf buf) {
        return new C2SClaimTaskPacket(buf.readUtf(), buf.readVarInt());
    }

    public static void handle(C2SClaimTaskPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ManufacturingService.claimTask(player, msg.workbenchId, msg.taskId);
        });
        context.setPacketHandled(true);
    }
}
