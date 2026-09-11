package com.deltanexus.system.trade;

import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 交易行价格策略（单一方向：买入价 / 卖出价 / 市场参考价共用）。
 *
 * <p>三种模式：</p>
 * <ul>
 *   <li>{@code fixed}   —— 固定价；</li>
 *   <li>{@code formula} —— 单行 JS 表达式（价格引擎 ctx 内求值，如
 *       {@code floor(clamp(feed('moligod').price('1029') * 0.9 * (1 - 0.02 * floor(good.stock / 100)), 1, 1000000))}）；</li>
 *   <li>{@code code}    —— 自定义 JS 函数 {@code function(ctx){ ... return number; }}。</li>
 * </ul>
 *
 * <p>求值结果必须为有限数字；交易行统一向下取整并强制 ≥ 1（0/负/异常 = 定价不可用，禁止交易）。</p>
 */
public final class PricePolicy {

    public enum Mode {
        FIXED("fixed"),
        FORMULA("formula"),
        CODE("code");

        public final String key;

        Mode(String key) {
            this.key = key;
        }

        public static Mode parse(@Nullable String s) {
            if (s == null || s.isBlank()) {
                return FIXED;
            }
            for (Mode m : values()) {
                if (m.key.equalsIgnoreCase(s.trim())) {
                    return m;
                }
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "expr", "expression" -> FORMULA;
                case "script", "js" -> CODE;
                default -> FIXED;
            };
        }
    }

    /** 模式。 */
    public Mode mode = Mode.FIXED;
    /** fixed：固定价格。 */
    public double value = 0;
    /** formula：JS 表达式。 */
    public String expr = "";
    /** code：JS 函数体。 */
    public String script = "";

    public PricePolicy() {
    }

    public PricePolicy(Mode mode) {
        this.mode = mode;
    }

    /** 策略是否完整（缺少必要内容返回 false）。 */
    public boolean isValid() {
        return switch (mode) {
            case FIXED -> value >= 0;
            case FORMULA -> expr != null && !expr.isBlank();
            case CODE -> script != null && !script.isBlank();
        };
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("mode", mode.key);
        switch (mode) {
            case FIXED -> obj.addProperty("value", value);
            case FORMULA -> {
                if (expr != null && !expr.isBlank()) {
                    obj.addProperty("expr", expr);
                }
            }
            case CODE -> {
                if (script != null && !script.isBlank()) {
                    obj.addProperty("script", script);
                }
            }
        }
        return obj;
    }

    public static PricePolicy fromJson(@Nullable JsonObject obj) {
        PricePolicy p = new PricePolicy();
        if (obj == null) {
            return p;
        }
        p.mode = Mode.parse(obj.has("mode") ? obj.get("mode").getAsString() : null);
        p.value = obj.has("value") ? obj.get("value").getAsDouble() : 0;
        p.expr = obj.has("expr") ? obj.get("expr").getAsString() : "";
        p.script = obj.has("script") ? obj.get("script").getAsString() : "";
        return p;
    }

    /** 简短描述（指令 /Web 展示）。 */
    public String describe() {
        return switch (mode) {
            case FIXED -> "固定价 " + (long) value;
            case FORMULA -> "公式: " + (expr == null || expr.isBlank() ? "(空)" : expr);
            case CODE -> "脚本: " + (script == null || script.isBlank() ? "(空)" : script);
        };
    }
}
