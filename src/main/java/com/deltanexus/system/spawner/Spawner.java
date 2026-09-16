package com.deltanexus.system.spawner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 刷兵器模型（对应 {@code saves/<世界名>/deltanexus/spawners.json} 中的单个条目）。
 *
 * <p>刷兵器 = 实体 + 点位 + 规则。字段与《0.4.0beta刷兵系统设计方案》一一对应。</p>
 */
public class Spawner {

    /** 随机实体池条目。 */
    public static final class PoolEntry {
        public String id;
        public int weight = 1;

        public static PoolEntry fromJson(JsonObject obj) {
            PoolEntry p = new PoolEntry();
            if (obj.has("id")) {
                p.id = obj.get("id").getAsString();
            }
            if (obj.has("weight")) {
                p.weight = Math.max(1, obj.get("weight").getAsInt());
            }
            return p;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id);
            obj.addProperty("weight", weight);
            return obj;
        }
    }

    /** 点位。 */
    public static final class SpawnerPoint {
        public double x;
        public double y;
        public double z;
        public double radius = 0.0;
        public boolean enabled = true;

        public static SpawnerPoint fromJson(JsonObject obj) {
            SpawnerPoint p = new SpawnerPoint();
            p.x = obj.has("x") ? obj.get("x").getAsDouble() : 0;
            p.y = obj.has("y") ? obj.get("y").getAsDouble() : 64;
            p.z = obj.has("z") ? obj.get("z").getAsDouble() : 0;
            p.radius = obj.has("radius") ? Math.max(0, obj.get("radius").getAsDouble()) : 0;
            p.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
            return p;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("x", x);
            obj.addProperty("y", y);
            obj.addProperty("z", z);
            obj.addProperty("radius", radius);
            obj.addProperty("enabled", enabled);
            return obj;
        }
    }

    /** 刷兵条件（不满足则不刷）。 */
    public static final class SpawnerCondition {
        public List<String> difficulty = new ArrayList<>();
        public int playerOnlineMin = 0;

        public static SpawnerCondition fromJson(JsonObject obj) {
            SpawnerCondition c = new SpawnerCondition();
            if (obj == null) {
                return c;
            }
            if (obj.has("difficulty") && obj.get("difficulty").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("difficulty")) {
                    if (el.isJsonPrimitive()) {
                        c.difficulty.add(el.getAsString().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            c.playerOnlineMin = obj.has("player_online_min") ? Math.max(0, obj.get("player_online_min").getAsInt()) : 0;
            return c;
        }

        /** 就地加载（保持对象身份，运行时指令/热载用）。 */
        public void loadFrom(JsonObject obj) {
            SpawnerCondition c = fromJson(obj);
            this.difficulty.clear();
            this.difficulty.addAll(c.difficulty);
            this.playerOnlineMin = c.playerOnlineMin;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            JsonArray arr = new JsonArray();
            for (String d : difficulty) {
                arr.add(d);
            }
            obj.add("difficulty", arr);
            obj.addProperty("player_online_min", playerOnlineMin);
            return obj;
        }
    }

    /** 刷兵安全检测。 */
    public static final class SpawnerSafety {
        public boolean requireSolidGround = true;
        public boolean avoidLava = true;
        public boolean avoidWater = true;
        public int maxAttemptsPerEntity = 5;

        public static SpawnerSafety fromJson(JsonObject obj) {
            SpawnerSafety s = new SpawnerSafety();
            if (obj == null) {
                return s;
            }
            s.requireSolidGround = !obj.has("require_solid_ground") || obj.get("require_solid_ground").getAsBoolean();
            s.avoidLava = !obj.has("avoid_lava") || obj.get("avoid_lava").getAsBoolean();
            s.avoidWater = !obj.has("avoid_water") || obj.get("avoid_water").getAsBoolean();
            s.maxAttemptsPerEntity = obj.has("max_attempts_per_entity")
                    ? Math.max(1, obj.get("max_attempts_per_entity").getAsInt()) : 5;
            return s;
        }

        /** 就地加载。 */
        public void loadFrom(JsonObject obj) {
            SpawnerSafety s = fromJson(obj);
            this.requireSolidGround = s.requireSolidGround;
            this.avoidLava = s.avoidLava;
            this.avoidWater = s.avoidWater;
            this.maxAttemptsPerEntity = s.maxAttemptsPerEntity;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("require_solid_ground", requireSolidGround);
            obj.addProperty("avoid_lava", avoidLava);
            obj.addProperty("avoid_water", avoidWater);
            obj.addProperty("max_attempts_per_entity", maxAttemptsPerEntity);
            return obj;
        }
    }

    /** 生成后特效。 */
    public static final class SpawnerEffect {
        public String particle; // 可空
        public String sound;    // 可空

        public static SpawnerEffect fromJson(JsonObject obj) {
            SpawnerEffect e = new SpawnerEffect();
            if (obj == null) {
                return e;
            }
            if (obj.has("particle")) {
                e.particle = obj.get("particle").getAsString();
            }
            if (obj.has("sound")) {
                e.sound = obj.get("sound").getAsString();
            }
            return e;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            if (particle != null) {
                obj.addProperty("particle", particle);
            }
            if (sound != null) {
                obj.addProperty("sound", sound);
            }
            return obj;
        }
    }

    /** 生成后行为。 */
    public static final class SpawnerBehavior {
        public SpawnerEffect spawnEffect = new SpawnerEffect();
        public List<String> onSpawnCommands = new ArrayList<>();
        public boolean glowing = false;
        public boolean persistent = true;
        public boolean silent = false;
        public boolean noAi = false;

        public static SpawnerBehavior fromJson(JsonObject obj) {
            SpawnerBehavior b = new SpawnerBehavior();
            if (obj == null) {
                return b;
            }
            if (obj.has("spawn_effect")) {
                b.spawnEffect = SpawnerEffect.fromJson(obj.getAsJsonObject("spawn_effect"));
            }
            if (obj.has("on_spawn_commands") && obj.get("on_spawn_commands").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("on_spawn_commands")) {
                    if (el.isJsonPrimitive()) {
                        b.onSpawnCommands.add(el.getAsString());
                    }
                }
            }
            b.glowing = obj.has("glowing") && obj.get("glowing").getAsBoolean();
            b.persistent = !obj.has("persistent") || obj.get("persistent").getAsBoolean();
            b.silent = obj.has("silent") && obj.get("silent").getAsBoolean();
            b.noAi = obj.has("no_ai") && obj.get("no_ai").getAsBoolean();
            return b;
        }

        /** 就地加载。 */
        public void loadFrom(JsonObject obj) {
            SpawnerBehavior b = fromJson(obj);
            this.spawnEffect.particle = b.spawnEffect.particle;
            this.spawnEffect.sound = b.spawnEffect.sound;
            this.onSpawnCommands.clear();
            this.onSpawnCommands.addAll(b.onSpawnCommands);
            this.glowing = b.glowing;
            this.persistent = b.persistent;
            this.silent = b.silent;
            this.noAi = b.noAi;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.add("spawn_effect", spawnEffect.toJson());
            JsonArray arr = new JsonArray();
            for (String c : onSpawnCommands) {
                arr.add(c);
            }
            obj.add("on_spawn_commands", arr);
            obj.addProperty("glowing", glowing);
            obj.addProperty("persistent", persistent);
            obj.addProperty("silent", silent);
            obj.addProperty("no_ai", noAi);
            return obj;
        }
    }

    // ------------------------------------------------------------------
    // 字段
    // ------------------------------------------------------------------

    public String entity;               // 可空（与 entityPool 二选一）
    public List<PoolEntry> entityPool;  // 可空
    public CompoundTag nbt;             // 可空
    public String count = "1";
    public String point;                // 可空；group:名称 或 点名
    public final Map<String, SpawnerPoint> points = new LinkedHashMap<>();
    public final SpawnerCondition condition = new SpawnerCondition();
    public final SpawnerSafety safety = new SpawnerSafety();
    public final SpawnerBehavior behavior = new SpawnerBehavior();

    public static Spawner fromJson(JsonObject obj) {
        Spawner s = new Spawner();
        if (obj.has("entity")) {
            s.entity = obj.get("entity").getAsString();
        }
        if (obj.has("entity_pool") && obj.get("entity_pool").isJsonArray()) {
            s.entityPool = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("entity_pool")) {
                if (el.isJsonObject()) {
                    s.entityPool.add(PoolEntry.fromJson(el.getAsJsonObject()));
                }
            }
        }
        if (obj.has("nbt") && obj.get("nbt").isJsonObject()) {
            Tag t = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, obj.get("nbt"));
            if (t instanceof CompoundTag c) {
                s.nbt = c;
            }
        }
        if (obj.has("count")) {
            s.count = obj.get("count").getAsString();
        }
        if (obj.has("point")) {
            s.point = obj.get("point").getAsString();
        }
        if (obj.has("points") && obj.get("points").isJsonObject()) {
            JsonObject ps = obj.getAsJsonObject("points");
            for (Map.Entry<String, JsonElement> e : ps.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    s.points.put(e.getKey(), SpawnerPoint.fromJson(e.getValue().getAsJsonObject()));
                }
            }
        }
        s.condition.loadFrom(obj.has("condition") && obj.get("condition").isJsonObject()
                ? obj.getAsJsonObject("condition") : null);
        s.safety.loadFrom(obj.has("safety") && obj.get("safety").isJsonObject()
                ? obj.getAsJsonObject("safety") : null);
        s.behavior.loadFrom(obj.has("behavior") && obj.get("behavior").isJsonObject()
                ? obj.getAsJsonObject("behavior") : null);
        return s;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        if (entity != null) {
            obj.addProperty("entity", entity);
        }
        if (entityPool != null) {
            JsonArray arr = new JsonArray();
            for (PoolEntry p : entityPool) {
                arr.add(p.toJson());
            }
            obj.add("entity_pool", arr);
        }
        if (nbt != null) {
            obj.add("nbt", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, nbt));
        }
        obj.addProperty("count", count);
        if (point != null) {
            obj.addProperty("point", point);
        }
        JsonObject ps = new JsonObject();
        for (Map.Entry<String, SpawnerPoint> e : points.entrySet()) {
            ps.add(e.getKey(), e.getValue().toJson());
        }
        obj.add("points", ps);
        obj.add("condition", condition.toJson());
        obj.add("safety", safety.toJson());
        obj.add("behavior", behavior.toJson());
        return obj;
    }
}