package com.deltanexus.system.client;

import com.deltanexus.system.network.packet.SyncTradeCatalogPacket;

import java.util.List;

/**
 * 客户端交易行目录暂存（客户端专用）。
 *
 * <p>服务端在“打开交易行”前与每次成交/补货/重载后下发 {@link SyncTradeCatalogPacket}，
 * 本类持有最近一份快照；交易行界面（TradeScreen）在构造/重绘时读取。</p>
 */
public final class TradeClientState {

    private static volatile List<SyncTradeCatalogPacket.Category> categories = List.of();
    private static volatile List<SyncTradeCatalogPacket.Good> goods = List.of();
    private static volatile long serverTimeMs = 0L;
    private static volatile long revision = 0L;
    /** 是否已收到过服务端目录（区分“还没收到”与“已收到但为空”）。 */
    private static volatile boolean arrived = false;

    private TradeClientState() {
    }

    public static void setCatalog(SyncTradeCatalogPacket msg) {
        categories = msg.categories;
        goods = msg.goods;
        serverTimeMs = msg.serverTimeMs;
        revision++;
        arrived = true;
    }

    /** 是否已收到目录快照。 */
    public static boolean hasCatalog() {
        return arrived;
    }

    public static List<SyncTradeCatalogPacket.Category> categories() {
        return categories;
    }

    public static List<SyncTradeCatalogPacket.Good> goods() {
        return goods;
    }

    public static SyncTradeCatalogPacket.Good good(String id) {
        for (SyncTradeCatalogPacket.Good g : goods) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        return null;
    }

    public static long serverTimeMs() {
        return serverTimeMs;
    }

    /** 目录修订号（每次收到新快照自增，界面可据此判断是否需要重绘）。 */
    public static long revision() {
        return revision;
    }
}
