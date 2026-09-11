package com.deltanexus.system.trade;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.JsonConfigWriter;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易行定义配置（{@code config/deltanexus/trade.json}，热加载，/dn reload 生效）。
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "settings": { "multiplier": 1.0, "feed_refresh_interval_s": 600, "eval_timeout_ms": 20 },
 *   "categories": [ { "id": "material", "name": "材料" } ],
 *   "goods": [ ... TradeGood ... ]
 * }
 * }</pre>
 *
 * <p>运行时库存（{@link TradeStockStore}）单独存于 trade-stock.json。
 * 商品目录默认为空：管理员用 /dn trade 或 Web 编辑器逐件上架。</p>
 */
public final class TradeConfig {

    /** 全局参数。 */
    public static final class Settings {
        /** 全局价格倍率（所有模式求值后统一乘，再取整；默认 1.0）。 */
        public double multiplier = 1.0;
        /** 外部源(Feed)刷新间隔（秒）。 */
        public int feedRefreshIntervalS = 600;
        /** 单次 JS 求值超时（毫秒）。 */
        public int evalTimeoutMs = 20;
        /** 是否允许玩家卖出（回收）物品，默认开。 */
        public boolean sellEnabled = true;
        /** 卖出价 ≥ 买入价时的套利保护：warn（仅告警，默认）/ block（禁止卖出该商品）/ off。 */
        public String sellSpreadGuard = "warn";

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("multiplier", multiplier);
            obj.addProperty("feed_refresh_interval_s", feedRefreshIntervalS);
            obj.addProperty("eval_timeout_ms", evalTimeoutMs);
            obj.addProperty("sell_enabled", sellEnabled);
            obj.addProperty("sell_spread_guard", sellSpreadGuard);
            return obj;
        }

        public static Settings fromJson(JsonObject obj) {
            Settings s = new Settings();
            if (obj == null) {
                return s;
            }
            if (obj.has("multiplier")) {
                double m = obj.get("multiplier").getAsDouble();
                s.multiplier = m > 0 ? m : 1.0;
            }
            s.feedRefreshIntervalS = obj.has("feed_refresh_interval_s")
                    ? Math.max(1, obj.get("feed_refresh_interval_s").getAsInt()) : 600;
            s.evalTimeoutMs = obj.has("eval_timeout_ms")
                    ? Math.max(1, Math.min(1000, obj.get("eval_timeout_ms").getAsInt())) : 20;
            s.sellEnabled = !obj.has("sell_enabled") || obj.get("sell_enabled").getAsBoolean();
            String guard = obj.has("sell_spread_guard")
                    ? obj.get("sell_spread_guard").getAsString().trim().toLowerCase(java.util.Locale.ROOT) : "warn";
            s.sellSpreadGuard = switch (guard) {
                case "block", "off" -> guard;
                default -> "warn";
            };
            return s;
        }

        /** 就地加载（保持对象身份不变，热重载用）。 */
        public void loadFrom(JsonObject obj) {
            Settings s = fromJson(obj);
            this.multiplier = s.multiplier;
            this.feedRefreshIntervalS = s.feedRefreshIntervalS;
            this.evalTimeoutMs = s.evalTimeoutMs;
            this.sellEnabled = s.sellEnabled;
            this.sellSpreadGuard = s.sellSpreadGuard;
        }
    }

    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("deltanexus/trade.json");

    private static volatile TradeConfig INSTANCE;

    /** 分类（插入序 = 侧栏展示序）。 */
    private final Map<String, TradeCategory> categories = new LinkedHashMap<>();
    /** 商品（插入序）。 */
    private final Map<String, TradeGood> goods = new LinkedHashMap<>();
    private final Settings settings = new Settings();

    private TradeConfig() {
    }

    // ------------------------------------------------------------------
    // 单例 / 生命周期
    // ------------------------------------------------------------------

    public static TradeConfig get() {
        TradeConfig c = INSTANCE;
        if (c == null) {
            synchronized (TradeConfig.class) {
                c = INSTANCE;
                if (c == null) {
                    c = new TradeConfig();
                    INSTANCE = c;
                }
            }
        }
        return c;
    }

    /** 首次启动生成默认文件（空目录）；已存在则热加载；两种情况都加载运行时库存。 */
    public static void writeDefaultIfMissing() {
        TradeConfig c = get();
        if (Files.exists(PATH)) {
            c.reload();
        } else {
            c.saveNow();
            DeltaNexus.LOGGER.info("[DN] 已生成默认交易行配置 {}", PATH);
        }
        // 关键：库存文件必须每次启动都加载（此前仅在配置缺失时创建，导致重启后库存归零）
        TradeStockStore.writeDefaultIfMissing();
    }

    /** 热加载（/dn reload / Web 修改后调用）。 */
    public synchronized void reload() {
        JsonObject root = JsonConfigWriter.readJson(PATH);
        categories.clear();
        goods.clear();
        if (root == null) {
            DeltaNexus.LOGGER.warn("[DN] 交易行配置缺失或非法，已重置为空目录");
            return;
        }
        settings.loadFrom(root.has("settings") && root.get("settings").isJsonObject()
                ? root.getAsJsonObject("settings") : null);
        if (root.has("categories") && root.get("categories").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("categories")) {
                if (el.isJsonObject()) {
                    TradeCategory cat = TradeCategory.fromJson(el.getAsJsonObject());
                    if (cat.isValid() && !categories.containsKey(cat.id)) {
                        categories.put(cat.id, cat);
                    }
                }
            }
        }
        int invalid = 0;
        if (root.has("goods") && root.get("goods").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("goods")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                TradeGood g = TradeGood.fromJson(el.getAsJsonObject());
                if (!g.isValid()) {
                    invalid++;
                    continue;
                }
                String issue = g.validate(this);
                if (issue != null) {
                    DeltaNexus.LOGGER.warn("[DN] 交易行商品 {} 配置不完整已保留但不会上架: {}", g.id, issue);
                }
                goods.put(g.id, g);
            }
        }
        DeltaNexus.LOGGER.info("[DN] 交易行配置热加载完成：分类 {} 商品 {}（跳过 {} 条非法）",
                categories.size(), goods.size(), invalid);
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    public synchronized void saveNow() {
        JsonConfigWriter.writeJsonNow(PATH, toJson());
    }

    public synchronized void saveDebounced() {
        JsonConfigWriter.saveJsonDebounced(PATH, toJson());
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("settings", settings.toJson());
        com.google.gson.JsonArray cats = new com.google.gson.JsonArray();
        for (TradeCategory c : categories.values()) {
            cats.add(c.toJson());
        }
        root.add("categories", cats);
        com.google.gson.JsonArray gs = new com.google.gson.JsonArray();
        for (TradeGood g : goods.values()) {
            gs.add(g.toJson());
        }
        root.add("goods", gs);
        return root;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public boolean hasCategory(String categoryId) {
        return categoryId != null && categories.containsKey(categoryId);
    }

    public TradeCategory category(String categoryId) {
        return categories.get(categoryId);
    }

    public boolean hasGood(String goodId) {
        return goods.containsKey(goodId);
    }

    public TradeGood good(String goodId) {
        return goods.get(goodId);
    }

    /** 分类（插入序；外部只读遍历用快照）。 */
    public List<TradeCategory> categoriesSnapshot() {
        return new ArrayList<>(categories.values());
    }

    /** 商品快照（插入序）。 */
    public List<TradeGood> goodsSnapshot() {
        return new ArrayList<>(goods.values());
    }

    /** 指定分类下的商品（含排序：order 升序，稳定于插入序）。 */
    public List<TradeGood> goodsOfCategory(String categoryId) {
        List<TradeGood> list = new ArrayList<>();
        for (TradeGood g : goods.values()) {
            if (categoryId == null || categoryId.equals(g.categoryId)) {
                list.add(g);
            }
        }
        list.sort((a, b) -> Integer.compare(a.order, b.order));
        return list;
    }

    /** 全部商品（按 order 升序）。 */
    public List<TradeGood> goodsSorted() {
        return goodsOfCategory(null);
    }

    // ------------------------------------------------------------------
    // 修改（指令 / Web 调用后需 save）
    // ------------------------------------------------------------------

    public Map<String, TradeCategory> categoriesMutable() {
        return categories;
    }

    public Map<String, TradeGood> goodsMutable() {
        return goods;
    }

    /** 重载 settings 字段引用（读接口用 get() 后直接读字段，修改需 save）。 */
    public Settings settings() {
        return settings;
    }
}
