package com.deltanexus.system.trade;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.common.NbtMatcher;
import com.deltanexus.system.common.NbtSpec;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 交易行商品规格：物品 id + NBT 模板 + 匹配模式（+ 可选耐久度要求）。
 *
 * <p>匹配口径继承自 {@link NbtSpec}（{@code id / full_nbt / partial_nbt} + 键规则 {@code match_keys}），
 * 与配方输入 / 升级材料 / 安全箱限制完全一致。</p>
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
public final class ItemSpec extends NbtSpec {

    /** 物品注册 id（如 "minecraft:diamond"）。 */
    public String item = "minecraft:air";
    /** 单次交易单位内包含的物品数量（默认 1）。 */
    public int unitCount = 1;

    // ---- 耐久度要求（默认关闭，可与匹配模式共存） ----
    /** 是否要求耐久度满足条件。 */
    public boolean durabilityEnabled = false;
    /** 运算符：= / < / > / <= / >=（对剩余耐久）。 */
    public String durabilityOp = "=";
    /** 比较值（剩余耐久）。 */
    public int durabilityValue = 0;

    public ItemSpec() {
    }

    /** 实际物品栈是否匹配本规格（匹配模式 + 耐久度要求）。 */
    public boolean matches(ItemStack actual) {
        Item expected = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item));
        if (expected == null || actual == null || actual.isEmpty() || !actual.is(expected)) {
            return false;
        }
        return matchesNbt(actual.getTag()) && durabilityMatches(actual);
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
        writeNbtJson(obj);
        if (unitCount != 1) {
            obj.addProperty("unit_count", unitCount);
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
        spec.unitCount = obj.has("unit_count") ? Math.max(1, obj.get("unit_count").getAsInt()) : 1;
        spec.readNbtJson(obj);
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
