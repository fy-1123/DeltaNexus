package com.deltanexus.system.server;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.common.WorkbenchRegistry;
import com.deltanexus.system.config.ModConfig;
import com.deltanexus.system.config.Recipe;
import com.deltanexus.system.config.RecipeCache;
import com.deltanexus.system.config.UpgradeConfig;
import com.deltanexus.system.menu.WarehouseMenu;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.OpenScreenPacket;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /dn 指令树（配置修改全部指令化，任务一；参数动态补全，任务五）。
 *
 * <p>子指令：open / reload / export / setting / warehouse / info /
 * workbench / recipe / tree / data / web / help。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID)
public final class CommandDN {

    /** 工作台 id 动态补全（任务五）。 */
    private static final SuggestionProvider<CommandSourceStack> WORKBENCH_IDS = (ctx, builder) -> {
        for (WorkbenchRegistry.Workbench wb : WorkbenchRegistry.get().all()) {
            builder.suggest(wb.id, Component.literal(wb.display));
        }
        return builder.buildFuture();
    };

    /** 配方 id 动态补全（全部配方）。 */
    private static final SuggestionProvider<CommandSourceStack> RECIPE_IDS = (ctx, builder) -> {
        for (Recipe r : RecipeCache.get().all()) {
            builder.suggest(r.recipeId);
        }
        return builder.buildFuture();
    };

    /** 升级树等级动态补全（已有等级）。 */
    private static final SuggestionProvider<CommandSourceStack> TREE_LEVELS = (ctx, builder) -> {
        for (UpgradeConfig.UpgradeLevel u : UpgradeConfig.get().all()) {
            builder.suggest(u.level);
        }
        return builder.buildFuture();
    };

    /** 安全箱升级树等级动态补全（已有等级，1.1.0Alpha）。 */
    private static final SuggestionProvider<CommandSourceStack> SAFE_TREE_LEVELS = (ctx, builder) -> {
        for (UpgradeConfig.UpgradeLevel u : UpgradeConfig.get().safeAll()) {
            builder.suggest(u.level);
        }
        return builder.buildFuture();
    };

    /** 权限类型补全（warehouse/workbench/special/safe_box/trade/all，1.1.0Alpha / 2.0.3 Alpha/ 2.1Alpha / 0.2.0Beta）。 */
    private static final SuggestionProvider<CommandSourceStack> PERM_TYPES = (ctx, builder) -> {
        builder.suggest("warehouse", Component.literal("仓库"));
        builder.suggest("workbench", Component.literal("配方工作台"));
        builder.suggest("special", Component.literal("特勤处"));
        builder.suggest("safe_box", Component.literal("安全箱"));
        builder.suggest("trade", Component.literal("交易行"));
        builder.suggest("all", Component.literal("全部"));
        return builder.buildFuture();
    };

    /** 权限取值补全（allow/deny，1.1.0）。 */
    private static final SuggestionProvider<CommandSourceStack> PERM_VALUES = (ctx, builder) -> {
        builder.suggest("allow", Component.literal("允许"));
        builder.suggest("deny", Component.literal("拒绝"));
        return builder.buildFuture();
    };

    /** NBT 匹配模式补全（安全箱限制，1.1.0Alpha）。 */
    private static final SuggestionProvider<CommandSourceStack> NBT_MATCH_TYPES = (ctx, builder) -> {
        builder.suggest("contains", Component.literal("包含匹配，默认"));
        builder.suggest("exact", Component.literal("完全匹配"));
        return builder.buildFuture();
    };

    /** 在线玩家名补全（权限/玩家数据指令，2.0.7Alpha）。 */
    private static final SuggestionProvider<CommandSourceStack> PLAYER_NAMES = (ctx, builder) -> {
        var server = ctx.getSource().getServer();
        if (server != null) {
            for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                builder.suggest(p.getGameProfile().getName());
            }
        }
        return builder.buildFuture();
    };

    /** 注册物品 ID 补全（minecraft:xxx / 模组:xxx，2.0.7Alpha）。 */
    private static final SuggestionProvider<CommandSourceStack> ITEM_IDS = (ctx, builder) ->
            net.minecraft.commands.SharedSuggestionProvider.suggestResource(
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKeys(), builder);

    /** 物品「类」名补全（已存在的类，2.0.7Alpha）。 */
    private static final SuggestionProvider<CommandSourceStack> CLASS_NAMES = (ctx, builder) -> {
        for (String name : com.deltanexus.system.grid.GridClassConfig.allClasses().keySet()) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    };

    /** 已注册模组容器类名补全（0.3.0Beta）。 */
    private static final SuggestionProvider<CommandSourceStack> CONTAINER_NAMES = (ctx, builder) -> {
        for (String name : com.deltanexus.system.grid.GridRegistry.registeredNames()) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    };

    /** 列表索引补全（0 ~ 9，配方/升级材料索引等，2.0.7Alpha）。 */
    private static final SuggestionProvider<CommandSourceStack> LIST_INDEX = (ctx, builder) -> {
        for (int i = 0; i < 10; i++) {
            builder.suggest(i);
        }
        return builder.buildFuture();
    };

    /** 安全箱限制物品补全（any 或物品 ID，2.0.7Alpha）。 */
    private static final SuggestionProvider<CommandSourceStack> RESTRICT_ITEM = (ctx, builder) -> {
        builder.suggest("any", Component.literal("任意物品"));
        net.minecraft.commands.SharedSuggestionProvider.suggestResource(
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKeys(), builder);
        return builder.buildFuture();
    };

    private CommandDN() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("dn")
                // open：打开界面
                .then(Commands.literal("open")
                        .then(Commands.literal("help").executes(ctx -> helpOpen(ctx.getSource())))
                        .then(Commands.literal("warehouse")
                                .executes(ctx -> openWarehouse(ctx.getSource())))
                        .then(Commands.literal("special")
                                .executes(ctx -> openSpecialOps(ctx.getSource())))
                        .then(Commands.literal("manufacture")
                                .executes(ctx -> openManufacture(ctx.getSource())))
                        .then(Commands.literal("trade")
                                .executes(ctx -> openTrade(ctx.getSource()))))
                // reload
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(4))
                        .executes(ctx -> reload(ctx.getSource()))
                        .then(Commands.literal("help").executes(ctx -> helpReload(ctx.getSource()))))
                // export
                .then(Commands.literal("export")
                        .requires(s -> s.hasPermission(4))
                        .executes(ctx -> export(ctx.getSource()))
                        .then(Commands.literal("help").executes(ctx -> helpExport(ctx.getSource()))))
                // setting（全局设置：原 speed / queue / currency / mode 顶层指令迁入）
                .then(Commands.literal("setting")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("help").executes(ctx -> helpSetting(ctx.getSource())))
                        .then(Commands.literal("speed")
                                .then(Commands.argument("multiplier", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.01D, 100.0D))
                                        .executes(ctx -> speed(ctx.getSource(),
                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "multiplier")))))
                        .then(Commands.literal("queue")
                                .then(Commands.argument("size", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> queue(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "size")))))
                        .then(Commands.literal("mode")
                                .then(Commands.literal("offline")
                                        .executes(ctx -> mode(ctx.getSource(), "offline")))
                                .then(Commands.literal("online")
                                        .executes(ctx -> mode(ctx.getSource(), "online"))))
                        .then(Commands.literal("currency")
                                .then(Commands.literal("scoreboard")
                                        .then(Commands.argument("objective", StringArgumentType.word())
                                                .executes(ctx -> currencyScoreboard(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "objective")))))
                                .then(Commands.literal("vault")
                                        .executes(ctx -> currencyVault(ctx.getSource())))
                                .then(Commands.literal("playerpoints")
                                        .executes(ctx -> currencyPlayerPoints(ctx.getSource())))))
                // warehouse
                .then(Commands.literal("warehouse")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("help").executes(ctx -> helpWarehouse(ctx.getSource())))
                        .then(Commands.literal("rows")
                                .then(Commands.argument("rows", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> whRows(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "rows")))))
                        .then(Commands.literal("slots")
                                .then(Commands.argument("base_slots", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 576))
                                        .executes(ctx -> whSlots(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "base_slots"))))))
                // info
                .then(Commands.literal("info")
                        .requires(s -> s.hasPermission(4))
                        .executes(ctx -> info(ctx.getSource()))
                        .then(Commands.literal("help").executes(ctx -> helpInfo(ctx.getSource()))))
                // workbench
                .then(Commands.literal("workbench")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("help").executes(ctx -> helpWorkbench(ctx.getSource())))
                        .then(Commands.literal("list")
                                .executes(ctx -> workbenchList(ctx.getSource())))
                        .then(Commands.literal("add")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("display", StringArgumentType.greedyString())
                                                .executes(ctx -> workbenchAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "display"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(WORKBENCH_IDS)
                                        .executes(ctx -> workbenchRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("rename")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(WORKBENCH_IDS)
                                        .then(Commands.argument("display", StringArgumentType.greedyString())
                                                .executes(ctx -> workbenchRename(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "display")))))))
                // recipe（含 time / level 配置指令）
                .then(Commands.literal("recipe")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("help").executes(ctx -> helpRecipe(ctx.getSource())))
                        .then(Commands.literal("list")
                                .then(Commands.argument("workbench", StringArgumentType.word())
                                        .suggests(WORKBENCH_IDS)
                                        .executes(ctx -> recipeList(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "workbench")))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .executes(ctx -> recipeGet(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("workbench", StringArgumentType.word())
                                        .suggests(WORKBENCH_IDS)
                                        .then(Commands.argument("recipe_id", StringArgumentType.word())
                                                .executes(ctx -> recipeAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "workbench"),
                                                        StringArgumentType.getString(ctx, "recipe_id"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .executes(ctx -> recipeRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("rename")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .then(Commands.argument("display", StringArgumentType.greedyString())
                                                .executes(ctx -> recipeRename(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "display"))))))
                        .then(Commands.literal("addinput")
                                .then(Commands.argument("recipe_id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .executes(ctx -> recipeAddInput(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "recipe_id")))))
                        .then(Commands.literal("addoutput")
                                .then(Commands.argument("recipe_id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .executes(ctx -> recipeAddOutput(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "recipe_id")))))
                        .then(Commands.literal("delinput")
                                .then(Commands.argument("recipe_id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).suggests(LIST_INDEX)
                                                .executes(ctx -> recipeDelInput(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "recipe_id"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"))))))
                        .then(Commands.literal("deloutput")
                                .then(Commands.argument("recipe_id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).suggests(LIST_INDEX)
                                                .executes(ctx -> recipeDelOutput(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "recipe_id"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"))))))
                        .then(Commands.literal("setinput")
                                .then(Commands.argument("recipe_id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).suggests(LIST_INDEX)
                                                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                        .executes(ctx -> recipeSetInput(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "recipe_id"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("setoutput")
                                .then(Commands.argument("recipe_id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).suggests(LIST_INDEX)
                                                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                        .executes(ctx -> recipeSetOutput(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "recipe_id"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("time")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                .executes(ctx -> recipeTime(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "seconds"))))))
                        .then(Commands.literal("level")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                                .executes(ctx -> recipeLevel(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"))))))
                        .then(Commands.literal("parallel")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(RECIPE_IDS)
                                        .then(Commands.argument("limit", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100))
                                                .executes(ctx -> recipeParallel(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "limit"))))))
                        .then(Commands.literal("reload")
                                .executes(ctx -> reload(ctx.getSource()))))
                // tree（level 参数补全已有等级）
                .then(Commands.literal("tree")
                        .requires(s -> s.hasPermission(4))
                        .then(Commands.literal("help").executes(ctx -> helpTree(ctx.getSource())))
                        .then(Commands.literal("get")
                                .executes(ctx -> treeGet(ctx.getSource())))
                        .then(Commands.literal("add")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .executes(ctx -> treeAdd(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level")))))
                        .then(Commands.literal("cost")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(TREE_LEVELS)
                                        .then(Commands.argument("cost_money", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                                .executes(ctx -> treeCost(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cost_money"))))))
                        .then(Commands.literal("slots")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(TREE_LEVELS)
                                        .then(Commands.argument("unlock_slots", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                .executes(ctx -> treeSlots(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "unlock_slots"))))))
                        .then(Commands.literal("additem")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(TREE_LEVELS)
                                        .executes(ctx -> treeAddItem(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level")))))
                        .then(Commands.literal("delitem")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(TREE_LEVELS)
                                        .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).suggests(LIST_INDEX)
                                                .executes(ctx -> treeDelItem(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"))))))
                        .then(Commands.literal("setitem")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(TREE_LEVELS)
                                        .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).suggests(LIST_INDEX)
                                                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                        .executes(ctx -> treeSetItem(ctx.getSource(),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(TREE_LEVELS)
                                        .executes(ctx -> treeRemove(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"))))))
                // data（玩家仓库/安全箱数据管理，玩家名 Tab 补全在线玩家）
                .then(Commands.literal("data")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("help").executes(ctx -> helpData(ctx.getSource())))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.literal("get")
                                        .executes(ctx -> dataGet(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"))))
                                .then(Commands.literal("level")
                                        .then(Commands.argument("level", IntegerArgumentType.integer(0))
                                                .executes(ctx -> dataLevel(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "level")))))
                                .then(Commands.literal("slots")
                                        .then(Commands.argument("slots", IntegerArgumentType.integer(0))
                                                .executes(ctx -> dataSlots(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "slots")))))
                                .then(Commands.literal("safe")
                                        .then(Commands.argument("level", IntegerArgumentType.integer(0))
                                                .executes(ctx -> dataSafe(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "level")))))
                                .then(Commands.literal("reset")
                                        .executes(ctx -> dataReset(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"))))))
                // safe（安全箱：默认尺寸 + 升级树管理，1.1.0Alpha）
                .then(Commands.literal("safe")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("help").executes(ctx -> helpSafe(ctx.getSource())))
                        .then(Commands.literal("get")
                                .executes(ctx -> safeGet(ctx.getSource())))
                        .then(Commands.literal("size")
                                .then(Commands.argument("width", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3))
                                        .then(Commands.argument("height", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3))
                                                .executes(ctx -> safeSize(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "width"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "height"))))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .executes(ctx -> safeAdd(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level")))))
                        .then(Commands.literal("cost")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(SAFE_TREE_LEVELS)
                                        .then(Commands.argument("cost_money", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                                .executes(ctx -> safeCost(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cost_money"))))))
                        .then(Commands.literal("rows")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(SAFE_TREE_LEVELS)
                                        .then(Commands.argument("unlock_rows", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3))
                                                .then(Commands.argument("unlock_cols", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3))
                                                        .executes(ctx -> safeRows(ctx.getSource(),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "unlock_rows"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "unlock_cols")))))))
                        .then(Commands.literal("additem")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(SAFE_TREE_LEVELS)
                                        .executes(ctx -> safeAddItem(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level")))))
                        .then(Commands.literal("delitem")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(SAFE_TREE_LEVELS)
                                        .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).suggests(LIST_INDEX)
                                                .executes(ctx -> safeDelItem(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"))))))
                        .then(Commands.literal("setitem")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(SAFE_TREE_LEVELS)
                                        .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).suggests(LIST_INDEX)
                                                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                        .executes(ctx -> safeSetItem(ctx.getSource(),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .suggests(SAFE_TREE_LEVELS)
                                        .executes(ctx -> safeRemove(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level")))))
                        .then(Commands.literal("restrict")
                                .then(Commands.argument("item", StringArgumentType.word()).suggests(RESTRICT_ITEM)
                                        .then(Commands.argument("match_type", StringArgumentType.word())
                                                .suggests(NBT_MATCH_TYPES)
                                                .then(Commands.argument("nbt", StringArgumentType.greedyString())
                                                        .executes(ctx -> safeRestrict(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "item"),
                                                                StringArgumentType.getString(ctx, "match_type"),
                                                                StringArgumentType.getString(ctx, "nbt")))))))
                        .then(Commands.literal("restrictions")
                                .executes(ctx -> safeRestrictions(ctx.getSource())))
                        .then(Commands.literal("unrestrict")
                                .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0)).suggests(LIST_INDEX)
                                        .executes(ctx -> safeUnrestrict(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index"))))))
                // perm（权限管理，1.1.0Alpha：控制玩家能否打开仓库/工作台/特勤处/安全箱）
                .then(Commands.literal("perm")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("help").executes(ctx -> helpPerm(ctx.getSource())))
                        .then(Commands.literal("get")
                                .executes(ctx -> permGet(ctx.getSource()))
                                .then(Commands.argument("target", EntityArgument.entities())
                                        .executes(ctx -> permGetOne(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "target")))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("target", EntityArgument.entities())
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests(PERM_TYPES)
                                                .then(Commands.argument("allow", StringArgumentType.word())
                                                        .suggests(PERM_VALUES)
                                                        .executes(ctx -> permSet(ctx.getSource(),
                                                                EntityArgument.getEntities(ctx, "target"),
                                                                StringArgumentType.getString(ctx, "type"),
                                                                StringArgumentType.getString(ctx, "allow")))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("target", EntityArgument.entities())
                                        .executes(ctx -> permRemove(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "target")))))
                        .then(Commands.literal("default")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests(PERM_TYPES)
                                        .then(Commands.argument("allow", StringArgumentType.word())
                                                .suggests(PERM_VALUES)
                                                .executes(ctx -> permDefault(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        StringArgumentType.getString(ctx, "allow"))))))
                        .then(Commands.literal("op")
                                .then(Commands.argument("allow", StringArgumentType.word())
                                        .suggests(PERM_VALUES)
                                        .executes(ctx -> permOp(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "allow")))))
                        .then(Commands.literal("getop")
                                .executes(ctx -> permGetOp(ctx.getSource()))))
                // feature（2.1Alpha：禁用玩家全部 mod 功能，UI 恢复原版）
                .then(Commands.literal("feature")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("help").executes(ctx -> helpFeature(ctx.getSource())))
                        .then(Commands.literal("get")
                                .executes(ctx -> featureGet(ctx.getSource()))
                                .then(Commands.argument("target", EntityArgument.entities())
                                        .executes(ctx -> featureGetOne(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "target")))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("target", EntityArgument.entities())
                                        .then(Commands.argument("allow", StringArgumentType.word())
                                                .suggests(PERM_VALUES)
                                                .executes(ctx -> featureSet(ctx.getSource(),
                                                        EntityArgument.getEntities(ctx, "target"),
                                                        StringArgumentType.getString(ctx, "allow")))))))
                // grid（格式背包配置：物品尺寸 + 快捷栏规则 + 物品类，2.0.2Alpha / 2.0.3Alpha）
                .then(Commands.literal("grid")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("help").executes(ctx -> helpGrid(ctx.getSource())))
                        .then(Commands.literal("list")
                                .executes(ctx -> gridList(ctx.getSource())))
                        .then(Commands.literal("size")
                                .then(Commands.argument("w", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 9))
                                        .then(Commands.argument("h", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 9))
                                                .executes(ctx -> gridSize(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "w"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "h"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("item_id", net.minecraft.commands.arguments.ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                        .executes(ctx -> gridRemove(ctx.getSource(),
                                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item_id").toString()))))
                        .then(Commands.literal("hotbar")
                                .then(Commands.argument("rules", StringArgumentType.greedyString())
                                        .executes(ctx -> gridHotbar(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "rules")))))
                        // 0.3.0Beta：容器显式注册（未注册的模组容器不再默认启用网格）
                        .then(Commands.literal("containers")
                                .executes(ctx -> gridContainers(ctx.getSource())))
                        .then(Commands.literal("register")
                                .then(Commands.argument("class_name", StringArgumentType.greedyString())
                                        .executes(ctx -> gridRegister(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "class_name")))))
                        .then(Commands.literal("unregister")
                                .then(Commands.argument("class_name", StringArgumentType.greedyString())
                                        .suggests(CONTAINER_NAMES)
                                        .executes(ctx -> gridUnregister(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "class_name")))))
                        // 2.0.6Alpha：类命令顶层别名（/dn grid setclass 与 /dn grid class setclass 等价）
                        .then(Commands.literal("setclass")
                                .then(Commands.argument("item_id", net.minecraft.commands.arguments.ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                        .then(Commands.argument("name", StringArgumentType.word()).suggests(CLASS_NAMES)
                                                .executes(ctx -> gridSetClass(ctx.getSource(),
                                                        net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item_id").toString(),
                                                        StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("unsetclass")
                                .then(Commands.argument("item_id", net.minecraft.commands.arguments.ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                        .executes(ctx -> gridUnsetClass(ctx.getSource(),
                                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item_id").toString()))))
                        .then(Commands.literal("class")
                                .then(Commands.literal("list")
                                        .executes(ctx -> gridClassList(ctx.getSource())))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("name", StringArgumentType.word()).suggests(CLASS_NAMES)
                                                .then(Commands.argument("r", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 255))
                                                        .then(Commands.argument("g", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 255))
                                                                .then(Commands.argument("b", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 255))
                                                                        .executes(ctx -> gridClassSet(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "name"),
                                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "r"),
                                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "g"),
                                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "b"))))))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("name", StringArgumentType.word()).suggests(CLASS_NAMES)
                                                .executes(ctx -> gridClassRemove(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name")))))
                                .then(Commands.literal("setclass")
                                        .then(Commands.argument("item_id", net.minecraft.commands.arguments.ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                                .then(Commands.argument("name", StringArgumentType.word()).suggests(CLASS_NAMES)
                                                        .executes(ctx -> gridSetClass(ctx.getSource(),
                                                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item_id").toString(),
                                                                StringArgumentType.getString(ctx, "name"))))))
                                .then(Commands.literal("unsetclass")
                                        .then(Commands.argument("item_id", net.minecraft.commands.arguments.ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                                .executes(ctx -> gridUnsetClass(ctx.getSource(),
                                                        net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item_id").toString()))))))
                // class（2.0.7Alpha：物品「类」配置顶层指令，与 /dn grid class 等价）
                .then(Commands.literal("class")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("help").executes(ctx -> helpClass(ctx.getSource())))
                        .then(Commands.literal("list")
                                .executes(ctx -> gridClassList(ctx.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(CLASS_NAMES)
                                        .then(Commands.argument("r", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 255))
                                                .then(Commands.argument("g", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 255))
                                                        .then(Commands.argument("b", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 255))
                                                                .executes(ctx -> gridClassSet(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "name"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "r"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "g"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "b"))))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(CLASS_NAMES)
                                        .executes(ctx -> gridClassRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("setclass")
                                .then(Commands.argument("item_id", net.minecraft.commands.arguments.ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                        .then(Commands.argument("name", StringArgumentType.word()).suggests(CLASS_NAMES)
                                                .executes(ctx -> gridSetClass(ctx.getSource(),
                                                        net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item_id").toString(),
                                                        StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("unsetclass")
                                .then(Commands.argument("item_id", net.minecraft.commands.arguments.ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                        .executes(ctx -> gridUnsetClass(ctx.getSource(),
                                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item_id").toString())))))
                // web（网页编辑器，安全配置仅可改文件；on 每次开启刷新令牌）
                .then(Commands.literal("web")
                        .requires(s -> s.hasPermission(4))
                        .executes(ctx -> webStatus(ctx.getSource()))
                        .then(Commands.literal("on").executes(ctx -> webOn(ctx.getSource())))
                        .then(Commands.literal("off").executes(ctx -> webOff(ctx.getSource())))
                        .then(Commands.literal("help").executes(ctx -> helpWeb(ctx.getSource()))))
                // trade（0.2.0Beta：交易行管理：分类/商品/价格/上下限/补货/外部源）
                .then(TradeAdminHandler.tradeNode().requires(s -> s.hasPermission(2)))
                // spawner（0.4.0Beta：刷兵系统：实体+点位+规则，仅这一级子指令）
                .then(com.deltanexus.system.spawner.SpawnerCommand.spawnerNode()
                        .requires(s -> s.hasPermission(2)))
                // help（仅总览，各指令详细帮助用 /dn <指令> help）
                .then(Commands.literal("help")
                        .executes(ctx -> help(ctx.getSource()))));
    }

    private static int openWarehouse(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ManufacturingService.openWarehouse(player);
        return 1;
    }

    /** 打开特勤处（2.0.2Alpha：仓库/安全箱升级独立界面）。 */
    private static int openSpecialOps(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ManufacturingService.openSpecialOps(player);
        return 1;
    }

    private static int openManufacture(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        // 直接打开工作台总览（与 G 键一致，无单独工作台界面参数）
        ManufacturingService.openWorkbenchOverview(player);
        return 1;
    }

    /** 打开交易行（0.2.0Beta）：服务端权限校验 + 目录下发 + OpenScreen。 */
    private static int openTrade(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        TradeService.open(player);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        RecipeCache.get().reload();
        UpgradeConfig.get().reload();
        WorkbenchRegistry.get().reload();
        PermissionManager.reload();
        com.deltanexus.system.config.SafeBoxRestrictions.reload();
        com.deltanexus.system.trade.TradeConfig.get().reload();
        com.deltanexus.system.trade.TradeStockStore.load();
        // 0.4.0Beta：刷兵系统配置热加载（全球层 + 已加载世界层）
        com.deltanexus.system.spawner.SpawnerManager.reloadAll(source.getServer());
        source.sendSuccess(() -> Component.translatable("msg.dn.reload.done",
                RecipeCache.get().size(), UpgradeConfig.get().maxLevel(), WorkbenchRegistry.get().size()), true);
        // 交易行目录可能因配置/源刷新变化：向在线玩家重推
        TradeService.sendSyncToAll(source.getServer());
        // 检测机制：外部修改升级树后重载，解锁容量超过当前行数容量时自动扩容并同步在线玩家
        if (ManufacturingService.autoExpandRows()) {
            ManufacturingService.applyRowsToOnline(source.getServer());
            source.sendSuccess(() -> Component.translatable("msg.dn.tree.rows_expanded",
                    UpgradeConfig.get().maxUnlockSlots(), ModConfig.warehouseRows(),
                    ModConfig.warehouseRows() * WarehouseMenu.WAREHOUSE_COLS), true);
        }
        return 1;
    }

    private static int export(CommandSourceStack source) {
        RecipeCache.get().exportBackup();
        source.sendSuccess(() -> Component.translatable("msg.dn.export.done"), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // 配置专用子命令（任务二：替代 /dn config，按功能分组、热加载生效）
    // ------------------------------------------------------------------

    private static int speed(CommandSourceStack source, double multiplier) {
        ModConfig.TIME_MULTIPLIER.set(multiplier);
        ModConfig.SERVER_SPEC.save();
        source.sendSuccess(() -> Component.translatable("msg.dn.config.set",
                Component.translatable("gui.dn.config.speed").getString(), multiplier), true);
        return 1;
    }

    private static int queue(CommandSourceStack source, int size) {
        ModConfig.MAX_QUEUE_SIZE.set(size);
        ModConfig.SERVER_SPEC.save();
        source.sendSuccess(() -> Component.translatable("msg.dn.config.set",
                Component.translatable("gui.dn.config.queue").getString(), size), true);
        return 1;
    }

    private static int currencyScoreboard(CommandSourceStack source, String objective) {
        ModConfig.CURRENCY_TYPE.set("scoreboard");
        ModConfig.CURRENCY_SCOREBOARD.set(objective.trim());
        ModConfig.SERVER_SPEC.save();
        source.sendSuccess(() -> Component.translatable("msg.dn.config.set",
                Component.translatable("gui.dn.config.currency").getString(), "scoreboard:" + objective.trim()), true);
        return 1;
    }

    private static int currencyVault(CommandSourceStack source) {
        boolean api = CurrencyManager.isVaultApiPresent();
        boolean provider = CurrencyManager.isVaultAvailable();
        DeltaNexus.LOGGER.info("[DN] Vault 检测结果: API={}, 经济提供者={}", api, provider);
        if (!provider) {
            // 区分两类故障：Vault 未安装 / 已装但无经济插件提供者
            source.sendFailure(Component.translatable(api
                    ? "msg.dn.config.vault_no_provider" : "msg.dn.config.vault_missing"));
            return 0;
        }
        ModConfig.CURRENCY_TYPE.set("vault");
        ModConfig.SERVER_SPEC.save();
        source.sendSuccess(() -> Component.translatable("msg.dn.config.set",
                Component.translatable("gui.dn.config.currency").getString(), "vault"), true);
        return 1;
    }

    /** 切换为 PlayerPoints 点券货币（需已安装 PlayerPoints，与 WarZDM 点券同源）。 */
    private static int currencyPlayerPoints(CommandSourceStack source) {
        if (!CurrencyManager.isPlayerPointsAvailable()) {
            source.sendFailure(Component.translatable("msg.dn.config.playerpoints_missing"));
            return 0;
        }
        ModConfig.CURRENCY_TYPE.set("playerpoints");
        ModConfig.SERVER_SPEC.save();
        source.sendSuccess(() -> Component.translatable("msg.dn.config.set",
                Component.translatable("gui.dn.config.currency").getString(), "playerpoints"), true);
        return 1;
    }

    private static int whRows(CommandSourceStack source, int rows) {
        ModConfig.WAREHOUSE_ROWS.set(rows);
        ModConfig.SERVER_SPEC.save();
        // 检测机制：行数低于升级树所需容量时自动扩容，以树的需求为准
        boolean expanded = ManufacturingService.autoExpandRows();
        // 行数变更立即生效：在线玩家容量扩容 + 同步包重发（打开中的仓库界面刷新滚动范围）
        ManufacturingService.applyRowsToOnline(source.getServer());
        if (expanded) {
            source.sendSuccess(() -> Component.translatable("msg.dn.tree.rows_expanded",
                    UpgradeConfig.get().maxUnlockSlots(), ModConfig.warehouseRows(),
                    ModConfig.warehouseRows() * WarehouseMenu.WAREHOUSE_COLS), true);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.config.set",
                Component.translatable("gui.dn.config.wh_rows").getString(), rows), true);
        return 1;
    }

    private static int whSlots(CommandSourceStack source, int baseSlots) {
        ModConfig.BASE_SLOTS.set(baseSlots);
        ModConfig.SERVER_SPEC.save();
        source.sendSuccess(() -> Component.translatable("msg.dn.config.set",
                Component.translatable("gui.dn.config.wh_slots").getString(), baseSlots), true);
        return 1;
    }

    /** 制造计时模式切换（离线/在线）。 */
    private static int mode(CommandSourceStack source, String mode) {
        ModConfig.MANUFACTURE_MODE.set(mode);
        ModConfig.SERVER_SPEC.save();
        source.sendSuccess(() -> Component.translatable("msg.dn.config.set",
                Component.translatable("gui.dn.config.mode").getString(),
                Component.translatable("gui.dn.config.mode_" + mode).getString()), true);
        return 1;
    }

    /**
     * 查看当前全部配置（2.0.7Alpha 分节排版：全局/仓库/安全箱/权限/格式背包/其他）。
     * 与旧版单行堆积不同，逐项分行 + 着色，管理员一眼可读。
     */
    private static int info(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("§e========== 三角联结配置 ==========§r\n");

        // 全局设置
        sb.append("§a[全局设置]§r\n");
        sb.append("  §7速度倍率§r: §f").append(ModConfig.timeMultiplier()).append("x§r")
                .append("  §7并行队列§r: §f").append(ModConfig.maxQueueSize()).append("§r")
                .append("  §7计时模式§r: §f")
                .append(ModConfig.onlineMode() ? "在线，仅在线计时" : "离线，离线照常计时").append("§r\n");
        sb.append("  §7货币§r: §f").append(currencySummary()).append("§r\n");

        // 仓库
        sb.append("§a[仓库]§r\n");
        sb.append("  §7升级树最大等级§r: §fLv").append(UpgradeConfig.get().maxLevel()).append("§r")
                .append("  §7总行数§r: §f").append(ModConfig.warehouseRows()).append("§r")
                .append("  §70级解锁§r: §f").append(ModConfig.baseSlots()).append(" 格§r")
                .append("  §7总容量§r: §f").append(ModConfig.warehouseRows() * 9).append(" 格§r\n");

        // 安全箱
        sb.append("§a[安全箱]§r\n");
        sb.append("  §7默认尺寸§r: §f").append(ModConfig.safeBoxWidth()).append("x").append(ModConfig.safeBoxHeight()).append("§r")
                .append("  §7升级树最大等级§r: §fLv").append(UpgradeConfig.get().safeMaxLevel()).append("§r")
                .append("  §7NBT限制§r: §f").append(com.deltanexus.system.config.SafeBoxRestrictions.size()).append(" 条§r\n");

        // 权限
        sb.append("§a[权限默认]§r\n");
        sb.append("  §7OP豁免§r=§f").append(PermissionManager.opExempt() ? "是" : "否").append("§r")
                .append("  §7仓库§r=§f").append(PermissionManager.defaultWarehouse() ? "允许" : "拒绝").append("§r")
                .append("  §7工作台§r=§f").append(PermissionManager.defaultWorkbench() ? "允许" : "拒绝").append("§r")
                .append("  §7特勤处§r=§f").append(PermissionManager.defaultSpecial() ? "允许" : "拒绝").append("§r")
                .append("  §7安全箱§r=§f").append(PermissionManager.defaultSafeBox() ? "允许" : "拒绝").append("§r\n");

        // 格式背包
        sb.append("§a[格式背包]§r\n");
        sb.append("  §7快捷栏规则§r: §f").append(String.join(",", com.deltanexus.system.grid.GridConfig.rules())).append("§r\n");
        sb.append("  §7自定义尺寸§r: §f").append(com.deltanexus.system.grid.ItemSizeConfig.allCustom().size()).append(" 个§r")
                .append("  §7类§r: §f").append(com.deltanexus.system.grid.GridClassConfig.allClasses().size()).append(" 个§r")
                .append("  §7已归属物品§r: §f").append(com.deltanexus.system.grid.GridClassConfig.allItemClasses().size()).append(" 个§r\n");

        // 其他
        sb.append("§a[其他]§r\n");
        int online = source.getServer() != null ? source.getServer().getPlayerList().getPlayers().size() : 0;
        sb.append("  §7在线玩家§r: §f").append(online).append("§r")
                .append("  §7Web 编辑器§r: §f")
                .append(com.deltanexus.system.web.WebEditorServer.isRunning()
                        ? "运行中 " + com.deltanexus.system.web.WebEditorServer.urlWithToken()
                        : "未启动，/dn web on 启动").append("§r\n");
        sb.append("  §7模组版本§r: §f").append(modVersion()).append("§r\n");

        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /** 货币类型摘要（含可用性自诊断）。 */
    private static String currencySummary() {
        String type = ModConfig.currencyType();
        switch (type) {
            case "vault" -> {
                return CurrencyManager.isVaultAvailable()
                        ? "vault，Vault 经济提供者已连接"
                        : (CurrencyManager.isVaultApiPresent()
                                ? "vault，未找到经济提供者" : "vault，未检测到 Vault API");
            }
            case "playerpoints" -> {
                return CurrencyManager.isPlayerPointsAvailable()
                        ? "playerpoints，点券插件已连接" : "playerpoints，插件不可用";
            }
            case "scoreboard" -> {
                return "scoreboard，计分板 " + ModConfig.currencyScoreboard();
            }
            default -> {
                return "scoreboard，计分板 " + ModConfig.currencyScoreboard();
            }
        }
    }

    /** 实际模组版本（读取 mods.toml，而非硬编码）。 */
    private static String modVersion() {
        return net.minecraftforge.fml.ModList.get().getModContainerById(DeltaNexus.MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("?");
    }

    // ------------------------------------------------------------------
    // 工作台管理
    // ------------------------------------------------------------------

    private static int workbenchList(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder();
        for (WorkbenchRegistry.Workbench wb : WorkbenchRegistry.get().all()) {
            sb.append(wb.id).append("=").append(wb.display).append("(").append(wb.recipesDir).append(") ");
        }
        source.sendSuccess(() -> Component.literal(
                Component.translatable("msg.dn.workbench.list", WorkbenchRegistry.get().size()).getString()
                        + " " + sb), true);
        return 1;
    }

    private static int workbenchAdd(CommandSourceStack source, String id, String display) {
        String lowerId = id.toLowerCase(Locale.ROOT);
        if (WorkbenchRegistry.get().exists(lowerId)) {
            source.sendFailure(Component.translatable("msg.dn.workbench.exists", lowerId));
            return 0;
        }
        WorkbenchRegistry.get().add(new WorkbenchRegistry.Workbench(lowerId, display, lowerId));
        JsonConfigWriterSave();
        try {
            java.nio.file.Files.createDirectories(RecipeCache.get().recipesDir().resolve(lowerId));
        } catch (Exception ignored) {
        }
        RecipeCache.get().reload();
        source.sendSuccess(() -> Component.translatable("msg.dn.workbench.added", lowerId, display, lowerId), true);
        return 1;
    }

    private static int workbenchRemove(CommandSourceStack source, String id) {
        String lowerId = id.toLowerCase(Locale.ROOT);
        WorkbenchRegistry.Workbench removed = WorkbenchRegistry.get().remove(lowerId);
        if (removed == null) {
            source.sendFailure(Component.translatable("msg.dn.workbench.not_found", lowerId));
            return 0;
        }
        JsonConfigWriterSave();
        source.sendSuccess(() -> Component.translatable("msg.dn.workbench.removed", lowerId), true);
        return 1;
    }

    private static int workbenchRename(CommandSourceStack source, String id, String display) {
        String lowerId = id.toLowerCase(Locale.ROOT);
        WorkbenchRegistry.Workbench wb = WorkbenchRegistry.get().getById(lowerId);
        if (wb == null) {
            source.sendFailure(Component.translatable("msg.dn.workbench.not_found", lowerId));
            return 0;
        }
        wb.display = display;
        JsonConfigWriterSave();
        source.sendSuccess(() -> Component.translatable("msg.dn.workbench.renamed", lowerId, display), true);
        return 1;
    }

    private static void JsonConfigWriterSave() {
        com.deltanexus.system.config.JsonConfigWriter.saveJsonDebounced(
                WorkbenchRegistry.get().path(), WorkbenchRegistry.get().toJson(), ModConfig.saveDebounceMs());
    }

    // ------------------------------------------------------------------
    // 配方管理（指令化，替代原配方编辑 UI）
    // ------------------------------------------------------------------

    private static int recipeList(CommandSourceStack source, String workbenchId) {
        WorkbenchRegistry.Workbench wb = WorkbenchRegistry.get().getById(workbenchId.toLowerCase(Locale.ROOT));
        if (wb == null) {
            source.sendFailure(Component.translatable("msg.dn.workbench.not_found", workbenchId));
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (Recipe r : RecipeCache.get().getByWorkbench(wb.id)) {
            sb.append(r.displayName());
            if (!r.displayName().equals(r.recipeId)) {
                sb.append("[").append(r.recipeId).append("]");
            }
            sb.append("(").append(r.baseDuration).append("s) ");
        }
        source.sendSuccess(() -> Component.literal(
                Component.translatable("msg.dn.recipe.list", wb.display, RecipeCache.get().getByWorkbench(wb.id).size()).getString()
                        + " " + sb), true);
        return 1;
    }

    private static int recipeGet(CommandSourceStack source, String recipeId) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null) {
            source.sendFailure(Component.translatable("msg.dn.recipe.not_found"));
            return 0;
        }
        StringBuilder sb = new StringBuilder("§e=== 配方 ").append(r.recipeId).append(" ===§r\n");
        if (!r.displayName().equals(r.recipeId)) {
            sb.append("§a显示名§r: ").append(r.displayName()).append("\n");
        }
        sb.append("§a工作台§r: ").append(r.workbenchId()).append("\n");
        sb.append("§a等级§r: ").append(r.requiredLevel)
                .append(" | §a耗时§r: ").append(r.baseDuration).append("s")
                .append(" | §a并行§r: ").append(r.maxParallel).append("\n");
        sb.append("§a原料§r: ");
        if (r.input.isEmpty()) {
            sb.append("空，用 addinput 补充");
        } else {
            for (int i = 0; i < r.input.size(); i++) {
                Recipe.Ingredient ing = r.input.get(i);
                if (i > 0) sb.append(", ");
                sb.append("[").append(i).append("] ").append(ing.item).append("x").append(ing.count);
                if (!ing.nbt.isBlank()) sb.append("(NBT:").append(ing.matchType.key()).append(")");
            }
        }
        sb.append("\n§a产物§r: ");
        if (r.output.isEmpty()) {
            sb.append("空，用 addoutput 补充");
        } else {
            for (int i = 0; i < r.output.size(); i++) {
                Recipe.Output out = r.output.get(i);
                if (i > 0) sb.append(", ");
                sb.append("[").append(i).append("] ").append(out.item).append("x").append(out.count);
                if (!out.nbt.isBlank()) sb.append("(+NBT)");
            }
        }
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /** 创建空配方骨架（无需 JSON，后续用 addinput/addoutput 补充原料产物）。 */
    private static int recipeAdd(CommandSourceStack source, String workbenchId, String recipeId) {
        try {
            ManufacturingService.createRecipe(workbenchId, recipeId);
            source.sendSuccess(() -> Component.translatable("msg.dn.recipe.saved", recipeId), true);
            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("msg.dn.workbench.not_found", workbenchId));
            return 0;
        }
    }

    private static int recipeRemove(CommandSourceStack source, String recipeId) {
        if (!ManufacturingService.removeRecipe(recipeId)) {
            source.sendFailure(Component.translatable("msg.dn.recipe.not_found"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.removed", recipeId), true);
        return 1;
    }

    /** 设置配方显示名（/dn recipe rename <id> <显示名>；未设置时界面回退显示 recipeId）。 */
    private static int recipeRename(CommandSourceStack source, String recipeId, String display) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null) {
            source.sendFailure(Component.translatable("msg.dn.recipe.not_found"));
            return 0;
        }
        r.displayName = display;
        RecipeCache.get().saveSingleRecipe(r);
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.renamed", recipeId, display), true);
        return 1;
    }

    /** 主手物品（含 NBT）添加为配方原料。 */
    private static int recipeAddInput(CommandSourceStack source, String recipeId) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
        if (!ManufacturingService.addRecipeInput(player, recipeId)) {
            source.sendFailure(Component.translatable("msg.dn.recipe.add_input_failed", recipeId));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.add_input_ok", recipeId), true);
        return 1;
    }

    /** 主手物品（含 NBT）添加为配方产物。 */
    private static int recipeAddOutput(CommandSourceStack source, String recipeId) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
        if (!ManufacturingService.addRecipeOutput(player, recipeId)) {
            source.sendFailure(Component.translatable("msg.dn.recipe.add_output_failed", recipeId));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.add_output_ok", recipeId), true);
        return 1;
    }

    /** 删除配方原料（按索引）。 */
    private static int recipeDelInput(CommandSourceStack source, String recipeId, int index) {
        if (!ManufacturingService.removeRecipeInput(recipeId, index)) {
            source.sendFailure(Component.translatable("msg.dn.recipe.del_failed", recipeId, index));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.del_input_ok", recipeId, index), true);
        return 1;
    }

    /** 删除配方产物（按索引）。 */
    private static int recipeDelOutput(CommandSourceStack source, String recipeId, int index) {
        if (!ManufacturingService.removeRecipeOutput(recipeId, index)) {
            source.sendFailure(Component.translatable("msg.dn.recipe.del_failed", recipeId, index));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.del_output_ok", recipeId, index), true);
        return 1;
    }

    /** 修改配方原料数量（按索引，1.0.4）。 */
    private static int recipeSetInput(CommandSourceStack source, String recipeId, int index, int count) {
        if (!ManufacturingService.setRecipeInputCount(recipeId, index, count)) {
            source.sendFailure(Component.translatable("msg.dn.recipe.set_failed", recipeId, index));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.set_input_ok", recipeId, index, count), true);
        return 1;
    }

    /** 修改配方产物数量（按索引，1.0.4）。 */
    private static int recipeSetOutput(CommandSourceStack source, String recipeId, int index, int count) {
        if (!ManufacturingService.setRecipeOutputCount(recipeId, index, count)) {
            source.sendFailure(Component.translatable("msg.dn.recipe.set_failed", recipeId, index));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.set_output_ok", recipeId, index, count), true);
        return 1;
    }

    /** 设置配方基础耗时（秒）。 */
    private static int recipeTime(CommandSourceStack source, String recipeId, int seconds) {
        if (!ManufacturingService.setRecipeTime(recipeId, seconds)) {
            source.sendFailure(Component.translatable("msg.dn.recipe.not_found"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.time_set", recipeId, seconds), true);
        return 1;
    }

    /** 设置配方所需仓库等级。 */
    private static int recipeLevel(CommandSourceStack source, String recipeId, int level) {
        if (!ManufacturingService.setRecipeLevel(recipeId, level)) {
            source.sendFailure(Component.translatable("msg.dn.recipe.not_found"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.level_set", recipeId, level), true);
        return 1;
    }

    /** 设置配方同时制作上限。 */
    private static int recipeParallel(CommandSourceStack source, String recipeId, int limit) {
        if (!ManufacturingService.setRecipeParallel(recipeId, limit)) {
            source.sendFailure(Component.translatable("msg.dn.recipe.not_found"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.recipe.parallel_set", recipeId, limit), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // 升级树管理（指令化，替代原升级编辑 UI）
    // ------------------------------------------------------------------

    private static int treeGet(CommandSourceStack source) {
        java.util.List<UpgradeConfig.UpgradeLevel> list = UpgradeConfig.get().all();
        if (list.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e升级树为空"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("§e=== 升级树 ===§r\n");
        for (UpgradeConfig.UpgradeLevel u : list) {
            sb.append("§aLv").append(u.level).append("§r: 费用 ").append(u.costMoney)
                    .append(" | 解锁槽位 ").append(u.unlockSlots);
            if (!u.requiredItems.isEmpty()) {
                sb.append(" | 材料: ");
                for (int i = 0; i < u.requiredItems.size(); i++) {
                    if (i > 0) sb.append(", ");
                    UpgradeConfig.RequiredItem req = u.requiredItems.get(i);
                    sb.append("[").append(i).append("] ").append(req.item).append("x").append(req.count);
                    if (!req.nbt.isBlank()) sb.append("(NBT:").append(req.matchType.key()).append(")");
                }
            }
            sb.append("\n");
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    /** 添加空等级节点（后续用 cost/slots/additem 补充）。 */
    private static int treeAdd(CommandSourceStack source, int level) {
        UpgradeConfig.get().getOrCreate(level);
        UpgradeConfig.get().saveNow();
        source.sendSuccess(() -> Component.translatable("msg.dn.tree.added", level), true);
        return 1;
    }

    /** 设置升级等级费用。 */
    private static int treeCost(CommandSourceStack source, int level, int cost) {
        ManufacturingService.setTreeCost(level, cost);
        source.sendSuccess(() -> Component.translatable("msg.dn.tree.cost_set", level, cost), true);
        return 1;
    }

    /** 设置升级解锁槽位数（超过当前容量时自动扩容行数并同步在线玩家）。 */
    private static int treeSlots(CommandSourceStack source, int level, int slots) {
        ManufacturingService.setTreeSlots(level, slots);
        source.sendSuccess(() -> Component.translatable("msg.dn.tree.slots_set", level, slots), true);
        // 检测机制：树中开放容量超过当前最大容量（行数 x 9）时自动扩容行数
        if (ManufacturingService.autoExpandRows()) {
            ManufacturingService.applyRowsToOnline(source.getServer());
            source.sendSuccess(() -> Component.translatable("msg.dn.tree.rows_expanded",
                    UpgradeConfig.get().maxUnlockSlots(), ModConfig.warehouseRows(),
                    ModConfig.warehouseRows() * WarehouseMenu.WAREHOUSE_COLS), true);
        }
        return 1;
    }

    /** 主手物品添加为升级所需材料。 */
    private static int treeAddItem(CommandSourceStack source, int level) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
        if (!ManufacturingService.addTreeItem(player, level)) {
            source.sendFailure(Component.translatable("msg.dn.tree.add_item_failed", level));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.tree.add_item_ok", level), true);
        return 1;
    }

    /** 删除升级所需材料（按索引）。 */
    private static int treeDelItem(CommandSourceStack source, int level, int index) {
        if (!ManufacturingService.removeTreeItem(level, index)) {
            source.sendFailure(Component.translatable("msg.dn.tree.del_item_failed", level, index));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.tree.del_item_ok", level, index), true);
        return 1;
    }

    /** 修改升级所需材料数量（按索引，1.0.4）。 */
    private static int treeSetItem(CommandSourceStack source, int level, int index, int count) {
        if (!ManufacturingService.setTreeItemCount(level, index, count)) {
            source.sendFailure(Component.translatable("msg.dn.tree.item_set_failed", level, index));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.tree.item_set_ok", level, index, count), true);
        return 1;
    }

    private static int treeRemove(CommandSourceStack source, int level) {
        JsonObject tree = UpgradeConfig.get().toJson();
        JsonArray arr = tree.has("warehouse_upgrades") ? tree.getAsJsonArray("warehouse_upgrades") : new JsonArray();
        boolean removed = false;
        List<JsonElement> toRemove = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el.isJsonObject() && el.getAsJsonObject().has("level")
                    && el.getAsJsonObject().get("level").getAsInt() == level) {
                toRemove.add(el);
            }
        }
        for (JsonElement el : toRemove) {
            arr.remove(el);
            removed = true;
        }
        if (!removed) {
            source.sendFailure(Component.translatable("msg.dn.tree.level_not_found", level));
            return 0;
        }
        ManufacturingService.saveUpgradeTree(tree.toString());
        source.sendSuccess(() -> Component.translatable("msg.dn.tree.saved"), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // 玩家仓库数据管理（/dn data <玩家>，需 op 权限 4）
    // ------------------------------------------------------------------

    /** 查看玩家仓库/安全箱数据（等级 / 解锁槽位 / 容量）。 */
    private static int dataGet(CommandSourceStack source, ServerPlayer target) {
        IPlayerData data = ManufacturingService.data(target);
        if (data == null) {
            source.sendFailure(Component.translatable("msg.dn.data.not_found", target.getGameProfile().getName()));
            return 0;
        }
        int capacity = data.getCapacity();
        int unlocked = data.getUnlockedSlots().cardinality();
        int rows = Math.max(1, (capacity + 8) / 9);
        String name = target.getGameProfile().getName();
        source.sendSuccess(() -> Component.translatable("msg.dn.data.get",
                name, data.getWarehouseLevel(), UpgradeConfig.get().maxLevel(),
                unlocked, capacity, capacity, rows,
                data.getSafeBoxLevel(), UpgradeConfig.get().safeMaxLevel(),
                data.getSafeBoxHeight(), data.getSafeBoxWidth()), false);
        return 1;
    }

    /** 设置玩家仓库等级（0 ~ 升级树最大等级，超出拒绝）；容量随等级联动解锁。 */
    private static int dataLevel(CommandSourceStack source, ServerPlayer target, int level) {
        int max = UpgradeConfig.get().maxLevel();
        if (level > max) {
            source.sendFailure(Component.translatable("msg.dn.data.level_too_high", max));
            return 0;
        }
        IPlayerData data = ManufacturingService.data(target);
        if (data == null) {
            source.sendFailure(Component.translatable("msg.dn.data.not_found", target.getGameProfile().getName()));
            return 0;
        }
        // 会话内扩容：等级对应容量可能超过登录时容量，先按当前配置页数扩容
        ManufacturingService.ensureWarehouseCapacity(target);
        data.setWarehouseLevel(level);
        // 容量联动：按升级树该等级（取不高于 level 的最高已定义等级）的解锁数解锁（只增不减）
        data.unlockUpTo(UpgradeConfig.get().unlockSlotsForLevel(level));
        int unlocked = data.getUnlockedSlots().cardinality();
        notifyTarget(target, "msg.dn.data.notify.level", level);
        ManufacturingService.sendSyncWarehouse(target);
        source.sendSuccess(() -> Component.translatable("msg.dn.data.level_set",
                target.getGameProfile().getName(), level, unlocked, data.getCapacity()), true);
        return 1;
    }

    /** 精确设置玩家解锁槽位（0 ~ 当前容量，缩小会锁定多余槽位）。 */
    private static int dataSlots(CommandSourceStack source, ServerPlayer target, int slots) {
        IPlayerData data = ManufacturingService.data(target);
        if (data == null) {
            source.sendFailure(Component.translatable("msg.dn.data.not_found", target.getGameProfile().getName()));
            return 0;
        }
        // 会话内扩容：使容量跟上当前配置页数，管理员可设置到完整容量
        ManufacturingService.ensureWarehouseCapacity(target);
        int capacity = data.getCapacity();
        if (slots > capacity) {
            source.sendFailure(Component.translatable("msg.dn.data.slots_out_of_range", capacity));
            return 0;
        }
        data.setUnlockedSlots(slots);
        notifyTarget(target, "msg.dn.data.notify.slots", slots);
        ManufacturingService.sendSyncWarehouse(target);
        source.sendSuccess(() -> Component.translatable("msg.dn.data.slots_set",
                target.getGameProfile().getName(), slots, capacity), true);
        return 1;
    }

    /** 设置玩家安全箱等级（0 ~ 安全箱升级树最大等级，1.1.0Alpha）。 */
    private static int dataSafe(CommandSourceStack source, ServerPlayer target, int level) {
        int max = UpgradeConfig.get().safeMaxLevel();
        if (level > max) {
            source.sendFailure(Component.translatable("msg.dn.safe.data.level_too_high", max));
            return 0;
        }
        if (!ManufacturingService.setPlayerSafeLevel(target, level)) {
            source.sendFailure(Component.translatable("msg.dn.data.not_found", target.getGameProfile().getName()));
            return 0;
        }
        IPlayerData data = ManufacturingService.data(target);
        source.sendSuccess(() -> Component.translatable("msg.dn.safe.data.level_set",
                target.getGameProfile().getName(), level,
                data != null ? data.getSafeBoxHeight() : 0,
                data != null ? data.getSafeBoxWidth() : 0,
                data != null ? data.getSafeBoxUnlockedSlots() : 0), true);
        return 1;
    }

    /** 重置玩家仓库 + 安全箱数据（等级归 0；物品保留，缩位部分暂不可取）。 */
    private static int dataReset(CommandSourceStack source, ServerPlayer target) {
        IPlayerData data = ManufacturingService.data(target);
        if (data == null) {
            source.sendFailure(Component.translatable("msg.dn.data.not_found", target.getGameProfile().getName()));
            return 0;
        }
        int base = Math.min(ModConfig.baseSlots(), data.getCapacity());
        data.setWarehouseLevel(0);
        data.setUnlockedSlots(base);
        data.setSafeBoxLevel(0);
        // 0.2.0Beta：管理员重置视为显式意图 → 清除数据备份，避免登录兜底把旧数据恢复回来
        com.deltanexus.system.capability.CapabilityAttacher.clearBackupFor(target.getUUID());
        notifyTarget(target, "msg.dn.data.notify.reset");
        ManufacturingService.sendSyncWarehouse(target);
        ManufacturingService.syncSafeBox(target);
        source.sendSuccess(() -> Component.translatable("msg.dn.data.reset",
                target.getGameProfile().getName(), base), true);
        return 1;
    }

    /** 向被操作玩家发送热栏提示（数据被管理员修改）。 */
    private static void notifyTarget(ServerPlayer target, String key, Object... args) {
        target.displayClientMessage(Component.translatable(key, args), true);
    }

    // ------------------------------------------------------------------
    // 安全箱管理（/dn safe，1.1.0Alpha）
    // ------------------------------------------------------------------

    /** 查看安全箱配置与升级树。 */
    private static int safeGet(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("§e=== 安全箱 ===§r\n");
        sb.append("§a默认尺寸§r: ").append(ModConfig.safeBoxWidth()).append("x").append(ModConfig.safeBoxHeight())
                .append("，最大 3x3\n");
        java.util.List<UpgradeConfig.UpgradeLevel> list = UpgradeConfig.get().safeAll();
        if (list.isEmpty()) {
            sb.append("§e安全箱升级树为空，用 /dn safe add <等级> 添加§r");
        } else {
            sb.append("§e=== 安全箱升级树 ===§r\n");
            for (UpgradeConfig.UpgradeLevel u : list) {
                int[] dims = UpgradeConfig.safeDims(Math.max(1, u.unlockSlots));
                int r = u.unlockRows > 0 ? u.unlockRows : dims[1];
                int c = u.unlockCols > 0 ? u.unlockCols : dims[0];
                sb.append("§aLv").append(u.level).append("§r: 费用 ").append(u.costMoney)
                        .append(" | 解锁 ").append(r).append(" 行 x ").append(c)
                        .append(" 列，共 ").append(u.unlockSlots).append(" 格");
                if (!u.requiredItems.isEmpty()) {
                    sb.append(" | 材料: ");
                    for (int i = 0; i < u.requiredItems.size(); i++) {
                        if (i > 0) sb.append(", ");
                        UpgradeConfig.RequiredItem req = u.requiredItems.get(i);
                        sb.append("[").append(i).append("] ").append(req.item).append("x").append(req.count);
                        if (!req.nbt.isBlank()) sb.append("(NBT:").append(req.matchType.key()).append(")");
                    }
                }
                sb.append("\n");
            }
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    /** 设置安全箱默认尺寸（1 ~ 3 x 1 ~ 3，0 级玩家初始尺寸）。 */
    private static int safeSize(CommandSourceStack source, int width, int height) {
        ModConfig.SAFE_BOX_WIDTH.set(width);
        ModConfig.SAFE_BOX_HEIGHT.set(height);
        ModConfig.SERVER_SPEC.save();
        source.sendSuccess(() -> Component.translatable("msg.dn.config.set",
                Component.translatable("gui.dn.config.safe_size").getString(),
                width + "x" + height), true);
        return 1;
    }

    /** 添加空安全箱升级等级节点。 */
    private static int safeAdd(CommandSourceStack source, int level) {
        UpgradeConfig.get().safeGetOrCreate(level);
        UpgradeConfig.get().saveNow();
        source.sendSuccess(() -> Component.translatable("msg.dn.safe.tree.added", level), true);
        return 1;
    }

    /** 设置安全箱升级费用。 */
    private static int safeCost(CommandSourceStack source, int level, int cost) {
        ManufacturingService.setSafeTreeCost(level, cost);
        source.sendSuccess(() -> Component.translatable("msg.dn.safe.tree.cost_set", level, cost), true);
        return 1;
    }

    /** 设置安全箱升级解锁行 x 列（各 1 ~ 3，2.0.1Alpha 行列制）。 */
    private static int safeRows(CommandSourceStack source, int level, int rows, int cols) {
        ManufacturingService.setSafeTreeDims(level, rows, cols);
        source.sendSuccess(() -> Component.translatable("msg.dn.safe.tree.dims_set",
                level, rows, cols, rows * cols), true);
        return 1;
    }

    /** 主手物品添加为安全箱升级材料。 */
    private static int safeAddItem(CommandSourceStack source, int level) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
        if (!ManufacturingService.addSafeTreeItem(player, level)) {
            source.sendFailure(Component.translatable("msg.dn.tree.add_item_failed", level));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.safe.tree.add_item_ok", level), true);
        return 1;
    }

    /** 删除安全箱升级材料（按索引）。 */
    private static int safeDelItem(CommandSourceStack source, int level, int index) {
        if (!ManufacturingService.removeSafeTreeItem(level, index)) {
            source.sendFailure(Component.translatable("msg.dn.tree.del_item_failed", level, index));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.safe.tree.del_item_ok", level, index), true);
        return 1;
    }

    /** 修改安全箱升级材料数量（按索引）。 */
    private static int safeSetItem(CommandSourceStack source, int level, int index, int count) {
        if (!ManufacturingService.setSafeTreeItemCount(level, index, count)) {
            source.sendFailure(Component.translatable("msg.dn.tree.item_set_failed", level, index));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.tree.item_set_ok", level, index, count), true);
        return 1;
    }

    /** 删除安全箱升级等级。 */
    private static int safeRemove(CommandSourceStack source, int level) {
        if (!ManufacturingService.removeSafeLevel(level)) {
            source.sendFailure(Component.translatable("msg.dn.safe.tree.level_not_found", level));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.safe.tree.saved"), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // 安全箱 NBT 限制（/dn safe restrict，1.1.0Alpha）
    // ------------------------------------------------------------------

    /** 添加安全箱 NBT 限制规则：/dn safe restrict <item|any> <exact|contains> <nbt>。 */
    private static int safeRestrict(CommandSourceStack source, String item, String matchType, String nbt) {
        if (nbt.isBlank()) {
            source.sendFailure(Component.translatable("msg.dn.safe.restrict.nbt_empty"));
            return 0;
        }
        com.deltanexus.system.common.NbtMatcher.MatchType mt;
        if ("exact".equalsIgnoreCase(matchType)) {
            mt = com.deltanexus.system.common.NbtMatcher.MatchType.EXACT;
        } else if ("contains".equalsIgnoreCase(matchType)) {
            mt = com.deltanexus.system.common.NbtMatcher.MatchType.CONTAINS;
        } else {
            source.sendFailure(Component.translatable("msg.dn.safe.restrict.type_invalid"));
            return 0;
        }
        String itemId = "";
        if (!"any".equalsIgnoreCase(item)) {
            itemId = item.toLowerCase(Locale.ROOT);
            if (net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    net.minecraft.resources.ResourceLocation.tryParse(itemId)) == null) {
                source.sendFailure(Component.translatable("msg.dn.safe.restrict.item_invalid", item));
                return 0;
            }
        }
        final String displayItem = itemId.isEmpty() ? "any" : itemId;
        int index = com.deltanexus.system.config.SafeBoxRestrictions.add(itemId, nbt, mt);
        source.sendSuccess(() -> Component.translatable("msg.dn.safe.restrict.added",
                index, displayItem, mt.key()), true);
        return 1;
    }

    /** 查看安全箱 NBT 限制列表。 */
    private static int safeRestrictions(CommandSourceStack source) {
        java.util.List<com.deltanexus.system.config.SafeBoxRestrictions.Rule> rules =
                com.deltanexus.system.config.SafeBoxRestrictions.all();
        if (rules.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("msg.dn.safe.restrict.empty"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("§e=== 安全箱 NBT 限制 ===§r\n");
        for (int i = 0; i < rules.size(); i++) {
            com.deltanexus.system.config.SafeBoxRestrictions.Rule r = rules.get(i);
            sb.append("§a[").append(i).append("]§r 物品: ")
                    .append(r.item.isBlank() ? "any" : r.item)
                    .append(" | 匹配: ").append(r.matchType.key())
                    .append(" | NBT: ").append(r.nbt)
                    .append("\n");
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    /** 删除安全箱 NBT 限制（按索引）。 */
    private static int safeUnrestrict(CommandSourceStack source, int index) {
        if (!com.deltanexus.system.config.SafeBoxRestrictions.remove(index)) {
            source.sendFailure(Component.translatable("msg.dn.safe.restrict.not_found", index));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.safe.restrict.removed", index), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // 权限管理（/dn perm，1.1.0Alpha）
    // ------------------------------------------------------------------

    /** 查看权限规则：全局默认 + 玩家覆盖。 */
    private static int permGet(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("§e=== 权限管理 ===§r\n");
        sb.append("§a全局默认§r: 仓库=").append(PermissionManager.defaultWarehouse() ? "允许" : "拒绝")
                .append(" 工作台=").append(PermissionManager.defaultWorkbench() ? "允许" : "拒绝")
                .append(" 特勤处=").append(PermissionManager.defaultSpecial() ? "允许" : "拒绝")
                .append(" 安全箱=").append(PermissionManager.defaultSafeBox() ? "允许" : "拒绝")
                .append(" 交易行=").append(PermissionManager.defaultTrade() ? "允许" : "拒绝")
                .append("，OP 始终允许\n");
        if (PermissionManager.overrides().isEmpty()) {
            sb.append("§7无玩家覆盖，全部按全局默认§r");
        } else {
            for (Map.Entry<String, Boolean[]> e : PermissionManager.overrides().entrySet()) {
                Boolean[] v = e.getValue();
                sb.append("§a").append(e.getKey()).append("§r: 仓库=")
                        .append(permLabel(safeVal(v, PermissionManager.TYPE_WAREHOUSE), PermissionManager.defaultWarehouse()))
                        .append(" 工作台=")
                        .append(permLabel(safeVal(v, PermissionManager.TYPE_WORKBENCH), PermissionManager.defaultWorkbench()))
                        .append(" 特勤处=")
                        .append(permLabel(safeVal(v, PermissionManager.TYPE_SPECIAL), PermissionManager.defaultSpecial()))
                        .append(" 安全箱=")
                        .append(permLabel(safeVal(v, PermissionManager.TYPE_SAFE_BOX), PermissionManager.defaultSafeBox()))
                        .append(" 交易行=")
                        .append(permLabel(safeVal(v, PermissionManager.TYPE_TRADE), PermissionManager.defaultTrade()))
                        .append("\n");
            }
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    /** 数组越界安全取值（旧版存档可能只有 3 元素）。 */
    private static Boolean safeVal(Boolean[] v, int idx) {
        return v != null && idx >= 0 && idx < v.length ? v[idx] : null;
    }

    /** 权限显示：覆盖值 null 时跟随全局默认。 */
    private static String permLabel(Boolean override, boolean def) {
        boolean value = override != null ? override : def;
        return (value ? "允许" : "拒绝") + (override == null ? "(默认)" : "(覆盖)");
    }

    /** 查看目标（选择器/玩家名）的权限；非玩家实体跳过并提示。 */
    private static int permGetOne(CommandSourceStack source,
                                  java.util.Collection<? extends net.minecraft.world.entity.Entity> targets) {
        int shown = 0, skipped = 0;
        for (net.minecraft.world.entity.Entity e : targets) {
            if (!(e instanceof ServerPlayer sp)) {
                skipped++;
                continue;
            }
            String name = sp.getGameProfile().getName();
            Boolean wh = PermissionManager.getOverride(name, PermissionManager.TYPE_WAREHOUSE);
            Boolean wb = PermissionManager.getOverride(name, PermissionManager.TYPE_WORKBENCH);
            Boolean sp2 = PermissionManager.getOverride(name, PermissionManager.TYPE_SPECIAL);
            Boolean sb2 = PermissionManager.getOverride(name, PermissionManager.TYPE_SAFE_BOX);
            Boolean tr2 = PermissionManager.getOverride(name, PermissionManager.TYPE_TRADE);
            final String fName = name;
            source.sendSuccess(() -> Component.literal("§e" + fName + "§r: 仓库="
                    + (wh != null ? (wh ? "允许(覆盖)" : "拒绝(覆盖)") : (PermissionManager.defaultWarehouse() ? "允许(默认)" : "拒绝(默认)"))
                    + " 工作台="
                    + (wb != null ? (wb ? "允许(覆盖)" : "拒绝(覆盖)") : (PermissionManager.defaultWorkbench() ? "允许(默认)" : "拒绝(默认)"))
                    + " 特勤处="
                    + (sp2 != null ? (sp2 ? "允许(覆盖)" : "拒绝(覆盖)") : (PermissionManager.defaultSpecial() ? "允许(默认)" : "拒绝(默认)"))
                    + " 安全箱="
                    + (sb2 != null ? (sb2 ? "允许(覆盖)" : "拒绝(覆盖)") : (PermissionManager.defaultSafeBox() ? "允许(默认)" : "拒绝(默认)"))
                    + " 交易行="
                    + (tr2 != null ? (tr2 ? "允许(覆盖)" : "拒绝(覆盖)") : (PermissionManager.defaultTrade() ? "允许(默认)" : "拒绝(默认)"))), false);
            shown++;
        }
        if (skipped > 0) {
            final int fSkipped = skipped;
            source.sendSuccess(() -> Component.translatable("msg.dn.perm.skipped", fSkipped), false);
        }
        return shown > 0 ? 1 : 0;
    }

    /** 设置玩家权限覆盖（支持 @a 等目标选择器；非玩家实体跳过并提示）。 */
    private static int permSet(CommandSourceStack source,
                               java.util.Collection<? extends net.minecraft.world.entity.Entity> targets,
                               String type, String allow) {
        boolean value = parseAllow(allow);
        if (value && !allow.equalsIgnoreCase("allow")) {
            source.sendFailure(Component.literal("allow 仅支持 allow/deny"));
            return 0;
        }
        int t = parsePermType(type);
        if (t < -1) {
            source.sendFailure(Component.literal("type 仅支持 warehouse/workbench/special/safe_box/trade/all"));
            return 0;
        }
        int applied = 0, skipped = 0;
        for (net.minecraft.world.entity.Entity e : targets) {
            if (e instanceof ServerPlayer sp) {
                String name = sp.getGameProfile().getName();
                PermissionManager.setOverride(name, t, value);
                final String fName = name;
                final String fType = type;
                final String fValue = value ? "允许" : "拒绝";
                source.sendSuccess(() -> Component.translatable("msg.dn.perm.set",
                        fName, fType, fValue), true);
                applied++;
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            final int fSkipped = skipped;
            source.sendSuccess(() -> Component.translatable("msg.dn.perm.skipped", fSkipped), false);
        }
        if (applied == 0 && skipped > 0) {
            source.sendFailure(Component.translatable("msg.dn.perm.no_player"));
            return 0;
        }
        return applied > 0 ? 1 : 0;
    }

    /** 移除玩家权限覆盖（支持 @a 等目标选择器；非玩家实体跳过并提示）。 */
    private static int permRemove(CommandSourceStack source,
                                  java.util.Collection<? extends net.minecraft.world.entity.Entity> targets) {
        int removed = 0, skipped = 0;
        for (net.minecraft.world.entity.Entity e : targets) {
            if (e instanceof ServerPlayer sp) {
                String name = sp.getGameProfile().getName();
                final String fName = name;
                if (PermissionManager.removeOverride(name)) {
                    source.sendSuccess(() -> Component.translatable("msg.dn.perm.removed", fName), true);
                    removed++;
                } else {
                    source.sendFailure(Component.translatable("msg.dn.perm.not_found", fName));
                }
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            final int fSkipped = skipped;
            source.sendSuccess(() -> Component.translatable("msg.dn.perm.skipped", fSkipped), false);
        }
        return removed > 0 ? 1 : 0;
    }

    /** 设置全局默认权限。 */
    private static int permDefault(CommandSourceStack source, String type, String allow) {
        boolean value = parseAllow(allow);
        if (value && !allow.equalsIgnoreCase("allow")) {
            source.sendFailure(Component.literal("allow 仅支持 allow/deny"));
            return 0;
        }
        int t = parsePermType(type);
        if (t < -1) {
            source.sendFailure(Component.literal("type 仅支持 warehouse/workbench/special/safe_box/trade/all"));
            return 0;
        }
        PermissionManager.setDefault(t, value);
        source.sendSuccess(() -> Component.translatable("msg.dn.perm.default_set",
                type, value ? "允许" : "拒绝"), true);
        return 1;
    }

    private static boolean parseAllow(String allow) {
        return allow.equalsIgnoreCase("allow");
    }

    /** 解析权限类型：warehouse=0 / workbench=1 / special=2 / safe_box=3 / trade=4 / all=-1；非法返回 -2。 */
    private static int parsePermType(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "warehouse" -> PermissionManager.TYPE_WAREHOUSE;
            case "workbench" -> PermissionManager.TYPE_WORKBENCH;
            case "special" -> PermissionManager.TYPE_SPECIAL;
            case "safe_box", "safebox" -> PermissionManager.TYPE_SAFE_BOX;
            case "trade" -> PermissionManager.TYPE_TRADE;
            case "all" -> -1;
            default -> -2;
        };
    }

    // ------------------------------------------------------------------
    // OP 豁免（2.1Alpha：/dn perm op / getop）
    // ------------------------------------------------------------------

    /** 设置 OP 是否豁免权限判定。 */
    private static int permOp(CommandSourceStack source, String allow) {
        boolean value = parseAllow(allow);
        if (value && !allow.equalsIgnoreCase("allow")) {
            source.sendFailure(Component.literal("allow 仅支持 allow/deny"));
            return 0;
        }
        PermissionManager.setOpExempt(value);
        source.sendSuccess(() -> Component.translatable("msg.dn.perm.op_set",
                value ? "豁免，OP 不受权限限制" : "不豁免，OP 同样受权限限制"), true);
        return 1;
    }

    /** 查看 OP 豁免状态。 */
    private static int permGetOp(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.perm.op_get",
                PermissionManager.opExempt() ? "豁免，OP 不受权限限制" : "不豁免，OP 同样受权限限制"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 功能开关（2.1Alpha：/dn feature，禁用后无法使用全部 mod 功能，UI 恢复原版）
    // ------------------------------------------------------------------

    /** 查看功能开关状态：全局提示 + 被禁用玩家列表。 */
    private static int featureGet(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("§e=== 功能开关 ===§r\n");
        sb.append("§7说明§r: 被禁用的玩家无法使用任何 mod 功能，所有 UI 界面恢复原版。\n");
        java.util.Set<String> disabled = PermissionManager.disabledFeatures();
        if (disabled.isEmpty()) {
            sb.append("§7无玩家被禁用§r");
        } else {
            for (String name : disabled) {
                sb.append("§c").append(name).append("§r: 已禁用\n");
            }
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    /** 查看单个目标的开关状态。 */
    private static int featureGetOne(CommandSourceStack source,
                                     java.util.Collection<? extends net.minecraft.world.entity.Entity> targets) {
        int skipped = 0;
        for (net.minecraft.world.entity.Entity e : targets) {
            if (!(e instanceof ServerPlayer sp)) {
                skipped++;
                continue;
            }
            String name = sp.getGameProfile().getName();
            source.sendSuccess(() -> Component.literal("§e" + name + "§r: "
                    + (PermissionManager.featuresDisabled(name) ? "§c已禁用，所有 mod 功能不可用§r" : "§a已启用§r")), false);
        }
        if (skipped > 0) {
            final int fSkipped = skipped;
            source.sendSuccess(() -> Component.translatable("msg.dn.perm.skipped", fSkipped), false);
        }
        return 1;
    }

    /** 设置目标功能开关（支持 @a 等选择器；非玩家实体跳过并提示）。 */
    private static int featureSet(CommandSourceStack source,
                                  java.util.Collection<? extends net.minecraft.world.entity.Entity> targets,
                                  String allow) {
        boolean enabled = parseAllow(allow);
        if (enabled && !allow.equalsIgnoreCase("allow")) {
            source.sendFailure(Component.literal("allow 仅支持 allow/deny"));
            return 0;
        }
        int applied = 0, skipped = 0;
        for (net.minecraft.world.entity.Entity e : targets) {
            if (e instanceof ServerPlayer sp) {
                String name = sp.getGameProfile().getName();
                PermissionManager.setFeatures(name, enabled);
                // 立即同步 UI 白名单/功能开关到该玩家（界面即时恢复原版）
                ManufacturingService.sendUiWhitelist(sp);
                final String fName = name;
                final String fState = enabled ? "已启用" : "已禁用，所有 UI 恢复原版";
                source.sendSuccess(() -> Component.translatable("msg.dn.feature.set", fName, fState), true);
                applied++;
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            final int fSkipped = skipped;
            source.sendSuccess(() -> Component.translatable("msg.dn.perm.skipped", fSkipped), false);
        }
        if (applied == 0 && skipped > 0) {
            source.sendFailure(Component.translatable("msg.dn.perm.no_player"));
            return 0;
        }
        return applied > 0 ? 1 : 0;
    }

    /** 查看 Web 编辑器状态与访问地址（可点击跳转）。 */
    private static int webStatus(CommandSourceStack source) {
        com.deltanexus.system.web.WebConfig cfg = com.deltanexus.system.web.WebConfig.get();
        if (com.deltanexus.system.web.WebEditorServer.isRunning()) {
            sendWebMessage(source, "msg.dn.web.running", com.deltanexus.system.web.WebEditorServer.urlWithToken());
            source.sendSuccess(() -> Component.translatable("msg.dn.web.enabled_state",
                    cfg.enabled() ? "true" : "false"), true);
        } else {
            source.sendSuccess(() -> Component.translatable("msg.dn.web.not_running"), true);
            if (cfg.enabled()) {
                source.sendSuccess(() -> Component.translatable("msg.dn.web.enabled_auto_hint"), true);
            }
        }
        return 1;
    }

    /** 启动 Web 网页编辑器（每次开启重新生成内存令牌），并持久化 enabled=true（重启后自动启动）。 */
    private static int webOn(CommandSourceStack source) {
        if (!com.deltanexus.system.web.WebEditorServer.start()) {
            source.sendFailure(Component.translatable("msg.dn.web.start_failed"));
            return 0;
        }
        com.deltanexus.system.web.WebConfig cfg = com.deltanexus.system.web.WebConfig.get();
        cfg.setEnabled(true);
        cfg.save();
        sendWebMessage(source, "msg.dn.web.started", com.deltanexus.system.web.WebEditorServer.urlWithToken());
        source.sendSuccess(() -> Component.translatable("msg.dn.web.enabled_saved", cfg.path().toString()), true);
        return 1;
    }

    /** 停止 Web 网页编辑器，并持久化 enabled=false（重启后不再自动启动）。 */
    private static int webOff(CommandSourceStack source) {
        if (!com.deltanexus.system.web.WebEditorServer.isRunning()) {
            source.sendFailure(Component.translatable("msg.dn.web.off_not_running"));
            return 0;
        }
        com.deltanexus.system.web.WebEditorServer.stop();
        com.deltanexus.system.web.WebConfig cfg = com.deltanexus.system.web.WebConfig.get();
        cfg.setEnabled(false);
        cfg.save();
        source.sendSuccess(() -> Component.translatable("msg.dn.web.stopped"), true);
        return 1;
    }

    /** 发送「前缀 + 可点击链接 + 提示」消息（点击链接在客户端浏览器打开网页）。 */
    private static void sendWebMessage(CommandSourceStack source, String prefixKey, String url) {
        source.sendSuccess(() -> Component.translatable(prefixKey)
                .append(Component.literal(url).withStyle(style -> style
                        .withColor(net.minecraft.ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, url))))
                .append(Component.literal("\n"))
                .append(Component.translatable("msg.dn.web.hint")), true);
    }

    // ------------------------------------------------------------------
    // help 系统：/dn help 列出所有同级指令简述，/dn <指令> help 展开该指令详情
    // ------------------------------------------------------------------

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help"), false);
        return 1;
    }

    private static int helpOpen(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.open"), false);
        return 1;
    }

    private static int helpReload(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.reload"), false);
        return 1;
    }

    private static int helpExport(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.export"), false);
        return 1;
    }

    private static int helpSetting(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.setting"), false);
        return 1;
    }

    private static int helpWarehouse(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.warehouse"), false);
        return 1;
    }

    private static int helpInfo(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.info"), false);
        return 1;
    }

    private static int helpWorkbench(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.workbench"), false);
        return 1;
    }

    private static int helpRecipe(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.recipe"), false);
        return 1;
    }

    private static int helpTree(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.tree"), false);
        return 1;
    }

    private static int helpData(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.data"), false);
        return 1;
    }

    private static int helpSafe(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.safe"), false);
        return 1;
    }

    private static int helpPerm(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.perm"), false);
        return 1;
    }

    private static int helpFeature(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.feature"), false);
        return 1;
    }

    private static int helpWeb(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.web"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 格式背包配置管理（/dn grid，2.0.2Alpha）
    // ------------------------------------------------------------------

    /** 查看自定义物品尺寸与快捷栏规则。 */
    private static int gridList(CommandSourceStack source) {
        java.util.LinkedHashMap<String, int[]> sizes = com.deltanexus.system.grid.ItemSizeConfig.allCustom();
        StringBuilder sb = new StringBuilder("§e=== 格式背包配置 ===§r\n");
        if (sizes.isEmpty()) {
            sb.append("§7自定义物品尺寸：无，全部按默认 1x1§r\n");
        } else {
            sb.append("§a自定义物品尺寸§r:\n");
            for (java.util.Map.Entry<String, int[]> e : sizes.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" -> ")
                        .append(e.getValue()[0]).append("x").append(e.getValue()[1]).append("\n");
            }
        }
        sb.append("§a快捷栏规则§r: ").append(String.join(", ", com.deltanexus.system.grid.GridConfig.rules()));
        sb.append("\n§a已注册模组容器§r: ")
                .append(com.deltanexus.system.grid.GridRegistry.registeredNames().isEmpty()
                        ? "§7无（原版容器与玩家背包/仓库/安全箱为内置注册）§r"
                        : String.join(", ", com.deltanexus.system.grid.GridRegistry.registeredNames()));
        sb.append("\n§a兼容开关 legacy_any_container§r: ")
                .append(com.deltanexus.system.grid.GridConfig.legacyAnyContainer() ? "§a开§r" : "§7关§r");
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    /** 列出已注册的模组容器（0.3.0Beta）。 */
    private static int gridContainers(CommandSourceStack source) {
        java.util.Set<String> names = com.deltanexus.system.grid.GridRegistry.registeredNames();
        StringBuilder sb = new StringBuilder("§e=== 网格容器注册 ===§r\n");
        sb.append("§7内置：玩家背包 / 仓库 / 安全箱 / 原版 ≥9 格容器（合成格除外）§r\n");
        if (names.isEmpty()) {
            sb.append("§7模组容器：无（用 /dn grid register <类名> 添加）§r");
        } else {
            sb.append("§a模组容器§r:\n");
            for (String n : names) {
                sb.append("  ").append(n).append("\n");
            }
        }
        sb.append("\n§acommon.toml legacy_any_container§r: ")
                .append(com.deltanexus.system.grid.GridConfig.legacyAnyContainer()
                        ? "§a开（所有 ≥9 格容器一律接管）§r" : "§7关（仅原版 + 已注册）§r");
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    /** 注册模组容器类名（落盘 common.toml）。 */
    private static int gridRegister(CommandSourceStack source, String className) {
        if (!com.deltanexus.system.grid.GridRegistry.register(className)) {
            source.sendFailure(Component.translatable("msg.dn.grid.container_exists", className.trim()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.grid.container_registered", className.trim()), true);
        return 1;
    }

    /** 取消注册模组容器类名。 */
    private static int gridUnregister(CommandSourceStack source, String className) {
        if (!com.deltanexus.system.grid.GridRegistry.unregister(className)) {
            source.sendFailure(Component.translatable("msg.dn.grid.container_not_found", className.trim()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.dn.grid.container_unregistered", className.trim()), true);
        return 1;
    }

    /** 设置主手物品占用尺寸（1 ~ 9 x 1 ~ 9，写入 deltanexus-sizes.json 并同步客户端；2.1Alpha 不再需要物品 ID）。 */
    private static int gridSize(CommandSourceStack source, int w, int h) {
        if (!(source.getEntity() instanceof ServerPlayer sp)) {
            source.sendFailure(Component.literal("该指令需由玩家执行，需手持物品"));
            return 0;
        }
        net.minecraft.world.item.ItemStack held = sp.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.translatable("msg.dn.grid.no_held"));
            return 0;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(held.getItem());
        if (key == null) {
            source.sendFailure(Component.translatable("msg.dn.grid.no_held"));
            return 0;
        }
        String itemId = key.toString();
        if (!com.deltanexus.system.grid.ItemSizeConfig.setSize(itemId, w, h)) {
            source.sendFailure(Component.translatable("msg.dn.grid.size_invalid"));
            return 0;
        }
        ManufacturingService.broadcastGridConfig();
        source.sendSuccess(() -> Component.translatable("msg.dn.grid.size_set", itemId, w, h), true);
        return 1;
    }

    /** 移除物品自定义尺寸（恢复内置规则）。 */
    private static int gridRemove(CommandSourceStack source, String itemId) {
        if (!com.deltanexus.system.grid.ItemSizeConfig.removeSize(itemId.trim())) {
            source.sendFailure(Component.translatable("msg.dn.grid.size_not_found", itemId));
            return 0;
        }
        ManufacturingService.broadcastGridConfig();
        source.sendSuccess(() -> Component.translatable("msg.dn.grid.size_removed", itemId.trim()), true);
        return 1;
    }

    /** 设置快捷栏规则（格式 '起始-结束:模式,...'，同步客户端）。 */
    private static int gridHotbar(CommandSourceStack source, String rules) {
        if (!com.deltanexus.system.grid.GridConfig.setRules(rules)) {
            source.sendFailure(Component.translatable("msg.dn.grid.hotbar_invalid"));
            return 0;
        }
        ManufacturingService.broadcastGridConfig();
        source.sendSuccess(() -> Component.translatable("msg.dn.grid.hotbar_set", rules.trim()), true);
        return 1;
    }

    /** 查看物品「类」配置（类颜色 + 物品归属）。 */
    private static int gridClassList(CommandSourceStack source) {
        var classes = com.deltanexus.system.grid.GridClassConfig.allClasses();
        var items = com.deltanexus.system.grid.GridClassConfig.allItemClasses();
        StringBuilder sb = new StringBuilder("§e=== 物品类配置 ===§r\n");
        if (classes.isEmpty()) {
            sb.append("§7无类，用 /dn class set <类名> <R> <G> <B> 添加§r\n");
        } else {
            sb.append("§a类§r:\n");
            for (java.util.Map.Entry<String, int[]> e : classes.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" -> RGB(")
                        .append(e.getValue()[0]).append(",").append(e.getValue()[1]).append(",")
                        .append(e.getValue()[2]).append(")\n");
            }
        }
        if (items.isEmpty()) {
            sb.append("§7物品归属：无，使用默认灰色背景§r");
        } else {
            sb.append("§a物品归属§r:\n");
            for (java.util.Map.Entry<String, String> e : items.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" -> ").append(e.getValue()).append("\n");
            }
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    /** 设置类颜色（不存在则创建）。 */
    private static int gridClassSet(CommandSourceStack source, String name, int r, int g, int b) {
        if (!com.deltanexus.system.grid.GridClassConfig.setClass(name, r, g, b)) {
            source.sendFailure(Component.translatable("msg.dn.class.invalid"));
            return 0;
        }
        ManufacturingService.broadcastGridConfig();
        source.sendSuccess(() -> Component.translatable("msg.dn.class.set", name, r, g, b), true);
        return 1;
    }

    /** 删除类（物品归属一并清除）。 */
    private static int gridClassRemove(CommandSourceStack source, String name) {
        if (!com.deltanexus.system.grid.GridClassConfig.removeClass(name)) {
            source.sendFailure(Component.translatable("msg.dn.class.not_found", name));
            return 0;
        }
        ManufacturingService.broadcastGridConfig();
        source.sendSuccess(() -> Component.translatable("msg.dn.class.removed", name), true);
        return 1;
    }

    /** 设置物品所属类（类必须存在）。 */
    private static int gridSetClass(CommandSourceStack source, String itemId, String name) {
        if (!com.deltanexus.system.grid.GridClassConfig.setItemClass(itemId, name)) {
            source.sendFailure(Component.translatable("msg.dn.class.setclass_invalid", itemId, name));
            return 0;
        }
        ManufacturingService.broadcastGridConfig();
        source.sendSuccess(() -> Component.translatable("msg.dn.class.setclass_done", itemId, name), true);
        return 1;
    }

    /** 移除物品所属类。 */
    private static int gridUnsetClass(CommandSourceStack source, String itemId) {
        if (!com.deltanexus.system.grid.GridClassConfig.unsetItemClass(itemId)) {
            source.sendFailure(Component.translatable("msg.dn.grid.size_not_found", itemId));
            return 0;
        }
        ManufacturingService.broadcastGridConfig();
        source.sendSuccess(() -> Component.translatable("msg.dn.class.unsetclass_done", itemId), true);
        return 1;
    }

    private static int helpClass(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.class"), false);
        return 1;
    }

    private static int helpGrid(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("msg.dn.help.grid"), false);
        return 1;
    }

    /**
     * 服务器启动检测：外部编辑的升级树解锁容量超过配置行数容量（行数 x 9）时，
     * 自动扩容仓库行数（配置加载完成后触发，保证 ModConfig 可写）。
     */
    @SubscribeEvent
    public static void onServerStarting(net.minecraftforge.event.server.ServerStartingEvent event) {
        // 启动横幅（2.0.7Alpha：版本动态读取 mods.toml，替代硬编码；分节排版便于日志检索）
        String ver = modVersion();
        DeltaNexus.LOGGER.info("[DN] ============================================");
        DeltaNexus.LOGGER.info("[DN]   三角联结 DeltaNexus v{}", ver);
        DeltaNexus.LOGGER.info("[DN]   Minecraft 1.20.1 / Forge 47.4.x / 协议 {}", com.deltanexus.system.network.PacketHandler.PROTOCOL);
        DeltaNexus.LOGGER.info("[DN] ============================================");
        // 2.0.10Alpha：SERVER 类型旧配置（<world>/serverconfig/ModConfig.toml）逐键迁移至全局
        // config/deltanexus/ModConfig.toml（COMMON），旧文件改名 .migrated 保留
        ModConfig.migrateLegacyServerConfig(event.getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("serverconfig"));
        if (ManufacturingService.autoExpandRows()) {
            DeltaNexus.LOGGER.info("[DN] 升级树解锁容量 {} 格超过配置容量，仓库行数自动扩容至 {} 行，总容量 {} 格",
                    UpgradeConfig.get().maxUnlockSlots(), ModConfig.warehouseRows(),
                    ModConfig.warehouseRows() * WarehouseMenu.WAREHOUSE_COLS);
        }
    }
}
