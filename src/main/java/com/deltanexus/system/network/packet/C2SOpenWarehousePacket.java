package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：请求打开仓库（B 键 / /dn open warehouse）。
 */
public class C2SOpenWarehousePacket {

    public C2SOpenWarehousePacket() {
    }

    public static void encode(C2SOpenWarehousePacket msg, FriendlyByteBuf buf) {
    }

    public static C2SOpenWarehousePacket decode(FriendlyByteBuf buf) {
        return new C2SOpenWarehousePacket();
    }

    public static void handle(C2SOpenWarehousePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ManufacturingService.openWarehouse(player);
            }
        });
        context.setPacketHandled(true);
    }
}
