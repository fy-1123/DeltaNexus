package com.deltanexus.system.server;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.common.NbtMatcher;
import com.deltanexus.system.network.packet.SyncTradeCatalogPacket;
import com.deltanexus.system.trade.FeedSnapshot;
import com.deltanexus.system.trade.ItemSpec;
import com.deltanexus.system.trade.MarketFeed;
import com.deltanexus.system.trade.PricePolicy;
import com.deltanexus.system.trade.TradeCategory;
import com.deltanexus.system.trade.TradeConfig;
import com.deltanexus.system.trade.TradeFeedRegistry;
import com.deltanexus.system.trade.TradeGood;
import com.deltanexus.system.trade.TradeStockStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * /dn trade 管理指令（0.2.0Beta）：交易行分类/商品/价格/库存上下限/补货/外部源管理。
 *
 * <p>约定：改定义（分类/商品/价格/上下限）后立即落盘并重推目录给在线玩家；
 * 抓取物品一律用“主手物品含 NBT”，录入 item + NBT 模板（与 /dn recipe addinput 同风格）。</p>
 *
 * <p>价格模式指令：{@code fixed <值>} / {@code formula <表达式>} / {@code unset}；
 * code（自定义脚本）体量大且含换行，请走 Web 编辑器或直接编辑 trade.json（文档说明）。</p>
 */
public final class TradeAdminHandler {

    private TradeAdminHandler() {
    }

    // ------------------------------------------------------------------
    // 注册（由 CommandDN 在主链中挂接 /dn trade）
    // ------------------------------------------------------------------

    public static LiteralArgumentBuilder<CommandSourceStack> tradeNode() {
        return Commands.literal("trade")
                .requires(s -> s.hasPermission(2))
                // help
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                // 列表 / 详情 / 重载
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("get")
                        .then(Commands.argument("good", StringArgumentType.word()).suggests(GOOD_IDS)
                                .executes(ctx -> get(ctx.getSource(), StringArgumentType.getString(ctx, "good")))))
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(4))
                        .executes(ctx -> reload(ctx.getSource())))
                // 分类
                .then(Commands.literal("cat").then(Commands.literal("list").executes(ctx -> catList(ctx.getSource())))
                        .then(Commands.literal("add")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> catAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("rename")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(CATEGORY_IDS)
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> catRename(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(CATEGORY_IDS)
                                        .executes(ctx -> catRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"))))))
                // 商品
                .then(Commands.literal("good")
                        .then(Commands.literal("add")
                                .then(Commands.argument("category", StringArgumentType.word()).suggests(CATEGORY_IDS)
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(ctx -> goodAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "category"),
                                                        StringArgumentType.getString(ctx, "id"), null))
                                                .then(Commands.argument("display", StringArgumentType.greedyString())
                                                        .executes(ctx -> goodAdd(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "category"),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "display")))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .executes(ctx -> goodRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("rename")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .then(Commands.argument("display", StringArgumentType.greedyString())
                                                .executes(ctx -> goodRename(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "display"))))))
                        .then(Commands.literal("move")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .then(Commands.argument("category", StringArgumentType.word()).suggests(CATEGORY_IDS)
                                                .executes(ctx -> goodMove(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "category"))))))
                        .then(Commands.literal("enable")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> goodEnable(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        BoolArgumentType.getBool(ctx, "value"))))))
                        .then(Commands.literal("buyable")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> goodBuyable(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        BoolArgumentType.getBool(ctx, "value")))))))
                // 规格（主手物品含 NBT）
                .then(Commands.literal("item")
                        .then(Commands.literal("fromhand")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .executes(ctx -> itemFromHand(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("unit")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .executes(ctx -> itemUnit(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("mode")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .suggests(MATCH_MODES)
                                                .executes(ctx -> itemMode(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "mode"))))))
                        .then(Commands.literal("key")
                                .then(Commands.literal("list")
                                        .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                                .executes(ctx -> keyList(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id")))))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                                .then(Commands.argument("key", StringArgumentType.word())
                                                        .executes(ctx -> keyAdd(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "key"), null, null))
                                                        .then(Commands.argument("op", StringArgumentType.word())
                                                                .suggests(KEY_OPS)
                                                                .executes(ctx -> keyAdd(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "id"),
                                                                        StringArgumentType.getString(ctx, "key"),
                                                                        StringArgumentType.getString(ctx, "op"), null))
                                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                                        .executes(ctx -> keyAdd(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "id"),
                                                                                StringArgumentType.getString(ctx, "key"),
                                                                                StringArgumentType.getString(ctx, "op"),
                                                                                StringArgumentType.getString(ctx, "value"))))))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                                .then(Commands.argument("key", StringArgumentType.word())
                                                        .executes(ctx -> keyRemove(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "key")))))))
                        .then(Commands.literal("durability")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .then(Commands.argument("state", BoolArgumentType.bool())
                                                .executes(ctx -> durability(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        BoolArgumentType.getBool(ctx, "state"), null, 0))
                                                .then(Commands.argument("op", StringArgumentType.word())
                                                        .suggests(DURABILITY_OPS)
                                                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                                                .executes(ctx -> durability(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "id"),
                                                                        BoolArgumentType.getBool(ctx, "state"),
                                                                        StringArgumentType.getString(ctx, "op"),
                                                                        IntegerArgumentType.getInteger(ctx, "value")))))))))
                // 价格（三方向）
                .then(Commands.literal("price")
                        .then(Commands.literal("show")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .executes(ctx -> priceShow(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("fixed")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .then(Commands.argument("dir", StringArgumentType.word()).suggests(DIRECTIONS)
                                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
                                                        .executes(ctx -> priceFixed(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "dir"),
                                                                DoubleArgumentType.getDouble(ctx, "value")))))))
                        .then(Commands.literal("formula")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .then(Commands.argument("dir", StringArgumentType.word()).suggests(DIRECTIONS)
                                                .then(Commands.argument("expr", StringArgumentType.greedyString())
                                                        .executes(ctx -> priceFormula(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "dir"),
                                                                StringArgumentType.getString(ctx, "expr")))))))
                        .then(Commands.literal("unset")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                        .then(Commands.argument("dir", StringArgumentType.word()).suggests(DIRECTIONS)
                                                .executes(ctx -> priceUnset(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "dir")))))))
                // 库存上下限 / 补货
                .then(Commands.literal("limits")
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                .then(Commands.argument("min", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("max", IntegerArgumentType.integer(0))
                                                .executes(ctx -> limits(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "min"),
                                                        IntegerArgumentType.getInteger(ctx, "max"), true))
                                                .then(Commands.argument("block", BoolArgumentType.bool())
                                                        .executes(ctx -> limits(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                IntegerArgumentType.getInteger(ctx, "min"),
                                                                IntegerArgumentType.getInteger(ctx, "max"),
                                                                BoolArgumentType.getBool(ctx, "block"))))))))
                .then(Commands.literal("stock")
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(GOOD_IDS)
                                .executes(ctx -> stockShow(ctx.getSource(), StringArgumentType.getString(ctx, "id")))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .executes(ctx -> stockAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "count")))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("count", IntegerArgumentType.integer(0))
                                                .executes(ctx -> stockSet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "count")))))))
                // 外部源
                .then(Commands.literal("feed")
                        .then(Commands.literal("list").executes(ctx -> feedList(ctx.getSource())))
                        .then(Commands.literal("refresh").executes(ctx -> feedRefresh(ctx.getSource()))))
                // 全局设置
                .then(Commands.literal("setting")
                        .then(Commands.literal("get").executes(ctx -> settingGet(ctx.getSource())))
                        .then(Commands.literal("multiplier")
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.01D))
                                        .executes(ctx -> settingMultiplier(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "value")))))
                        .then(Commands.literal("feed_interval")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                        .executes(ctx -> settingFeedInterval(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "seconds")))))
                        .then(Commands.literal("timeout")
                                .then(Commands.argument("millis", IntegerArgumentType.integer(1, 1000))
                                        .executes(ctx -> settingTimeout(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "millis")))))
                        .then(Commands.literal("sell_enabled")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> settingSellEnabled(ctx.getSource(),
                                                BoolArgumentType.getBool(ctx, "value")))))
                        .then(Commands.literal("spread_guard")
                                .then(Commands.argument("mode", StringArgumentType.word()).suggests(SPREAD_GUARDS)
                                        .executes(ctx -> settingSpreadGuard(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mode"))))));
    }

    // ------------------------------------------------------------------
    // 补全
    // ------------------------------------------------------------------

    private static final SuggestionProvider<CommandSourceStack> CATEGORY_IDS =
            (ctx, builder) -> {
                for (TradeCategory c : TradeConfig.get().categoriesSnapshot()) {
                    builder.suggest(c.id);
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> GOOD_IDS =
            (ctx, builder) -> {
                for (TradeGood g : TradeConfig.get().goodsSnapshot()) {
                    builder.suggest(g.id);
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> MATCH_MODES =
            (ctx, builder) -> {
                builder.suggest("id");
                builder.suggest("full_nbt");
                builder.suggest("partial_nbt");
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> KEY_OPS =
            (ctx, builder) -> {
                builder.suggest("exact");
                builder.suggest("contains");
                builder.suggest("specified", Component.literal("指定键名与对应值"));
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> DURABILITY_OPS =
            (ctx, builder) -> {
                builder.suggest("=");
                builder.suggest("<");
                builder.suggest(">");
                builder.suggest("<=");
                builder.suggest(">=");
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> DIRECTIONS =
            (ctx, builder) -> {
                builder.suggest("buy");
                builder.suggest("sell");
                builder.suggest("market");
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> SPREAD_GUARDS =
            (ctx, builder) -> {
                builder.suggest("warn");
                builder.suggest("block");
                builder.suggest("off");
                return builder.buildFuture();
            };

    // ------------------------------------------------------------------
    // 处理
    // ------------------------------------------------------------------

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("""
                §e/dn trade§r 交易行管理（权限 2）：
                  §a/dn trade list§r 交易行总览
                  §a/dn trade get <商品id>§r 查看商品详情
                  §a/dn trade reload§r 热加载交易行配置
                  §a/dn trade cat list|add <id> <名>|rename <id> <名>|remove <id>§r 分类管理
                  §a/dn trade good add <分类> <id> [显示名]§r 用主手物品上架（含 NBT）
                  §a/dn trade good remove|rename|move|enable|buyable <…>§r 商品管理
                  §a/dn trade item fromhand|unit|mode <…>§r 规格 / 单位 / 匹配模式
                  §a/dn trade item key list|add <id> <键> [exact|contains|specified] [值]|remove <…>§r 指定键规则（specified=指定键名与值）
                  §a/dn trade item durability <id> <on|off> [op] [值]§r 耐久度要求（= / < / > / <= / >=，默认关）
                  §a/dn trade price <id> <buy|sell|market> fixed|formula|unset <…>§r 价格策略（code 用 Web）
                  §a/dn trade limits <id> <min> <max> [block]§r 库存上下限
                  §a/dn trade stock <id> add|set <n>§r 补货
                  §a/dn trade feed list|refresh§r 外部价格源
                  §a/dn trade setting get|multiplier|feed_interval|timeout|sell_enabled|spread_guard§r 全局设置
                  §7玩家侧：仓库界面点「出售」→ 多选物品 → 「确认」卖出（服务端权威结算）§r"""), false);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        TradeConfig cfg = TradeConfig.get();
        StringBuilder sb = new StringBuilder("§e=== 交易行 ===§r\n");
        sb.append("§a分类§r: ");
        if (cfg.categoriesSnapshot().isEmpty()) {
            sb.append("（空）\n");
        } else {
            for (TradeCategory c : cfg.categoriesSnapshot()) {
                sb.append(c.id).append('(').append(c.displayName()).append(") ");
            }
            sb.append('\n');
        }
        sb.append("§a商品 %s 个§r:\n".formatted(cfg.goodsSnapshot().size()));
        if (!cfg.goodsSnapshot().isEmpty()) {
            for (TradeGood g : cfg.goodsSnapshot()) {
                int stock = TradeStockStore.get(g.id);
                String state = !g.enabled ? "§7[停用]§r"
                        : !g.buyable ? "§7[禁买]§r"
                        : stock <= 0 ? "§c[缺货]§r"
                        : g.stockMin > 0 && stock <= g.stockMin && g.blockBelowMin ? "§e[低库存]§r" : "§a[在售]§r";
                sb.append("  ").append(state).append(' ')
                        .append(g.id).append(" §7(").append(g.displayName()).append(")§r ")
                        .append("分类=").append(g.categoryId)
                        .append(" 库存=").append(stock)
                        .append(" 限=").append(g.stockMin).append('/').append(g.stockMax)
                        .append(" 规格=").append(g.spec.item).append('/').append(g.spec.matchMode.key);
                PricePolicy bp = g.buyPolicy();
                if (bp != null) {
                    sb.append(" 买入[").append(bp.describe()).append(']');
                }
                if (g.sell != null && g.sell.isValid()) {
                    sb.append(" 卖出[").append(g.sell.describe()).append(']');
                }
                sb.append('\n');
            }
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    private static TradeGood requireGood(CommandSourceStack source, String id) throws CommandSyntaxException {
        TradeGood g = TradeConfig.get().good(id);
        if (g == null) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.literal("商品 " + id + " 不存在，请先 /dn trade good add")).create();
        }
        return g;
    }

    private static void commit(CommandSourceStack source, String ok) {
        TradeConfig.get().saveDebounced();
        TradeService.sendSyncToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(ok), true);
    }

    // ---- 分类 ----

    private static int catList(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("§e交易行分类§r:\n");
        for (TradeCategory c : TradeConfig.get().categoriesSnapshot()) {
            sb.append("  ").append(c.id).append(" §7(").append(c.displayName()).append(")§r\n");
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    private static int catAdd(CommandSourceStack source, String id, String name) {
        if (TradeConfig.get().hasCategory(id)) {
            source.sendFailure(Component.literal("分类 " + id + " 已存在"));
            return 0;
        }
        TradeConfig.get().categoriesMutable().put(id, new TradeCategory(id, name.trim()));
        commit(source, "已新增分类 " + id + "（" + name.trim() + "）");
        return 1;
    }

    private static int catRename(CommandSourceStack source, String id, String name) {
        TradeCategory c = TradeConfig.get().category(id);
        if (c == null) {
            source.sendFailure(Component.literal("分类 " + id + " 不存在"));
            return 0;
        }
        c.name = name.trim();
        commit(source, "分类 " + id + " 已重命名为 " + name.trim());
        return 1;
    }

    private static int catRemove(CommandSourceStack source, String id) {
        if (!TradeConfig.get().hasCategory(id)) {
            source.sendFailure(Component.literal("分类 " + id + " 不存在"));
            return 0;
        }
        int count = TradeConfig.get().categoriesMutable().remove(id) != null ? 1 : 0;
        if (count == 0) {
            return 0;
        }
        // 分类下商品保留但归类悬空（UI 不显示，管理员自行 move / remove）
        TradeConfig.get().saveDebounced();
        TradeService.sendSyncToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("已删除分类 " + id), true);
        return 1;
    }

    // ---- 商品 ----

    private static int goodAdd(CommandSourceStack source, String category, String id, String display)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!TradeConfig.get().hasCategory(category)) {
            source.sendFailure(Component.literal("分类 " + category + " 不存在"));
            return 0;
        }
        if (TradeConfig.get().hasGood(id)) {
            source.sendFailure(Component.literal("商品 " + id + " 已存在"));
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("请手持要上架的商品（含 NBT 模板）"));
            return 0;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(held.getItem());
        if (key == null) {
            source.sendFailure(Component.literal("无法识别主手物品"));
            return 0;
        }
        TradeGood g = new TradeGood();
        g.id = id;
        g.categoryId = category;
        g.spec.item = key.toString();
        g.spec.nbt = held.getTag() == null || held.getTag().isEmpty() ? "" : held.getTag().toString();
        g.spec.unitCount = 1;
        g.spec.matchMode = ItemSpec.MatchMode.ID;
        g.buyable = true;
        g.enabled = true;
        g.displayName = display == null || display.isBlank() ? g.displayName() : display.trim();
        TradeConfig.get().goodsMutable().put(id, g);
        commit(source, "已上架商品 " + id + "（" + g.spec.item + "），请继续配置价格与库存");
        return 1;
    }

    private static int goodRemove(CommandSourceStack source, String id) throws CommandSyntaxException {
        requireGood(source, id);
        TradeConfig.get().goodsMutable().remove(id);
        commit(source, "已下架并删除商品 " + id);
        return 1;
    }

    private static int goodRename(CommandSourceStack source, String id, String display) throws CommandSyntaxException {
        requireGood(source, id).displayName = display.trim();
        commit(source, "商品 " + id + " 显示名已设为 " + display.trim());
        return 1;
    }

    private static int goodMove(CommandSourceStack source, String id, String category) throws CommandSyntaxException {
        if (!TradeConfig.get().hasCategory(category)) {
            source.sendFailure(Component.literal("分类 " + category + " 不存在"));
            return 0;
        }
        requireGood(source, id).categoryId = category;
        commit(source, "商品 " + id + " 已移到分类 " + category);
        return 1;
    }

    private static int goodEnable(CommandSourceStack source, String id, boolean value) throws CommandSyntaxException {
        requireGood(source, id).enabled = value;
        commit(source, "商品 " + id + (value ? " 已上架" : " 已下架（停用）"));
        return 1;
    }

    private static int goodBuyable(CommandSourceStack source, String id, boolean value) throws CommandSyntaxException {
        requireGood(source, id).buyable = value;
        commit(source, "商品 " + id + (value ? " 已允许买入" : " 已禁止买入"));
        return 1;
    }

    // ---- 规格 ----

    private static int itemFromHand(CommandSourceStack source, String id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        TradeGood g = requireGood(source, id);
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("请手持物品以重新抓取 NBT 模板"));
            return 0;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(held.getItem());
        if (key == null) {
            source.sendFailure(Component.literal("无法识别主手物品"));
            return 0;
        }
        g.spec.item = key.toString();
        g.spec.nbt = held.getTag() == null || held.getTag().isEmpty() ? "" : held.getTag().toString();
        commit(source, "商品 " + id + " 规格已按主手物品更新（" + g.spec.item + "）");
        return 1;
    }

    private static int itemUnit(CommandSourceStack source, String id, int count) throws CommandSyntaxException {
        requireGood(source, id).spec.unitCount = count;
        commit(source, "商品 " + id + " 单次交易单位含物品数设为 " + count);
        return 1;
    }

    private static int itemMode(CommandSourceStack source, String id, String mode) throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        ItemSpec.MatchMode m = ItemSpec.MatchMode.parse(mode);
        g.spec.matchMode = m;
        commit(source, "商品 " + id + " 匹配模式设为 " + m.key);
        return 1;
    }

    private static int keyList(CommandSourceStack source, String id) throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        StringBuilder sb = new StringBuilder("§e商品 " + id + " partial 键§r:\n");
        if (g.spec.matchKeys.isEmpty()) {
            sb.append("  （空，匹配模式为 ").append(g.spec.matchMode.key).append("）");
        } else {
            for (java.util.Map.Entry<String, ItemSpec.KeyRule> e : g.spec.matchKeys.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue().describe()).append('\n');
            }
        }
        if (g.spec.durabilityEnabled) {
            sb.append("  耐久要求: ").append(g.spec.durabilityOp).append(' ').append(g.spec.durabilityValue);
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    private static int keyAdd(CommandSourceStack source, String id, String key, String op, String value)
            throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        ItemSpec.KeyOp parsed = op == null ? ItemSpec.KeyOp.EXACT : ItemSpec.KeyOp.parse(op);
        if (ItemSpec.KeyOp.isLegacyIgnore(op)) {
            source.sendFailure(Component.literal("ignore 已弃用：请用 exact / contains / specified（指定键值）"));
            return 0;
        }
        if (parsed == ItemSpec.KeyOp.SPECIFIED && (value == null || value.isBlank())) {
            source.sendFailure(Component.literal("specified（指定）需要同时给出值，例如：/dn trade item key add <id> <键> specified 0"));
            return 0;
        }
        g.spec.matchKeys.put(key, new ItemSpec.KeyRule(parsed, value == null ? "" : value));
        if (g.spec.matchMode != ItemSpec.MatchMode.PARTIAL_NBT) {
            g.spec.matchMode = ItemSpec.MatchMode.PARTIAL_NBT;
        }
        commit(source, "商品 " + id + " 指定键 " + key + " 规则设为 "
                + parsed.key + (value == null || value.isBlank() ? "" : "=" + value) + "（已切 partial_nbt）");
        return 1;
    }

    /** 耐久度要求（可与匹配模式共存；= / < / > / <= / >= 对剩余耐久）。 */
    private static int durability(CommandSourceStack source, String id, boolean enabled,
                                  String op, int value) throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        if (enabled && op != null) {
            String o = op.trim();
            if (!o.equals("=") && !o.equals("<") && !o.equals(">") && !o.equals("<=") && !o.equals(">=")) {
                source.sendFailure(Component.literal("耐久运算符仅支持 = / < / > / <= / >="));
                return 0;
            }
            g.spec.durabilityOp = o;
            g.spec.durabilityValue = value;
        }
        g.spec.durabilityEnabled = enabled;
        commit(source, "商品 " + id + " 耐久度要求已" + (enabled ? "开启" : "关闭")
                + (enabled ? "（" + g.spec.durabilityOp + " " + g.spec.durabilityValue + "）" : ""));
        return 1;
    }

    private static int keyRemove(CommandSourceStack source, String id, String key) throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        if (g.spec.matchKeys.remove(key) == null) {
            source.sendFailure(Component.literal("商品 " + id + " 没有指定键 " + key));
            return 0;
        }
        commit(source, "已移除商品 " + id + " 的指定键 " + key);
        return 1;
    }

    // ---- 价格 ----

    private static int priceFixed(CommandSourceStack source, String id, String dir, double value)
            throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        setPolicy(g, dir, policyFixed(value));
        commit(source, "商品 " + id + " " + dir + " 价设为固定 " + (long) value);
        return 1;
    }

    private static int priceFormula(CommandSourceStack source, String id, String dir, String expr)
            throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        if (!expr.contains("(")) {
            source.sendFailure(Component.literal("公式需为单行 JS 表达式（如 floor(feed('moligod').price('1029'))）"));
            return 0;
        }
        PricePolicy p = new PricePolicy(PricePolicy.Mode.FORMULA);
        p.expr = expr;
        setPolicy(g, dir, p);
        commit(source, "商品 " + id + " " + dir + " 价设为公式: " + expr);
        return 1;
    }

    private static int priceUnset(CommandSourceStack source, String id, String dir) throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        setPolicy(g, dir, null);
        commit(source, "已清除商品 " + id + " 的 " + dir + " 价策略");
        return 1;
    }

    private static void setPolicy(TradeGood g, String dir, PricePolicy p) {
        switch (dir) {
            case "sell" -> g.sell = p;
            case "market" -> g.market = p;
            default -> g.buy = p;
        }
    }

    private static PricePolicy policyFixed(double value) {
        PricePolicy p = new PricePolicy(PricePolicy.Mode.FIXED);
        p.value = value;
        return p;
    }

    private static int priceShow(CommandSourceStack source, String id) throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        SyncTradeCatalogPacket catalog = TradeService.buildCatalog();
        SyncTradeCatalogPacket.Good view = null;
        for (SyncTradeCatalogPacket.Good cg : catalog.goods) {
            if (cg.id.equals(id)) {
                view = cg;
                break;
            }
        }
        StringBuilder sb = new StringBuilder("§e商品 " + id + " 价格§r\n");
        if (view == null) {
            sb.append("  未在可交易列表中（可能未启用/缺买入策略）");
        } else {
            String buy = view.buyCode == 0 ? String.valueOf(view.buyPrice)
                    : switch (view.buyCode) {
                case 1 -> "缺货";
                case 2 -> "低于下限(限购0)";
                case 3 -> "定价不可用";
                default -> "不可购买";
            };
            sb.append("  买入价: ").append(buy).append('\n');
            sb.append("  卖出价: ").append(view.sellCode == 0 ? view.sellPrice : "—").append('\n');
            sb.append("  市场价: ").append(view.marketCode == 0 ? view.marketPrice : "—").append('\n');
            sb.append("  可购上限: ").append(view.buyLimit).append("（库存 ")
                    .append(view.stock).append("）\n");
        }
        sb.append("§7策略配置§r: ").append(g.buyPolicy() == null ? "无买入" : g.buyPolicy().describe());
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    // ---- 库存上下限 / 补货 ----

    private static int limits(CommandSourceStack source, String id, int min, int max, boolean block)
            throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        if (max > 0 && min > max) {
            source.sendFailure(Component.literal("下限不能大于上限"));
            return 0;
        }
        g.stockMin = min;
        g.stockMax = max;
        g.blockBelowMin = block;
        commit(source, "商品 " + id + " 库存上下限设为 min=" + min + " max=" + max
                + "，缺货阻断=" + block);
        return 1;
    }

    private static int stockShow(CommandSourceStack source, String id) throws CommandSyntaxException {
        TradeGood g = requireGood(source, id);
        int stock = TradeStockStore.get(id);
        source.sendSuccess(() -> Component.literal("商品 " + id + " 库存: " + stock
                + "（下限 " + g.stockMin + " 上限 " + g.stockMax
                + "，低于下限阻断=" + g.blockBelowMin + "）"), false);
        return 1;
    }

    private static int stockAdd(CommandSourceStack source, String id, int count) throws CommandSyntaxException {
        requireGood(source, id);
        int after = TradeStockStore.add(id, count);
        TradeService.sendSyncToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("商品 " + id + " 已补货 +" + count + "，当前 " + after), true);
        return 1;
    }

    private static int stockSet(CommandSourceStack source, String id, int count) throws CommandSyntaxException {
        requireGood(source, id);
        int after = TradeStockStore.set(id, count);
        TradeService.sendSyncToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("商品 " + id + " 库存已设为 " + after), true);
        return 1;
    }

    // ---- 外部源 ----

    private static int feedList(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("§e交易行外部价格源§r:\n");
        if (TradeFeedRegistry.isEmpty()) {
            sb.append("  §7无已注册源（可安装外部 companion mod 或直接使用 fixed/formula）§r");
        } else {
            for (MarketFeed feed : TradeFeedRegistry.all()) {
                FeedSnapshot snap = feed.snapshot();
                sb.append("  §a").append(feed.id()).append("§r ").append(feed.description())
                        .append(" | 行数 ").append(snap.size())
                        .append(" | 生成 ").append(snap.generatedAtEpochMs() == 0 ? "未知"
                                : java.time.Instant.ofEpochMilli(snap.generatedAtEpochMs()).toString())
                        .append('\n');
            }
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    private static int feedRefresh(CommandSourceStack source) {
        int n = 0;
        for (MarketFeed feed : TradeFeedRegistry.all()) {
            try {
                feed.refreshIfStale(0);
                n++;
            } catch (Exception e) {
                DeltaNexus.LOGGER.warn("[DN] 交易行源 '{}' 手动刷新异常: {}", feed.id(), e.getMessage());
            }
        }
        final int refreshed = n;
        source.sendSuccess(() -> Component.literal("已请求刷新 " + refreshed
                + " 个外部源，快照就绪后随目录下发"), true);
        return 1;
    }

    // ---- 全局设置 ----

    private static int settingGet(CommandSourceStack source) {
        TradeConfig.Settings s = TradeConfig.get().settings();
        source.sendSuccess(() -> Component.literal("§e交易行全局设置§r\n 倍率 " + s.multiplier
                + " / feed 刷新 " + s.feedRefreshIntervalS + "s / JS 超时 " + s.evalTimeoutMs + "ms"
                + " / 回收 " + (s.sellEnabled ? "开" : "关") + " / 价差保护 " + s.sellSpreadGuard
                + "\n 分类 " + TradeConfig.get().categoriesSnapshot().size()
                + " / 商品 " + TradeConfig.get().goodsSnapshot().size()), false);
        return 1;
    }

    private static int settingSellEnabled(CommandSourceStack source, boolean value) {
        TradeConfig.get().settings().sellEnabled = value;
        commit(source, "交易行回收功能已" + (value ? "开启" : "关闭"));
        return 1;
    }

    private static int settingSpreadGuard(CommandSourceStack source, String mode) {
        String m = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!m.equals("warn") && !m.equals("block") && !m.equals("off")) {
            source.sendFailure(Component.literal("spread_guard 仅支持 warn / block / off"));
            return 0;
        }
        TradeConfig.get().settings().sellSpreadGuard = m;
        commit(source, "交易行价差保护已设为 " + m);
        return 1;
    }

    private static int settingMultiplier(CommandSourceStack source, double value) {
        TradeConfig.get().settings().multiplier = value;
        commit(source, "交易行全局倍率已设为 " + value);
        return 1;
    }

    private static int settingFeedInterval(CommandSourceStack source, int seconds) {
        TradeConfig.get().settings().feedRefreshIntervalS = seconds;
        commit(source, "交易行外部源刷新间隔已设为 " + seconds + " 秒");
        return 1;
    }

    private static int settingTimeout(CommandSourceStack source, int millis) {
        TradeConfig.get().settings().evalTimeoutMs = millis;
        commit(source, "交易行公式求值超时已设为 " + millis + " ms");
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        TradeConfig.get().reload();
        TradeStockStore.load();
        TradeService.sendSyncToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("交易行配置已热加载：分类 "
                + TradeConfig.get().categoriesSnapshot().size() + " / 商品 "
                + TradeConfig.get().goodsSnapshot().size()), true);
        return 1;
    }

    private static int get(CommandSourceStack source, String id) {
        TradeConfig cfg = TradeConfig.get();
        TradeGood g = cfg.good(id);
        if (g == null) {
            source.sendFailure(Component.literal("商品 " + id + " 不存在"));
            return 0;
        }
        StringBuilder sb = new StringBuilder("§e商品 " + id + "§r\n");
        sb.append("  分类: ").append(g.categoryId).append(" / 显示名: ").append(g.displayName()).append('\n');
        sb.append("  上架: ").append(g.enabled).append(" / 可买入: ").append(g.buyable).append('\n');
        sb.append("  物品: ").append(g.spec.item)
                .append(" / 模式: ").append(g.spec.matchMode.key)
                .append(" / unit: ").append(g.spec.unitCount).append('\n');
        if (!g.spec.nbt.isBlank()) {
            sb.append("  NBT: ").append(g.spec.nbt).append('\n');
        }
        if (!g.spec.matchKeys.isEmpty()) {
            StringBuilder kb = new StringBuilder();
            g.spec.matchKeys.forEach((k, r) -> {
                if (kb.length() > 0) {
                    kb.append(", ");
                }
                kb.append(k).append('=').append(r.describe());
            });
            sb.append("  键: ").append(kb).append('\n');
        }
        if (g.spec.durabilityEnabled) {
            sb.append("  耐久要求: ").append(g.spec.durabilityOp).append(' ')
                    .append(g.spec.durabilityValue).append("（剩余耐久）\n");
        }
        sb.append("  库存: ").append(TradeStockStore.get(id))
                .append(" / 上下限: ").append(g.stockMin).append('/').append(g.stockMax)
                .append(" / 阻断: ").append(g.blockBelowMin).append('\n');
        if (g.buy != null) {
            sb.append("  买入价: ").append(g.buy.describe()).append('\n');
        }
        if (g.sell != null) {
            sb.append("  卖出价: ").append(g.sell.describe()).append('\n');
        }
        if (g.market != null) {
            sb.append("  市场价: ").append(g.market.describe()).append('\n');
        }
        String issue = g.validate(cfg);
        sb.append(issue == null ? "  §a校验通过§r" : "  §c校验: " + issue + "§r");
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }
}
