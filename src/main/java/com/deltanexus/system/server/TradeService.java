package com.deltanexus.system.server;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.common.FormatUtil;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.menu.WarehouseMenu;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2STradeSellPacket;
import com.deltanexus.system.network.packet.OpenScreenPacket;
import com.deltanexus.system.network.packet.SyncTradeCatalogPacket;
import com.deltanexus.system.trade.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易行服务端核心（0.2.0Beta）。
 *
 * <p>买入流水线（v1 唯一玩家操作方向）：</p>
 * <ol>
 *   <li>校验：商品存在且上架、可买入、库存 &gt; 0、未低于“缺货阻断线”（{@code block_below_min} 且
 *       stockMin&gt;0 时须 stock &gt; stockMin，一次购买上限 = stock - stockMin）；</li>
 *   <li>求价：按商品买入价策略求值（fixed/formula/code，JS 沙箱），乘全局倍率向下取整且 ≥ 1；
 *       0/负/异常 = 定价不可用，禁止交易并记日志；</li>
 *   <li>货币：沿用 {@link CurrencyManager} 当前货币，不足即拒绝；</li>
 *   <li>空间：把“unitCount×qty”拆成合法堆叠，用
 *       {@link com.deltanexus.system.grid.InventoryGridHandler#placeIntoWarehouse placeIntoWarehouse(simulate)}
 *       逐份做格式背包兼容的空间预演，放不下即拒绝购买并提示（不落入背包、不丢弃）；</li>
 *   <li>结算：扣钱 → 真实落库（每份落主格并写占位物）→ 扣减运行时库存 → 重发目录给买家。</li>
 * </ol>
 *
 * <p>消息一律 {@code msg.dn.trade.*} 文案键（语言文件），反馈用热栏 toast。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID)
public final class TradeService {

    // ---- 买入状态码（SyncTradeCatalogPacket.Good.buyCode） ----
    /** 可购买（buyPrice/buyLimit 有效）。 */
    public static final int BUY_OK = 0;
    /** 缺货（stock == 0）。 */
    public static final int BUY_OUT_OF_STOCK = 1;
    /** 库存低于下限（缺货阻断线）。 */
    public static final int BUY_LOW_STOCK = 2;
    /** 定价不可用（策略缺失/求值失败）。 */
    public static final int BUY_PRICE_UNAVAILABLE = 3;
    /** 不可购买（未上架/未启用/规格非法/策略缺失）。 */
    public static final int BUY_NOT_BUYABLE = 4;

    /** 未配置/无值（sell/market 与 buy 的价格列）。 */
    public static final int PRICE_NONE = 1;

    private TradeService() {
    }

    // ------------------------------------------------------------------
    // 打开
    // ------------------------------------------------------------------

    /** 打开交易行（/dn open trade、按键、C2S 同源；服务端权限权威）。 */
    public static void open(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        if (!PermissionManager.canOpenTrade(player)) {
            msg(player, "msg.dn.perm.denied.trade");
            return;
        }
        sendSync(player);
        PacketHandler.sendToPlayer(player, new OpenScreenPacket(
                OpenScreenPacket.SCREEN_TRADE, "", "", player.hasPermissions(4)));
    }

    // ------------------------------------------------------------------
    // 目录同步
    // ------------------------------------------------------------------

    /** 计算并下发目录快照给单个玩家。 */
    public static void sendSync(ServerPlayer player) {
        PacketHandler.sendToPlayer(player, buildCatalog());
    }

    public static void sendSyncToAll(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        SyncTradeCatalogPacket packet = buildCatalog();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketHandler.sendToPlayer(p, packet);
        }
    }

    /** 构建目录快照（服务端求值价格，客户端只读展示）。 */
    public static SyncTradeCatalogPacket buildCatalog() {
        TradeConfig cfg = TradeConfig.get();
        String feedJson = buildFeedJson();
        long now = System.currentTimeMillis();

        List<SyncTradeCatalogPacket.Category> categories = new ArrayList<>();
        for (TradeCategory c : cfg.categoriesSnapshot()) {
            categories.add(new SyncTradeCatalogPacket.Category(c.id, c.name));
        }

        List<SyncTradeCatalogPacket.Good> goods = new ArrayList<>();
        for (TradeGood g : cfg.goodsSnapshot()) {
            int stock = TradeStockStore.get(g.id);
            boolean listed = g.isListed();
            java.util.Map<String, ItemSpec.KeyRule> keys = g.spec.matchKeys;
            String modeKey = g.spec.matchMode.key;

            // 卖出价 / 市场参考（与买入解耦：只回收、不上架的商品同样可卖）
            long sellPrice = -1;
            int sellCode = PRICE_NONE;
            if (listed && g.sell != null && g.sell.isValid()) {
                Eval r = evalPrice(g, "sell", 1, feedJson);
                if (r.ok) {
                    sellPrice = r.price;
                    sellCode = BUY_OK;
                }
            }
            long marketPrice = -1;
            int marketCode = PRICE_NONE;
            if (listed && g.market != null && g.market.isValid()) {
                Eval r = evalPrice(g, "market", 1, feedJson);
                if (r.ok) {
                    marketPrice = r.price;
                    marketCode = BUY_OK;
                }
            }

            boolean buyable = listed && g.buyable && g.buy != null && g.buy.isValid();
            long buyPrice = -1;
            int buyCode = BUY_NOT_BUYABLE;
            int limit = 0;
            if (buyable) {
                int reserved = (g.blockBelowMin && g.stockMin > 0) ? g.stockMin : 0;
                limit = Math.max(0, stock - reserved);
                if (stock <= 0) {
                    buyCode = BUY_OUT_OF_STOCK;
                    limit = 0;
                } else if (limit <= 0) {
                    buyCode = BUY_LOW_STOCK;
                } else {
                    Eval r = evalPrice(g, "buy", 1, feedJson);
                    if (r.ok) {
                        buyCode = BUY_OK;
                        buyPrice = r.price;
                    } else {
                        buyCode = BUY_PRICE_UNAVAILABLE;
                        limit = 0;
                    }
                }
            }
            goods.add(new SyncTradeCatalogPacket.Good(
                    g.id, g.categoryId, g.displayName(), g.spec.item, g.spec.nbt,
                    g.spec.unitCount, modeKey, keys,
                    g.spec.durabilityEnabled, g.spec.durabilityOp, g.spec.durabilityValue,
                    stock, g.stockMin, g.stockMax, g.blockBelowMin,
                    buyable, buyPrice, buyCode, limit, sellPrice, sellCode, marketPrice, marketCode));
        }
        return new SyncTradeCatalogPacket(categories, goods, now);
    }

    // ------------------------------------------------------------------
    // 买入
    // ------------------------------------------------------------------

    /** 尝试买入（服务端权威；C2S 在主线程执行）。 */
    public static void buy(ServerPlayer player, String goodId, int qty) {
        if (player == null) {
            return;
        }
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        if (!PermissionManager.canOpenTrade(player)) {
            msg(player, "msg.dn.perm.denied.trade");
            return;
        }
        TradeConfig cfg = TradeConfig.get();
        TradeGood g = cfg.good(goodId);
        if (g == null || !g.isListed() || !g.buyable || g.buy == null || !g.buy.isValid()) {
            msg(player, "msg.dn.trade.buy.not_buyable");
            return;
        }
        int stock = TradeStockStore.get(goodId);
        if (stock <= 0) {
            msg(player, "msg.dn.trade.buy.out_of_stock");
            sendSync(player);
            return;
        }
        int reserved = (g.blockBelowMin && g.stockMin > 0) ? g.stockMin : 0;
        int limit = stock - reserved;
        if (limit <= 0) {
            msg(player, "msg.dn.trade.buy.low_stock", stock, g.stockMin);
            sendSync(player);
            return;
        }
        qty = Math.max(1, Math.min(qty, 100_000));
        if (qty > limit) {
            msg(player, "msg.dn.trade.buy.too_many", limit);
            return;
        }

        // 求价（按本次数量做单价求值；支持按量计价公式）
        String feedJson = buildFeedJson();
        Eval r = evalPrice(g, "buy", qty, feedJson);
        if (!r.ok) {
            DeltaNexus.LOGGER.warn("[DN] 交易行买入被拒 {}：商品 {} ({}) 定价不可用: {}",
                    player.getGameProfile().getName(), goodId, r.error);
            msg(player, "msg.dn.trade.buy.price_unavailable");
            sendSync(player);
            return;
        }
        long total;
        try {
            total = Math.multiplyExact(r.price, qty);
        } catch (ArithmeticException e) {
            msg(player, "msg.dn.trade.buy.price_unavailable");
            return;
        }

        // 库存/货币/空间预演（先不扣钱）
        if (!CurrencyManager.isUsable()) {
            msg(player, "msg.dn.trade.buy.no_currency");
            return;
        }
        if (!CurrencyManager.canAfford(player, total)) {
            msg(player, "msg.dn.trade.buy.no_money", FormatUtil.compact(total));
            return;
        }
        List<ItemStack> payload = buildPayload(g.spec, qty);
        for (ItemStack stack : payload) {
            if (!InventoryGridHandler.placeIntoWarehouse(player, stack, true).isEmpty()) {
                msg(player, "msg.dn.trade.buy.no_space");
                return;
            }
        }

        // 结算
        if (!CurrencyManager.spend(player, total)) {
            msg(player, "msg.dn.trade.buy.no_money", FormatUtil.compact(total));
            return;
        }
        for (ItemStack stack : payload) {
            ItemStack left = InventoryGridHandler.placeIntoWarehouse(player, stack, false);
            if (!left.isEmpty()) {
                // 理论不可达（预演已通过）；防御性兜底：退入背包，仍失败则掉落并告警
                DeltaNexus.LOGGER.error("[DN] 交易行落库意外失败 {} 商品 {} 剩余 {}",
                        player.getGameProfile().getName(), goodId, left.getCount());
                ItemStack rest = player.getInventory().add(left) ? ItemStack.EMPTY : left;
                if (!rest.isEmpty()) {
                    player.drop(rest, false);
                }
                sendSync(player);
                return;
            }
        }
        TradeStockStore.consume(goodId, qty);
        refreshOpenWarehouse(player);
        msg(player, "msg.dn.trade.buy.ok", g.displayName(), qty, FormatUtil.compact(total));
        sendSync(player);
        DeltaNexus.LOGGER.info("[DN] 交易行成交 {}：{} x{} = {}（单价 {}，库存 {}→{}）",
                player.getGameProfile().getName(), g.id, qty, total, r.price, stock, stock - qty);
    }

    /** 拆分成合法堆叠（每份 ≤ 最大堆叠；总量 = unitCount × qty）。 */
    private static List<ItemStack> buildPayload(ItemSpec spec, int qty) {
        long totalItems = (long) spec.unitCount * qty;
        List<ItemStack> out = new ArrayList<>();
        ItemStack proto = spec.buildStack(1);
        if (proto.isEmpty()) {
            return out;
        }
        int max = proto.getMaxStackSize();
        while (totalItems > 0) {
            int chunk = (int) Math.min(max, totalItems);
            ItemStack stack = spec.buildStack(chunk);
            if (stack.isEmpty()) {
                break;
            }
            out.add(stack);
            totalItems -= chunk;
        }
        return out;
    }

    /** 若买家开着仓库菜单则广播槽位（仓库内容仅经菜单槽位同步到客户端）。 */
    private static void refreshOpenWarehouse(ServerPlayer player) {
        if (player.containerMenu instanceof WarehouseMenu wm) {
            wm.broadcastChanges();
        }
    }

    // ------------------------------------------------------------------
    // 卖出（仓库界面回收）
    // ------------------------------------------------------------------

    /** 卖出计划项（source：0=仓库 1=背包 2=安全箱）。 */
    private record SellPlan(TradeGood good, int source, int slot, int qty, long unitPrice) {
    }

    /** 来源是否有效（含解锁校验）。 */
    private static boolean isValidSellSource(IPlayerData data, int source, int slot) {
        if (slot < 0) {
            return false;
        }
        return switch (source) {
            case C2STradeSellPacket.SOURCE_INVENTORY -> slot < 36;
            case C2STradeSellPacket.SOURCE_SAFE_BOX ->
                    slot < data.getSafeBoxHandler().getSlots() && data.isSafeSlotUnlocked(slot);
            default -> slot < data.getCapacity() && data.isSlotUnlocked(slot);
        };
    }

    /** 读取来源槽位物品。 */
    private static ItemStack readSellSource(ServerPlayer player, IPlayerData data, int source, int slot) {
        if (!isValidSellSource(data, source, slot)) {
            return ItemStack.EMPTY;
        }
        return switch (source) {
            case C2STradeSellPacket.SOURCE_INVENTORY -> player.getInventory().getItem(slot);
            case C2STradeSellPacket.SOURCE_SAFE_BOX -> data.getSafeBoxHandler().getStackInSlot(slot);
            default -> data.getWarehouseItem(slot);
        };
    }

    /** 写回来源槽位。 */
    private static void writeSellSource(ServerPlayer player, IPlayerData data, int source, int slot, ItemStack stack) {
        switch (source) {
            case C2STradeSellPacket.SOURCE_INVENTORY -> player.getInventory().setItem(slot, stack);
            case C2STradeSellPacket.SOURCE_SAFE_BOX -> data.getSafeBoxHandler().setStackInSlot(slot, stack);
            default -> data.setWarehouseItem(slot, stack);
        }
    }

    /** 原件回滚键（source + slot）。 */
    private static long originKey(int source, int slot) {
        return ((long) source << 32) | (slot & 0xFFFFFFFFL);
    }

    /**
     * 仓库界面卖出（服务端权威）：逐项校验槽位/物品/匹配/库存上限/价差/价格，
     * 扣物 → 付款 → 加库存 → arrange 清理占位物 → 刷新视口与余额；失败项跳过并汇总。
     */
    public static void sell(ServerPlayer player, List<C2STradeSellPacket.Entry> entries) {
        if (player == null || entries == null || entries.isEmpty()) {
            return;
        }
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        if (!PermissionManager.canOpenTrade(player)) {
            msg(player, "msg.dn.perm.denied.trade");
            return;
        }
        if (!TradeConfig.get().settings().sellEnabled) {
            msg(player, "msg.dn.trade.sell.disabled");
            return;
        }
        IPlayerData data = ManufacturingService.data(player);
        if (data == null) {
            return;
        }
        String feedJson = buildFeedJson();
        List<TradeGood> goods = TradeConfig.get().goodsSnapshot();

        List<SellPlan> plans = new ArrayList<>();
        Map<String, Integer> skipReasons = new java.util.LinkedHashMap<>();
        java.util.Set<String> spreadWarned = new java.util.HashSet<>();
        long total = 0L;

        for (C2STradeSellPacket.Entry e : entries) {
            int source = e.source;
            int slot = e.slot;
            if (!isValidSellSource(data, source, slot)) {
                bump(skipReasons, "槽位无效");
                continue;
            }
            ItemStack stack = readSellSource(player, data, source, slot);
            if (stack.isEmpty() || InventoryGridHandler.isSlave(stack)) {
                bump(skipReasons, "物品已变化");
                continue;
            }
            int qty = Math.min(Math.max(1, e.qty), stack.getCount());
            TradeGood g = bestSellGood(goods, stack);
            if (g == null) {
                bump(skipReasons, "不可回收");
                continue;
            }
            int stock = TradeStockStore.get(g.id);
            if (g.stockMax > 0 && stock >= g.stockMax) {
                bump(skipReasons, "回收已满");
                continue;
            }
            Eval r = evalPrice(g, "sell", qty, feedJson);
            if (!r.ok) {
                bump(skipReasons, "价格不可用");
                continue;
            }
            // 套利保护：卖出价 ≥ 买入价
            String guard = TradeConfig.get().settings().sellSpreadGuard;
            if (!"off".equals(guard) && g.buyable && g.buy != null && g.buy.isValid()) {
                Eval buyUnit = evalPrice(g, "buy", 1, feedJson);
                if (buyUnit.ok && r.price >= buyUnit.price) {
                    if ("block".equals(guard)) {
                        bump(skipReasons, "价差保护");
                        continue;
                    }
                    if (spreadWarned.add(g.id)) {
                        DeltaNexus.LOGGER.warn("[DN] 交易行价差告警：商品 {} 卖出价 {} ≥ 买入价 {}，"
                                + "存在买→卖套利（sell_spread_guard=warn）", g.id, r.price, buyUnit.price);
                    }
                }
            }
            long sub;
            try {
                sub = Math.multiplyExact(r.price, qty);
            } catch (ArithmeticException ex) {
                bump(skipReasons, "金额过大");
                continue;
            }
            total += sub;
            plans.add(new SellPlan(g, source, slot, qty, r.price));
        }

        if (plans.isEmpty()) {
            msg(player, "msg.dn.trade.sell.none");
            reportSkipped(player, skipReasons);
            return;
        }
        // 收款能力/上限预检（计分板为 int 分数）
        if ("scoreboard".equals(CurrencyManager.type())
                && CurrencyManager.getBalance(player) + total > Integer.MAX_VALUE) {
            msg(player, "msg.dn.trade.sell.amount_overflow");
            return;
        }
        if (!CurrencyManager.canPay(player, total)) {
            msg(player, "msg.dn.trade.sell.no_currency");
            return;
        }

        // 扣物（记录原件用于付款失败回滚；此时尚未 arrange，槽位内容可直接还原）
        Map<Long, ItemStack> originals = new java.util.LinkedHashMap<>();
        Map<String, Integer> stockAdd = new java.util.LinkedHashMap<>();
        int soldUnits = 0;
        long actualTotal = 0L;
        for (SellPlan p : plans) {
            ItemStack cur = readSellSource(player, data, p.source, p.slot);
            if (cur.isEmpty() || !p.good.spec.matches(cur)) {
                bump(skipReasons, "物品已变化");
                continue;
            }
            int take = Math.min(p.qty, cur.getCount());
            if (take <= 0) {
                continue;
            }
            originals.putIfAbsent(originKey(p.source, p.slot), cur.copy());
            ItemStack rest = cur.copy();
            rest.shrink(take);
            writeSellSource(player, data, p.source, p.slot, rest.isEmpty() ? ItemStack.EMPTY : rest);
            stockAdd.merge(p.good.id, take, Integer::sum);
            soldUnits += take;
            actualTotal += p.unitPrice * take;
        }
        if (soldUnits <= 0) {
            msg(player, "msg.dn.trade.sell.none");
            reportSkipped(player, skipReasons);
            return;
        }
        // 付款（失败则原样还原物品，不动库存/占位物）
        if (!CurrencyManager.pay(player, actualTotal)) {
            originals.forEach((key, stack) ->
                    writeSellSource(player, data, (int) (key >> 32), (int) (key & 0xFFFFFFFFL), stack));
            player.inventoryMenu.broadcastChanges();
            msg(player, "msg.dn.trade.sell.refunded");
            DeltaNexus.LOGGER.error("[DN] 交易行卖出付款失败，已还原 {} 件（玩家 {}）",
                    soldUnits, player.getGameProfile().getName());
            return;
        }
        // 成交：加库存 + 整理占位物（仓库/安全箱）+ 刷新视口/背包/余额
        stockAdd.forEach((id, n) -> TradeStockStore.add(id, n));
        InventoryGridHandler.arrange(player, data.getWarehouseHandler());
        InventoryGridHandler.arrange(player, data.getSafeBoxHandler());
        refreshOpenWarehouse(player);
        player.inventoryMenu.broadcastChanges();
        ManufacturingService.syncSafeBox(player);
        ManufacturingService.sendSyncWarehouse(player);
        msg(player, "msg.dn.trade.sell.ok", soldUnits, FormatUtil.compact(actualTotal));
        reportSkipped(player, skipReasons);
        DeltaNexus.LOGGER.info("[DN] 交易行回收 {}：{} 件 = {}（货币 {}）",
                player.getGameProfile().getName(), soldUnits, actualTotal, CurrencyManager.type());
    }

    private static void bump(Map<String, Integer> map, String reason) {
        map.merge(reason, 1, Integer::sum);
    }

    private static void reportSkipped(ServerPlayer player, Map<String, Integer> reasons) {
        if (reasons.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        reasons.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(k).append(' ').append(v).append(" 件");
        });
        int skipped = reasons.values().stream().mapToInt(Integer::intValue).sum();
        msg(player, "msg.dn.trade.sell.skipped", skipped, sb.toString());
    }

    /**
     * 匹配"最适合回收该物品"的商品：优先级 full_nbt &gt; partial_nbt &gt; id，
     * 同级取配置顺序靠前者；要求商品已上架且卖出价策略有效。
     */
    public static TradeGood bestSellGood(List<TradeGood> goods, ItemStack stack) {
        TradeGood best = null;
        int bestPriority = 0;
        for (TradeGood g : goods) {
            if (!g.isListed() || g.sell == null || !g.sell.isValid()) {
                continue;
            }
            if (!g.spec.matches(stack)) {
                continue;
            }
            int priority = switch (g.spec.matchMode) {
                case FULL_NBT -> 3;
                case PARTIAL_NBT -> 2;
                default -> 1;
            };
            if (priority > bestPriority) {
                best = g;
                bestPriority = priority;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 求价
    // ------------------------------------------------------------------

    private static final class Eval {
        final boolean ok;
        final long price;
        final String error;

        Eval(boolean ok, long price, String error) {
            this.ok = ok;
            this.price = price;
            this.error = error;
        }
    }

    /** 单方向单价求值（结果已乘全局倍率并 floor；失败 ok=false）。 */
    private static Eval evalPrice(TradeGood g, String direction, int qty, @Nullable String feedJson) {
        PricePolicy policy = switch (direction) {
            case "sell" -> g.sell;
            case "market" -> g.market;
            default -> g.buy;
        };
        if (policy == null || !policy.isValid()) {
            return new Eval(false, -1, "missing policy");
        }
        int stock = TradeStockStore.get(g.id);
        PriceEngine.Input in = new PriceEngine.Input();
        in.goodId = g.id;
        in.displayName = g.displayName();
        in.stock = stock;
        in.stockMin = g.stockMin;
        in.stockMax = g.stockMax;
        in.unitCount = g.spec.unitCount;
        in.qty = qty;
        in.direction = direction;
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        in.hour = now.getHour();
        in.weekday = now.getDayOfWeek().getValue();
        in.day = now.getDayOfMonth();

        try {
            double raw = PriceEngine.eval(policy, in, feedJson, TradeConfig.get().settings().evalTimeoutMs);
            if (!Double.isFinite(raw) || raw < 1.0) {
                return new Eval(false, -1, "non-positive or non-finite: " + raw);
            }
            double mult = TradeConfig.get().settings().multiplier;
            long price = (long) Math.floor(raw * (mult > 0 ? mult : 1.0));
            if (price < 1) {
                return new Eval(false, -1, "below 1 after multiplier");
            }
            return new Eval(true, price, null);
        } catch (PriceEngine.EvalException e) {
            return new Eval(false, -1, e.getMessage());
        }
    }

    /** 汇聚全部外部源快照并序列化为 JS ctx.feeds 字面量。 */
    private static String buildFeedJson() {
        List<FeedSnapshot> snaps = new ArrayList<>();
        for (MarketFeed feed : TradeFeedRegistry.all()) {
            snaps.add(feed.snapshot());
        }
        return PriceEngine.buildFeedsJson(snaps);
    }

    // ------------------------------------------------------------------
    // Feed 定时刷新（服务端 tick；由各 feed 自行决定是否异步抓网）
    // ------------------------------------------------------------------

    private static final Map<String, Long> LAST_REFRESH = new ConcurrentHashMap<>();
    private static long lastTick = 0;

    /** 服务器停止：把防反跳中的库存/定义立即刷盘（避免关服瞬间丢库存）。 */
    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        try {
            TradeStockStore.saveNow();
            TradeConfig.get().saveNow();
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] 交易行关服刷盘失败: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTick < 1000) {
            return;
        }
        lastTick = now;
        int intervalS = TradeConfig.get().settings().feedRefreshIntervalS;
        long intervalMs = Math.max(1, intervalS) * 1000L;
        for (MarketFeed feed : TradeFeedRegistry.all()) {
            Long last = LAST_REFRESH.get(feed.id());
            if (last != null && now - last < intervalMs) {
                continue;
            }
            LAST_REFRESH.put(feed.id(), now);
            try {
                feed.refreshIfStale(intervalMs);
            } catch (Exception e) {
                DeltaNexus.LOGGER.warn("[DN] 交易行源 '{}' 刷新异常: {}", feed.id(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // 反馈
    // ------------------------------------------------------------------

    /** 热栏提示（与制造系统 msg() 同风格）。 */
    public static void msg(ServerPlayer player, String key, Object... args) {
        if (player == null) {
            return;
        }
        Component text = Component.translatable(key, args);
        player.displayClientMessage(text, true);
    }

    /** 日志辅助：格式化 Debug（/dn trade debug 输出用，后续指令轮补）。 */
    public static String currencyLabel() {
        return CurrencyManager.type();
    }
}
