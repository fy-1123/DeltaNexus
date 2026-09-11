package com.deltanexus.system.trade;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部价格源快照（行表 + 元数据）。
 *
 * <p>语义约定：feed 负责把“自己世界的语义”归一成行表——每行是只读字段映射
 * （如 {@code id / name / display_name / current_price / category / grade}），
 * 字段含义对交易行核心透明；核心只约定：若行里有 {@code id} 或 {@code key} 字段，
 * 它将被视为稳定键（JS 层 {@code feed(name).price(key)} 使用）。</p>
 *
 * <p>0 价歧义在 feed 侧消除：无市价的条目不出现在 {@link #rows()} 中
 * （或把价格字段置为 null），核心永远只处理“有价 / 无条目”两种状态。</p>
 */
public final class FeedSnapshot {

    /** 单行只读视图。 */
    public static final class Row {
        private final Map<String, Object> fields;

        Row(Map<String, Object> fields) {
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        public Object get(String field) {
            return fields.get(field);
        }

        public boolean has(String field) {
            return fields.containsKey(field);
        }

        public Map<String, Object> fields() {
            return fields;
        }

        /** 稳定键（行内 id 或 key，无则返回字段化的行序数字符串）。 */
        public String stableKey() {
            Object v = fields.get("id");
            if (v == null) {
                v = fields.get("key");
            }
            return v == null ? null : String.valueOf(v);
        }

        /** 价格数值（行内 price 或 current_price；非数值返回 null）。 */
        public Double price() {
            Object v = fields.get("price");
            if (v == null) {
                v = fields.get("current_price");
            }
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            return null;
        }
    }

    private final String source;
    private final long generatedAtEpochMs;
    private final long loadedAtEpochMs;
    private final List<Row> rows;

    public FeedSnapshot(String source, long generatedAtEpochMs, long loadedAtEpochMs, List<Row> rows) {
        this.source = source;
        this.generatedAtEpochMs = generatedAtEpochMs;
        this.loadedAtEpochMs = loadedAtEpochMs;
        this.rows = List.copyOf(rows);
    }

    public static Row row(Map<String, Object> fields) {
        return new Row(fields);
    }

    /** 源 id（与 {@link MarketFeed#id()} 一致）。 */
    public String source() {
        return source;
    }

    /** 数据本身的生成时间（epoch ms；未知可填 loadedAt）。 */
    public long generatedAtEpochMs() {
        return generatedAtEpochMs;
    }

    /** 本地加载/抓取完成时间（epoch ms）。 */
    public long loadedAtEpochMs() {
        return loadedAtEpochMs;
    }

    public List<Row> rows() {
        return rows;
    }

    /** 行数（JS 层展示与日志用）。 */
    public int size() {
        return rows.size();
    }

    /** 按稳定键查行；无返回 null。 */
    public Row byKey(String key) {
        for (Row r : rows) {
            String k = r.stableKey();
            if (k != null && k.equals(key)) {
                return r;
            }
        }
        return null;
    }
}
