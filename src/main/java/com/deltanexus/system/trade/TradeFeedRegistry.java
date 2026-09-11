package com.deltanexus.system.trade;

import com.deltanexus.system.DeltaNexus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部价格源注册表（服务端）。
 *
 * <p>注册途径：</p>
 * <ul>
 *   <li>核心在 commonSetup 时于 mod bus 上发布 {@link RegisterMarketFeedsEvent}，
 *       companion/第三方源用 {@code @Mod.EventBusSubscriber(bus = Bus.MOD)} 监听并注册；</li>
 *   <li>亦可直接调用 {@link #register(MarketFeed)}（启动早期）。</li>
 * </ul>
 *
 * <p>重复 id 注册以最后一次为准并告警。脚本访问不到的源（未注册）在公式里为
 * {@code feed('xxx') == null}，由管理员表达式自行兜底；未处理的求值失败会禁止交易。</p>
 */
public final class TradeFeedRegistry {

    private static final Map<String, MarketFeed> FEEDS = new ConcurrentHashMap<>();

    private TradeFeedRegistry() {
    }

    public static void register(MarketFeed feed) {
        if (feed == null || feed.id() == null || feed.id().isBlank()) {
            DeltaNexus.LOGGER.warn("[DN] 交易行源注册失败：id 为空");
            return;
        }
        MarketFeed prev = FEEDS.put(feed.id(), feed);
        DeltaNexus.LOGGER.info("[DN] 交易行外部价格源已注册: '{}'{}",
                feed.id(), prev != null ? "（覆盖旧实例）" : "");
    }

    public static void unregister(String id) {
        FEEDS.remove(id);
    }

    /** 取源；未注册返回 null。 */
    public static MarketFeed get(String id) {
        return FEEDS.get(id);
    }

    /** 全部源快照（稳定副本）。 */
    public static List<MarketFeed> all() {
        return List.copyOf(FEEDS.values());
    }

    public static boolean isEmpty() {
        return FEEDS.isEmpty();
    }

    public static Map<String, MarketFeed> asMap() {
        return Map.copyOf(FEEDS);
    }
}
