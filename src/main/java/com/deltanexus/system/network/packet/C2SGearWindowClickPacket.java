package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.GearWindowService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -&gt; 服务端：装备窗口内的网格点击（0.5.0Beta 嵌套补丁）。
 *
 * <p>与 {@link C2SOverlayClickPacket} 同口径：左键取起/放下、右键取半/放一、Shift 整件移入背包，
 * 服务端权威裁决。区别是目标不再固定为「已装备的胸挂/背包」，而是由
 * {@code (rootType, rootRef, path)} 定位的任意一层装备网格——支持逐层嵌套。</p>
 */
public class C2SGearWindowClickPacket {

    public final int rootType;
    public final int rootRef;
    public final int[] path;
    public final int cell;
    public final int button;
    public final boolean shift;

    public C2SGearWindowClickPacket(int rootType, int rootRef, int[] path, int cell, int button, boolean shift) {
        this.rootType = rootType;
        this.rootRef = rootRef;
        this.path = path == null ? new int[0] : path.clone();
        this.cell = cell;
        this.button = button;
        this.shift = shift;
    }

    public static void encode(C2SGearWindowClickPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.rootType);
        buf.writeVarInt(msg.rootRef);
        buf.writeVarIntArray(msg.path);
        buf.writeVarInt(msg.cell);
        buf.writeVarInt(msg.button);
        buf.writeBoolean(msg.shift);
    }

    public static C2SGearWindowClickPacket decode(FriendlyByteBuf buf) {
        int rootType = buf.readVarInt();
        int rootRef = buf.readVarInt();
        int[] path = buf.readVarIntArray();
        int cell = buf.readVarInt();
        int button = buf.readVarInt();
        boolean shift = buf.readBoolean();
        return new C2SGearWindowClickPacket(rootType, rootRef, path, cell, button, shift);
    }

    public static void handle(C2SGearWindowClickPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                GearWindowService.click(player, msg.rootType, msg.rootRef, msg.path, msg.cell, msg.button, msg.shift);
            }
        });
        context.setPacketHandled(true);
    }
}
