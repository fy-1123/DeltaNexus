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
 * 客户端 -> 服务端：点击跨格物品的非左上角格（占位物格）时，将主物品整件捡起到光标。
 *
 * <p>0.3.0Beta：点击占位物格已由「菜单入口 + 屏幕入口」统一重定向到主格，
 * 正常客户端不再发送本包；保留它是为了兼容旧客户端与外部集成。服务端校验同步补强：
 * 容器必须启用网格、光标必须为空、主格按<b>容器索引</b>解析（不再当菜单索引用）。</p>
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
            if (!com.deltanexus.system.server.PermissionManager.canUseFeatures(player)) {
                return;
            }
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == null || msg.slotIndex < 0 || msg.slotIndex >= menu.slots.size()) {
                return;
            }
            if (com.deltanexus.system.grid.adapter.InputGate.serverSellMode(player)) {
                return;
            }
            Slot slot = menu.slots.get(msg.slotIndex);
            if (!com.deltanexus.system.grid.GridRegistry.isGridContainer(
                    player, InventoryGridHandler.gridContainerOf(slot))) {
                return;
            }
            if (!menu.getCarried().isEmpty()) {
                // 光标非空：直接覆盖会吞掉玩家手上的物品，拒绝处理
                return;
            }
            Slot masterSlot = InventoryGridHandler.resolveMasterSlot(menu, slot);
            if (masterSlot == null || masterSlot == slot) {
                return;
            }
            ItemStack master = masterSlot.getItem();
            if (master.isEmpty() || InventoryGridHandler.isSlave(master)) {
                return;
            }
            // 整体捡起主物品到光标，主格清空（占位物由网格求解自愈）
            menu.setCarried(master.copy());
            masterSlot.set(ItemStack.EMPTY);
            com.deltanexus.system.grid.core.GridService.markDirty(player);
        });
        context.setPacketHandled(true);
    }
}
