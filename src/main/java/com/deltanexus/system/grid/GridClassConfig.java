package com.deltanexus.system.grid;

import com.deltanexus.system.DeltaNexus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 格式背包「类」配置（2.0.3Alpha）：每个物品可归属一个「类」，每个类有独立的背景颜色，
 * 网格中跨格物品的背景墙按所属类着色（未配置类的物品使用默认灰色）。
 *
 * <p>配置文件：{@code config/deltanexus/grid_classes.json}</p>
 * <pre>{@code
 * {
 *   "classes": { "common": [92,96,104], "rare": [52,110,190], "epic": [150,86,210], "legendary": [214,158,60] },
 *   "items": { "minecraft:diamond": "rare" }
 * }
 * }</pre>
 *
 * <p>2.0.3Alpha：指令（/dn grid class/setclass）与 Web 编辑器管理；修改后随
 * {@code SyncGridSizesPacket} 同步客户端（运行时覆盖，渲染一致）。</p>
 *
 * <p>2.0.7Alpha 重构：配置文件为空/损坏时自动重建默认类（旧实现会静默留下空表，
 * 导致「类必须存在」校验失败、类背景不生效）；新增 {@link #isClassed(ItemStack)}
 * 显式判定物品是否命中类配置，渲染侧不再依赖 {@code bgOf(...) != DEFAULT_BG}
 * 的数组引用比较。</p>
 */
public class GridClassConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("deltanexus/grid_classes.json");

    /** 类名 -> RGB。 */
    private static final Map<String, int[]> CLASSES = new LinkedHashMap<>();
    /** 物品 -> 类名。 */
    private static final Map<String, String> ITEM_CLASS = new LinkedHashMap<>();
    /** 客户端运行时覆盖（来自服务端同步包）。 */
    private static final Map<String, int[]> RUNTIME_CLASSES = new LinkedHashMap<>();
    private static final Map<String, String> RUNTIME_ITEM_CLASS = new LinkedHashMap<>();

    /** 默认背景（未配置类的物品）。 */
    public static final int[] DEFAULT_BG = {0xFFB0B0B0, 0xFFC6C6C6, 0xFF373737};

    private GridClassConfig() {
    }

    public static void load() {
        if (Files.exists(PATH)) {
            try {
                String raw = Files.readString(PATH);
                // 2.0.7Alpha：空文件/纯空白视为未初始化 -> 重建默认类（旧实现留下空表）
                if (raw == null || raw.isBlank()) {
                    generateDefaults();
                    save();
                    return;
                }
                readInto(CLASSES, ITEM_CLASS);
            } catch (Exception e) {
                DeltaNexus.LOGGER.warn("[DN] 格式背包类配置读取失败，重建默认: {}", e.getMessage());
                generateDefaults();
                save();
            }
        } else {
            generateDefaults();
            save();
        }
    }

    private static void readInto(Map<String, int[]> classes, Map<String, String> itemClass) {
        try {
            // 2.0.7Alpha：先解析到临时表，成功后再整体替换，避免解析失败清空已有配置
            JsonObject root = com.google.gson.JsonParser.parseString(Files.readString(PATH)).getAsJsonObject();
            Map<String, int[]> parsedClasses = new LinkedHashMap<>();
            Map<String, String> parsedItems = new LinkedHashMap<>();
            if (root.has("classes") && root.get("classes").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("classes").entrySet()) {
                    int[] rgb = parseColor(e.getValue());
                    if (rgb != null) {
                        parsedClasses.put(e.getKey(), rgb);
                    }
                }
            }
            if (root.has("items") && root.get("items").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("items").entrySet()) {
                    parsedItems.put(e.getKey(), e.getValue().getAsString());
                }
            }
            classes.clear();
            itemClass.clear();
            classes.putAll(parsedClasses);
            itemClass.putAll(parsedItems);
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] 格式背包类配置解析失败: {}", e.getMessage());
        }
    }

    private static int[] parseColor(JsonElement el) {
        try {
            if (el.isJsonArray() && el.getAsJsonArray().size() >= 3) {
                JsonArray a = el.getAsJsonArray();
                return new int[]{clamp(a.get(0).getAsInt()), clamp(a.get(1).getAsInt()), clamp(a.get(2).getAsInt())};
            }
            if (el.isJsonPrimitive() && el.getAsString().startsWith("#")) {
                int rgb = Integer.parseInt(el.getAsString().substring(1), 16);
                return new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, toJsonString());
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] 格式背包类配置写入失败: {}", e.getMessage());
        }
    }

    private static String toJsonString() {
        JsonObject root = new JsonObject();
        JsonObject classes = new JsonObject();
        for (Map.Entry<String, int[]> e : CLASSES.entrySet()) {
            JsonArray c = new JsonArray();
            c.add(e.getValue()[0]);
            c.add(e.getValue()[1]);
            c.add(e.getValue()[2]);
            classes.add(e.getKey(), c);
        }
        root.add("classes", classes);
        JsonObject items = new JsonObject();
        for (Map.Entry<String, String> e : ITEM_CLASS.entrySet()) {
            items.addProperty(e.getKey(), e.getValue());
        }
        root.add("items", items);
        return GSON.toJson(root);
    }

    private static void generateDefaults() {
        CLASSES.put("common", new int[]{92, 96, 104});
        CLASSES.put("rare", new int[]{52, 110, 190});
        CLASSES.put("epic", new int[]{150, 86, 210});
        CLASSES.put("legendary", new int[]{214, 158, 60});
    }

    // ------------------------------------------------------------------
    // 查询（渲染用；运行时覆盖优先）
    // ------------------------------------------------------------------

    /** 物品是否命中类配置（已归属类且类颜色可解析）。渲染侧以此判定是否绘制类色墙。 */
    public static boolean isClassed(ItemStack stack) {
        String cls = itemClassOf(stack);
        if (cls == null) {
            return false;
        }
        return RUNTIME_CLASSES.containsKey(cls) || CLASSES.containsKey(cls);
    }

    /** 物品背景色 {底亮, 底暗, 边框}（按所属类；未配置类用默认灰）。 */
    public static int[] bgOf(ItemStack stack) {
        String cls = itemClassOf(stack);
        int[] rgb = cls != null ? (RUNTIME_CLASSES.containsKey(cls) ? RUNTIME_CLASSES.get(cls)
                : CLASSES.get(cls)) : null;
        if (rgb == null) {
            return DEFAULT_BG;
        }
        int light = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        // 底暗/边框由亮色微调（保持立体感）
        int dark = 0xFF000000 | ((rgb[0] * 3 / 4) << 16) | ((rgb[1] * 3 / 4) << 8) | (rgb[2] * 3 / 4);
        int border = 0xFF000000 | ((rgb[0] / 2) << 16) | ((rgb[1] / 2) << 8) | (rgb[2] / 2);
        return new int[]{dark, light, border};
    }

    /** 物品所属类名（未配置返回 null）。 */
    public static String itemClassOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return null;
        }
        String cls = RUNTIME_ITEM_CLASS.get(key.toString());
        if (cls == null) {
            cls = ITEM_CLASS.get(key.toString());
        }
        return cls;
    }

    // ------------------------------------------------------------------
    // 管理（服务端：指令 / Web）
    // ------------------------------------------------------------------

    /** 设置类颜色（不存在则创建）。 */
    public static synchronized boolean setClass(String name, int r, int g, int b) {
        if (name == null || name.isBlank()) {
            return false;
        }
        CLASSES.put(name.trim(), new int[]{clamp(r), clamp(g), clamp(b)});
        save();
        return true;
    }

    /** 删除类（物品归属一并清除）。 */
    public static synchronized boolean removeClass(String name) {
        if (name == null || name.isBlank() || CLASSES.remove(name.trim()) == null) {
            return false;
        }
        ITEM_CLASS.entrySet().removeIf(e -> e.getValue().equals(name.trim()));
        save();
        return true;
    }

    /** 设置物品所属类（类必须存在）。 */
    public static synchronized boolean setItemClass(String itemId, String className) {
        if (itemId == null || itemId.isBlank() || className == null || className.isBlank()
                || !CLASSES.containsKey(className.trim())
                || ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId.trim())) == null) {
            return false;
        }
        ITEM_CLASS.put(itemId.trim(), className.trim());
        save();
        return true;
    }

    /** 移除物品所属类。 */
    public static synchronized boolean unsetItemClass(String itemId) {
        if (itemId == null || itemId.isBlank() || ITEM_CLASS.remove(itemId.trim()) == null) {
            return false;
        }
        save();
        return true;
    }

    /** 全部类（名称 -> RGB）。 */
    public static synchronized LinkedHashMap<String, int[]> allClasses() {
        return new LinkedHashMap<>(CLASSES);
    }

    /** 全部物品归属（物品 -> 类名）。 */
    public static synchronized LinkedHashMap<String, String> allItemClasses() {
        return new LinkedHashMap<>(ITEM_CLASS);
    }

    /** 2.0.3Alpha：应用客户端运行时覆盖（服务端同步包）。 */
    public static synchronized void applyRuntime(Map<String, int[]> classes, Map<String, String> itemClass) {
        RUNTIME_CLASSES.clear();
        RUNTIME_ITEM_CLASS.clear();
        if (classes != null) {
            RUNTIME_CLASSES.putAll(classes);
        }
        if (itemClass != null) {
            RUNTIME_ITEM_CLASS.putAll(itemClass);
        }
    }
}
