package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：仓库滚轮滚动（2.0.1Alpha，替代翻页）。
 * 2.0.8Alpha：服务端原位替换视口槽位（不重建菜单），光标物品与界面状态不丢失。
 */
public class C2SWarehouseScrollPacket {

    public final int scrollRow;

    public C2SWarehouseScrollPacket(int scrollRow) {
        this.scrollRow = scrollRow;
    }

    public static void encode(C2SWarehouseScrollPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.scrollRow);
    }

    public static C2SWarehouseScrollPacket decode(FriendlyByteBuf buf) {
        return new C2SWarehouseScrollPacket(buf.readVarInt());
    }

    public static void handle(C2SWarehouseScrollPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ManufacturingService.scrollWarehouse(player, msg.scrollRow);
            }
        });
        context.setPacketHandled(true);
    }
}
