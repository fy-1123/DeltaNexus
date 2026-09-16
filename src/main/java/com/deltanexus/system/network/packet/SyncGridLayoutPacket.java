package com.deltanexus.system.network.packet;

import com.deltanexus.system.grid.core.GridLayout;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -&gt; 客户端：当前菜单的网格布局（0.3.0Beta 第二阶段，协议 dn4）。
 *
 * <p>此前客户端只能靠“槽位内容 + 占位物 NBT + 每格尺寸表”自己猜哪些格子属于同一件跨格物品；
 * 一旦两侧口径不同，就会出现「客户端画出两件物品/一个格子里两样东西」这类假性复制与重叠。
 * 现在服务端把<b>唯一几何真相</b>（{@link GridLayout}）按菜单槽位下标下发，客户端直接照它渲染与判定点击，
 * 不再自行推导。</p>
 *
 * <p>包体：{@code containerId}（忽略过期包）+ {@code revision}（布局版本，单调递增）+ 每个落位物品的
 * {@code 锚点槽位 / 宽 / 高 / 是否旋转}。1x1 物品不必下发（客户端按普通槽位渲染即可）。</p>
 */
public class SyncGridLayoutPacket {

    /** 单个落位物品（{@code rowStride} = 该容器在菜单中的列数，客户端据此换算覆盖范围，无需自己猜几何）。 */
    public record Entry(int anchorSlot, int w, int h, boolean rotated, int rowStride) {

        /** 覆盖的菜单槽位数量。 */
        public int span() {
            return w * h;
        }
    }

    public final int containerId;
    public final long revision;
    public final List<Entry> entries;

    public SyncGridLayoutPacket(int containerId, long revision, List<Entry> entries) {
        this.containerId = containerId;
        this.revision = revision;
        this.entries = entries == null ? List.of() : entries;
    }

    public static void encode(SyncGridLayoutPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.containerId);
        buf.writeVarLong(msg.revision);
        buf.writeVarInt(msg.entries.size());
        for (Entry e : msg.entries) {
            buf.writeVarInt(e.anchorSlot());
            buf.writeByte(e.w());
            buf.writeByte(e.h());
            buf.writeBoolean(e.rotated());
            buf.writeByte(e.rowStride());
        }
    }

    public static SyncGridLayoutPacket decode(FriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        long revision = buf.readVarLong();
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readVarInt(), buf.readByte(), buf.readByte(),
                    buf.readBoolean(), buf.readByte()));
        }
        return new SyncGridLayoutPacket(containerId, revision, entries);
    }

    public static void handle(SyncGridLayoutPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.apply(msg)));
        context.setPacketHandled(true);
    }

    /** 客户端处理（单独类，避免专用服务器加载客户端类型）。 */
    @net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
    public static final class ClientHandler {

        private ClientHandler() {
        }

        public static void apply(SyncGridLayoutPacket msg) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null || mc.player.containerMenu == null) {
                return;
            }
            if (mc.player.containerMenu.containerId != msg.containerId) {
                return; // 过期/其他菜单的布局
            }
            com.deltanexus.system.client.GridLayoutClient.apply(msg.containerId, msg.revision, msg.entries);
        }
    }
}
