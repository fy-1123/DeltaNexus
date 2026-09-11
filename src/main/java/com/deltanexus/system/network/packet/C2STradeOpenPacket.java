package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.TradeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：请求打开交易行（未绑定按键 / 客户端 UI 入口同源）。
 * 服务端在 {@link TradeService#open(ServerPlayer)} 做权限校验并下发目录 + 开屏。
 */
public class C2STradeOpenPacket {

    public C2STradeOpenPacket() {
    }

    public static void encode(C2STradeOpenPacket msg, FriendlyByteBuf buf) {
    }

    public static C2STradeOpenPacket decode(FriendlyByteBuf buf) {
        return new C2STradeOpenPacket();
    }

    public static void handle(C2STradeOpenPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                TradeService.open(player);
            }
        });
        context.setPacketHandled(true);
    }
}
