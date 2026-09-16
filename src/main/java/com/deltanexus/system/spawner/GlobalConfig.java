package com.deltanexus.system.spawner;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.JsonConfigWriter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局保护配置（{@code config/deltanexus/global.json}）。
 *
 * <p>{@code enabled = false}（默认）时全部全局限制不生效，刷兵不受任何全局限制；
 * {@code enabled = true} 时全部生效。</p>
 */
public final class GlobalConfig {

    /** 全局限制项。 */
    public static final class GlobalLimits {
        public int maxTotalEntitiesPerWorld = 200;
        public int maxEntitiesPerChunk = 20;
        public double minTpsToAllowSpawn = 15.0;
        public int minFreeMemoryPercent = 20;

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("max_total_entities_per_world", maxTotalEntitiesPerWorld);
            obj.addProperty("max_entities_per_chunk", maxEntitiesPerChunk);
            obj.addProperty("min_tps_to_allow_spawn", minTpsToAllowSpawn);
            obj.addProperty("min_free_memory_percent", minFreeMemoryPercent);
            return obj;
        }

        public static GlobalLimits fromJson(JsonObject obj) {
            GlobalLimits g = new GlobalLimits();
            if (obj == null) {
                return g;
            }
            g.maxTotalEntitiesPerWorld = obj.has("max_total_entities_per_world")
                    ? Math.max(0, obj.get("max_total_entities_per_world").getAsInt()) : 200;
            g.maxEntitiesPerChunk = obj.has("max_entities_per_chunk")
                    ? Math.max(0, obj.get("max_entities_per_chunk").getAsInt()) : 20;
            g.minTpsToAllowSpawn = obj.has("min_tps_to_allow_spawn")
                    ? Math.max(0, obj.get("min_tps_to_allow_spawn").getAsDouble()) : 15.0;
            g.minFreeMemoryPercent = obj.has("min_free_memory_percent")
                    ? Math.max(0, obj.get("min_free_memory_percent").getAsInt()) : 20;
            return g;
        }
    }

    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("deltanexus/global.json");

    private static volatile GlobalConfig INSTANCE;

    /** enabled=false 时全局保护整体关闭。默认关闭。 */
    public boolean enabled = false;
    /** 禁止刷兵的世界模式（通配符，匹配维度 ID 或世界文件夹名）。 */
    public final List<String> blacklistedWorldPatterns = new ArrayList<>();
    public final GlobalLimits limits = new GlobalLimits();

    private GlobalConfig() {
    }

    public static GlobalConfig get() {
        GlobalConfig c = INSTANCE;
        if (c == null) {
            synchronized (GlobalConfig.class) {
                c = INSTANCE;
                if (c == null) {
                    c = new GlobalConfig();
                    INSTANCE = c;
                }
            }
        }
        return c;
    }

    public static void writeDefaultIfMissing() {
        GlobalConfig c = get();
        if (Files.exists(PATH)) {
            c.reload();
        } else {
            c.saveNow();
            DeltaNexus.LOGGER.info("[DN] 已生成全局保护配置 {}", PATH);
        }
    }

    public synchronized void reload() {
        JsonObject root = JsonConfigWriter.readJson(PATH);
        if (root == null) {
            DeltaNexus.LOGGER.warn("[DN] 全局保护配置缺失或非法，已重置为默认");
            return;
        }
        enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
        blacklistedWorldPatterns.clear();
        if (root.has("blacklisted_world_patterns") && root.get("blacklisted_world_patterns").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("blacklisted_world_patterns")) {
                if (el.isJsonPrimitive()) {
                    blacklistedWorldPatterns.add(el.getAsString());
                }
            }
        }
        GlobalLimits fresh = GlobalLimits.fromJson(root.has("global_limits") && root.get("global_limits").isJsonObject()
                ? root.getAsJsonObject("global_limits") : null);
        limits.maxTotalEntitiesPerWorld = fresh.maxTotalEntitiesPerWorld;
        limits.maxEntitiesPerChunk = fresh.maxEntitiesPerChunk;
        limits.minTpsToAllowSpawn = fresh.minTpsToAllowSpawn;
        limits.minFreeMemoryPercent = fresh.minFreeMemoryPercent;
    }

    public synchronized void saveNow() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        JsonArray arr = new JsonArray();
        for (String p : blacklistedWorldPatterns) {
            arr.add(p);
        }
        root.add("blacklisted_world_patterns", arr);
        root.add("global_limits", limits.toJson());
        JsonConfigWriter.writeJsonNow(PATH, root);
    }

    /** 简单通配符匹配（* 匹配任意串，? 匹配单字符），大小写不敏感。 */
    public static boolean matches(String pattern, String value) {
        String p = pattern.toLowerCase(java.util.Locale.ROOT);
        String v = value.toLowerCase(java.util.Locale.ROOT);
        return regex(p).matcher(v).matches();
    }

    private static java.util.regex.Pattern regex(String wildcard) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : wildcard.toCharArray()) {
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else {
                sb.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append('$');
        return java.util.regex.Pattern.compile(sb.toString());
    }
}