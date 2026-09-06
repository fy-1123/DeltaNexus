package com.deltanexus.system.common;

/**
 * 数值紧凑显示工具（货币等大数的 K/M/B 单位）。
 *
 * <p>规则（向下取整）：</p>
 * <ul>
 *   <li>v &gt;= 1,000,000,000 → {@code v/1e9}B（10 亿 = 1B）</li>
 *   <li>v &gt;= 1,000,000     → {@code v/1e6}M（5,621,000 → 5M）</li>
 *   <li>v &gt;= 1,000         → {@code v/1e3}K（1,234 → 1K）</li>
 *   <li>否则原样输出</li>
 * </ul>
 */
public final class FormatUtil {

    private FormatUtil() {
    }

    /** 数字紧凑格式（K/M/B，向下取整）。 */
    public static String compact(long v) {
        if (v >= 1_000_000_000L) {
            return (v / 1_000_000_000L) + "B";
        }
        if (v >= 1_000_000L) {
            return (v / 1_000_000L) + "M";
        }
        if (v >= 1_000L) {
            return (v / 1_000L) + "K";
        }
        return String.valueOf(v);
    }

    /** 持有/需求紧凑格式：{@code "1K/5K"}。 */
    public static String compactNeed(long held, long needed) {
        return compact(held) + "/" + compact(needed);
    }
}
