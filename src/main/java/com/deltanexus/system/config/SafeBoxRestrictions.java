package com.deltanexus.system.config;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.common.NbtMatcher;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 安全箱 NBT 限制（1.1.0Alpha）：拥有指定 NBT 的物品无法放入安全箱。
 *
 * <p>配置文件 {@code config/deltanexus/safe_box_restrictions.json}（热加载，/dn reload 生效）：</p>
 * <pre>{@code
 * { "restrictions": [
 *     { "item": "", "nbt": "{display:{Name:\"\\\"禁入\\\"\"}}", "match_type": "contains" },
 *     { "item": "minecraft:diamond", "nbt": "{Damage:0}", "match_type": "exact" }
 * ] }
 * }</pre>
 *
 * <p>规则说明：{@code item} 为空 = 任意物品；{@code nbt} 为期望 NBT 字符串；
 * {@code match_type} 支持 {@code exact}（完全相等）/ {@code contains}（包含键值）。
 * 物品放入安全箱时（仓库界面/安全箱界面/背包覆盖层）均做服务端校验，命中任意规则即拒绝。</p>
 */
public final class SafeBoxRestrictions {

    /** 单条限制规则。 */
    public static class Rule {
        /** 限制的物品注册名；空串 = 任意物品。 */
        public String item = "";
        /** 期望 NBT 字符串（exact/contains 匹配）。 */
        public String nbt = "";
        /** NBT 匹配模式（仅 exact/contains 有意义）。 */
        public NbtMatcher.MatchType matchType = NbtMatcher.MatchType.CONTAINS;

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("item", item);
            o.addProperty("nbt", nbt);
            o.addProperty("match_type", matchType.key());
            return o;
        }

        public static Rule fromJson(JsonObject o) {
            Rule r = new Rule();
            r.item = o.has("item") ? o.get("item").getAsString() : "";
            r.nbt = o.has("nbt") ? o.get("nbt").getAsString() : "";
            String mt = o.has("match_type") ? o.get("match_type").getAsString() : "contains";
            r.matchType = "exact".equalsIgnoreCase(mt)
                    ? NbtMatcher.MatchType.EXACT : NbtMatcher.MatchType.CONTAINS;
            return r;
        }
    }

    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("deltanexus/safe_box_restrictions.json");
    private static final List<Rule> RULES = new ArrayList<>();

    private SafeBoxRestrictions() {
    }

    // ------------------------------------------------------------------
    // 加载 / 保存
    // ------------------------------------------------------------------

    public static synchronized void reload() {
        RULES.clear();
        JsonObject root = JsonConfigWriter.readJson(PATH);
        if (root == null || !root.has("restrictions") || !root.get("restrictions").isJsonArray()) {
            return;
        }
        for (JsonElement el : root.getAsJsonArray("restrictions")) {
            if (el.isJsonObject()) {
                RULES.add(Rule.fromJson(el.getAsJsonObject()));
            }
        }
        DeltaNexus.LOGGER.info("[DN] 安全箱 NBT 限制热加载完成，共 {} 条", RULES.size());
    }

    /** 首次启动生成默认限制文件（空列表）。 */
    public static void writeDefaultIfMissing() {
        if (Files.exists(PATH)) {
            reload();
            return;
        }
        saveNow();
        DeltaNexus.LOGGER.info("[DN] 已生成默认安全箱 NBT 限制配置 {}", PATH);
    }

    public static synchronized void saveNow() {
        JsonConfigWriter.writeJsonNow(PATH, toJson());
    }

    public static Path path() {
        return PATH;
    }

    public static JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Rule r : RULES) {
            arr.add(r.toJson());
        }
        root.add("restrictions", arr);
        return root;
    }

    // ------------------------------------------------------------------
    // 修改（指令 / Web 调用）
    // ------------------------------------------------------------------

    /** 添加限制规则（item 空串 = 任意物品）。 */
    public static synchronized int add(String item, String nbt, NbtMatcher.MatchType matchType) {
        Rule r = new Rule();
        r.item = item == null ? "" : item.trim();
        r.nbt = nbt == null ? "" : nbt.trim();
        r.matchType = matchType == NbtMatcher.MatchType.EXACT
                ? NbtMatcher.MatchType.EXACT : NbtMatcher.MatchType.CONTAINS;
        RULES.add(r);
        saveNow();
        return RULES.size() - 1;
    }

    /** 删除限制规则（按索引）。 */
    public static synchronized boolean remove(int index) {
        if (index < 0 || index >= RULES.size()) {
            return false;
        }
        RULES.remove(index);
        saveNow();
        return true;
    }

    public static List<Rule> all() {
        return RULES;
    }

    public static int size() {
        return RULES.size();
    }

    // ------------------------------------------------------------------
    // 判定（服务端：放入安全箱时校验）
    // ------------------------------------------------------------------

    /** 物品是否命中任意限制规则（命中 = 禁止放入安全箱）。 */
    public static boolean isRestricted(ItemStack stack) {
        if (stack == null || stack.isEmpty() || RULES.isEmpty()) {
            return false;
        }
        for (Rule r : RULES) {
            if (r.nbt == null || r.nbt.isBlank()) {
                continue;
            }
            if (r.item != null && !r.item.isBlank()) {
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(r.item));
                if (item == null || !stack.is(item)) {
                    continue;
                }
            }
            net.minecraft.nbt.CompoundTag expected = NbtMatcher.parseTag(r.nbt);
            if (expected == null) {
                continue;
            }
            if (NbtMatcher.matchesNbt(stack.getTag(), expected, r.matchType)) {
                return true;
            }
        }
        return false;
    }
}
