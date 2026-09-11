package com.deltanexus.system.network.packet;

import com.deltanexus.system.server.TradeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：仓库界面「出售」确认包（多槽位 + 数量）。
 *
 * <p>槽位使用**仓库全局槽位索引**（与视口滚动无关）；服务端逐项重新读取物品、
 * 重新匹配商品与计价（客户端只提交意图，不做权威判定）。</p>
 */
public class C2STradeSellPacket {

    /** 来源：仓库（视口全局槽位）。 */
    public static final int SOURCE_WAREHOUSE = 0;
    /** 来源：玩家背包/快捷栏（0..35）。 */
    public static final int SOURCE_INVENTORY = 1;
    /** 来源：安全箱（0..8，按解锁格）。 */
    public static final int SOURCE_SAFE_BOX = 2;

    /** 单项：来源 + 索引 + 数量（数量由客户端选中态决定，服务端会再夹取到实际堆叠）。 */
    public static final class Entry {
        public final int source;
        public final int slot;
        public final int qty;

        public Entry(int source, int slot, int qty) {
            this.source = source;
            this.slot = slot;
            this.qty = qty;
        }
    }

    public final List<Entry> entries;

    public C2STradeSellPacket(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static void encode(C2STradeSellPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entries.size());
        for (Entry e : msg.entries) {
            buf.writeVarInt(e.source);
            buf.writeVarInt(e.slot);
            buf.writeVarInt(e.qty);
        }
    }

    public static C2STradeSellPacket decode(FriendlyByteBuf buf) {
        int size = Math.min(512, buf.readVarInt());
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new C2STradeSellPacket(entries);
    }

    public static void handle(C2STradeSellPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                TradeService.sell(player, msg.entries);
            }
        });
        context.setPacketHandled(true);
    }
}
