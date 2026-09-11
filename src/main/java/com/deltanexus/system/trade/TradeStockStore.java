package com.deltanexus.system.trade;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.JsonConfigWriter;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易行运行时库存存档（{@code config/deltanexus/trade-stock.json}）。
 *
 * <p>与定义文件 {@code trade.json} 分离的原因：买入/补货会高频小改库存，若与定义
 * 同文件会把整份定义（含公式/脚本）一起反复落盘，增加冲突与写放大。</p>
 *
 * <p>约定：某商品在文件中无记录 = 库存 0；{@link TradeConfig} 热加载后新出现的商品
 * 自动按 0 起步，由管理员用指令/Web 补货。</p>
 */
public final class TradeStockStore {

    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("deltanexus/trade-stock.json");

    private static final Map<String, Integer> STOCK = new ConcurrentHashMap<>();

    private TradeStockStore() {
    }

    // ------------------------------------------------------------------
    // 加载 / 保存
    // ------------------------------------------------------------------

    public static synchronized void load() {
        STOCK.clear();
        JsonObject root = JsonConfigWriter.readJson(PATH);
        if (root == null || !root.has("goods") || !root.get("goods").isJsonObject()) {
            return;
        }
        JsonObject goods = root.getAsJsonObject("goods");
        for (Map.Entry<String, JsonElement> e : goods.entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                try {
                    STOCK.put(e.getKey(), Math.max(0, e.getValue().getAsInt()));
                } catch (NumberFormatException ignored) {
                    // 非法值忽略
                }
            }
        }
        DeltaNexus.LOGGER.info("[DN] 交易行库存加载完成：{} 个商品有记录", STOCK.size());
    }

    /** 首次启动时确保文件存在（无则生成空壳）。 */
    public static void writeDefaultIfMissing() {
        if (Files.exists(PATH)) {
            load();
            return;
        }
        saveNow();
    }

    public static synchronized void saveNow() {
        JsonConfigWriter.writeJsonNow(PATH, toJson());
    }

    public static void saveDebounced() {
        JsonConfigWriter.saveJsonDebounced(PATH, toJson());
    }

    public static JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonObject goods = new JsonObject();
        for (Map.Entry<String, Integer> e : STOCK.entrySet()) {
            goods.addProperty(e.getKey(), e.getValue());
        }
        root.add("goods", goods);
        return root;
    }

    // ------------------------------------------------------------------
    // 访问 / 修改
    // ------------------------------------------------------------------

    /** 当前库存（无记录 = 0）。 */
    public static int get(String goodId) {
        Integer v = STOCK.get(goodId);
        return v == null ? 0 : v;
    }

    /** 设置库存（负数按 0 截断；变更时立即落盘，避免重启/重载丢库存）。 */
    public static synchronized int set(String goodId, int count) {
        int value = Math.max(0, count);
        Integer prev = STOCK.put(goodId, value);
        if (prev == null || prev != value) {
            saveNow();
        }
        return value;
    }

    /** 增减库存（结果 < 0 截断为 0）；返回调整后的值。 */
    public static synchronized int add(String goodId, int delta) {
        int value = Math.max(0, get(goodId) + delta);
        STOCK.put(goodId, value);
        saveNow();
        return value;
    }

    /** 扣除（买入成功后调用）；库存不足返回 false 且不修改。 */
    public static synchronized boolean consume(String goodId, int amount) {
        int cur = get(goodId);
        if (cur < amount) {
            return false;
        }
        STOCK.put(goodId, cur - amount);
        saveNow();
        return true;
    }

    /** 全部库存快照（用于 /dn trade list 与 Web）。 */
    public static Map<String, Integer> snapshot() {
        return Map.copyOf(STOCK);
    }
}
