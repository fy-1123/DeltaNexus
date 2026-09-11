package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：请求打开「特勤处」（2.0.2Alpha：仓库/安全箱升级独立界面）。
 * 服务端发送仓库同步包（含升级数据）供特勤处界面渲染，不打开容器菜单。
 */
public class C2SOpenSpecialOpsPacket {

    public C2SOpenSpecialOpsPacket() {
    }

    public static void encode(C2SOpenSpecialOpsPacket msg, FriendlyByteBuf buf) {
    }

    public static C2SOpenSpecialOpsPacket decode(FriendlyByteBuf buf) {
        return new C2SOpenSpecialOpsPacket();
    }

    public static void handle(C2SOpenSpecialOpsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ManufacturingService.openSpecialOps(player);
            }
        });
        context.setPacketHandled(true);
    }
}
