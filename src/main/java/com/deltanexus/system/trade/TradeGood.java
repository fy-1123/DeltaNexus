package com.deltanexus.system.trade;

import com.deltanexus.system.DeltaNexus;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 交易行商品定义（管理员配置）。
 *
 * <p>字段语义：</p>
 * <ul>
 *   <li>{@code spec}：商品规格（物品 id + NBT 模板 + 匹配模式）；买入生成的物品即按模板构造；</li>
 *   <li>{@code buy/sell/market}：三个方向的价格策略。v1 只执行买入（玩家→系统）；
 *       sell/market 用于信息展示与后续版本（未配置时二级界面显示 “—”）；</li>
 *   <li>{@code stockMin/stockMax}：库存上下限（0 = 不限）。下限同时是可选的“缺货阻断线”
 *       （{@code block_below_min}，stock ≤ stockMin 且 stockMin &gt; 0 时禁止买入）；
 *       二者都作为公式变量 limits.stockMin / limits.stockMax 暴露；</li>
 *   <li>{@code buyable}：是否可买入（v1 唯一的玩家操作方向）；{@code enabled}：整件商品是否上架。</li>
 * </ul>
 */
public final class TradeGood {

    /** 商品 id（唯一）。 */
    public String id = "";
    /** 所属分类 id（参考制作台：每个商品必属一个分类）。 */
    public String categoryId = "";
    /** 显示名（空串回退到物品默认名）。 */
    public String displayName = "";
    /** 排序权重（小在前）。 */
    public int order = 0;
    /** 是否上架（总开关）。 */
    public boolean enabled = true;
    /** 是否可买入（v1 唯一执行方向）。 */
    public boolean buyable = true;
    /** 商品规格。 */
    public ItemSpec spec = new ItemSpec();

    // ------- 价格策略（方向） -------
    /** 买入价（玩家向交易行购买，v1 执行）。 */
    public PricePolicy buy;
    /** 卖出价（玩家卖给交易行，预留：展示/后续版本）。 */
    public PricePolicy sell;
    /** 市场参考价（中间价，展示用，可选）。 */
    public PricePolicy market;

    // ------- 库存上下限 -------
    /** 库存下限（0 = 不限）。 */
    public int stockMin = 0;
    /** 库存上限（0 = 不限；作为公式变量/补货参考）。 */
    public int stockMax = 0;
    /** stock ≤ stockMin 时禁止买入（仅当 stockMin &gt; 0）。 */
    public boolean blockBelowMin = true;

    public TradeGood() {
    }

    /** 显示名（未配置回退到物品注册名/默认显示名）。 */
    public String displayName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        ItemStack icon = spec.iconStack();
        if (!icon.isEmpty()) {
            try {
                return icon.getHoverName().getString();
            } catch (Exception e) {
                // 回退到注册名
            }
        }
        return spec.item;
    }

    /** 可买入价策略；不可买入或无策略返回 null。 */
    @Nullable
    public PricePolicy buyPolicy() {
        return buyable ? buy : null;
    }

    public boolean isValid() {
        return id != null && !id.isBlank();
    }

    /** 配置完整性校验：返回问题原因，null 表示通过。 */
    @Nullable
    public String validate(TradeConfig config) {
        if (id == null || id.isBlank()) {
            return "商品缺少 id";
        }
        if (config != null && (categoryId == null || !config.hasCategory(categoryId))) {
            return "分类不存在: " + categoryId;
        }
        String specIssue = spec.validate();
        if (specIssue != null) {
            return "商品规格非法: " + specIssue;
        }
        if (buyable && (buy == null || !buy.isValid())) {
            return "可买入商品缺少有效买入价策略";
        }
        if (stockMin < 0 || stockMax < 0) {
            return "库存上下限不能为负";
        }
        if (stockMax > 0 && stockMin > stockMax) {
            return "库存下限不能大于上限";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("category", categoryId);
        if (displayName != null && !displayName.isBlank()) {
            obj.addProperty("display_name", displayName);
        }
        obj.addProperty("order", order);
        obj.addProperty("enabled", enabled);
        obj.addProperty("buyable", buyable);
        obj.add("item", spec.toJson());

        JsonObject prices = new JsonObject();
        if (buy != null) {
            prices.add("buy", buy.toJson());
        }
        if (sell != null) {
            prices.add("sell", sell.toJson());
        }
        if (market != null) {
            prices.add("market", market.toJson());
        }
        obj.add("prices", prices);

        if (stockMin > 0 || stockMax > 0) {
            JsonObject stock = new JsonObject();
            if (stockMin > 0) {
                stock.addProperty("min", stockMin);
            }
            if (stockMax > 0) {
                stock.addProperty("max", stockMax);
            }
            stock.addProperty("block_below_min", blockBelowMin);
            obj.add("stock_limits", stock);
        }
        return obj;
    }

    public static TradeGood fromJson(JsonObject obj) {
        TradeGood g = new TradeGood();
        if (obj == null) {
            return g;
        }
        g.id = obj.has("id") ? obj.get("id").getAsString() : "";
        g.categoryId = obj.has("category") ? obj.get("category").getAsString() : "";
        g.displayName = obj.has("display_name") ? obj.get("display_name").getAsString() : "";
        g.order = obj.has("order") ? obj.get("order").getAsInt() : 0;
        g.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
        g.buyable = !obj.has("buyable") || obj.get("buyable").getAsBoolean();
        if (obj.has("item") && obj.get("item").isJsonObject()) {
            g.spec = ItemSpec.fromJson(obj.getAsJsonObject("item"));
        }
        if (obj.has("prices") && obj.get("prices").isJsonObject()) {
            JsonObject prices = obj.getAsJsonObject("prices");
            if (prices.has("buy")) {
                g.buy = PricePolicy.fromJson(prices.getAsJsonObject("buy"));
            }
            if (prices.has("sell")) {
                g.sell = PricePolicy.fromJson(prices.getAsJsonObject("sell"));
            }
            if (prices.has("market")) {
                g.market = PricePolicy.fromJson(prices.getAsJsonObject("market"));
            }
        }
        if (obj.has("stock_limits") && obj.get("stock_limits").isJsonObject()) {
            JsonObject stock = obj.getAsJsonObject("stock_limits");
            g.stockMin = stock.has("min") ? Math.max(0, stock.get("min").getAsInt()) : 0;
            g.stockMax = stock.has("max") ? Math.max(0, stock.get("max").getAsInt()) : 0;
            g.blockBelowMin = !stock.has("block_below_min") || stock.get("block_below_min").getAsBoolean();
        }
        return g;
    }

    /** 判定商品是否对玩家可见/可交易（不校验价格与库存）。 */
    public boolean isListed() {
        return enabled && spec.isValid();
    }

    /** 稳定显示键（日志/错误用）。 */
    public String logKey() {
        return id;
    }
}
