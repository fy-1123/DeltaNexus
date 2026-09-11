package com.deltanexus.system.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：交易行目录快照（分类 + 商品 + 已解析价格/库存）。
 *
 * <p>打开交易行前由服务端先行下发（同通道有序，先目录后开屏），买入/补货/重载后
 * 亦重新下发以刷新界面。客户端内容仅经 {@link ClientHandler} 写入暂存，
 * 具体界面（TradeScreen）从暂存读取。</p>
 *
 * <p>价格编码：{@code price} 为服务端已按全局倍率取整的单价（&gt;=1）；
 * {@code code}：0=可用；其余方向码 1=未配置；买入方向另有
 * 1=缺货 2=低于库存下限 3=定价不可用 4=不可购买。</p>
 */
public class SyncTradeCatalogPacket {

    /** 分类条目。 */
    public static final class Category {
        public final String id;
        public final String name;

        public Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 商品条目（展示与操作状态）。 */
    public static final class Good {
        public final String id;
        public final String categoryId;
        public final String displayName;
        /** 物品注册 id（客户端据此建图标）。 */
        public final String itemId;
        /** NBT 模板（SNBT，可能为空）。 */
        public final String nbt;
        public final int unitCount;
        /** 匹配模式：id / full_nbt / partial_nbt（客户端仓库卖出判定用）。 */
        public final String matchMode;
        /** partial_nbt 指定键 -> 规则（op=exact/contains/specified，specified 时带 value）。 */
        public final java.util.Map<String, com.deltanexus.system.trade.ItemSpec.KeyRule> matchKeys;
        /** 耐久度要求开关（默认 false）。 */
        public final boolean durabilityEnabled;
        /** 耐久度运算符（= / < / > / <= / >=）。 */
        public final String durabilityOp;
        /** 耐久度比较值（剩余耐久）。 */
        public final int durabilityValue;
        public final int stock;
        public final int stockMin;
        public final int stockMax;
        public final boolean blockBelowMin;
        public final boolean buyEnabled;
        // 买入
        public final long buyPrice;
        public final int buyCode;
        public final int buyLimit;
        // 卖出（回收）
        public final long sellPrice;
        public final int sellCode;
        // 市场参考（展示）
        public final long marketPrice;
        public final int marketCode;

        public Good(String id, String categoryId, String displayName, String itemId, String nbt,
                    int unitCount, String matchMode,
                    java.util.Map<String, com.deltanexus.system.trade.ItemSpec.KeyRule> matchKeys,
                    boolean durabilityEnabled, String durabilityOp, int durabilityValue,
                    int stock, int stockMin, int stockMax, boolean blockBelowMin,
                    boolean buyEnabled, long buyPrice, int buyCode, int buyLimit,
                    long sellPrice, int sellCode, long marketPrice, int marketCode) {
            this.id = id;
            this.categoryId = categoryId;
            this.displayName = displayName;
            this.itemId = itemId;
            this.nbt = nbt;
            this.unitCount = unitCount;
            this.matchMode = matchMode;
            this.matchKeys = matchKeys == null ? java.util.Map.of() : java.util.Map.copyOf(matchKeys);
            this.durabilityEnabled = durabilityEnabled;
            this.durabilityOp = durabilityOp;
            this.durabilityValue = durabilityValue;
            this.stock = stock;
            this.stockMin = stockMin;
            this.stockMax = stockMax;
            this.blockBelowMin = blockBelowMin;
            this.buyEnabled = buyEnabled;
            this.buyPrice = buyPrice;
            this.buyCode = buyCode;
            this.buyLimit = buyLimit;
            this.sellPrice = sellPrice;
            this.sellCode = sellCode;
            this.marketPrice = marketPrice;
            this.marketCode = marketCode;
        }
    }

    public final List<Category> categories;
    public final List<Good> goods;
    public final long serverTimeMs;

    public SyncTradeCatalogPacket(List<Category> categories, List<Good> goods, long serverTimeMs) {
        this.categories = List.copyOf(categories);
        this.goods = List.copyOf(goods);
        this.serverTimeMs = serverTimeMs;
    }

    public static void encode(SyncTradeCatalogPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.categories.size());
        for (Category c : msg.categories) {
            buf.writeUtf(c.id);
            buf.writeUtf(c.name);
        }
        buf.writeVarInt(msg.goods.size());
        for (Good g : msg.goods) {
            buf.writeUtf(g.id);
            buf.writeUtf(g.categoryId);
            buf.writeUtf(g.displayName);
            buf.writeUtf(g.itemId);
            buf.writeUtf(g.nbt);
            buf.writeVarInt(g.unitCount);
            buf.writeUtf(g.matchMode == null ? "id" : g.matchMode);
            buf.writeVarInt(g.matchKeys.size());
            for (var e : g.matchKeys.entrySet()) {
                com.deltanexus.system.trade.ItemSpec.KeyRule rule = e.getValue();
                buf.writeUtf(e.getKey());
                buf.writeUtf(rule == null || rule.op == null ? "exact" : rule.op.key);
                buf.writeUtf(rule == null || rule.value == null ? "" : rule.value);
            }
            buf.writeBoolean(g.durabilityEnabled);
            buf.writeUtf(g.durabilityOp == null ? "=" : g.durabilityOp);
            buf.writeVarInt(g.durabilityValue);
            buf.writeVarInt(g.stock);
            buf.writeVarInt(g.stockMin);
            buf.writeVarInt(g.stockMax);
            buf.writeBoolean(g.blockBelowMin);
            buf.writeBoolean(g.buyEnabled);
            buf.writeLong(g.buyPrice);
            buf.writeVarInt(g.buyCode);
            buf.writeVarInt(g.buyLimit);
            buf.writeLong(g.sellPrice);
            buf.writeVarInt(g.sellCode);
            buf.writeLong(g.marketPrice);
            buf.writeVarInt(g.marketCode);
        }
        buf.writeLong(msg.serverTimeMs);
    }

    public static SyncTradeCatalogPacket decode(FriendlyByteBuf buf) {
        List<Category> categories = new ArrayList<>();
        int catSize = buf.readVarInt();
        for (int i = 0; i < catSize; i++) {
            categories.add(new Category(buf.readUtf(), buf.readUtf()));
        }
        List<Good> goods = new ArrayList<>();
        int goodSize = buf.readVarInt();
        for (int i = 0; i < goodSize; i++) {
            String id = buf.readUtf();
            String categoryId = buf.readUtf();
            String displayName = buf.readUtf();
            String itemId = buf.readUtf();
            String nbt = buf.readUtf();
            int unitCount = buf.readVarInt();
            String matchMode = buf.readUtf();
            int keySize = buf.readVarInt();
            java.util.Map<String, com.deltanexus.system.trade.ItemSpec.KeyRule> matchKeys = new java.util.LinkedHashMap<>();
            for (int k = 0; k < keySize; k++) {
                String key = buf.readUtf();
                String op = buf.readUtf();
                String value = buf.readUtf();
                matchKeys.put(key, new com.deltanexus.system.trade.ItemSpec.KeyRule(
                        com.deltanexus.system.trade.ItemSpec.KeyOp.parse(op), value));
            }
            boolean durabilityEnabled = buf.readBoolean();
            String durabilityOp = buf.readUtf();
            int durabilityValue = buf.readVarInt();
            goods.add(new Good(
                    id, categoryId, displayName, itemId, nbt,
                    unitCount, matchMode, matchKeys,
                    durabilityEnabled, durabilityOp, durabilityValue,
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                    buf.readBoolean(), buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                    buf.readLong(), buf.readVarInt(), buf.readLong(), buf.readVarInt()));
        }
        return new SyncTradeCatalogPacket(categories, goods, buf.readLong());
    }

    public static void handle(SyncTradeCatalogPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.handle(msg)));
        context.setPacketHandled(true);
    }

    /** 客户端处理（专用服务器不加载）。 */
    @OnlyIn(Dist.CLIENT)
    private static class ClientHandler {
        static void handle(SyncTradeCatalogPacket msg) {
            com.deltanexus.system.client.TradeClientState.setCatalog(msg);
        }
    }
}
