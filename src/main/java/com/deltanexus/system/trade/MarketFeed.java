package com.deltanexus.system.trade;

/**
 * 外部价格源（Market Feed）SPI。
 *
 * <p>交易行核心不关心某个源的具体数据长什么样、来自哪里——它只通过本接口拿到
 * “行表快照”。私有源（如 moligod companion，仅服务端、不外发）或任何第三方源
 * 实现本接口并经 {@link TradeFeedRegistry} 注册后，即可在价格公式/脚本里以
 * {@code feed('<id>')} 访问。</p>
 *
 * <p>线程约束：{@link #snapshot()} 必须廉价且可随时调用（返回最近一次成功快照）；
 * 需要抓网络的实现应在 {@link #refreshIfStale(long)} 内**异步**发起刷新（不阻塞
 * Minecraft 主线程），旧快照在刷新完成前继续可用。</p>
 */
public interface MarketFeed {

    /** 源 id（唯一；脚本中以 feed('id') 访问）。 */
    String id();

    /** 最近一次成功快照（无数据时返回空行表快照，绝不返回 null）。 */
    FeedSnapshot snapshot();

    /**
     * 若上次加载早于 maxAgeMs 则触发一次刷新（实现内自行决定同步/异步与节流）。
     *
     * @param maxAgeMs 期望数据新鲜度上限
     */
    void refreshIfStale(long maxAgeMs);

    /** 人类可读描述（/dn trade debug、Web 展示）。 */
    String description();
}
