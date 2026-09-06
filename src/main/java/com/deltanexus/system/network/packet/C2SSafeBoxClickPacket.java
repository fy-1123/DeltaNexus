package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：背包界面（原版 InventoryScreen）安全箱槽位交互（1.1.0）。
 *
 * <p>动作：0 = 点击（光标与槽位交换/合并），1 = 潜行点击（槽位物品整体移入背包）。
 * 服务端以玩家当前容器（InventoryMenu）的光标栈为准执行，随后回发 {@link SyncSafeBoxPacket}
 * （含最新槽位物品与光标栈），保证客户端覆盖层与服务器一致。</p>
 */
public class C2SSafeBoxClickPacket {

    public static final int ACTION_CLICK = 0;
    public static final int ACTION_SHIFT = 1;

    public final int slot;
    public final int action;

    public C2SSafeBoxClickPacket(int slot, int action) {
        this.slot = slot;
        this.action = action;
    }

    public static void encode(C2SSafeBoxClickPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.slot);
        buf.writeVarInt(msg.action);
    }

    public static C2SSafeBoxClickPacket decode(FriendlyByteBuf buf) {
        return new C2SSafeBoxClickPacket(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(C2SSafeBoxClickPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ManufacturingService.safeBoxClick(player, msg.slot, msg.action);
            }
        });
        context.setPacketHandled(true);
    }
}
