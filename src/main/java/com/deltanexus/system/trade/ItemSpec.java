package com.deltanexus.system.trade;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.common.NbtMatcher;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 交易行商品规格：物品 id + NBT 模板 + 匹配模式（+ 可选耐久度要求）。
 *
 * <p>匹配模式（管理员对“卖出/校验/生成”的统一口径）：</p>
 * <ul>
 *   <li>{@code id}          —— 只按注册 id（忽略 NBT）；</li>
 *   <li>{@code full_nbt}    —— 整份 NBT 与模板精确相等（模板空串 = 要求无 NBT）；</li>
 *   <li>{@code partial_nbt} —— 只对 {@link #matchKeys} 列出的键做比较，每个键一条规则：
 *       <ul>
 *         <li>{@code exact}     —— 键值必须等于<b>模板</b>中该键的值；</li>
 *         <li>{@code contains}  —— 键值包含模板值（字符串子串 / 列表元素）；</li>
 *         <li>{@code specified} —— <b>指定键名与对应值</b>：值直接写在规则里，与模板无关。</li>
 *       </ul>
 *       （旧版 {@code ignore} 已弃用：读取时该键按“不参与匹配”跳过）</li>
 * </ul>
 *
 * <p>耐久度要求（默认关闭，可与任意匹配模式共存）：对「可损坏物品」比较
 * <b>剩余耐久 = 最大耐久 − Damage</b>，运算符支持 {@code = / < / > / <= / >=}。</p>
 *
 * <p>配置 JSON 示例：</p>
 * <pre>{@code
 * "item": {
 *   "id": "minecraft:diamond_sword",
 *   "nbt": "{Damage:0}",
 *   "unit_count": 1,
 *   "match_mode": "partial_nbt",
 *   "match_keys": { "Damage": "exact", "display": { "op": "specified", "value": "{\"Name\":\"\\\"定制剑\\\"\"}" } },
 *   "durability": { "enabled": true, "op": ">", "value": 100 }
 * }
 * }</pre>
 */
public final class ItemSpec {

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
                case "full", "fullnbt", "exact_nbt" -> FULL_NBT;
                case "partial", "partialnbt", "keys" -> PARTIAL_NBT;
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

    /** 物品注册 id（如 "minecraft:diamond"）。 */
    public String item = "minecraft:air";
    /** NBT 模板（SNBT 文本）：生成物品时写入；full_nbt/partial_nbt（exact/contains）作为期望值来源。 */
    public String nbt = "";
    /** 单次交易单位内包含的物品数量（默认 1）。 */
    public int unitCount = 1;
    /** 匹配模式。 */
    public MatchMode matchMode = MatchMode.ID;
    /** partial_nbt：指定键 -> 规则（exact / contains / specified）。 */
    public final Map<String, KeyRule> matchKeys = new LinkedHashMap<>();

    // ---- 耐久度要求（默认关闭，可与匹配模式共存） ----
    /** 是否要求耐久度满足条件。 */
    public boolean durabilityEnabled = false;
    /** 运算符：= / < / > / <= / >=（对剩余耐久）。 */
    public String durabilityOp = "=";
    /** 比较值（剩余耐久）。 */
    public int durabilityValue = 0;

    public ItemSpec() {
    }

    /** NBT 模板解析缓存（避免每次 matches 都做 SNBT 解析；字符串变化才重解析）。 */
    private transient String cachedNbtString;
    private transient CompoundTag cachedNbtTag;

    /** 解析后的期望 NBT（缓存）。 */
    private CompoundTag expectedTag() {
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

    /** 实际物品栈是否匹配本规格（匹配模式 + 耐久度要求）。 */
    public boolean matches(ItemStack actual) {
        Item expected = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item));
        if (expected == null || actual == null || actual.isEmpty() || !actual.is(expected)) {
            return false;
        }
        CompoundTag actualTag = actual.getTag();
        CompoundTag expectedTag = expectedTag();
        boolean modeOk = switch (matchMode) {
            case ID -> true;
            case FULL_NBT -> fullEquals(actualTag, expectedTag);
            case PARTIAL_NBT -> partialMatches(actualTag, expectedTag);
        };
        return modeOk && durabilityMatches(actual);
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

    /**
     * 耐久度要求：剩余耐久 = 最大耐久 − Damage。
     * 不可损坏的物品（无耐久属性）视为不满足（要求开启时）。
     */
    private boolean durabilityMatches(ItemStack stack) {
        if (!durabilityEnabled) {
            return true;
        }
        if (!stack.isDamageableItem()) {
            return false;
        }
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        return switch (durabilityOp == null ? "=" : durabilityOp.trim()) {
            case "<" -> remaining < durabilityValue;
            case ">" -> remaining > durabilityValue;
            case "<=" -> remaining <= durabilityValue;
            case ">=" -> remaining >= durabilityValue;
            default -> remaining == durabilityValue;
        };
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    /** 按模板生成一份商品物品栈（count 由调用方控制，须 ≤ 堆叠上限）。 */
    public ItemStack buildStack(int count) {
        Item itemObj = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item));
        if (itemObj == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(itemObj, Math.max(1, count));
        CompoundTag tag = NbtMatcher.parseTag(nbt);
        if (tag != null && !tag.isEmpty()) {
            stack.setTag(tag);
        }
        return stack;
    }

    /** 展示/信息用单份图标。 */
    public ItemStack iconStack() {
        return buildStack(1);
    }

    /** 规格是否可用（id 必须可解析且非空气，unit_count ≥ 1）。 */
    public boolean isValid() {
        if (item == null || item.isBlank() || item.equals("minecraft:air")) {
            return false;
        }
        Item itemObj = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item));
        if (itemObj == null) {
            return false;
        }
        return unitCount >= 1;
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", item);
        if (nbt != null && !nbt.isBlank()) {
            obj.addProperty("nbt", nbt);
        }
        if (unitCount != 1) {
            obj.addProperty("unit_count", unitCount);
        }
        obj.addProperty("match_mode", matchMode.key);
        if (matchMode == MatchMode.PARTIAL_NBT && !matchKeys.isEmpty()) {
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
        if (durabilityEnabled) {
            JsonObject d = new JsonObject();
            d.addProperty("enabled", true);
            d.addProperty("op", durabilityOp == null ? "=" : durabilityOp);
            d.addProperty("value", durabilityValue);
            obj.add("durability", d);
        }
        return obj;
    }

    public static ItemSpec fromJson(JsonObject obj) {
        ItemSpec spec = new ItemSpec();
        if (obj == null) {
            return spec;
        }
        spec.item = obj.has("id") ? obj.get("id").getAsString() : "minecraft:air";
        spec.nbt = obj.has("nbt") ? obj.get("nbt").getAsString() : "";
        spec.unitCount = obj.has("unit_count") ? Math.max(1, obj.get("unit_count").getAsInt()) : 1;
        spec.matchMode = MatchMode.parse(obj.has("match_mode") ? obj.get("match_mode").getAsString() : null);
        if (obj.has("match_keys") && obj.get("match_keys").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("match_keys").entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonObject()) {
                    JsonObject ro = v.getAsJsonObject();
                    String opStr = ro.has("op") ? ro.get("op").getAsString() : "exact";
                    if (KeyOp.isLegacyIgnore(opStr)) {
                        continue;
                    }
                    KeyRule rule = new KeyRule(KeyOp.parse(opStr),
                            ro.has("value") ? ro.get("value").getAsString() : "");
                    spec.matchKeys.put(e.getKey(), rule);
                } else {
                    String opStr = v.getAsString();
                    if (KeyOp.isLegacyIgnore(opStr)) {
                        // 旧 ignore：该键不参与匹配
                        continue;
                    }
                    spec.matchKeys.put(e.getKey(), new KeyRule(KeyOp.parse(opStr), ""));
                }
            }
        }
        if (obj.has("durability") && obj.get("durability").isJsonObject()) {
            JsonObject d = obj.getAsJsonObject("durability");
            spec.durabilityEnabled = !d.has("enabled") || d.get("enabled").getAsBoolean();
            spec.durabilityOp = d.has("op") ? d.get("op").getAsString() : "=";
            spec.durabilityValue = d.has("value") ? d.get("value").getAsInt() : 0;
        }
        return spec;
    }

    /** 校验并打印问题（非法时返回原因，合法返回 null）。 */
    @Nullable
    public String validate() {
        if (item == null || item.isBlank()) {
            return "缺少物品 id";
        }
        if (ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item)) == null) {
            return "物品 id 无法解析: " + item;
        }
        if (item.equals("minecraft:air")) {
            return "物品不能是空气";
        }
        if (unitCount < 1) {
            return "unit_count 必须 ≥ 1";
        }
        if (matchMode == MatchMode.FULL_NBT && nbt != null && !nbt.isBlank()
                && NbtMatcher.parseTag(nbt) == null) {
            return "full_nbt 模式下 NBT 模板非法";
        }
        if (matchMode == MatchMode.PARTIAL_NBT) {
            CompoundTag tpl = NbtMatcher.parseTag(nbt);
            if (tpl == null && nbt != null && !nbt.isBlank()) {
                return "partial_nbt 模式下 NBT 模板非法";
            }
            if (matchKeys.isEmpty()) {
                DeltaNexus.LOGGER.warn("[DN] 交易行商品 {} 使用 partial_nbt 但未指定 match_keys", item);
            }
            for (Map.Entry<String, KeyRule> e : matchKeys.entrySet()) {
                KeyRule rule = e.getValue();
                if (rule != null && rule.op == KeyOp.SPECIFIED && (rule.value == null || rule.value.isBlank())) {
                    DeltaNexus.LOGGER.warn("[DN] 交易行商品 {} 的指定键 '{}' 未填值，将退化为仅要求键存在",
                            item, e.getKey());
                }
            }
        }
        if (durabilityEnabled) {
            String op = durabilityOp == null ? "" : durabilityOp.trim();
            if (!op.equals("=") && !op.equals("<") && !op.equals(">")
                    && !op.equals("<=") && !op.equals(">=")) {
                return "耐久度运算符仅支持 = / < / > / <= / >=";
            }
        }
        return null;
    }
}
