package com.deltanexus.system.client;

import com.deltanexus.system.network.packet.SyncTradeCatalogPacket;
import com.deltanexus.system.trade.ItemSpec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端「可回收商品」索引（0.2.0Beta，仓库界面卖出用）。
 *
 * <p>性能设计：</p>
 * <ul>
 *   <li>只索引目录中<b>卖出价可用</b>（sellCode==0）的商品；</li>
 *   <li>按物品注册 id 分桶，桶内按匹配优先级 full_nbt &gt; partial_nbt &gt; id 排序，
 *       每个仓库格只查同 id 小桶，不遍历全部商品；</li>
 *   <li>索引按 {@link TradeClientState#revision()} 缓存，目录不变则零重建；</li>
 *   <li>NBT 模板由 {@link ItemSpec} 内部缓存解析结果，避免每次匹配重复解析 SNBT。</li>
 * </ul>
 */
public final class TradeSellIndex {

    /** 命中结果（展示用）。 */
    public static final class Match {
        public final String goodId;
        public final String displayName;
        /** 目录中已求值的卖出单价（qty=1 口径，仅用于展示与预估）。 */
        public final long unitPrice;
        public final int priority;

        Match(String goodId, String displayName, long unitPrice, int priority) {
            this.goodId = goodId;
            this.displayName = displayName;
            this.unitPrice = unitPrice;
            this.priority = priority;
        }
    }

    private record Entry(String goodId, String displayName, long unitPrice, int priority, ItemSpec spec) {
    }

    private static volatile TradeSellIndex instance;
    private static volatile long builtRevision = Long.MIN_VALUE;

    private final Map<String, List<Entry>> byItem;
    private final long revision;

    private TradeSellIndex(Map<String, List<Entry>> byItem, long revision) {
        this.byItem = byItem;
        this.revision = revision;
    }

    /** 取当前目录对应的索引（目录修订号变化才重建）。 */
    public static TradeSellIndex get() {
        long rev = TradeClientState.revision();
        TradeSellIndex idx = instance;
        if (idx != null && idx.revision == rev) {
            return idx;
        }
        synchronized (TradeSellIndex.class) {
            if (instance != null && instance.revision == rev) {
                return instance;
            }
            Map<String, List<Entry>> map = new HashMap<>();
            for (SyncTradeCatalogPacket.Good g : TradeClientState.goods()) {
                if (g.sellCode != 0 || g.itemId == null || g.itemId.isEmpty()) {
                    continue;
                }
                ItemSpec spec = new ItemSpec();
                spec.item = g.itemId;
                spec.nbt = g.nbt == null ? "" : g.nbt;
                spec.unitCount = Math.max(1, g.unitCount);
                spec.matchMode = ItemSpec.MatchMode.parse(g.matchMode);
                for (Map.Entry<String, ItemSpec.KeyRule> e : g.matchKeys.entrySet()) {
                    ItemSpec.KeyRule r = e.getValue();
                    spec.matchKeys.put(e.getKey(), new ItemSpec.KeyRule(
                            r == null || r.op == null ? ItemSpec.KeyOp.EXACT : r.op,
                            r == null || r.value == null ? "" : r.value));
                }
                spec.durabilityEnabled = g.durabilityEnabled;
                spec.durabilityOp = g.durabilityOp == null ? "=" : g.durabilityOp;
                spec.durabilityValue = g.durabilityValue;
                int priority = switch (spec.matchMode) {
                    case FULL_NBT -> 3;
                    case PARTIAL_NBT -> 2;
                    default -> 1;
                };
                map.computeIfAbsent(g.itemId, k -> new ArrayList<>())
                        .add(new Entry(g.id, g.displayName, g.sellPrice, priority, spec));
            }
            map.values().forEach(list -> list.sort((a, b) -> Integer.compare(b.priority(), a.priority())));
            instance = new TradeSellIndex(map, rev);
            builtRevision = rev;
            return instance;
        }
    }

    /** 匹配最适合回收该物品的商品；无则 null。 */
    public Match match(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return null;
        }
        List<Entry> list = byItem.get(key.toString());
        if (list == null) {
            return null;
        }
        for (Entry e : list) {
            if (e.spec().matches(stack)) {
                return new Match(e.goodId(), e.displayName(), e.unitPrice(), e.priority());
            }
        }
        return null;
    }

    public long revision() {
        return revision;
    }
}
