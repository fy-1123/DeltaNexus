package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：请求安全箱升级（1.1.0）。
 *
 * <p>体验优先：升级点击不受网络限流约束（避免误吞玩家操作），
 * 服务端仍以配置/材料校验兜底（与仓库升级一致）。</p>
 */
public class C2SUpgradeSafeBoxPacket {

    public C2SUpgradeSafeBoxPacket() {
    }

    public static void encode(C2SUpgradeSafeBoxPacket msg, FriendlyByteBuf buf) {
    }

    public static C2SUpgradeSafeBoxPacket decode(FriendlyByteBuf buf) {
        return new C2SUpgradeSafeBoxPacket();
    }

    public static void handle(C2SUpgradeSafeBoxPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ManufacturingService.upgradeSafeBox(player);
            }
        });
        context.setPacketHandled(true);
    }
}
