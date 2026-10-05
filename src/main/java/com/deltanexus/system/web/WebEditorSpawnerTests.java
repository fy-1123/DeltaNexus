package com.deltanexus.system.web;

import com.deltanexus.system.spawner.Spawner;
import com.deltanexus.system.spawner.SpawnerWorldStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Web 编辑器「刷兵系统」接口回归测试（0.5.0Beta）。
 *
 * <p>只在 GameTestServer（{@code gradlew runGameTestServer}）执行；注册方式与其它回归测试一致
 * （{@link #register()} 由 {@code DeltaNexus} 构造器调用）。覆盖三件事：</p>
 * <ol>
 *   <li>概览载荷的 {@code spawner} 段结构（全局 / 日志 / 世界 / 维度列表）；</li>
 *   <li>世界层保存的<b>合并语义</b>（新增 / 更新 / 显式删除，且落盘可读回）；</li>
 *   <li>在线玩家位置字段（{@code dimension/x/y/z/yaw/pitch}）——刷兵点位「取玩家坐标」的数据源。</li>
 * </ol>
 *
 * <p>合并逻辑用临时目录里的 {@link SpawnerWorldStore} 验证，绝不触碰真实世界配置。</p>
 */
public class WebEditorSpawnerTests {

    /** 由 {@code DeltaNexus} 构造器调用。 */
    public static void register() {
        GameTestRegistry.register(WebEditorSpawnerTests.class);
    }

    /** 概览载荷：spawner 段必须带全局 / 日志 / 世界 / 维度，刷兵器条目带 _point_count 等展示字段。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void overviewSpawnerSectionShape(GameTestHelper helper) {
        JsonObject spawner = WebEditorServer.spawnerJson();
        for (String key : new String[]{"global", "logging", "world", "point_names"}) {
            if (!spawner.has(key)) {
                helper.fail("spawner 段缺少 " + key);
            }
        }
        JsonObject global = spawner.getAsJsonObject("global");
        if (!global.has("enabled") || !global.has("blacklisted_world_patterns") || !global.has("limits")) {
            helper.fail("global 段字段不全：" + global);
        }
        JsonObject logging = spawner.getAsJsonObject("logging");
        if (!logging.has("log_level") || !logging.has("levels") || logging.getAsJsonArray("levels").isEmpty()) {
            helper.fail("logging 段字段不全：" + logging);
        }
        JsonObject world = spawner.getAsJsonObject("world");
        if (!world.has("spawners") || !world.has("groups") || !world.has("dimensions")) {
            helper.fail("world 段字段不全：" + world);
        }
        // GameTestServer 常驻三维度：主世界 / 末地 / 下界
        JsonArray dims = world.getAsJsonArray("dimensions");
        if (dims.size() < 3) {
            helper.fail("维度列表应至少 3 个，实际 " + dims.size());
        }
        boolean overworld = false;
        for (var el : dims) {
            if ("minecraft:overworld".equals(el.getAsJsonObject().get("id").getAsString())) {
                overworld = true;
            }
        }
        if (!overworld) {
            helper.fail("维度列表里应包含 minecraft:overworld：" + dims);
        }
        helper.succeed();
    }

    /** 世界层保存：合并语义（新增 / 更新 / 删除）+ 落盘读回，且展示字段（_ 开头）不落盘。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void worldSaveMergesAndPersists(GameTestHelper helper) throws Exception {
        Path dir = Files.createTempDirectory("dn-spawner-web-test");
        SpawnerWorldStore store = new SpawnerWorldStore(dir);
        try {
            // ① 新增两件刷兵器 + 一个点位组
            JsonObject first = JsonParser.parseString("""
                    {"spawners":{"web_a":{"entity":"minecraft:zombie","count":"3-5",
                      "points":{"p1":{"x":100.5,"y":64,"z":-20.25,"radius":2.5,"enabled":true}},
                      "condition":{"difficulty":["normal"],"player_online_min":1},
                      "behavior":{"spawn_effect":{"particle":"minecraft:flame"},"on_spawn_commands":["say hi"]},
                      "nbt":{"Health":20.0},"_point_count":1,"_has_entity":true},
                     "web_b":{"entity":"minecraft:skeleton"}},
                     "groups":{"gA":["p1","p2","p1"]}}
                    """).getAsJsonObject();
            JsonObject r1 = WebEditorServer.applyWorldConfig(store, first);
            if (!r1.get("ok").getAsBoolean()) {
                helper.fail("首次保存应成功：" + r1);
            }
            store.saveNow();

            List<String> names = store.namesForPattern("*");
            if (names.size() != 2 || !store.has("web_a") || !store.has("web_b")) {
                helper.fail("应有 web_a / web_b 两件刷兵器，实际 " + names);
            }
            Spawner a = store.get("web_a");
            if (a.points.size() != 1 || Math.abs(a.points.get("p1").x - 100.5) > 1e-6
                    || a.points.get("p1").radius != 2.5) {
                helper.fail("点位未正确写入：" + a.points);
            }
            if (a.condition.playerOnlineMin != 1 || a.condition.difficulty.size() != 1) {
                helper.fail("条件未正确写入：" + a.condition.toJson());
            }
            if (a.behavior.spawnEffect.particle == null || a.behavior.onSpawnCommands.size() != 1) {
                helper.fail("行为未正确写入：" + a.behavior.toJson());
            }
            if (a.nbt == null || !a.nbt.contains("Health")) {
                helper.fail("实体 NBT 未正确写入：" + a.toJson());
            }
            List<String> gA = store.groups().get("gA");
            if (gA == null || gA.size() != 2) {
                helper.fail("点位组应去重为 2 个点名，实际 " + gA);
            }

            // ② 更新一件（改实体与点位）+ 删除另一件 + 删除组
            JsonObject second = JsonParser.parseString("""
                    {"spawners":{"web_a":{"entity":"minecraft:creeper","count":"1",
                      "points":{"p9":{"x":1,"y":2,"z":3,"radius":0,"enabled":true}}}},
                     "groups":{"gB":["p9"]},
                     "removed_spawners":["web_b"],
                     "removed_groups":["gA"]}
                    """).getAsJsonObject();
            JsonObject r2 = WebEditorServer.applyWorldConfig(store, second);
            if (!r2.get("ok").getAsBoolean() || !r2.get("msg").getAsString().contains("更新 1")
                    || !r2.get("msg").getAsString().contains("删除 2")) {
                helper.fail("第二次保存的计数应为 更新 1 / 删除 2：" + r2);
            }
            store.saveNow();
            if (store.has("web_b")) {
                helper.fail("web_b 应已被删除");
            }
            if (!store.has("web_a") || !"minecraft:creeper".equals(store.get("web_a").entity)
                    || !store.get("web_a").points.containsKey("p9")) {
                helper.fail("web_a 应被整件替换为最新内容：" + store.get("web_a").toJson());
            }
            if (store.groups().containsKey("gA") || !store.groups().containsKey("gB")) {
                helper.fail("点位组应删 gA 建 gB，实际 " + store.groups().keySet());
            }

            // ③ 落盘 → 重新读回（模拟服务器重启）：内容一致，且没有 _ 展示字段
            SpawnerWorldStore reloaded = new SpawnerWorldStore(dir);
            reloaded.load();
            if (!reloaded.has("web_a") || reloaded.get("web_a").points.size() != 1
                    || !"minecraft:creeper".equals(reloaded.get("web_a").entity)) {
                helper.fail("落盘读回后内容不一致：" + reloaded.spawnersMutable().keySet());
            }
            String raw = Files.readString(dir.resolve("deltanexus").resolve("spawners.json"));
            if (raw.contains("_point_count") || raw.contains("_has_entity")) {
                helper.fail("展示字段（_ 开头）不应写进配置文件：" + raw);
            }

            // ④ 非法刷兵器名必须被拒绝，且不产生任何写入
            JsonObject bad = JsonParser.parseString("{\"spawners\":{\"bad name!\":{\"entity\":\"minecraft:zombie\"}}}")
                    .getAsJsonObject();
            JsonObject r4 = WebEditorServer.applyWorldConfig(store, bad);
            if (r4.get("ok").getAsBoolean()) {
                helper.fail("非法刷兵器名应被拒绝");
            }
            if (store.namesForPattern("*").size() != 1) {
                helper.fail("被拒绝的保存不应改动任何刷兵器：" + store.namesForPattern("*"));
            }
        } finally {
            deleteTree(dir);
        }
        helper.succeed();
    }

    /** 在线玩家位置字段（刷兵点位「取该玩家坐标」的数据源）。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void playerPositionFields(GameTestHelper helper) {
        ServerPlayer p = FakePlayerFactory.getMinecraft(helper.getLevel());
        JsonObject o = new JsonObject();
        WebEditorServer.positionInto(o, p);
        for (String key : new String[]{"dimension", "x", "y", "z", "yaw", "pitch"}) {
            if (!o.has(key)) {
                helper.fail("玩家位置字段缺少 " + key + "：" + o);
            }
        }
        String dim = o.get("dimension").getAsString();
        if (!dim.contains(":")) {
            helper.fail("维度应是完整 id（namespace:path），实际 " + dim);
        }
        // 坐标必须是有限数值（点位编辑器直接 Number() 转换）
        for (String key : new String[]{"x", "y", "z"}) {
            double v = o.get(key).getAsDouble();
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                helper.fail(key + " 不是有限数值：" + v);
            }
        }
        helper.succeed();
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 测试清理失败不影响断言
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
