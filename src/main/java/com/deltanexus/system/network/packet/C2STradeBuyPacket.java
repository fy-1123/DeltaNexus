package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.TradeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：买入请求（goodId + 数量，单位 = 商品 unit）。
 * 服务端权威校验：权限/上架/库存/上下限/价格/货币/仓库空间，任一失败发文案提示。
 */
public class C2STradeBuyPacket {

    public final String goodId;
    public final int qty;

    public C2STradeBuyPacket(String goodId, int qty) {
        this.goodId = goodId == null ? "" : goodId;
        this.qty = qty;
    }

    public static void encode(C2STradeBuyPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.goodId);
        buf.writeVarInt(msg.qty);
    }

    public static C2STradeBuyPacket decode(FriendlyByteBuf buf) {
        return new C2STradeBuyPacket(buf.readUtf(), buf.readVarInt());
    }

    public static void handle(C2STradeBuyPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                TradeService.buy(player, msg.goodId, msg.qty);
            }
        });
        context.setPacketHandled(true);
    }
}
