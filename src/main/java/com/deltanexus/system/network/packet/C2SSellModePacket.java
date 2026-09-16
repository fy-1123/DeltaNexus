package com.deltanexus.system.network.packet;

import com.deltanexus.system.menu.WarehouseMenu;
import com.deltanexus.system.server.PermissionManager;
import com.deltanexus.system.server.TradeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -&gt; 服务端：进入 / 退出仓库界面的「出售模式」（0.3.0Beta，协议 dn3）。
 *
 * <p>0.2.x 的出售模式只是客户端状态：服务端完全不知道玩家正在出售，物品移动的门闸形同虚设。
 * 本包把出售模式提升为<b>服务端权威状态</b>（由 {@link WarehouseMenu} 持有）：</p>
 * <ul>
 *   <li>服务端在出售模式下冻结一切物品移动（{@code GridAwareMenu.clicked} / {@code quickMoveStack} 直接返回），
 *       覆盖左/右键取放、Shift 快捷移动、数字键换位、Q 丢弃、拖拽；</li>
 *   <li>跨格拾取 {@code C2SPickupGridStackPacket} 与旋转 {@code RotationPacket} 同样读取该门闸；</li>
 *   <li>成交仍走 {@link C2STradeSellPacket}（不是点击，不受门闸影响），服务端逐项重新校验来源槽位。</li>
 * </ul>
 *
 * <p>校验：玩家功能开关 + 交易行权限 + 当前菜单必须是仓库菜单。菜单关闭时状态随菜单销毁，
 * 另在 {@code GridService.onMenuClosed} 兜底清除。</p>
 */
public class C2SSellModePacket {

    /** true = 进入出售模式；false = 退出。 */
    public final boolean active;

    public C2SSellModePacket(boolean active) {
        this.active = active;
    }

    public static void encode(C2SSellModePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.active);
    }

    public static C2SSellModePacket decode(FriendlyByteBuf buf) {
        return new C2SSellModePacket(buf.readBoolean());
    }

    public static void handle(C2SSellModePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!PermissionManager.canUseFeatures(player)) {
                return;
            }
            if (!PermissionManager.canOpenTrade(player)) {
                TradeService.msg(player, "msg.dn.perm.denied.trade");
                return;
            }
            AbstractContainerMenu menu = player.containerMenu;
            if (!(menu instanceof WarehouseMenu warehouse)) {
                return;
            }
            warehouse.setSellMode(msg.active);
            com.deltanexus.system.DeltaNexus.LOGGER.info("[DN] 服务端出售模式：{}（{}）",
                    msg.active ? "开启" : "关闭", player.getGameProfile().getName());
            if (msg.active) {
                // 进入出售模式：光标上残留的占位物先修复（否则会被冻结在手上）
                com.deltanexus.system.grid.core.GridService.repairCarriedSlave(player);
            }
            // 状态变化后同 Tick 收敛并同步（布局/选中状态与客户端一致）
            com.deltanexus.system.grid.core.GridService.markDirty(player);
            menu.broadcastChanges();
        });
        context.setPacketHandled(true);
    }
}
