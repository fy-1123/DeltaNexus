package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：请求工作台总览数据（任务二全屏 UI 打开时发送）。
 */
public class C2SRequestWorkbenchDataPacket {

    public C2SRequestWorkbenchDataPacket() {
    }

    public static void encode(C2SRequestWorkbenchDataPacket msg, FriendlyByteBuf buf) {
    }

    public static C2SRequestWorkbenchDataPacket decode(FriendlyByteBuf buf) {
        return new C2SRequestWorkbenchDataPacket();
    }

    public static void handle(C2SRequestWorkbenchDataPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ManufacturingService.sendWorkbenchData(player);
            }
        });
        context.setPacketHandled(true);
    }
}
