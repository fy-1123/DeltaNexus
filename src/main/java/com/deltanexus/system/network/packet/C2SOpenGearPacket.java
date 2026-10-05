package com.deltanexus.system.network.packet;

import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.server.GearService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：左键点击装备槽（胸挂/背包）的请求（0.5.0Beta）。
 *
 * <p>0.5.0Beta 起不再打开独立装备界面——装备内容通过仓库/背包/容器 UI
 * 的中列网格查看。此包现在只负责「把光标上的同类装备装到该槽」。</p>
 */
public class C2SOpenGearPacket {

    public final int kindOrdinal;

    public C2SOpenGearPacket(GearKind kind) {
        this.kindOrdinal = kind == null ? 0 : kind.ordinal();
    }

    public static void encode(C2SOpenGearPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.kindOrdinal);
    }

    public static C2SOpenGearPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        GearKind kind = ordinal >= 0 && ordinal < GearKind.values().length
                ? GearKind.values()[ordinal] : GearKind.BACKPACK;
        return new C2SOpenGearPacket(kind);
    }

    public static void handle(C2SOpenGearPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            GearKind kind = GearKind.values()[msg.kindOrdinal];
            // 左键点装备槽：装上去（不打开独立界面）
            GearService.equipFromCursor(player, kind);
        });
        context.setPacketHandled(true);
    }
}
