package com.deltanexus.system.trade;

import net.minecraftforge.eventbus.api.Event;

/**
 * Mod 总线事件：核心在 commonSetup 时发布，外部源（companion）监听并注册
 * {@link MarketFeed} 实现。例（外部 mod）：
 *
 * <pre>{@code
 * @Mod.EventBusSubscriber(modid = "moligodfeed", bus = Mod.EventBusSubscriber.Bus.MOD)
 * public class FeedRegistrar {
 *     @SubscribeEvent
 *     public static void onRegister(RegisterMarketFeedsEvent event) {
 *         event.register(new MoligodFeed());
 *     }
 * }
 * }</pre>
 */
public class RegisterMarketFeedsEvent extends Event {

    public void register(MarketFeed feed) {
        TradeFeedRegistry.register(feed);
    }
}
