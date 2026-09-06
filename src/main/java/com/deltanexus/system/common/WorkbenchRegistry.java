package com.deltanexus.system.common;

import com.deltanexus.system.config.JsonConfigWriter;
import com.deltanexus.system.DeltaNexus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工作台注册表（动态化）。
 *
 * <p>不再硬编码"枪械台/防具台/制药台"三个类别，工作台由
 * {@code config/deltanexus/workbenches.json} 配置 + {@code /dn workbench} 指令管理，
 * 每个工作台含 id（网络/存储标识）、display（显示名）、recipesDir（配方子目录）。</p>
 *
 * <pre>{@code
 * { "workbenches": [
 *     { "id": "armor", "display": "防具工作台", "recipes_dir": "armor_workbench" }
 * ] }
 * }</pre>
 */
public final class WorkbenchRegistry {

    /** 工作台定义。 */
    public static class Workbench {
        public String id;
        public String display;
        public String recipesDir;

        public Workbench() {
        }

        public Workbench(String id, String display, String recipesDir) {
            this.id = id;
            this.display = display;
            this.recipesDir = recipesDir;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id);
            obj.addProperty("display", display);
            obj.addProperty("recipes_dir", recipesDir);
            return obj;
        }

        public static Workbench fromJson(JsonObject obj) {
            Workbench w = new Workbench();
            w.id = obj.has("id") ? obj.get("id").getAsString().trim().toLowerCase(Locale.ROOT) : "";
            w.display = obj.has("display") ? obj.get("display").getAsString() : w.id;
            w.recipesDir = obj.has("recipes_dir") ? obj.get("recipes_dir").getAsString().trim()
                    : (w.id.isEmpty() ? "" : w.id);
            return w;
        }
    }

    private static WorkbenchRegistry INSTANCE;

    private final Path path;
    private final Map<String, Workbench> byId = new LinkedHashMap<>();
    private final Map<String, String> dirToId = new LinkedHashMap<>();

    private WorkbenchRegistry(Path path) {
        this.path = path;
    }

    public static synchronized WorkbenchRegistry get() {
        if (INSTANCE == null) {
            INSTANCE = new WorkbenchRegistry(FMLPaths.CONFIGDIR.get().resolve("deltanexus/workbenches.json"));
        }
        return INSTANCE;
    }

    public Path path() {
        return path;
    }

    public synchronized void reload() {
        byId.clear();
        dirToId.clear();
        JsonObject root = JsonConfigWriter.readJson(path);
        if (root == null || !root.has("workbenches")) {
            return;
        }
        for (JsonElement el : root.getAsJsonArray("workbenches")) {
            if (el.isJsonObject()) {
                Workbench w = Workbench.fromJson(el.getAsJsonObject());
                if (!w.id.isEmpty() && !byId.containsKey(w.id)) {
                    byId.put(w.id, w);
                    dirToId.put(w.recipesDir, w.id);
                }
            }
        }
        DeltaNexus.LOGGER.info("[DN] 工作台配置热加载完成，共 {} 个", byId.size());
    }

    public Workbench getById(String id) {
        return id == null ? null : byId.get(id);
    }

    /** 按配方目录名查找（旧数据兼容）。 */
    public Workbench getByRecipesDir(String dir) {
        if (dir == null) {
            return null;
        }
        String id = dirToId.get(dir);
        return id == null ? null : byId.get(id);
    }

    public List<Workbench> all() {
        return new ArrayList<>(byId.values());
    }

    public int size() {
        return byId.size();
    }

    public boolean exists(String id) {
        return byId.containsKey(id);
    }

    /** 显示名（找不到回退 id）。 */
    public String displayName(String id) {
        Workbench w = byId.get(id);
        return w != null ? w.display : id;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Workbench w : byId.values()) {
            arr.add(w.toJson());
        }
        root.add("workbenches", arr);
        return root;
    }

    /** 解析 + 内存刷新 + 异步防反跳落盘。 */
    public synchronized void save(String jsonString) {
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(jsonString).getAsJsonObject();
            byId.clear();
            dirToId.clear();
            if (root.has("workbenches")) {
                for (JsonElement el : root.getAsJsonArray("workbenches")) {
                    if (el.isJsonObject()) {
                        Workbench w = Workbench.fromJson(el.getAsJsonObject());
                        if (!w.id.isEmpty() && !byId.containsKey(w.id)) {
                            byId.put(w.id, w);
                            dirToId.put(w.recipesDir, w.id);
                        }
                    }
                }
            }
            JsonConfigWriter.saveJsonDebounced(path, toJson(), com.deltanexus.system.config.ModConfig.saveDebounceMs());
            DeltaNexus.LOGGER.info("[DN] 工作台配置已更新");
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] 工作台配置 JSON 解析失败: {}", e.getMessage());
        }
    }

    /** 内存级修改（指令用），不入盘。 */
    public synchronized void add(Workbench w) {
        if (w == null || w.id.isEmpty() || byId.containsKey(w.id)) {
            return;
        }
        byId.put(w.id, w);
        dirToId.put(w.recipesDir, w.id);
    }

    public synchronized Workbench remove(String id) {
        Workbench w = byId.remove(id);
        if (w != null) {
            dirToId.remove(w.recipesDir);
        }
        return w;
    }

    /** 首次启动：生成默认示例工作台「列示」。 */
    public void writeDefaultIfMissing() {
        if (Files.exists(path)) {
            reload();
            return;
        }
        try {
            Files.createDirectories(path.getParent());
        } catch (Exception e) {
            DeltaNexus.LOGGER.error("[DN] 创建配置目录失败: {}", e.getMessage());
            return;
        }
        add(new Workbench("lieshi", "列示", "lieshi"));
        JsonConfigWriter.writeJsonNow(path, toJson());
        DeltaNexus.LOGGER.info("[DN] 已生成默认工作台配置");
    }
}
