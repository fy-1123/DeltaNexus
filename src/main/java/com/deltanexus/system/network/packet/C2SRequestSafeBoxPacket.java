package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：请求安全箱状态（1.1.0Alpha）。
 *
 * <p>客户端打开背包界面（原版 InventoryScreen）时发送一次，
 * 服务端回复 {@link SyncSafeBoxPacket}（含权限判定，无权时 allowed=false）。</p>
 */
public class C2SRequestSafeBoxPacket {

    public C2SRequestSafeBoxPacket() {
    }

    public static void encode(C2SRequestSafeBoxPacket msg, FriendlyByteBuf buf) {
    }

    public static C2SRequestSafeBoxPacket decode(FriendlyByteBuf buf) {
        return new C2SRequestSafeBoxPacket();
    }

    public static void handle(C2SRequestSafeBoxPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ManufacturingService.syncSafeBox(player);
            }
        });
        context.setPacketHandled(true);
    }
}
