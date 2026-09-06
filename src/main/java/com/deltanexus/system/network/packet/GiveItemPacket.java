package com.deltanexus.system.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：领取结果（领取瞬间允许携带完整 ItemStack 数据）。
 * 仅当服务端校验成功后才发送。
 */
public class GiveItemPacket {

    public final int taskId;
    public final boolean success;
    public final String message;
    public final List<ItemStack> items;

    public GiveItemPacket(int taskId, boolean success, String message, List<ItemStack> items) {
        this.taskId = taskId;
        this.success = success;
        this.message = message;
        this.items = items;
    }

    public static void encode(GiveItemPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.taskId);
        buf.writeBoolean(msg.success);
        buf.writeUtf(msg.message == null ? "" : msg.message);
        buf.writeVarInt(msg.items.size());
        for (ItemStack stack : msg.items) {
            buf.writeItem(stack);
        }
    }

    public static GiveItemPacket decode(FriendlyByteBuf buf) {
        int taskId = buf.readVarInt();
        boolean success = buf.readBoolean();
        String message = buf.readUtf();
        int count = buf.readVarInt();
        List<ItemStack> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(buf.readItem());
        }
        return new GiveItemPacket(taskId, success, message, items);
    }

    public static void handle(GiveItemPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.handle(msg)));
        context.setPacketHandled(true);
    }

    /** 客户端处理（2.0.7 拆分：专用服务器不加载本类）。 */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static class ClientHandler {
        static void handle(GiveItemPacket msg) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && msg.message != null && !msg.message.isBlank()) {
                mc.player.displayClientMessage(Component.literal(msg.message), true);
            }
        }
    }
}
