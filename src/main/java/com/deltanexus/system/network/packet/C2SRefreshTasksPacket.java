package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：刷新制造台（被动计算，非 Tick 轮询）。
 */
public class C2SRefreshTasksPacket {

    public final String workbenchId;

    public C2SRefreshTasksPacket(String workbenchId) {
        this.workbenchId = workbenchId;
    }

    public static void encode(C2SRefreshTasksPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.workbenchId);
    }

    public static C2SRefreshTasksPacket decode(FriendlyByteBuf buf) {
        return new C2SRefreshTasksPacket(buf.readUtf());
    }

    public static void handle(C2SRefreshTasksPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ManufacturingService.refreshTasks(player, msg.workbenchId);
        });
        context.setPacketHandled(true);
    }
}
