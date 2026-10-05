package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.GearGridService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -&gt; 服务端：背包/容器界面里「非菜单槽」的点击。
 *
 * <p>类塔克夫界面把胸挂/背包网格与（容器界面下的）盔甲/副手直接画在背包界面里，
 * 这些位置不在原版 {@code AbstractContainerMenu} 的槽位表里，因此交互必须自带通道：</p>
 * <ul>
 *   <li>{@link #TARGET_RIG} / {@link #TARGET_BACKPACK} —— 装备网格的某个格号
 *       （客户端已把足迹格重定向到锚点格，服务端仍以锚点解析为准）；</li>
 *   <li>{@link #TARGET_ARMOR} —— 盔甲位（0=头盔 1=胸甲 2=护腿 3=靴子）；</li>
 *   <li>{@link #TARGET_OFFHAND} —— 副手位。</li>
 * </ul>
 *
 * <p>服务端权威：所有变更都在服务端执行，并立即回发装备几何同步；
 * 物品守恒由内核（{@code GridStore} / {@code StoreContainer}）保证，放不下的一律留在原处。</p>
 */
public class C2SOverlayClickPacket {

    public static final int TARGET_RIG = 0;
    public static final int TARGET_BACKPACK = 1;
    public static final int TARGET_ARMOR = 2;
    public static final int TARGET_OFFHAND = 3;

    public final int target;
    /** 网格格号 / 盔甲位序号；副手忽略。 */
    public final int index;
    /** 0 = 左键，1 = 右键。 */
    public final int button;
    /** Shift（快速转移）。 */
    public final boolean shift;

    public C2SOverlayClickPacket(int target, int index, int button, boolean shift) {
        this.target = target;
        this.index = index;
        this.button = button;
        this.shift = shift;
    }

    public static void encode(C2SOverlayClickPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.target);
        buf.writeVarInt(msg.index);
        buf.writeVarInt(msg.button);
        buf.writeBoolean(msg.shift);
    }

    public static C2SOverlayClickPacket decode(FriendlyByteBuf buf) {
        return new C2SOverlayClickPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(C2SOverlayClickPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                GearGridService.handleOverlayClick(player, msg.target, msg.index, msg.button, msg.shift);
            }
        });
        context.setPacketHandled(true);
    }
}