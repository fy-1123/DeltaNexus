package com.deltanexus.system.spawner;

import com.deltanexus.system.spawner.Spawner.PoolEntry;
import com.deltanexus.system.spawner.Spawner.SpawnerPoint;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /dn spawner} 指令树（0.4.0Beta 刷兵系统）。
 *
 * <p>全部指令权限等级 2（OP）。一级只此一个 {@code spawner} 子指令挂在 {@code /dn} 下。</p>
 */
public final class SpawnerCommand {

    private static final String[] SET_KEYS = {"entity", "entity_pool", "count", "point"};
    private static final String[] LOG_LEVELS = {"off", "error", "warn", "info", "debug"};

    private static final SuggestionProvider<CommandSourceStack> SPAWNER_NAMES = (ctx, b) -> {
        for (String n : SpawnerManager.world(ctx.getSource().getLevel()).spawnersMutable().keySet()) {
            b.suggest(n);
        }
        return b.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> POINT_NAMES = (ctx, b) -> {
        SpawnerWorldStore st = SpawnerManager.world(ctx.getSource().getLevel());
        for (Spawner s : st.spawnersMutable().values()) {
            for (String ptn : s.points.keySet()) {
                b.suggest(ptn);
            }
        }
        return b.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> GROUP_NAMES = (ctx, b) -> {
        for (String n : SpawnerManager.world(ctx.getSource().getLevel()).groups().keySet()) {
            b.suggest(n);
        }
        return b.buildFuture();
    };

    /** 建议「当前 name 刷兵器」下的点位名（供 point del/move/radius/enable/disable/list 的 pattern 使用）。 */
    private static final SuggestionProvider<CommandSourceStack> POINTS_OF_NAME = (ctx, b) -> {
        String name;
        try {
            name = ctx.getArgument("name", String.class);
        } catch (IllegalArgumentException ignored) {
            return b.buildFuture();
        }
        Spawner s = SpawnerManager.world(ctx.getSource().getLevel()).get(name);
        if (s != null) {
            for (String ptn : s.points.keySet()) {
                b.suggest(ptn);
            }
        }
        return b.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> SET_KEY_SUG = (ctx, b) -> {
        SharedSuggestionProvider.suggest(SET_KEYS, b);
        return b.buildFuture();
    };

    /** set 的 value：当 key=entity 时补全已注册实体类型。 */
    private static final SuggestionProvider<CommandSourceStack> SET_VALUE_SUG = (ctx, b) -> {
        String key;
        try {
            key = ctx.getArgument("key", String.class);
        } catch (IllegalArgumentException ignored) {
            return b.buildFuture();
        }
        if ("entity".equalsIgnoreCase(key.trim())) {
            SharedSuggestionProvider.suggestResource(ForgeRegistries.ENTITY_TYPES.getKeys(), b);
        }
        return b.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> LOG_LEVEL_SUG = (ctx, b) -> {
        SharedSuggestionProvider.suggest(LOG_LEVELS, b);
        return b.buildFuture();
    };

    private SpawnerCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> spawnerNode() {
        return Commands.literal("spawner")
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                // new / del / list / info
                .then(Commands.literal("new")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> create(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("setup")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SPAWNER_NAMES)
                                .executes(ctx -> setup(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("del")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SPAWNER_NAMES)
                                .executes(ctx -> delete(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource(), ""))
                        .then(Commands.argument("pattern", StringArgumentType.greedyString())
                                .suggests(SPAWNER_NAMES)
                                .executes(ctx -> list(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "pattern")))))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SPAWNER_NAMES)
                                .executes(ctx -> info(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                // set / nbt
                .then(Commands.literal("set")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SPAWNER_NAMES)
                                .then(Commands.argument("key", StringArgumentType.string())
                                        .suggests(SET_KEY_SUG)
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .suggests(SET_VALUE_SUG)
                                                .executes(ctx -> set(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "key"),
                                                StringArgumentType.getString(ctx, "value")))))))
                .then(Commands.literal("nbt")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SPAWNER_NAMES)
                                .then(Commands.argument("key", StringArgumentType.string())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> nbt(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "key"),
                                                        StringArgumentType.getString(ctx, "value")))))))
                // point
                .then(Commands.literal("point")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(SPAWNER_NAMES)
                                        .then(Commands.argument("point", StringArgumentType.string())
                                                .executes(ctx -> pointAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "point"), null))
                                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                                        .executes(ctx -> pointAdd(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "name"),
                                                                                StringArgumentType.getString(ctx, "point"),
                                                                                new double[]{
                                                                                        DoubleArgumentType.getDouble(ctx, "x"),
                                                                                        DoubleArgumentType.getDouble(ctx, "y"),
                                                                                        DoubleArgumentType.getDouble(ctx, "z")}))))))))
                        .then(Commands.literal("del")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(SPAWNER_NAMES)
                                        .then(Commands.argument("pattern", StringArgumentType.greedyString())
                                                .suggests(POINTS_OF_NAME)
                                                .executes(ctx -> pointDel(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "pattern"))))))
                        .then(Commands.literal("list")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(SPAWNER_NAMES)
                                        .executes(ctx -> pointList(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), ""))
                                        .then(Commands.argument("pattern", StringArgumentType.greedyString())
                                                .suggests(POINTS_OF_NAME)
                                                .executes(ctx -> pointList(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "pattern"))))))
                        .then(Commands.literal("move")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(SPAWNER_NAMES)
                                        .then(Commands.argument("pattern", StringArgumentType.greedyString())
                                                .suggests(POINTS_OF_NAME)
                                                .then(Commands.argument("offset", StringArgumentType.string())
                                                        .executes(ctx -> pointMove(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "pattern"),
                                                                StringArgumentType.getString(ctx, "offset")))))))
                        .then(Commands.literal("radius")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(SPAWNER_NAMES)
                                        .then(Commands.argument("pattern", StringArgumentType.greedyString())
                                                .suggests(POINTS_OF_NAME)
                                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0))
                                                        .executes(ctx -> pointRadius(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "pattern"),
                                                                DoubleArgumentType.getDouble(ctx, "radius")))))))
                        .then(Commands.literal("enable")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(SPAWNER_NAMES)
                                        .then(Commands.argument("pattern", StringArgumentType.greedyString())
                                                .suggests(POINTS_OF_NAME)
                                                .executes(ctx -> pointEnabled(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "pattern"), true)))))
                        .then(Commands.literal("disable")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(SPAWNER_NAMES)
                                        .then(Commands.argument("pattern", StringArgumentType.greedyString())
                                                .suggests(POINTS_OF_NAME)
                                                .executes(ctx -> pointEnabled(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "pattern"), false))))))
                // group
                .then(Commands.literal("group")
                        .then(Commands.literal("add")
                                .then(Commands.argument("group", StringArgumentType.string())
                                        .then(Commands.argument("pattern", StringArgumentType.greedyString())
                                                .suggests(POINT_NAMES)
                                                .executes(ctx -> groupAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "group"),
                                                        StringArgumentType.getString(ctx, "pattern"))))))
                        .then(Commands.literal("del")
                                .then(Commands.argument("group", StringArgumentType.string())
                                        .suggests(GROUP_NAMES)
                                        .executes(ctx -> groupDel(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "group")))))
                        .then(Commands.literal("list").executes(ctx -> groupList(ctx.getSource()))))
                // run / preview / dry-run / log
                .then(Commands.literal("run")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SPAWNER_NAMES)
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"),
                                        ctx.getSource().getLevel(), false))
                                .then(Commands.argument("world", ResourceLocationArgument.id())
                                        .executes(ctx -> runWorld(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                ResourceLocationArgument.getId(ctx, "world"), false)))))
                .then(Commands.literal("preview")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SPAWNER_NAMES)
                                .executes(ctx -> preview(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("dry-run")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SPAWNER_NAMES)
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"),
                                        ctx.getSource().getLevel(), true))
                                .then(Commands.argument("world", ResourceLocationArgument.id())
                                        .executes(ctx -> runWorld(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                ResourceLocationArgument.getId(ctx, "world"), true)))))
                .then(Commands.literal("log")
                        .then(Commands.argument("level", StringArgumentType.string())
                                .suggests(LOG_LEVEL_SUG)
                                .executes(ctx -> log(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "level")))));
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private static SpawnerWorldStore store(CommandSourceStack source) {
        return SpawnerManager.world(source.getLevel());
    }

    private static int run(CommandSourceStack source, String name, ServerLevel level, boolean dryRun) {
        source.sendSuccess(() -> SpawnerService.run(source, name, level, dryRun), true);
        return 1;
    }

    private static int runWorld(CommandSourceStack source, String name, ResourceLocation worldId, boolean dryRun) {
        ServerLevel level = source.getServer().getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, worldId));
        if (level == null) {
            source.sendFailure(Component.literal("§c未知世界：" + worldId));
            return 0;
        }
        return run(source, name, level, dryRun);
    }

    private static int preview(CommandSourceStack source, String name) {
        source.sendSuccess(() -> SpawnerService.preview(source, name, source.getLevel()), true);
        return 1;
    }

    private static int create(CommandSourceStack source, String name) {
        SpawnerWorldStore st = store(source);
        if (st.has(name)) {
            source.sendFailure(Component.literal("§c刷兵器已存在：" + name));
            return 0;
        }
        st.spawnersMutable().put(name, new Spawner());
        st.saveNow();
        source.sendSuccess(() -> Component.literal("§a已创建刷兵器 §f" + name
                + " §7（用 set 配置实体/数量/点位，用 nbt 配属性）"), true);
        return 1;
    }

    /** setup 引导：创建（若不存在）+ 进度清单，每步给出可直接执行的指令。 */
    private static int setup(CommandSourceStack source, String name) {
        SpawnerWorldStore st = store(source);
        Spawner s = st.get(name);
        StringBuilder sb = new StringBuilder("§e/dn spawner setup " + name + "§r 配置向导：\n");
        boolean fresh = false;
        if (s == null) {
            s = new Spawner();
            st.spawnersMutable().put(name, s);
            st.saveNow();
            fresh = true;
            sb.append("  §b已创建刷兵器 " + name + "§r，开始配置：\n");
        }

        boolean hasEntity = s.entity != null && !s.entity.isBlank()
                || s.entityPool != null && !s.entityPool.isEmpty();
        sb.append("  [1/3] 实体 ");
        if (hasEntity) {
            String show = s.entity != null ? s.entity : "随机池(" + s.entityPool.size() + ")";
            sb.append("§a✓ " + show + "§r\n");
        } else {
            sb.append("§c✗§r 未配置 → §b/dn spawner set " + name
                    + " entity <实体ID>§r（或 entity_pool <JSON>；输入实体开头可用 Tab 补全）\n");
        }

        int pts = st.resolvePointTargets(s).size();
        sb.append("  [2/3] 点位 ");
        if (pts > 0) {
            sb.append("§a✓ " + pts + " 个§r");
            if (s.point != null && !s.point.isBlank()) {
                sb.append("（引用 " + s.point + "）");
            }
            sb.append("\n");
        } else {
            sb.append("§c✗§r 未配置 → 逐个 §b/dn spawner point add " + name
                    + " <点位> [x y z]§r；或用已有点位建组后 §b/dn spawner set " + name
                    + " point group:<组>\n");
        }

        boolean hasCount = s.count != null && !s.count.isBlank();
        sb.append("  [3/3] 数量 ");
        if (hasCount) {
            sb.append("§a✓ " + s.count + "§r");
            if (pts > 0 && !String.valueOf(s.count).toLowerCase(java.util.Locale.ROOT)
                    .contains("per")) {
                sb.append("（count 小于点位时随机抽点，不用写死）");
            }
            sb.append("\n");
        } else {
            sb.append("§c✗§r 未配置（默认 1） → §b/dn spawner set " + name
                    + " count <数量>§r，如 5、3-6、per_point:1\n");
        }

        boolean ready = hasEntity && pts > 0;
        sb.append("  ").append(ready ? "§a全部就绪 → §b/dn spawner run " + name
                + "§r（先 §b/dn spawner preview " + name + "§r 核对）"
                : "§7完成后 /dn spawner run " + name + " 触发刷兵");
        if (fresh) {
            sb.append("  §7（重复本指令可随时查看进度）");
        }
        source.sendSuccess(() -> Component.literal(sb.toString()), true);
        return 1;
    }

    private static int delete(CommandSourceStack source, String name) {
        SpawnerWorldStore st = store(source);
        if (!st.has(name)) {
            source.sendFailure(Component.literal("§c刷兵器不存在：" + name));
            return 0;
        }
        st.spawnersMutable().remove(name);
        st.saveNow();
        source.sendSuccess(() -> Component.literal("§a已删除刷兵器 " + name), true);
        return 1;
    }

    private static int list(CommandSourceStack source, String pattern) {
        SpawnerWorldStore st = store(source);
        StringBuilder sb = new StringBuilder("§6刷兵器列表");
        int n = 0;
        for (Map.Entry<String, Spawner> e : st.spawnersMutable().entrySet()) {
            if (!pattern.isEmpty() && !GlobalConfig.matches(pattern, e.getKey())) {
                continue;
            }
            Spawner s = e.getValue();
            String ent = s.entityPool != null && !s.entityPool.isEmpty()
                    ? s.entityPool.size() + " 池" : (s.entity == null ? "未配置" : s.entity);
            sb.append("\n §7").append(e.getKey())
                    .append(" §f").append(ent)
                    .append(" §7点位").append(s.points.size())
                    .append(" x").append(s.count);
            n++;
        }
        sb.append("\n§7共 ").append(n).append(" 个");
        source.sendSuccess(() -> Component.literal(sb.toString()), true);
        return 1;
    }

    private static int info(CommandSourceStack source, String name) {
        SpawnerWorldStore st = store(source);
        Spawner s = st.get(name);
        if (s == null) {
            source.sendFailure(Component.literal("§c刷兵器不存在：" + name));
            return 0;
        }
        source.sendSuccess(() -> SpawnerService.preview(source, name, source.getLevel()), true);
        return 1;
    }

    private static int set(CommandSourceStack source, String name, String key, String value) {
        SpawnerWorldStore st = store(source);
        Spawner s = st.get(name);
        if (s == null) {
            source.sendFailure(Component.literal("§c刷兵器不存在：" + name));
            return 0;
        }
        String k = key.toLowerCase(java.util.Locale.ROOT);
        switch (k) {
            case "entity" -> {
                if (isClear(value)) {
                    s.entity = null;
                } else {
                    s.entity = value;
                }
            }
            case "entity_pool" -> {
                if (isClear(value)) {
                    s.entityPool = null;
                } else {
                    try {
                        JsonArray arr = JsonParser.parseString(value).getAsJsonArray();
                        List<PoolEntry> pool = new ArrayList<>();
                        for (JsonElement el : arr) {
                            if (el.isJsonObject()) {
                                pool.add(PoolEntry.fromJson(el.getAsJsonObject()));
                            }
                        }
                        if (pool.isEmpty()) {
                            source.sendFailure(Component.literal("§c实体池为空，请提供 [{\"id\":\"minecraft:zombie\",\"weight\":5},...]"));
                            return 0;
                        }
                        s.entityPool = pool;
                    } catch (Exception ex) {
                        source.sendFailure(Component.literal("§c实体池格式非法：" + ex.getMessage()));
                        return 0;
                    }
                }
            }
            case "count" -> s.count = value;
            case "point" -> {
                if (isClear(value)) {
                    s.point = null;
                } else {
                    s.point = value;
                }
            }
            default -> {
                source.sendFailure(Component.literal("§c未知字段 " + key + "，支持：entity / entity_pool / count / point"));
                return 0;
            }
        }
        st.saveNow();
        source.sendSuccess(() -> Component.literal("§a已设置 §f" + name + "§7." + k + " = §f" + value), true);
        return 1;
    }

    private static int nbt(CommandSourceStack source, String name, String key, String value) {
        SpawnerWorldStore st = store(source);
        Spawner s = st.get(name);
        if (s == null) {
            source.sendFailure(Component.literal("§c刷兵器不存在：" + name));
            return 0;
        }
        if (s.nbt == null) {
            s.nbt = new CompoundTag();
        }
        // 便捷键：name → CustomName JSON 文本
        if ("name".equalsIgnoreCase(key)) {
            s.nbt.putString("CustomName", "{\"text\":\"" + value.replace("\"", "\\\"") + "\"}");
            s.nbt.putBoolean("CustomNameVisible", true);
        } else if (isClear(value)) {
            s.nbt.remove(key);
        } else {
            s.nbt.put(key, parseTagValue(value, key));
        }
        st.saveNow();
        source.sendSuccess(() -> Component.literal("§a已设置 §f" + name + " §7NBT[" + key + "] = §f" + value), true);
        return 1;
    }

    private static Tag parseTagValue(String raw, String key) {
        String v = raw.trim();
        // 复合/列表形式走 SNBT
        if (v.startsWith("{") || v.startsWith("[")) {
            try {
                return TagParser.parseTag(v);
            } catch (Exception ignored) {
                // 回退纯文本
            }
        }
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return ByteTag.valueOf(Boolean.parseBoolean(v));
        }
        if (v.matches("-?\\d+")) {
            try {
                return IntTag.valueOf(Integer.parseInt(v));
            } catch (NumberFormatException ignored) {
            }
        }
        return StringTag.valueOf(raw);
    }

    private static boolean isClear(String v) {
        return v == null || v.isBlank() || "-".equals(v.trim())
                || "none".equalsIgnoreCase(v.trim()) || "null".equalsIgnoreCase(v.trim());
    }

    // ------------------------------------------------------------------
    // point
    // ------------------------------------------------------------------

    private static int pointAdd(CommandSourceStack source, String name, String ptn, double[] xyz) {
        SpawnerWorldStore st = store(source);
        Spawner s = st.get(name);
        if (s == null) {
            source.sendFailure(Component.literal("§c刷兵器不存在：" + name));
            return 0;
        }
        SpawnerPoint p = new SpawnerPoint();
        if (xyz != null) {
            p.x = xyz[0];
            p.y = xyz[1];
            p.z = xyz[2];
        } else {
            p.x = source.getPosition().x();
            p.y = source.getPosition().y();
            p.z = source.getPosition().z();
        }
        s.points.put(ptn, p);
        st.saveNow();
        source.sendSuccess(() -> Component.literal("§a已添加点位 §f" + ptn
                + " §7(" + (int) p.x + ", " + (int) p.y + ", " + (int) p.z + ")"), true);
        return 1;
    }

    private static int pointDel(CommandSourceStack source, String name, String pattern) {
        SpawnerWorldStore st = store(source);
        Spawner s = st.get(name);
        if (s == null) {
            source.sendFailure(Component.literal("§c刷兵器不存在：" + name));
            return 0;
        }
        List<String> removed = new ArrayList<>();
        for (String ptn : new ArrayList<>(s.points.keySet())) {
            if (GlobalConfig.matches(pattern, ptn)) {
                s.points.remove(ptn);
                removed.add(ptn);
            }
        }
        st.saveNow();
        source.sendSuccess(() -> Component.literal("§a已删除点位 " + removed.size() + " 个：" + removed), true);
        return 1;
    }

    private static int pointList(CommandSourceStack source, String name, String pattern) {
        SpawnerWorldStore st = store(source);
        Spawner s = st.get(name);
        if (s == null) {
            source.sendFailure(Component.literal("§c刷兵器不存在：" + name));
            return 0;
        }
        StringBuilder sb = new StringBuilder("§6点位 " + name);
        int n = 0;
        for (Map.Entry<String, SpawnerPoint> e : s.points.entrySet()) {
            if (!pattern.isEmpty() && !GlobalConfig.matches(pattern, e.getKey())) {
                continue;
            }
            SpawnerPoint p = e.getValue();
            sb.append("\n §7").append(e.getKey())
                    .append(" §f").append((int) p.x).append(", ").append((int) p.y).append(", ").append((int) p.z)
                    .append(" §7r").append(p.radius)
                    .append(p.enabled ? "" : " §c[禁用]");
            n++;
        }
        sb.append("\n§7共 ").append(n).append(" 个");
        source.sendSuccess(() -> Component.literal(sb.toString()), true);
        return 1;
    }

    private static int pointMove(CommandSourceStack source, String name, String pattern, String offsetStr) {
        SpawnerWorldStore st = store(source);
        Spawner s = st.get(name);
        if (s == null) {
            source.sendFailure(Component.literal("§c刷兵器不存在：" + name));
            return 0;
        }
        double[] off = parseOffset(offsetStr);
        if (off == null) {
            source.sendFailure(Component.literal("§c位移格式应为 x,y,z（逗号分隔）"));
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, SpawnerPoint> e : s.points.entrySet()) {
            if (GlobalConfig.matches(pattern, e.getKey())) {
                SpawnerPoint p = e.getValue();
                p.x += off[0];
                p.y += off[1];
                p.z += off[2];
                n++;
            }
        }
        st.saveNow();
        int N = n;
        source.sendSuccess(() -> Component.literal("§a位移 " + N + " 个点位 (" + offsetStr + ")"), true);
        return 1;
    }

    private static int pointRadius(CommandSourceStack source, String name, String pattern, double radius) {
        SpawnerWorldStore st = store(source);
        Spawner s = st.get(name);
        if (s == null) {
            source.sendFailure(Component.literal("§c刷兵器不存在：" + name));
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, SpawnerPoint> e : s.points.entrySet()) {
            if (GlobalConfig.matches(pattern, e.getKey())) {
                e.getValue().radius = radius;
                n++;
            }
        }
        st.saveNow();
        int R = n;
        source.sendSuccess(() -> Component.literal("§a已设置 " + R + " 个点位半径为 " + radius), true);
        return 1;
    }

    private static int pointEnabled(CommandSourceStack source, String name, String pattern, boolean enabled) {
        SpawnerWorldStore st = store(source);
        Spawner s = st.get(name);
        if (s == null) {
            source.sendFailure(Component.literal("§c刷兵器不存在：" + name));
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, SpawnerPoint> e : s.points.entrySet()) {
            if (GlobalConfig.matches(pattern, e.getKey())) {
                e.getValue().enabled = enabled;
                n++;
            }
        }
        st.saveNow();
        int E = n;
        source.sendSuccess(() -> Component.literal("§a已" + (enabled ? "启用" : "禁用") + " " + E + " 个点位"), true);
        return 1;
    }

    private static double[] parseOffset(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // group
    // ------------------------------------------------------------------

    private static int groupAdd(CommandSourceStack source, String group, String pattern) {
        SpawnerWorldStore st = store(source);
        List<String> matched = st.collectPointNames(pattern);
        if (matched.isEmpty()) {
            source.sendFailure(Component.literal("§c没有任何点位匹配 " + pattern));
            return 0;
        }
        List<String> g = st.groups().computeIfAbsent(group, k -> new ArrayList<>());
        for (String m : matched) {
            if (!g.contains(m)) {
                g.add(m);
            }
        }
        st.saveNow();
        source.sendSuccess(() -> Component.literal("§a组 §f" + group + "§a 归入 " + matched.size() + " 个点位：" + matched), true);
        return 1;
    }

    private static int groupDel(CommandSourceStack source, String group) {
        SpawnerWorldStore st = store(source);
        if (st.groups().remove(group) == null) {
            source.sendFailure(Component.literal("§c组不存在：" + group));
            return 0;
        }
        st.saveNow();
        source.sendSuccess(() -> Component.literal("§a已删除组 " + group), true);
        return 1;
    }

    private static int groupList(CommandSourceStack source) {
        SpawnerWorldStore st = store(source);
        StringBuilder sb = new StringBuilder("§6点位组");
        for (Map.Entry<String, List<String>> e : st.groups().entrySet()) {
            sb.append("\n §7").append(e.getKey()).append(" §f").append(e.getValue().size()).append(" 个");
        }
        if (st.groups().isEmpty()) {
            sb.append(" §7（空）");
        }
        source.sendSuccess(() -> Component.literal(sb.toString()), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // log
    // ------------------------------------------------------------------

    private static int log(CommandSourceStack source, String level) {
        String lvl = level.trim().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.List.of(LOG_LEVELS).contains(lvl)) {
            source.sendFailure(Component.literal("§c日志级别应为 " + String.join(" / ", LOG_LEVELS)));
            return 0;
        }
        LogConfig.get().level = lvl;
        LogConfig.get().saveNow();
        source.sendSuccess(() -> Component.literal("§a刷兵日志级别已设为 " + lvl), true);
        return 1;
    }

    private static int help(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder();
        sb.append("§e/dn spawner§r 刷兵系统（零活动：仅在执行请求时刷一次，不监听事件/无冷却/无定时/无波次）：\n");

        sb.append("  §a/dn spawner new <名字>§r 新建刷兵器\n");
        sb.append("  §a/dn spawner setup <名字>§r 配置向导：自动建+进度清单，缺哪步给哪条指令\n");
        sb.append("  §a/dn spawner del <名字>§r 删除刷兵器\n");
        sb.append("  §a/dn spawner list [pattern]§r 列出刷兵器（pattern 支持 * 和 ? 通配）\n");
        sb.append("  §a/dn spawner info <名字>§r 查看刷兵器详情\n");

        sb.append("  §a/dn spawner set <名字> <key> <值>§r 设置字段：\n");
        sb.append("    §7entity <实体ID>§r 单一实体，如 minecraft:zombie（- 清除）\n");
        sb.append("    §7entity_pool <JSON>§r 实体池，如 [{\"id\":\"minecraft:zombie\",\"weight\":5},...]（- 清除）\n");
        sb.append("    §7count <count>§r 数量，如 5、5-10，或 per_point:1-3（每点位区间）\n");
        sb.append("    §7point <名字>§r 挂载点位名称（- 清除）\n");

        sb.append("  §a/dn spawner nbt <名字> <键> <值>§r 设置实体 NBT：\n");
        sb.append("    §7name <文本>§r 便捷设为 CustomName（自动显示）\n");
        sb.append("    §7其它键§r 支持 SNBT，如 {NoAI:1b}、[{...}]；true/false→布尔、整数→int\n");

        sb.append("  §a/dn spawner point add <名字> <点位> [x y z]§r 添加点位（缺省坐标为当前站/所在，含视角精确点）\n");
        sb.append("  §a/dn spawner point del <名字> <pattern>§r 删除点位（按通配）\n");
        sb.append("  §a/dn spawner point list <名字> [pattern]§r 列出点位\n");
        sb.append("  §a/dn spawner point move <名字> <pattern> <x,y,z>§r 位移点位\n");
        sb.append("  §a/dn spawner point radius <名字> <pattern> <半径>§r 设置点位随机半径（≥0）\n");
        sb.append("  §a/dn spawner point enable|disable <名字> <pattern>§r 启用/禁用点位\n");

        sb.append("  §a/dn spawner group add <组> <pattern>§r 把匹配点位归入组\n");
        sb.append("  §a/dn spawner group del <组>§r 删除组\n");
        sb.append("  §a/dn spawner group list§r 列出组\n");

        sb.append("  §a/dn spawner run <名字> [世界]§r 执行刷兵一次\n");
        sb.append("  §a/dn spawner preview <名字>§r 只做静态推算（不改世界）\n");
        sb.append("  §a/dn spawner dry-run <名字> [世界]§r 干跑：完整流程但不生成实体\n");
        sb.append("  §a/dn spawner log <off|error|warn|info|debug>§r 设置日志级别\n");
        sb.append("  §7注：configuration 修改后输入 /dn reload 热加载生效");
        source.sendSuccess(() -> Component.literal(sb.toString()), true);
        return 1;
    }
}