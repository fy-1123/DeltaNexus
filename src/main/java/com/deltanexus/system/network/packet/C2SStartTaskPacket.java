package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：开始制造任务。
 */
public class C2SStartTaskPacket {

    public final String workbenchId;
    public final String recipeId;

    public C2SStartTaskPacket(String workbenchId, String recipeId) {
        this.workbenchId = workbenchId;
        this.recipeId = recipeId;
    }

    public static void encode(C2SStartTaskPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.workbenchId);
        buf.writeUtf(msg.recipeId);
    }

    public static C2SStartTaskPacket decode(FriendlyByteBuf buf) {
        return new C2SStartTaskPacket(buf.readUtf(), buf.readUtf());
    }

    public static void handle(C2SStartTaskPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ManufacturingService.startTask(player, msg.workbenchId, msg.recipeId);
        });
        context.setPacketHandled(true);
    }
}
