package com.deltanexus.system.config;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.common.NbtSpec;
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
 *     { "item": "", "match_mode": "partial_nbt", "match_keys": { "display": "exact" } },
 *     { "item": "minecraft:diamond", "nbt": "{Damage:0}", "match_mode": "full_nbt" }
 * ] }
 * }</pre>
 *
 * <p>规则说明：{@code item} 为空 = 任意物品；{@code nbt} 为期望 NBT 模板；
 * {@code match_mode} 支持 {@code full_nbt}（完全相等）/ {@code partial_nbt}（键规则
 * {@code match_keys}），与交易行口径一致（旧 {@code match_type}/{@code exact}/{@code contains}
 * 读取时自动迁移）。
 * 物品放入安全箱时（仓库界面/安全箱界面/背包覆盖层）均做服务端校验，命中任意规则即拒绝。</p>
 */
public final class SafeBoxRestrictions {

    /** 单条限制规则（NBT 匹配口径继承 {@link NbtSpec}）。 */
    public static class Rule extends NbtSpec {
        /** 限制的物品注册名；空串 = 任意物品。 */
        public String item = "";

        public Rule() {
            matchMode = MatchMode.PARTIAL_NBT;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("item", item);
            writeNbtJson(o);
            return o;
        }

        public static Rule fromJson(JsonObject o) {
            Rule r = new Rule();
            r.item = o.has("item") ? o.get("item").getAsString() : "";
            r.readNbtJson(o);
            // 安全箱限制只接受 full_nbt / partial_nbt（旧值 exact/contains 自动迁移）
            if (r.matchMode != MatchMode.FULL_NBT) {
                r.matchMode = MatchMode.PARTIAL_NBT;
            }
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
    public static synchronized int add(String item, String nbt, NbtSpec.MatchMode matchMode) {
        Rule r = new Rule();
        r.item = item == null ? "" : item.trim();
        r.nbt = nbt == null ? "" : nbt.trim();
        r.matchMode = matchMode == NbtSpec.MatchMode.FULL_NBT
                ? NbtSpec.MatchMode.FULL_NBT : NbtSpec.MatchMode.PARTIAL_NBT;
        r.normalizeMatch();
        RULES.add(r);
        saveNow();
        return RULES.size() - 1;
    }

    /** 添加限制规则（携带键规则，供 Web / 指令使用）。 */
    public static synchronized int add(Rule rule) {
        if (rule == null) {
            return -1;
        }
        rule.normalizeMatch();
        RULES.add(rule);
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
            if ((r.nbt == null || r.nbt.isBlank()) && r.matchKeys.isEmpty()) {
                continue;
            }
            if (r.item != null && !r.item.isBlank()) {
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(r.item));
                if (item == null || !stack.is(item)) {
                    continue;
                }
            }
            if (r.matchesNbt(stack.getTag())) {
                return true;
            }
        }
        return false;
    }
}
