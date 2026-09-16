package com.deltanexus.system.spawner;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.JsonConfigWriter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界层刷兵配置（{@code saves/<世界>/deltanexus/spawners.json} + {@code pointgroups.json}）。
 *
 * <p>以「世界文件夹路径」为键、不持有任何世界引用。克隆世界文件夹后配置自动携带。</p>
 */
public final class SpawnerWorldStore {

    private final Path dir;
    private final Path spawnersPath;
    private final Path groupsPath;

    /** 刷兵器（插入序）。 */
    private final Map<String, Spawner> spawners = new LinkedHashMap<>();
    /** 点位组：组名 → 点位名列表。 */
    private final Map<String, List<String>> groups = new LinkedHashMap<>();

    public SpawnerWorldStore(Path worldFolderPath) {
        this.dir = worldFolderPath.resolve("deltanexus");
        this.spawnersPath = dir.resolve("spawners.json");
        this.groupsPath = dir.resolve("pointgroups.json");
    }

    public Path dir() {
        return dir;
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    public synchronized void load() {
        spawners.clear();
        JsonObject root = JsonConfigWriter.readJson(spawnersPath);
        if (root != null) {
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    spawners.put(e.getKey(), Spawner.fromJson(e.getValue().getAsJsonObject()));
                }
            }
        }
        groups.clear();
        JsonObject gRoot = JsonConfigWriter.readJson(groupsPath);
        if (gRoot != null) {
            for (Map.Entry<String, JsonElement> e : gRoot.entrySet()) {
                if (e.getValue().isJsonArray()) {
                    List<String> list = new ArrayList<>();
                    for (JsonElement el : e.getValue().getAsJsonArray()) {
                        if (el.isJsonPrimitive()) {
                            list.add(el.getAsString());
                        }
                    }
                    groups.put(e.getKey(), list);
                }
            }
        }
    }

    public synchronized void saveNow() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Spawner> e : spawners.entrySet()) {
            root.add(e.getKey(), e.getValue().toJson());
        }
        JsonConfigWriter.writeJsonNow(spawnersPath, root);
        JsonObject g = new JsonObject();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            JsonArray arr = new JsonArray();
            for (String s : e.getValue()) {
                arr.add(s);
            }
            g.add(e.getKey(), arr);
        }
        JsonConfigWriter.writeJsonNow(groupsPath, g);
    }

    // ------------------------------------------------------------------
    // 查询 / 修改
    // ------------------------------------------------------------------

    public boolean has(String name) {
        return spawners.containsKey(name);
    }

    public Spawner get(String name) {
        return spawners.get(name);
    }

    public Map<String, Spawner> spawnersMutable() {
        return spawners;
    }

    public List<String> namesForPattern(String pattern) {
        List<String> out = new ArrayList<>();
        for (String name : spawners.keySet()) {
            if (GlobalConfig.matches(pattern, name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** 收集本世界所有刷兵器下匹配 pattern 的点位名（去重、保持插入序）。 */
    public List<String> collectPointNames(String pattern) {
        List<String> out = new ArrayList<>();
        for (Spawner s : spawners.values()) {
            for (String ptn : s.points.keySet()) {
                if (GlobalConfig.matches(pattern, ptn) && !out.contains(ptn)) {
                    out.add(ptn);
                }
            }
        }
        return out;
    }

    public Map<String, List<String>> groups() {
        return groups;
    }

    /** 解析刷兵器的目标点位列表（含组展开与 isEnabled 过滤已在调用方处理）。 */
    public List<String> resolvePointTargets(Spawner s) {
        List<String> target = new ArrayList<>();
        String ref = s.point;
        if (ref != null && !ref.isBlank()) {
            if (ref.startsWith("group:")) {
                String groupName = ref.substring("group:".length());
                List<String> g = groups.get(groupName);
                if (g != null) {
                    target.addAll(g);
                }
            } else {
                target.add(ref);
            }
        } else {
            target.addAll(s.points.keySet());
        }
        return target;
    }

    public void logLoadSummary() {
        DeltaNexus.LOGGER.info("[DN] 刷兵配置加载完成：{}（刷兵器 {} / 点位组 {}）",
                dir, spawners.size(), groups.size());
    }
}