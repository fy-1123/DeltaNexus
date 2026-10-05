package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.GearWindowService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -&gt; 服务端：请求打开「装备窗口」（0.5.0Beta 嵌套补丁）。
 *
 * <p>窗口展示的是一格装备（胸挂/背包）内部的内容，位置由三元组
 * {@code (rootType, rootRef, path)} 唯一定位：</p>
 * <ul>
 *   <li>{@link #ROOT_EQUIPPED} —— {@code rootRef} = {@code GearKind} 序号，根为玩家已装备的装备；</li>
 *   <li>{@link #ROOT_MENU_SLOT} —— {@code rootRef} = 当前菜单槽位下标，根为该槽里的装备；</li>
 * </ul>
 * <p>{@code path} 为从根出发逐层下沉的格号列表（空 = 根装备本身）。服务端解析成功后回发
 * {@link SyncGearWindowPacket}；解析失败回发 close 标记让客户端收起该窗口。</p>
 */
public class C2SOpenGearWindowPacket {

    /** 根类型：玩家已装备的装备（rootRef = GearKind 序号）。 */
    public static final int ROOT_EQUIPPED = 0;
    /** 根类型：当前菜单某个槽位里的装备（rootRef = 菜单槽位下标）。 */
    public static final int ROOT_MENU_SLOT = 1;

    public final int rootType;
    public final int rootRef;
    public final int[] path;

    public C2SOpenGearWindowPacket(int rootType, int rootRef, int[] path) {
        this.rootType = rootType;
        this.rootRef = rootRef;
        this.path = path == null ? new int[0] : path.clone();
    }

    public static void encode(C2SOpenGearWindowPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.rootType);
        buf.writeVarInt(msg.rootRef);
        buf.writeVarIntArray(msg.path);
    }

    public static C2SOpenGearWindowPacket decode(FriendlyByteBuf buf) {
        int rootType = buf.readVarInt();
        int rootRef = buf.readVarInt();
        int[] path = buf.readVarIntArray();
        return new C2SOpenGearWindowPacket(rootType, rootRef, path);
    }

    public static void handle(C2SOpenGearWindowPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                GearWindowService.open(player, msg.rootType, msg.rootRef, msg.path);
            }
        });
        context.setPacketHandled(true);
    }
}
