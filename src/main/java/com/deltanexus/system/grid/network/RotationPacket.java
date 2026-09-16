package com.deltanexus.system.grid.network;

import com.deltanexus.system.grid.GridRegistry;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.grid.adapter.InputGate;
import com.deltanexus.system.grid.core.GridService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：旋转光标物品（R 键，格式背包）。
 *
 * <p>0.3.0Beta 校验补强（旧版无任何校验，任意菜单都能给物品打标记）：</p>
 * <ul>
 *   <li>玩家功能开关（{@code /dn feature deny}）；</li>
 *   <li>出售模式门闸（{@link InputGate#serverSellMode}）；</li>
 *   <li>光标物品必须存在，且当前菜单存在启用了网格的容器（否则旋转标记毫无意义）；</li>
 *   <li>写标记改为 {@code deltanexus.grid.rotated}（新键，清除旧键），
 *       判定与交易/管道比较统一走 {@link InventoryGridHandler#isRotated(ItemStack)}。</li>
 * </ul>
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
            if (player == null) {
                return;
            }
            if (!com.deltanexus.system.server.PermissionManager.canUseFeatures(player)) {
                return;
            }
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == null || InputGate.serverSellMode(player)) {
                return;
            }
            boolean gridMenu = menu.slots.stream().anyMatch(slot ->
                    GridRegistry.isGridContainer(player, InventoryGridHandler.gridContainerOf(slot)));
            if (!gridMenu) {
                return;
            }
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) {
                return;
            }
            InventoryGridHandler.setRotated(carried, !InventoryGridHandler.isRotated(carried));
            // 提交屏障：旋转改变占用宽高，同 Tick 内重新求解并广播
            GridService.markDirty(player);
        });
        ctx.setPacketHandled(true);
    }
}
