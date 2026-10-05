package com.deltanexus.system.common;

import com.deltanexus.system.grid.core.GridTags;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * NBT 规格基类：所有「按 NBT 匹配」的配置（配方输入、升级材料、安全箱限制、交易行商品）
 * 共用同一套口径——{@code id / full_nbt / partial_nbt} + 键规则（{@code match_keys}）。
 *
 * <p>匹配模式：</p>
 * <ul>
 *   <li>{@code id}          —— 只按注册 id（忽略 NBT）；</li>
 *   <li>{@code full_nbt}    —— 整份 NBT 与模板精确相等（模板空 = 要求无 NBT）；</li>
 *   <li>{@code partial_nbt} —— 只对 {@link #matchKeys} 列出的键做比较，每个键一条规则：
 *       <ul>
 *         <li>{@code exact}     —— 键值必须等于<b>模板</b>中该键的值；</li>
 *         <li>{@code contains}  —— 键值包含模板值（字符串子串 / 列表元素）；</li>
 *         <li>{@code specified} —— 指定键名与对应值：值直接写在规则里，与模板无关。</li>
 *       </ul>
 *       （旧版 {@code ignore} 已弃用：读取时该键按“不参与匹配”跳过）</li>
 * </ul>
 *
 * <p>所有匹配均在剥离网格内部标记（旋转 / 占位物）后进行，使「旋转过的物品」与
 * 「未旋转的同种物品」等价（与交易行一致）。</p>
 *
 * <p>兼容：读取时同时接受旧词汇 {@code match_type}（{@code exact/full/fullnbt/exact_nbt →
 * full_nbt}，{@code contains/partial/partialnbt/keys → partial_nbt}，{@code ignore/none → id}）。</p>
 */
public class NbtSpec {

    /** 匹配模式。 */
    public enum MatchMode {
        ID("id"),
        FULL_NBT("full_nbt"),
        PARTIAL_NBT("partial_nbt");

        public final String key;

        MatchMode(String key) {
            this.key = key;
        }

        public static MatchMode parse(@Nullable String s) {
            if (s == null || s.isBlank()) {
                return ID;
            }
            String v = s.trim().toLowerCase(Locale.ROOT);
            for (MatchMode m : values()) {
                if (m.key.equals(v)) {
                    return m;
                }
            }
            return switch (v) {
                case "full", "fullnbt", "exact_nbt", "exact" -> FULL_NBT;
                case "partial", "partialnbt", "keys", "contains" -> PARTIAL_NBT;
                default -> ID;
            };
        }
    }

    /** 指定键的运算符（partial_nbt）。 */
    public enum KeyOp {
        EXACT("exact"),
        CONTAINS("contains"),
        /** 指定：键名 + 显式值（值写在规则里，不依赖模板）。 */
        SPECIFIED("specified");

        public final String key;

        KeyOp(String key) {
            this.key = key;
        }

        public static KeyOp parse(@Nullable String s) {
            if (s == null || s.isBlank()) {
                return EXACT;
            }
            for (KeyOp op : values()) {
                if (op.key.equalsIgnoreCase(s.trim())) {
                    return op;
                }
            }
            return EXACT;
        }

        /** 旧版 ignore（0.2.0Beta 早期写法）：读取时按“该键不参与匹配”处理。 */
        public static boolean isLegacyIgnore(@Nullable String s) {
            return s != null && "ignore".equalsIgnoreCase(s.trim());
        }
    }

    /** partial_nbt 的单条键规则：运算符 + （specified 时的）显式值。 */
    public static final class KeyRule {
        public KeyOp op = KeyOp.EXACT;
        /** specified 模式的显式值（SNBT 片段；exact/contains 留空表示取模板值）。 */
        public String value = "";

        public KeyRule() {
        }

        public KeyRule(KeyOp op, String value) {
            this.op = op;
            this.value = value == null ? "" : value;
        }

        public String describe() {
            return op.key + (value == null || value.isBlank() ? "" : "=" + value);
        }
    }

    /** NBT 模板（SNBT 文本）：full_nbt 精确相等；partial_nbt（exact/contains）作为期望值来源。 */
    public String nbt = "";
    /** 匹配模式。 */
    public MatchMode matchMode = MatchMode.ID;
    /** partial_nbt：指定键 -> 规则（exact / contains / specified）。 */
    public final Map<String, KeyRule> matchKeys = new LinkedHashMap<>();

    public NbtSpec() {
    }

    /** NBT 模板解析缓存（避免每次 matches 都做 SNBT 解析；字符串变化才重解析）。 */
    private transient String cachedNbtString;
    private transient CompoundTag cachedNbtTag;

    /** 解析后的期望 NBT（缓存）。 */
    public CompoundTag expectedTag() {
        String s = nbt == null ? "" : nbt;
        if (!s.equals(cachedNbtString)) {
            cachedNbtString = s;
            cachedNbtTag = NbtMatcher.parseTag(s);
        }
        return cachedNbtTag;
    }

    // ------------------------------------------------------------------
    // 匹配
    // ------------------------------------------------------------------

    /** 给定实际 NBT 是否满足本规格（匹配模式 + 键规则），两侧剥离网格内部标记。 */
    public boolean matchesNbt(@Nullable CompoundTag actualRaw) {
        CompoundTag actual = strip(actualRaw);
        CompoundTag expected = strippedExpectedTag();
        return switch (matchMode == null ? MatchMode.ID : matchMode) {
            case ID -> true;
            case FULL_NBT -> fullEquals(actual, expected);
            case PARTIAL_NBT -> partialMatches(actual, expected);
        };
    }

    /** 期望 NBT 的「剥离网格键」副本（不改动缓存，不修改配置本体）。 */
    public CompoundTag strippedExpectedTag() {
        CompoundTag tag = expectedTag();
        if (tag == null || tag.isEmpty()) {
            return tag;
        }
        CompoundTag copy = tag.copy();
        GridTags.stripGridKeys(copy);
        return copy;
    }

    @Nullable
    private static CompoundTag strip(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return tag;
        }
        CompoundTag copy = tag.copy();
        GridTags.stripGridKeys(copy);
        return copy;
    }

    private boolean fullEquals(@Nullable CompoundTag actual, @Nullable CompoundTag expected) {
        if (expected == null || expected.isEmpty()) {
            return actual == null || actual.isEmpty();
        }
        return Objects.equals(actual, expected);
    }

    private boolean partialMatches(@Nullable CompoundTag actual, @Nullable CompoundTag expected) {
        if (actual == null) {
            return matchKeys.isEmpty();
        }
        for (Map.Entry<String, KeyRule> e : matchKeys.entrySet()) {
            String key = e.getKey();
            KeyRule rule = e.getValue();
            if (rule == null || rule.op == null) {
                continue;
            }
            if (!actual.contains(key)) {
                return false;
            }
            Tag have = actual.get(key);
            Tag want;
            if (rule.op == KeyOp.SPECIFIED) {
                want = parseValueTag(rule.value);
                if (want == null) {
                    // 指定模式但值为空：退化为“仅要求键存在”
                    continue;
                }
            } else {
                want = expected != null && expected.contains(key) ? expected.get(key) : null;
                if (want == null) {
                    continue;
                }
            }
            if (rule.op == KeyOp.CONTAINS ? !containsValue(have, want) : !Objects.equals(have, want)) {
                return false;
            }
        }
        return true;
    }

    /** 指定值解析：优先按 SNBT 标签解析，失败则按数字，最后回退字符串标签。 */
    @Nullable
    public static Tag parseValueTag(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            Tag parsed = TagParser.parseTag(s);
            if (parsed != null) {
                return parsed;
            }
        } catch (Exception ignored) {
        }
        try {
            return IntTag.valueOf(Integer.parseInt(s));
        } catch (Exception ignored) {
        }
        try {
            return DoubleTag.valueOf(Double.parseDouble(s));
        } catch (Exception ignored) {
        }
        return StringTag.valueOf(s);
    }

    /** CONTAINS：字符串子串；列表则元素相等；其余按整体相等兜底。 */
    private boolean containsValue(Tag have, Tag want) {
        if (have instanceof StringTag h && want instanceof StringTag w) {
            return h.getAsString().contains(w.getAsString());
        }
        if (have instanceof ListTag list) {
            for (Tag t : list) {
                if (Objects.equals(t, want)) {
                    return true;
                }
            }
            return false;
        }
        return Objects.equals(have, want);
    }

    // ------------------------------------------------------------------
    // JSON（子类在自己的 toJson/fromJson 内调用）
    // ------------------------------------------------------------------

    /** 写入 nbt / match_mode / match_keys 三个字段。 */
    public void writeNbtJson(JsonObject obj) {
        if (nbt != null && !nbt.isBlank()) {
            obj.addProperty("nbt", nbt);
        }
        obj.addProperty("match_mode", (matchMode == null ? MatchMode.ID : matchMode).key);
        if ((matchMode == MatchMode.PARTIAL_NBT || !matchKeys.isEmpty()) && !matchKeys.isEmpty()) {
            JsonObject keys = new JsonObject();
            for (Map.Entry<String, KeyRule> e : matchKeys.entrySet()) {
                KeyRule rule = e.getValue();
                if (rule == null) {
                    continue;
                }
                if (rule.op == KeyOp.SPECIFIED || (rule.value != null && !rule.value.isBlank())) {
                    JsonObject ro = new JsonObject();
                    ro.addProperty("op", rule.op.key);
                    if (rule.value != null && !rule.value.isBlank()) {
                        ro.addProperty("value", rule.value);
                    }
                    keys.add(e.getKey(), ro);
                } else {
                    keys.addProperty(e.getKey(), rule.op.key);
                }
            }
            obj.add("match_keys", keys);
        }
    }

    /** 读取 nbt / match_mode（兼容 match_type）/ match_keys 三个字段。 */
    public void readNbtJson(JsonObject obj) {
        if (obj == null) {
            return;
        }
        if (obj.has("nbt") && obj.get("nbt").isJsonPrimitive()) {
            nbt = obj.get("nbt").getAsString();
        }
        String modeStr = obj.has("match_mode") ? obj.get("match_mode").getAsString()
                : (obj.has("match_type") ? obj.get("match_type").getAsString() : null);
        matchMode = MatchMode.parse(modeStr);
        matchKeys.clear();
        if (obj.has("match_keys") && obj.get("match_keys").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("match_keys").entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonObject()) {
                    JsonObject ro = v.getAsJsonObject();
                    String opStr = ro.has("op") ? ro.get("op").getAsString() : "exact";
                    if (KeyOp.isLegacyIgnore(opStr)) {
                        continue;
                    }
                    matchKeys.put(e.getKey(), new KeyRule(KeyOp.parse(opStr),
                            ro.has("value") ? ro.get("value").getAsString() : ""));
                } else if (v.isJsonPrimitive()) {
                    String opStr = v.getAsString();
                    if (KeyOp.isLegacyIgnore(opStr)) {
                        continue;
                    }
                    matchKeys.put(e.getKey(), new KeyRule(KeyOp.parse(opStr), ""));
                }
            }
        }
        normalizeMatch();
    }

    /**
     * 旧配置自动迁移（partial_nbt 从「SNBT 模板」转为「键规则」）：
     * 若 partial_nbt 缺少 match_keys 但填了 NBT 模板，则把模板的每个顶层键转为一条
     * {@code exact} 规则（与旧语义「模板中每个键都存在且相等」等价）；
     * 若已填了 match_keys，则确保模式为 partial_nbt。
     */
    public void normalizeMatch() {
        if (matchMode == MatchMode.PARTIAL_NBT && matchKeys.isEmpty() && nbt != null && !nbt.isBlank()) {
            CompoundTag tpl = NbtMatcher.parseTag(nbt);
            if (tpl != null) {
                for (String k : tpl.getAllKeys()) {
                    matchKeys.put(k, new KeyRule(KeyOp.EXACT, ""));
                }
            }
        }
        if (!matchKeys.isEmpty() && matchMode != MatchMode.PARTIAL_NBT) {
            matchMode = MatchMode.PARTIAL_NBT;
        }
    }
}
