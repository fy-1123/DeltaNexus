package com.deltanexus.system.config;

import com.deltanexus.system.common.NbtMatcher;
import com.deltanexus.system.DeltaNexus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方 POJO（"代码即配置，游戏内可改"）。
 *
 * <p>JSON 格式（与 Plan.md 阶段 1 一致）：</p>
 * <pre>{@code
 * {
 *   "type": "armor_workbench",
 *   "recipe_id": "iron_vest_mk1",
 *   "required_level": 1,
 *   "base_duration": 30,
 *   "input":  [ { "item": "minecraft:iron_ingot", "count": 5, "nbt": { "match_type": "ignore" } } ],
 *   "output": [ { "item": "minecraft:iron_chestplate", "count": 1, "nbt": "{Enchantments:[{id:\"minecraft:protection\",lvl:2}]}" } ]
 * }
 * }</pre>
 */
public class Recipe {

    /** 配方输入物品定义。 */
    public static class Ingredient {
        public String item = "minecraft:air";
        public int count = 1;
        public NbtMatcher.MatchType matchType = NbtMatcher.MatchType.IGNORE;
        /** NBT 字符串（exact/contains 时使用，可空）。 */
        public String nbt = "";

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("item", item);
            obj.addProperty("count", count);
            JsonObject nbtObj = new JsonObject();
            nbtObj.addProperty("match_type", matchType.key());
            if (nbt != null && !nbt.isBlank()) {
                nbtObj.addProperty("nbt", nbt);
            }
            obj.add("nbt", nbtObj);
            return obj;
        }

        public static Ingredient fromJson(JsonObject obj) {
            Ingredient ing = new Ingredient();
            ing.item = obj.has("item") ? obj.get("item").getAsString() : "minecraft:air";
            ing.count = obj.has("count") ? Math.max(1, obj.get("count").getAsInt()) : 1;
            if (obj.has("nbt")) {
                JsonElement nbtEl = obj.get("nbt");
                if (nbtEl.isJsonObject()) {
                    JsonObject nbtObj = nbtEl.getAsJsonObject();
                    ing.matchType = NbtMatcher.MatchType.parse(
                            nbtObj.has("match_type") ? nbtObj.get("match_type").getAsString() : null);
                    if (nbtObj.has("nbt")) {
                        ing.nbt = nbtObj.get("nbt").getAsString();
                    }
                } else if (nbtEl.isJsonPrimitive()) {
                    ing.nbt = nbtEl.getAsString();
                    if (ing.nbt.isBlank()) {
                        ing.matchType = NbtMatcher.MatchType.IGNORE;
                    }
                }
            }
            return ing;
        }
    }

    /** 配方输出物品定义。 */
    public static class Output {
        public String item = "minecraft:air";
        public int count = 1;
        /** NBT 字符串，如 "{Damage:0}"；空串表示无 NBT。 */
        public String nbt = "";

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("item", item);
            obj.addProperty("count", count);
            obj.addProperty("nbt", nbt == null ? "" : nbt);
            return obj;
        }

        public static Output fromJson(JsonObject obj) {
            Output out = new Output();
            out.item = obj.has("item") ? obj.get("item").getAsString() : "minecraft:air";
            out.count = obj.has("count") ? Math.max(1, obj.get("count").getAsInt()) : 1;
            out.nbt = obj.has("nbt") ? obj.get("nbt").getAsString() : "";
            return out;
        }

        /** 构造输出物品栈（含 NBT 解析，非法 NBT 返回无 NBT 物品）。 */
        public ItemStack toItemStack() {
            var itemObj = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    net.minecraft.resources.ResourceLocation.tryParse(this.item));
            if (itemObj == null) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = new ItemStack(itemObj, Math.max(1, count));
            var tag = NbtMatcher.parseTag(nbt);
            if (tag != null) {
                stack.setTag(tag);
            }
            return stack;
        }
    }

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public String recipeId = "";
    /** 配方显示名（空串时回退 recipeId，展示层使用 {@link #displayName()}）。 */
    public String displayName = "";
    /** 配方所属工作台的配方目录名（= 磁盘子目录名）。 */
    public String type = "armor_workbench";
    public int requiredLevel = 0;
    /** 基础耗时（秒）。 */
    public long baseDuration = 30;
    /** 该配方可同时制作的上限（进行中任务数，默认 1，与制造台按钮三状态联动）。 */
    public int maxParallel = 1;
    public final List<Ingredient> input = new ArrayList<>();
    public final List<Output> output = new ArrayList<>();

    /** 配方所属工作台 id（通过注册表映射目录名）。 */
    public String workbenchId() {
        var wb = com.deltanexus.system.common.WorkbenchRegistry.get().getByRecipesDir(type);
        return wb != null ? wb.id : type;
    }

    /** 显示名（未设置时回退 recipeId）。 */
    public String displayName() {
        return displayName != null && !displayName.isBlank() ? displayName : recipeId;
    }

    /** 配方展示图标：第一个输出物品。 */
    public ItemStack iconItem() {
        if (!output.isEmpty()) {
            return output.get(0).toItemStack();
        }
        return new ItemStack(Items.BARRIER);
    }

    /** 基础耗时毫秒。 */
    public long baseDurationMs() {
        return baseDuration * 1000L;
    }

    /** 合法性：仅需 recipeId 非空（允许空 input/output 骨架，便于先建后补）。 */
    public boolean isValid() {
        return recipeId != null && !recipeId.isBlank();
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("recipe_id", recipeId);
        if (displayName != null && !displayName.isBlank()) {
            obj.addProperty("display_name", displayName);
        }
        obj.addProperty("required_level", requiredLevel);
        obj.addProperty("base_duration", baseDuration);
        obj.addProperty("max_parallel", maxParallel);
        JsonArray in = new JsonArray();
        for (Ingredient i : input) {
            in.add(i.toJson());
        }
        obj.add("input", in);
        JsonArray out = new JsonArray();
        for (Output o : output) {
            out.add(o.toJson());
        }
        obj.add("output", out);
        return obj;
    }

    public String toJsonString() {
        return GSON.toJson(toJson());
    }

    /** 从 JSON 解析；非法字段自动容错。 */
    public static Recipe fromJson(JsonObject obj) {
        Recipe r = new Recipe();
        if (obj == null) {
            return r;
        }
        r.type = obj.has("type") ? obj.get("type").getAsString() : "armor_workbench";
        r.recipeId = obj.has("recipe_id") ? obj.get("recipe_id").getAsString() : "";
        r.displayName = obj.has("display_name") ? obj.get("display_name").getAsString() : "";
        r.requiredLevel = obj.has("required_level") ? Math.max(0, obj.get("required_level").getAsInt()) : 0;
        r.baseDuration = obj.has("base_duration") ? Math.max(1, obj.get("base_duration").getAsLong()) : 30;
        r.maxParallel = obj.has("max_parallel") ? Math.max(1, Math.min(100, obj.get("max_parallel").getAsInt())) : 1;
        if (obj.has("input") && obj.get("input").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("input")) {
                if (el.isJsonObject()) {
                    r.input.add(Ingredient.fromJson(el.getAsJsonObject()));
                }
            }
        }
        if (obj.has("output") && obj.get("output").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("output")) {
                if (el.isJsonObject()) {
                    r.output.add(Output.fromJson(el.getAsJsonObject()));
                }
            }
        }
        return r;
    }

    public static Recipe fromJsonString(String json) {
        try {
            return fromJson(com.google.gson.JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] 配方 JSON 解析失败: {}", e.getMessage());
            return new Recipe();
        }
    }
}
