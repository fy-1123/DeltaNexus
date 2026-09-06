package com.deltanexus.system.grid.network;

import com.deltanexus.system.grid.InventoryGridHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：旋转光标物品（R 键，格式背包 2.0.0 集成自 expansionpack）。
 *
 * <p>服务端在光标物品 NBT 上切换 {@code deltanexus.is_rotated} 标记并同步容器，
 * 网格求解与客户端渲染据此交换占用宽高（旋转 90°）。</p>
 */
public class RotationPacket {

    public RotationPacket() {
    }

    public static void encode(RotationPacket msg, FriendlyByteBuf buf) {
    }

    public static RotationPacket decode(FriendlyByteBuf buf) {
        return new RotationPacket();
    }

    public static void handle(RotationPacket msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                ItemStack carried = player.containerMenu.getCarried();
                if (!carried.isEmpty()) {
                    boolean rot = carried.getOrCreateTag().getBoolean(InventoryGridHandler.IS_ROTATED);
                    carried.getOrCreateTag().putBoolean(InventoryGridHandler.IS_ROTATED, !rot);
                    player.containerMenu.sendAllDataToRemote();
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
