package com.deltanexus.system.network.packet;

import com.deltanexus.system.grid.InventoryGridHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：点击跨格物品的非左上角格（占位物格）时，将主物品整件捡起到光标（2.0.3Alpha）。
 *
 * <p>占位物由网格求解自愈；不做「移动到点击格」（2.0.4Alpha 曾误加，2.0.5Alpha 撤销）。</p>
 */
public class C2SPickupGridStackPacket {

    public final int slotIndex;

    public C2SPickupGridStackPacket(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public static void encode(C2SPickupGridStackPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.slotIndex);
    }

    public static C2SPickupGridStackPacket decode(FriendlyByteBuf buf) {
        return new C2SPickupGridStackPacket(buf.readVarInt());
    }

    public static void handle(C2SPickupGridStackPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            AbstractContainerMenu menu = player.containerMenu;
            if (msg.slotIndex < 0 || msg.slotIndex >= menu.slots.size()) {
                return;
            }
            Slot slot = menu.slots.get(msg.slotIndex);
            ItemStack stack = slot.getItem();
            if (!InventoryGridHandler.isSlave(stack) || !stack.hasTag()
                    || !stack.getTag().contains(InventoryGridHandler.MASTER_SLOT)) {
                return;
            }
            int masterId = stack.getTag().getInt(InventoryGridHandler.MASTER_SLOT);
            if (masterId < 0 || masterId >= menu.slots.size()) {
                return;
            }
            Slot masterSlot = menu.slots.get(masterId);
            ItemStack master = masterSlot.getItem();
            if (master.isEmpty() || InventoryGridHandler.isSlave(master)) {
                return;
            }
            // 整体捡起主物品到光标，主格清空（占位物由网格求解自愈）
            menu.setCarried(master.copy());
            masterSlot.set(ItemStack.EMPTY);
            menu.broadcastChanges();
        });
        context.setPacketHandled(true);
    }
}
