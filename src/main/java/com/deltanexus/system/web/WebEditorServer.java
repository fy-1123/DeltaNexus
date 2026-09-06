package com.deltanexus.system.web;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.common.NbtMatcher;
import com.deltanexus.system.common.WorkbenchRegistry;
import com.deltanexus.system.config.JsonConfigWriter;
import com.deltanexus.system.config.ModConfig;
import com.deltanexus.system.config.Recipe;
import com.deltanexus.system.config.RecipeCache;
import com.deltanexus.system.config.UpgradeConfig;
import com.deltanexus.system.server.CurrencyManager;
import com.deltanexus.system.server.ManufacturingService;
import com.deltanexus.system.server.PermissionManager;
import com.deltanexus.system.config.SafeBoxRestrictions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Web 网页编辑器（嵌入式 HTTP 服务，JDK 自带 com.sun.net.httpserver，无新增依赖）。
 *
 * <p>网页可完成 /dn 指令的全部管理操作（配置/工作台/配方/升级树/玩家数据/重载导出），
 * 且带 token 鉴权；网页服务自身配置（监听地址/端口/鉴权/对外地址）只能改
 * {@code config/deltanexus/web-editor.yml}，指令不可修改（安全约束）。</p>
 *
 * <p>访问令牌（token）仅存内存、不落盘：每次启动 Web 服务重新生成，
 * 服务停止或服务器重启后旧 token 立即失效。配置 {@code web-editor.enabled=true}
 * 时服务器启动后自动开启（无需再手动 /dn web on）。</p>
 *
 * <p>所有状态变更统一投递到服务器主线程执行（网络发包/配置读写线程安全）。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID)
public final class WebEditorServer {

    private static final String PAGE_PATH = "/assets/deltanexus/web/index.html";

    private static HttpServer server;
    private static ExecutorService executor;
    /**
     * 当前 MinecraftServer 实例。注意：{@code ServerLifecycleHooks.getCurrentServer()} 是
     * ThreadLocal，在 Web HTTP 工作线程上返回 null（这就是此前所有 web 操作报
     * 「服务器未运行」的根因）；必须在指令线程（start 调用处）捕获。
     */
    private static net.minecraft.server.MinecraftServer currentServer;
    /** 访问令牌（仅内存，不落盘；每次启动 Web 服务重新生成，停止/重启后失效）。 */
    private static volatile String currentToken = "";

    private WebEditorServer() {
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 启动 Web 服务。已运行则先停止再重启；<b>每次启动都会重新生成访问令牌
     * （仅内存，旧令牌立即失效；不写入任何配置文件）</b>。
     * 返回 false 表示启动失败（端口占用等）。
     */
    public static synchronized boolean start() {
        if (server != null) {
            stop();
        }
        // 在指令/服务器线程捕获服务器实例（getCurrentServer 是 ThreadLocal，HTTP 线程拿不到）
        currentServer = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        WebConfig cfg = WebConfig.get();
        // 安全：每次开启刷新令牌（仅内存，不落盘；token_auth 关闭时也统一处理）
        currentToken = randomToken();
        try {
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "dn-WebEditor");
                t.setDaemon(true);
                return t;
            });
            server = HttpServer.create(new InetSocketAddress(cfg.host(), cfg.port()), 0);
            server.setExecutor(executor);
            server.createContext("/", new PageHandler());
            server.createContext("/api", new ApiHandler());
            server.start();
            DeltaNexus.LOGGER.info("[DN] Web 网页编辑器已启动: {}，token 鉴权 {}",
                    urlWithToken(), cfg.tokenAuth() ? "开启，令牌仅存内存" : "关闭");
            return true;
        } catch (Exception e) {
            DeltaNexus.LOGGER.error("[DN] Web 网页编辑器启动失败: {}", e.toString());
            if (server != null) {
                server.stop(0);
                server = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            currentServer = null;
            currentToken = "";
            return false;
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        currentServer = null;
        currentToken = "";
        DeltaNexus.LOGGER.info("[DN] Web 网页编辑器已停止");
    }

    public static boolean isRunning() {
        return server != null;
    }

    /** 访问地址（不含 token）。public-url 配置了对外地址时优先使用，否则按 host:port 拼接。 */
    public static String url() {
        WebConfig cfg = WebConfig.get();
        String pub = cfg.publicUrl();
        if (!pub.isEmpty()) {
            return pub.endsWith("/") ? pub : pub + "/";
        }
        return "http://" + cfg.host() + ":" + cfg.port() + "/";
    }

    /** 访问地址（含 token，用于指令提示）。 */
    public static String urlWithToken() {
        WebConfig cfg = WebConfig.get();
        return cfg.tokenAuth() ? url() + "?token=" + currentToken : url();
    }

    /** 生成 32 位十六进制访问令牌（仅内存，不落盘）。 */
    private static String randomToken() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 服务器停止时关闭 Web 服务。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        stop();
    }

    // ------------------------------------------------------------------
    // HTTP 基础设施
    // ------------------------------------------------------------------

    /** 页面处理器：返回内嵌的 index.html。 */
    private static final class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (InputStream in = WebEditorServer.class.getClassLoader().getResourceAsStream(PAGE_PATH)) {
                if (in == null) {
                    byte[] notFound = "index.html not found".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, notFound.length);
                    exchange.getResponseBody().write(notFound);
                    return;
                }
                byte[] html = in.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, html.length);
                exchange.getResponseBody().write(html);
            } finally {
                exchange.close();
            }
        }
    }

    /** API 处理器：token 鉴权 + 路由分发。 */
    private static final class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!authorized(exchange)) {
                    send(exchange, 401, err("未授权：token 无效或缺失，可在地址栏追加 ?token=xxx，或使用请求头 Authorization: Bearer xxx"));
                    return;
                }
                JsonObject result = route(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), readBody(exchange));
                send(exchange, 200, result);
            } catch (Throwable t) {
                send(exchange, 500, err("服务器内部错误: " + t));
            } finally {
                exchange.close();
            }
        }
    }

    private static boolean authorized(HttpExchange exchange) {
        WebConfig cfg = WebConfig.get();
        if (!cfg.tokenAuth()) {
            return true;
        }
        String token = exchange.getRequestURI().getQuery() != null ? queryParam(exchange.getRequestURI().getQuery(), "token") : null;
        if (token == null) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                token = auth.substring(7).trim();
            }
        }
        return token != null && token.equals(currentToken);
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && pair.substring(0, idx).equals(name)) {
                return pair.substring(idx + 1);
            }
        }
        return null;
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return new JsonObject();
        }
        try {
            JsonElement el = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static void send(HttpExchange exchange, int code, JsonObject obj) throws IOException {
        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static JsonObject ok(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("msg", msg == null ? "" : msg);
        return o;
    }

    private static JsonObject err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("msg", msg == null ? "" : msg);
        return o;
    }

    private static String msg(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** 状态变更投递到服务器主线程执行（网络发包/配置读写安全）。 */
    private static JsonObject onServer(Supplier<JsonObject> action) {
        net.minecraft.server.MinecraftServer mc = currentServer;
        if (mc == null || !mc.isRunning()) {
            return err("服务器未运行");
        }
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.complete(err("异常: " + t));
            }
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return err("执行超时或中断: " + e);
        }
    }

    // ------------------------------------------------------------------
    // 路由
    // ------------------------------------------------------------------

    private static JsonObject route(String method, String path, JsonObject body) {
        if ("GET".equals(method) && path.equals("/api/overview")) {
            return onServer(WebEditorServer::overview);
        }
        if (!"POST".equals(method)) {
            return err("仅支持 GET /api/overview 与 POST 操作");
        }
        return switch (path) {
            case "/api/reload" -> onServer(() -> doReload());
            case "/api/export" -> onServer(() -> doExport());
            case "/api/workbench/add" -> onServer(() -> workbenchAdd(body));
            case "/api/workbench/remove" -> onServer(() -> workbenchRemove(body));
            case "/api/workbench/rename" -> onServer(() -> workbenchRename(body));
            case "/api/recipe/add" -> onServer(() -> recipeAdd(body));
            case "/api/recipe/remove" -> onServer(() -> recipeRemove(body));
            case "/api/recipe/rename" -> onServer(() -> recipeRename(body));
            case "/api/recipe/input/add" -> onServer(() -> recipeInputAdd(body));
            case "/api/recipe/input/remove" -> onServer(() -> recipeInputRemove(body));
            case "/api/recipe/input/update" -> onServer(() -> recipeInputUpdate(body));
            case "/api/recipe/output/add" -> onServer(() -> recipeOutputAdd(body));
            case "/api/recipe/output/remove" -> onServer(() -> recipeOutputRemove(body));
            case "/api/recipe/output/update" -> onServer(() -> recipeOutputUpdate(body));
            case "/api/recipe/time" -> onServer(() -> recipeTime(body));
            case "/api/recipe/level" -> onServer(() -> recipeLevel(body));
            case "/api/recipe/parallel" -> onServer(() -> recipeParallel(body));
            case "/api/tree/add" -> onServer(() -> treeAdd(body));
            case "/api/tree/remove" -> onServer(() -> treeRemove(body));
            case "/api/tree/cost" -> onServer(() -> treeCost(body));
            case "/api/tree/slots" -> onServer(() -> treeSlots(body));
            case "/api/tree/item/add" -> onServer(() -> treeItemAdd(body));
            case "/api/tree/item/remove" -> onServer(() -> treeItemRemove(body));
            case "/api/tree/item/update" -> onServer(() -> treeItemUpdate(body));
            case "/api/safe/tree/add" -> onServer(() -> safeTreeAdd(body));
            case "/api/safe/tree/remove" -> onServer(() -> safeTreeRemove(body));
            case "/api/safe/tree/cost" -> onServer(() -> safeTreeCost(body));
            case "/api/safe/tree/dims" -> onServer(() -> safeTreeDims(body));
            case "/api/safe/tree/item/add" -> onServer(() -> safeTreeItemAdd(body));
            case "/api/safe/tree/item/remove" -> onServer(() -> safeTreeItemRemove(body));
            case "/api/safe/tree/item/update" -> onServer(() -> safeTreeItemUpdate(body));
            case "/api/safe/restrict/add" -> onServer(() -> safeRestrictAdd(body));
            case "/api/safe/restrict/remove" -> onServer(() -> safeRestrictRemove(body));
            case "/api/setting/speed" -> onServer(() -> settingSpeed(body));
            case "/api/setting/queue" -> onServer(() -> settingQueue(body));
            case "/api/setting/mode" -> onServer(() -> settingMode(body));
            case "/api/setting/currency" -> onServer(() -> settingCurrency(body));
            case "/api/setting/warehouse/rows" -> onServer(() -> settingRows(body));
            case "/api/setting/warehouse/slots" -> onServer(() -> settingSlots(body));
            case "/api/setting/safe/size" -> onServer(() -> settingSafeSize(body));
            case "/api/grid/size/set" -> onServer(() -> gridSizeSet(body));
            case "/api/grid/size/remove" -> onServer(() -> gridSizeRemove(body));
            case "/api/grid/hotbar" -> onServer(() -> gridHotbar(body));
            case "/api/grid/class/set" -> onServer(() -> gridClassSet(body));
            case "/api/grid/class/remove" -> onServer(() -> gridClassRemove(body));
            case "/api/grid/class/setclass" -> onServer(() -> gridClassSetItem(body));
            case "/api/grid/class/unsetclass" -> onServer(() -> gridClassUnsetItem(body));
            case "/api/player/level" -> onServer(() -> playerLevel(body));
            case "/api/player/safe/level" -> onServer(() -> playerSafeLevel(body));
            case "/api/player/reset" -> onServer(() -> playerReset(body));
            case "/api/perm/set" -> onServer(() -> permSet(body));
            case "/api/perm/remove" -> onServer(() -> permRemove(body));
            case "/api/perm/default" -> onServer(() -> permDefault(body));
            case "/api/perm/op" -> onServer(() -> permOp(body));
            case "/api/perm/feature" -> onServer(() -> permFeature(body));
            default -> err("未知接口: " + path);
        };
    }

    // ------------------------------------------------------------------
    // 概览
    // ------------------------------------------------------------------

    private static JsonObject overview() {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("version", "2.2");
        root.addProperty("web_running", isRunning());
        root.addProperty("web_url", urlWithToken());

        JsonObject settings = new JsonObject();
        settings.addProperty("speed", ModConfig.timeMultiplier());
        settings.addProperty("queue", ModConfig.maxQueueSize());
        settings.addProperty("mode", ModConfig.onlineMode() ? "online" : "offline");
        settings.addProperty("currency", ModConfig.currencyType());
        settings.addProperty("currency_item", ModConfig.currencyItem());
        settings.addProperty("currency_scoreboard", ModConfig.currencyScoreboard());
        settings.addProperty("vault_api", CurrencyManager.isVaultApiPresent());
        settings.addProperty("vault_provider", CurrencyManager.isVaultAvailable());
        settings.addProperty("playerpoints", CurrencyManager.isPlayerPointsAvailable());
        settings.addProperty("web_enabled", WebConfig.get().enabled());
        settings.addProperty("public_url", WebConfig.get().publicUrl());
        settings.addProperty("warehouse_rows", ModConfig.warehouseRows());
        settings.addProperty("warehouse_slots", ModConfig.baseSlots());
        settings.addProperty("safe_box_width", ModConfig.safeBoxWidth());
        settings.addProperty("safe_box_height", ModConfig.safeBoxHeight());
        // 格式背包（2.0.2/2.0.3）：物品尺寸 + 快捷栏规则 + 类配置
        JsonArray gridSizes = new JsonArray();
        for (java.util.Map.Entry<String, int[]> e : com.deltanexus.system.grid.ItemSizeConfig.allCustom().entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("item", e.getKey());
            o.addProperty("w", e.getValue()[0]);
            o.addProperty("h", e.getValue()[1]);
            gridSizes.add(o);
        }
        settings.add("grid_sizes", gridSizes);
        settings.addProperty("hotbar_rules", String.join(",", com.deltanexus.system.grid.GridConfig.rules()));
        JsonObject gridClasses = new JsonObject();
        for (java.util.Map.Entry<String, int[]> e : com.deltanexus.system.grid.GridClassConfig.allClasses().entrySet()) {
            JsonArray c = new JsonArray();
            c.add(e.getValue()[0]);
            c.add(e.getValue()[1]);
            c.add(e.getValue()[2]);
            gridClasses.add(e.getKey(), c);
        }
        settings.add("grid_classes", gridClasses);
        JsonObject gridItemClass = new JsonObject();
        for (java.util.Map.Entry<String, String> e : com.deltanexus.system.grid.GridClassConfig.allItemClasses().entrySet()) {
            gridItemClass.addProperty(e.getKey(), e.getValue());
        }
        settings.add("grid_item_class", gridItemClass);
        root.add("settings", settings);

        JsonArray wbs = new JsonArray();
        for (WorkbenchRegistry.Workbench wb : WorkbenchRegistry.get().all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", wb.id);
            o.addProperty("display", wb.display);
            o.addProperty("recipes_dir", wb.recipesDir);
            wbs.add(o);
        }
        root.add("workbenches", wbs);

        JsonArray recipes = new JsonArray();
        for (Recipe r : RecipeCache.get().all()) {
            recipes.add(recipeJson(r));
        }
        root.add("recipes", recipes);

        JsonArray tree = new JsonArray();
        for (UpgradeConfig.UpgradeLevel u : UpgradeConfig.get().all()) {
            JsonObject o = new JsonObject();
            o.addProperty("level", u.level);
            o.addProperty("cost_money", u.costMoney);
            o.addProperty("unlock_slots", u.unlockSlots);
            JsonArray items = new JsonArray();
            for (UpgradeConfig.RequiredItem ri : u.requiredItems) {
                JsonObject it = new JsonObject();
                it.addProperty("item", ri.item);
                it.addProperty("count", ri.count);
                it.addProperty("nbt", ri.nbt == null ? "" : ri.nbt);
                it.addProperty("match_type", ri.matchType.key());
                items.add(it);
            }
            o.add("required_items", items);
            tree.add(o);
        }
        root.add("tree", tree);
        root.addProperty("tree_max_level", UpgradeConfig.get().maxLevel());

        // 安全箱升级树（1.1.0；2.0.1 起解锁行 x 列）
        JsonArray safeTree = new JsonArray();
        for (UpgradeConfig.UpgradeLevel u : UpgradeConfig.get().safeAll()) {
            JsonObject o = new JsonObject();
            o.addProperty("level", u.level);
            o.addProperty("cost_money", u.costMoney);
            o.addProperty("unlock_rows", Math.max(1, u.unlockRows));
            o.addProperty("unlock_cols", Math.max(1, u.unlockCols));
            o.addProperty("unlock_slots", u.unlockSlots);
            JsonArray items = new JsonArray();
            for (UpgradeConfig.RequiredItem ri : u.requiredItems) {
                JsonObject it = new JsonObject();
                it.addProperty("item", ri.item);
                it.addProperty("count", ri.count);
                it.addProperty("nbt", ri.nbt == null ? "" : ri.nbt);
                it.addProperty("match_type", ri.matchType.key());
                items.add(it);
            }
            o.add("required_items", items);
            safeTree.add(o);
        }
        root.add("safe_tree", safeTree);
        root.addProperty("safe_tree_max_level", UpgradeConfig.get().safeMaxLevel());

        // 安全箱 NBT 限制（1.1.0）
        JsonArray safeRestrictions = new JsonArray();
        for (SafeBoxRestrictions.Rule r : SafeBoxRestrictions.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("item", r.item);
            o.addProperty("nbt", r.nbt);
            o.addProperty("match_type", r.matchType.key());
            safeRestrictions.add(o);
        }
        root.add("safe_restrictions", safeRestrictions);

        JsonArray players = new JsonArray();
        net.minecraft.server.MinecraftServer mc = currentServer;
        if (mc != null) {
            for (ServerPlayer p : mc.getPlayerList().getPlayers()) {
                IPlayerData data = ManufacturingService.data(p);
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getGameProfile().getName());
                o.addProperty("level", data == null ? 0 : data.getWarehouseLevel());
                o.addProperty("unlocked", data == null ? 0 : data.getUnlockedSlots().cardinality());
                o.addProperty("capacity", data == null ? 0 : data.getCapacity());
                o.addProperty("rows", data == null ? 1 : Math.max(1, data.getCapacity() / 9));
                o.addProperty("safe_level", data == null ? 0 : data.getSafeBoxLevel());
                o.addProperty("safe_unlocked", data == null ? 0 : data.getSafeBoxUnlockedSlots());
                o.addProperty("safe_width", data == null ? 1 : data.getSafeBoxWidth());
                o.addProperty("safe_height", data == null ? 1 : data.getSafeBoxHeight());
                players.add(o);
            }
        }
        root.add("players", players);

        // 权限管理（1.1.0 / 2.1）
        JsonObject perms = new JsonObject();
        perms.addProperty("op_exempt", PermissionManager.opExempt());
        perms.addProperty("default_warehouse", PermissionManager.defaultWarehouse());
        perms.addProperty("default_workbench", PermissionManager.defaultWorkbench());
        perms.addProperty("default_special", PermissionManager.defaultSpecial());
        perms.addProperty("default_safe_box", PermissionManager.defaultSafeBox());
        JsonObject permPlayers = new JsonObject();
        for (Map.Entry<String, Boolean[]> e : PermissionManager.overrides().entrySet()) {
            Boolean[] v = e.getValue();
            JsonObject o = new JsonObject();
            if (v.length > PermissionManager.TYPE_WAREHOUSE && v[PermissionManager.TYPE_WAREHOUSE] != null) {
                o.addProperty("warehouse", v[PermissionManager.TYPE_WAREHOUSE]);
            }
            if (v.length > PermissionManager.TYPE_WORKBENCH && v[PermissionManager.TYPE_WORKBENCH] != null) {
                o.addProperty("workbench", v[PermissionManager.TYPE_WORKBENCH]);
            }
            if (v.length > PermissionManager.TYPE_SPECIAL && v[PermissionManager.TYPE_SPECIAL] != null) {
                o.addProperty("special", v[PermissionManager.TYPE_SPECIAL]);
            }
            if (v.length > PermissionManager.TYPE_SAFE_BOX && v[PermissionManager.TYPE_SAFE_BOX] != null) {
                o.addProperty("safe_box", v[PermissionManager.TYPE_SAFE_BOX]);
            }
            // 2.1：功能开关（仅禁用玩家写出 features=false）
            if (PermissionManager.featuresDisabled(e.getKey())) {
                o.addProperty("features", false);
            }
            permPlayers.add(e.getKey(), o);
        }
        // 2.1：仅禁用功能（无权限覆盖）的玩家也列出
        for (String name : PermissionManager.disabledFeatures()) {
            if (!permPlayers.has(name)) {
                JsonObject o = new JsonObject();
                o.addProperty("features", false);
                permPlayers.add(name, o);
            }
        }
        perms.add("players", permPlayers);
        root.add("permissions", perms);
        return root;
    }

    private static JsonObject recipeJson(Recipe r) {
        JsonObject o = new JsonObject();
        o.addProperty("recipe_id", r.recipeId);
        o.addProperty("display_name", r.displayName());
        o.addProperty("workbench", r.workbenchId());
        o.addProperty("required_level", r.requiredLevel);
        o.addProperty("base_duration", r.baseDuration);
        o.addProperty("max_parallel", r.maxParallel);
        JsonArray in = new JsonArray();
        for (Recipe.Ingredient ing : r.input) {
            JsonObject io = new JsonObject();
            io.addProperty("item", ing.item);
            io.addProperty("count", ing.count);
            io.addProperty("nbt", ing.nbt == null ? "" : ing.nbt);
            io.addProperty("match_type", ing.matchType.key());
            in.add(io);
        }
        o.add("input", in);
        JsonArray out = new JsonArray();
        for (Recipe.Output ou : r.output) {
            JsonObject oo = new JsonObject();
            oo.addProperty("item", ou.item);
            oo.addProperty("count", ou.count);
            oo.addProperty("nbt", ou.nbt == null ? "" : ou.nbt);
            out.add(oo);
        }
        o.add("output", out);
        return o;
    }

    // ------------------------------------------------------------------
    // 操作：配置 / 工作台 / 配方 / 升级树 / 玩家（与 /dn 指令等价）
    // ------------------------------------------------------------------

    private static JsonObject doReload() {
        RecipeCache.get().reload();
        UpgradeConfig.get().reload();
        WorkbenchRegistry.get().reload();
        PermissionManager.reload();
        SafeBoxRestrictions.reload();
        boolean expanded = ManufacturingService.autoExpandRows();
        if (expanded) {
            ManufacturingService.applyRowsToOnline(currentServer);
        }
        return ok(msg("msg.dn.reload.done", RecipeCache.get().size(), UpgradeConfig.get().maxLevel(), WorkbenchRegistry.get().size())
                + (expanded ? "；" + msg("msg.dn.tree.rows_expanded", UpgradeConfig.get().maxUnlockSlots(),
                ModConfig.warehouseRows(), ModConfig.warehouseRows() * 9) : ""));
    }

    private static JsonObject doExport() {
        RecipeCache.get().exportBackup();
        return ok(msg("msg.dn.export.done"));
    }

    private static JsonObject workbenchAdd(JsonObject body) {
        String id = str(body, "id");
        String display = str(body, "display");
        if (id.isEmpty()) {
            return err("缺少工作台 id");
        }
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (WorkbenchRegistry.get().exists(lower)) {
            return err(msg("msg.dn.workbench.exists", lower));
        }
        WorkbenchRegistry.get().add(new WorkbenchRegistry.Workbench(lower, display.isEmpty() ? lower : display, lower));
        JsonConfigWriter.saveJsonDebounced(WorkbenchRegistry.get().path(), WorkbenchRegistry.get().toJson(), ModConfig.saveDebounceMs());
        try {
            java.nio.file.Files.createDirectories(RecipeCache.get().recipesDir().resolve(lower));
        } catch (Exception ignored) {
        }
        RecipeCache.get().reload();
        return ok(msg("msg.dn.workbench.added", lower, display, lower));
    }

    private static JsonObject workbenchRemove(JsonObject body) {
        String id = str(body, "id").toLowerCase(java.util.Locale.ROOT);
        if (WorkbenchRegistry.get().remove(id) == null) {
            return err(msg("msg.dn.workbench.not_found", id));
        }
        JsonConfigWriter.saveJsonDebounced(WorkbenchRegistry.get().path(), WorkbenchRegistry.get().toJson(), ModConfig.saveDebounceMs());
        return ok(msg("msg.dn.workbench.removed", id));
    }

    private static JsonObject workbenchRename(JsonObject body) {
        String id = str(body, "id").toLowerCase(java.util.Locale.ROOT);
        WorkbenchRegistry.Workbench wb = WorkbenchRegistry.get().getById(id);
        if (wb == null) {
            return err(msg("msg.dn.workbench.not_found", id));
        }
        wb.display = str(body, "display");
        JsonConfigWriter.saveJsonDebounced(WorkbenchRegistry.get().path(), WorkbenchRegistry.get().toJson(), ModConfig.saveDebounceMs());
        return ok(msg("msg.dn.workbench.renamed", id, wb.display));
    }

    private static JsonObject recipeAdd(JsonObject body) {
        String wb = str(body, "workbench");
        String recipeId = str(body, "recipe_id");
        if (recipeId.isEmpty()) {
            return err("缺少配方 id");
        }
        try {
            ManufacturingService.createRecipe(wb, recipeId);
            return ok(msg("msg.dn.recipe.saved", recipeId));
        } catch (IllegalArgumentException e) {
            return err(msg("msg.dn.workbench.not_found", wb));
        }
    }

    private static JsonObject recipeRemove(JsonObject body) {
        String id = str(body, "recipe_id");
        if (!ManufacturingService.removeRecipe(id)) {
            return err(msg("msg.dn.recipe.not_found"));
        }
        return ok(msg("msg.dn.recipe.removed", id));
    }

    private static JsonObject recipeRename(JsonObject body) {
        Recipe r = RecipeCache.get().get(str(body, "recipe_id"));
        if (r == null) {
            return err(msg("msg.dn.recipe.not_found"));
        }
        r.displayName = str(body, "display_name");
        RecipeCache.get().saveSingleRecipe(r);
        return ok(msg("msg.dn.recipe.renamed", r.recipeId, r.displayName));
    }

    private static JsonObject recipeInputAdd(JsonObject body) {
        Recipe r = RecipeCache.get().get(str(body, "recipe_id"));
        if (r == null) {
            return err(msg("msg.dn.recipe.not_found"));
        }
        String item = str(body, "item");
        if (ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item)) == null) {
            return err("物品不存在: " + item);
        }
        Recipe.Ingredient ing = new Recipe.Ingredient();
        ing.item = item;
        ing.count = Math.max(1, body.has("count") ? body.get("count").getAsInt() : 1);
        ing.nbt = body.has("nbt") ? body.get("nbt").getAsString() : "";
        ing.matchType = NbtMatcher.MatchType.parse(body.has("match_type") ? body.get("match_type").getAsString() : null);
        r.input.add(ing);
        RecipeCache.get().saveSingleRecipe(r);
        return ok(msg("msg.dn.recipe.add_input_ok", r.recipeId));
    }

    private static JsonObject recipeInputRemove(JsonObject body) {
        String id = str(body, "recipe_id");
        int index = body.has("index") ? body.get("index").getAsInt() : -1;
        Recipe r = RecipeCache.get().get(id);
        if (r == null || index < 0 || index >= r.input.size()) {
            return err(msg("msg.dn.recipe.del_failed", id, index));
        }
        r.input.remove(index);
        RecipeCache.get().saveSingleRecipe(r);
        return ok(msg("msg.dn.recipe.del_input_ok", id, index));
    }

    private static JsonObject recipeOutputAdd(JsonObject body) {
        Recipe r = RecipeCache.get().get(str(body, "recipe_id"));
        if (r == null) {
            return err(msg("msg.dn.recipe.not_found"));
        }
        String item = str(body, "item");
        if (ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item)) == null) {
            return err("物品不存在: " + item);
        }
        Recipe.Output out = new Recipe.Output();
        out.item = item;
        out.count = Math.max(1, body.has("count") ? body.get("count").getAsInt() : 1);
        out.nbt = body.has("nbt") ? body.get("nbt").getAsString() : "";
        r.output.add(out);
        RecipeCache.get().saveSingleRecipe(r);
        return ok(msg("msg.dn.recipe.add_output_ok", r.recipeId));
    }

    private static JsonObject recipeOutputRemove(JsonObject body) {
        String id = str(body, "recipe_id");
        int index = body.has("index") ? body.get("index").getAsInt() : -1;
        Recipe r = RecipeCache.get().get(id);
        if (r == null || index < 0 || index >= r.output.size()) {
            return err(msg("msg.dn.recipe.del_failed", id, index));
        }
        r.output.remove(index);
        RecipeCache.get().saveSingleRecipe(r);
        return ok(msg("msg.dn.recipe.del_output_ok", id, index));
    }

    /** 修改配方原料数量（按索引，1.0.4）。 */
    private static JsonObject recipeInputUpdate(JsonObject body) {
        String id = str(body, "recipe_id");
        int index = body.has("index") ? body.get("index").getAsInt() : -1;
        int count = body.has("count") ? body.get("count").getAsInt() : -1;
        if (!ManufacturingService.setRecipeInputCount(id, index, count)) {
            return err(msg("msg.dn.recipe.set_failed", id, index));
        }
        return ok(msg("msg.dn.recipe.set_input_ok", id, index, count));
    }

    /** 修改配方产物数量（按索引，1.0.4）。 */
    private static JsonObject recipeOutputUpdate(JsonObject body) {
        String id = str(body, "recipe_id");
        int index = body.has("index") ? body.get("index").getAsInt() : -1;
        int count = body.has("count") ? body.get("count").getAsInt() : -1;
        if (!ManufacturingService.setRecipeOutputCount(id, index, count)) {
            return err(msg("msg.dn.recipe.set_failed", id, index));
        }
        return ok(msg("msg.dn.recipe.set_output_ok", id, index, count));
    }

    private static JsonObject recipeTime(JsonObject body) {
        String id = str(body, "recipe_id");
        long seconds = body.has("seconds") ? body.get("seconds").getAsLong() : -1;
        if (!ManufacturingService.setRecipeTime(id, seconds)) {
            return err(msg("msg.dn.recipe.not_found"));
        }
        return ok(msg("msg.dn.recipe.time_set", id, seconds));
    }

    private static JsonObject recipeLevel(JsonObject body) {
        String id = str(body, "recipe_id");
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        if (!ManufacturingService.setRecipeLevel(id, level)) {
            return err(msg("msg.dn.recipe.not_found"));
        }
        return ok(msg("msg.dn.recipe.level_set", id, level));
    }

    private static JsonObject recipeParallel(JsonObject body) {
        String id = str(body, "recipe_id");
        int limit = body.has("limit") ? body.get("limit").getAsInt() : -1;
        if (!ManufacturingService.setRecipeParallel(id, limit)) {
            return err(msg("msg.dn.recipe.not_found"));
        }
        return ok(msg("msg.dn.recipe.parallel_set", id, limit));
    }

    private static JsonObject treeAdd(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        if (level < 1) {
            return err("等级必须 >= 1");
        }
        UpgradeConfig.get().getOrCreate(level);
        UpgradeConfig.get().saveNow();
        return ok(msg("msg.dn.tree.added", level));
    }

    private static JsonObject treeRemove(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        JsonObject tree = UpgradeConfig.get().toJson();
        JsonArray arr = tree.has("warehouse_upgrades") ? tree.getAsJsonArray("warehouse_upgrades") : new JsonArray();
        boolean removed = false;
        for (java.util.Iterator<JsonElement> it = arr.iterator(); it.hasNext(); ) {
            JsonElement el = it.next();
            if (el.isJsonObject() && el.getAsJsonObject().has("level")
                    && el.getAsJsonObject().get("level").getAsInt() == level) {
                it.remove();
                removed = true;
            }
        }
        if (!removed) {
            return err(msg("msg.dn.tree.level_not_found", level));
        }
        ManufacturingService.saveUpgradeTree(tree.toString());
        return ok(msg("msg.dn.tree.saved"));
    }

    private static JsonObject treeCost(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        int cost = body.has("cost") ? body.get("cost").getAsInt() : -1;
        if (level < 1 || cost < 0) {
            return err("参数不合法");
        }
        ManufacturingService.setTreeCost(level, cost);
        return ok(msg("msg.dn.tree.cost_set", level, cost));
    }

    private static JsonObject treeSlots(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        int slots = body.has("slots") ? body.get("slots").getAsInt() : -1;
        if (level < 1 || slots < 1) {
            return err("参数不合法");
        }
        ManufacturingService.setTreeSlots(level, slots);
        boolean expanded = ManufacturingService.autoExpandRows();
        if (expanded) {
            ManufacturingService.applyRowsToOnline(currentServer);
            return ok(msg("msg.dn.tree.slots_set", level, slots) + "；" + msg("msg.dn.tree.rows_expanded",
                    UpgradeConfig.get().maxUnlockSlots(), ModConfig.warehouseRows(), ModConfig.warehouseRows() * 9));
        }
        return ok(msg("msg.dn.tree.slots_set", level, slots));
    }

    private static JsonObject treeItemAdd(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        String item = str(body, "item");
        if (level < 1 || ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item)) == null) {
            return err("参数不合法或物品不存在: " + item);
        }
        int count = Math.max(1, body.has("count") ? body.get("count").getAsInt() : 1);
        String nbt = body.has("nbt") ? body.get("nbt").getAsString() : "";
        NbtMatcher.MatchType mt = NbtMatcher.MatchType.parse(body.has("match_type") ? body.get("match_type").getAsString() : null);
        UpgradeConfig.get().addRequiredItem(level, item, count, nbt, mt);
        return ok(msg("msg.dn.tree.add_item_ok", level));
    }

    private static JsonObject treeItemRemove(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        int index = body.has("index") ? body.get("index").getAsInt() : -1;
        if (!UpgradeConfig.get().removeRequiredItem(level, index)) {
            return err(msg("msg.dn.tree.del_item_failed", level, index));
        }
        return ok(msg("msg.dn.tree.del_item_ok", level, index));
    }

    /** 修改升级所需材料数量（按索引，1.0.4）。 */
    private static JsonObject treeItemUpdate(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        int index = body.has("index") ? body.get("index").getAsInt() : -1;
        int count = body.has("count") ? body.get("count").getAsInt() : -1;
        if (!ManufacturingService.setTreeItemCount(level, index, count)) {
            return err(msg("msg.dn.tree.item_set_failed", level, index));
        }
        return ok(msg("msg.dn.tree.item_set_ok", level, index, count));
    }

    private static JsonObject settingSpeed(JsonObject body) {
        double v = body.has("multiplier") ? body.get("multiplier").getAsDouble() : -1;
        if (v < 0.01 || v > 100.0) {
            return err("倍率范围 0.01 ~ 100");
        }
        ModConfig.TIME_MULTIPLIER.set(v);
        ModConfig.SERVER_SPEC.save();
        return ok(msg("msg.dn.config.set", msg("gui.dn.config.speed"), v));
    }

    private static JsonObject settingQueue(JsonObject body) {
        int v = body.has("size") ? body.get("size").getAsInt() : -1;
        if (v < 1 || v > 100) {
            return err("队列范围 1 ~ 100");
        }
        ModConfig.MAX_QUEUE_SIZE.set(v);
        ModConfig.SERVER_SPEC.save();
        return ok(msg("msg.dn.config.set", msg("gui.dn.config.queue"), v));
    }

    private static JsonObject settingMode(JsonObject body) {
        String mode = str(body, "mode");
        if (!mode.equals("offline") && !mode.equals("online")) {
            return err("mode 仅支持 offline/online");
        }
        ModConfig.MANUFACTURE_MODE.set(mode);
        ModConfig.SERVER_SPEC.save();
        return ok(msg("msg.dn.config.set", msg("gui.dn.config.mode"), msg("gui.dn.config.mode_" + mode)));
    }

    private static JsonObject settingCurrency(JsonObject body) {
        String type = str(body, "type");
        switch (type) {
            case "item" -> {
                String item = str(body, "item");
                if (item.isEmpty()) {
                    return err("缺少物品 ID");
                }
                ModConfig.CURRENCY_TYPE.set("item");
                ModConfig.CURRENCY_ITEM.set(item);
                ModConfig.SERVER_SPEC.save();
                return ok(msg("msg.dn.config.set", msg("gui.dn.config.currency"), item));
            }
            case "scoreboard" -> {
                String obj = str(body, "objective");
                if (obj.isEmpty()) {
                    return err("缺少计分板目标名");
                }
                ModConfig.CURRENCY_TYPE.set("scoreboard");
                ModConfig.CURRENCY_SCOREBOARD.set(obj);
                ModConfig.SERVER_SPEC.save();
                return ok(msg("msg.dn.config.set", msg("gui.dn.config.currency"), "scoreboard:" + obj));
            }
            case "vault" -> {
                if (!CurrencyManager.isVaultAvailable()) {
                    return err(CurrencyManager.isVaultApiPresent()
                            ? msg("msg.dn.config.vault_no_provider") : msg("msg.dn.config.vault_missing"));
                }
                ModConfig.CURRENCY_TYPE.set("vault");
                ModConfig.SERVER_SPEC.save();
                return ok(msg("msg.dn.config.set", msg("gui.dn.config.currency"), "vault"));
            }
            case "playerpoints" -> {
                if (!CurrencyManager.isPlayerPointsAvailable()) {
                    return err(msg("msg.dn.config.playerpoints_missing"));
                }
                ModConfig.CURRENCY_TYPE.set("playerpoints");
                ModConfig.SERVER_SPEC.save();
                return ok(msg("msg.dn.config.set", msg("gui.dn.config.currency"), "playerpoints"));
            }
            default -> {
                return err("type 仅支持 item/scoreboard/vault/playerpoints");
            }
        }
    }

    private static JsonObject settingRows(JsonObject body) {
        int v = body.has("rows") ? body.get("rows").getAsInt() : -1;
        if (v < 1 || v > 64) {
            return err("行数范围 1 ~ 64");
        }
        ModConfig.WAREHOUSE_ROWS.set(v);
        ModConfig.SERVER_SPEC.save();
        boolean expanded = ManufacturingService.autoExpandRows();
        ManufacturingService.applyRowsToOnline(currentServer);
        if (expanded) {
            return ok(msg("msg.dn.tree.rows_expanded", UpgradeConfig.get().maxUnlockSlots(),
                    ModConfig.warehouseRows(), ModConfig.warehouseRows() * 9));
        }
        return ok(msg("msg.dn.config.set", msg("gui.dn.config.wh_rows"), v));
    }

    private static JsonObject settingSlots(JsonObject body) {
        int v = body.has("slots") ? body.get("slots").getAsInt() : -1;
        if (v < 0 || v > 576) {
            return err("槽位范围 0 ~ 576");
        }
        ModConfig.BASE_SLOTS.set(v);
        ModConfig.SERVER_SPEC.save();
        return ok(msg("msg.dn.config.set", msg("gui.dn.config.wh_slots"), v));
    }

    /** 设置安全箱默认尺寸（1 ~ 3 x 1 ~ 3，1.1.0）。 */
    private static JsonObject settingSafeSize(JsonObject body) {
        int w = body.has("width") ? body.get("width").getAsInt() : -1;
        int h = body.has("height") ? body.get("height").getAsInt() : -1;
        if (w < 1 || w > 3 || h < 1 || h > 3) {
            return err("尺寸范围：宽 x 高各为 1 ~ 3");
        }
        ModConfig.SAFE_BOX_WIDTH.set(w);
        ModConfig.SAFE_BOX_HEIGHT.set(h);
        ModConfig.SERVER_SPEC.save();
        return ok(msg("msg.dn.config.set", msg("gui.dn.config.safe_size"), w + "x" + h));
    }

    // ------------------------------------------------------------------
    // 格式背包配置（2.0.2：物品尺寸 + 快捷栏规则）
    // ------------------------------------------------------------------

    private static JsonObject gridSizeSet(JsonObject body) {
        String item = str(body, "item");
        int w = body.has("w") ? body.get("w").getAsInt() : -1;
        int h = body.has("h") ? body.get("h").getAsInt() : -1;
        if (item.isEmpty() || ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item)) == null
                || w < 1 || w > 9 || h < 1 || h > 9) {
            return err("参数不合法或物品不存在: " + item);
        }
        if (!com.deltanexus.system.grid.ItemSizeConfig.setSize(item, w, h)) {
            return err(msg("msg.dn.grid.size_invalid"));
        }
        ManufacturingService.broadcastGridConfig();
        return ok(msg("msg.dn.grid.size_set", item, w, h));
    }

    private static JsonObject gridSizeRemove(JsonObject body) {
        String item = str(body, "item");
        if (!com.deltanexus.system.grid.ItemSizeConfig.removeSize(item)) {
            return err(msg("msg.dn.grid.size_not_found", item));
        }
        ManufacturingService.broadcastGridConfig();
        return ok(msg("msg.dn.grid.size_removed", item));
    }

    private static JsonObject gridHotbar(JsonObject body) {
        String rules = str(body, "rules");
        if (!com.deltanexus.system.grid.GridConfig.setRules(rules)) {
            return err(msg("msg.dn.grid.hotbar_invalid"));
        }
        ManufacturingService.broadcastGridConfig();
        return ok(msg("msg.dn.grid.hotbar_set", rules));
    }

    // ------------------------------------------------------------------
    // 格式背包类配置（2.0.3）
    // ------------------------------------------------------------------

    private static JsonObject gridClassSet(JsonObject body) {
        String name = str(body, "name");
        int r = body.has("r") ? body.get("r").getAsInt() : -1;
        int g = body.has("g") ? body.get("g").getAsInt() : -1;
        int b = body.has("b") ? body.get("b").getAsInt() : -1;
        if (name.isEmpty() || r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
            return err(msg("msg.dn.class.invalid"));
        }
        if (!com.deltanexus.system.grid.GridClassConfig.setClass(name, r, g, b)) {
            return err(msg("msg.dn.class.invalid"));
        }
        ManufacturingService.broadcastGridConfig();
        return ok(msg("msg.dn.class.set", name, r, g, b));
    }

    private static JsonObject gridClassRemove(JsonObject body) {
        String name = str(body, "name");
        if (!com.deltanexus.system.grid.GridClassConfig.removeClass(name)) {
            return err(msg("msg.dn.class.not_found", name));
        }
        ManufacturingService.broadcastGridConfig();
        return ok(msg("msg.dn.class.removed", name));
    }

    private static JsonObject gridClassSetItem(JsonObject body) {
        String item = str(body, "item");
        String name = str(body, "name");
        if (!com.deltanexus.system.grid.GridClassConfig.setItemClass(item, name)) {
            return err(msg("msg.dn.class.setclass_invalid", item, name));
        }
        ManufacturingService.broadcastGridConfig();
        return ok(msg("msg.dn.class.setclass_done", item, name));
    }

    private static JsonObject gridClassUnsetItem(JsonObject body) {
        String item = str(body, "item");
        if (!com.deltanexus.system.grid.GridClassConfig.unsetItemClass(item)) {
            return err(msg("msg.dn.grid.size_not_found", item));
        }
        ManufacturingService.broadcastGridConfig();
        return ok(msg("msg.dn.class.unsetclass_done", item));
    }

    // ------------------------------------------------------------------
    // 安全箱升级树（1.1.0，与仓库升级树同构）
    // ------------------------------------------------------------------

    private static JsonObject safeTreeAdd(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        if (level < 1) {
            return err("等级必须 >= 1");
        }
        UpgradeConfig.get().safeGetOrCreate(level);
        UpgradeConfig.get().saveNow();
        return ok(msg("msg.dn.safe.tree.added", level));
    }

    private static JsonObject safeTreeRemove(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        if (!ManufacturingService.removeSafeLevel(level)) {
            return err(msg("msg.dn.safe.tree.level_not_found", level));
        }
        return ok(msg("msg.dn.safe.tree.saved"));
    }

    private static JsonObject safeTreeCost(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        int cost = body.has("cost") ? body.get("cost").getAsInt() : -1;
        if (level < 1 || cost < 0) {
            return err("参数不合法");
        }
        ManufacturingService.setSafeTreeCost(level, cost);
        return ok(msg("msg.dn.safe.tree.cost_set", level, cost));
    }

    private static JsonObject safeTreeDims(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        int rows = body.has("rows") ? body.get("rows").getAsInt() : -1;
        int cols = body.has("cols") ? body.get("cols").getAsInt() : -1;
        if (level < 1 || rows < 1 || rows > 3 || cols < 1 || cols > 3) {
            return err("参数不合法：行/列各为 1 ~ 3");
        }
        ManufacturingService.setSafeTreeDims(level, rows, cols);
        return ok(msg("msg.dn.safe.tree.dims_set", level, rows, cols, rows * cols));
    }

    private static JsonObject safeTreeItemAdd(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        String item = str(body, "item");
        if (level < 1 || ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item)) == null) {
            return err("参数不合法或物品不存在: " + item);
        }
        int count = Math.max(1, body.has("count") ? body.get("count").getAsInt() : 1);
        String nbt = body.has("nbt") ? body.get("nbt").getAsString() : "";
        NbtMatcher.MatchType mt = NbtMatcher.MatchType.parse(body.has("match_type") ? body.get("match_type").getAsString() : null);
        UpgradeConfig.get().safeAddRequiredItem(level, item, count, nbt, mt);
        return ok(msg("msg.dn.safe.tree.add_item_ok", level));
    }

    private static JsonObject safeTreeItemRemove(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        int index = body.has("index") ? body.get("index").getAsInt() : -1;
        if (!UpgradeConfig.get().safeRemoveRequiredItem(level, index)) {
            return err(msg("msg.dn.tree.del_item_failed", level, index));
        }
        return ok(msg("msg.dn.safe.tree.del_item_ok", level, index));
    }

    private static JsonObject safeTreeItemUpdate(JsonObject body) {
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        int index = body.has("index") ? body.get("index").getAsInt() : -1;
        int count = body.has("count") ? body.get("count").getAsInt() : -1;
        if (!ManufacturingService.setSafeTreeItemCount(level, index, count)) {
            return err(msg("msg.dn.tree.item_set_failed", level, index));
        }
        return ok(msg("msg.dn.tree.item_set_ok", level, index, count));
    }

    // ------------------------------------------------------------------
    // 安全箱 NBT 限制（1.1.0）
    // ------------------------------------------------------------------

    /** 添加安全箱 NBT 限制规则：{item?(空=任意), nbt, match_type?(exact/contains)}。 */
    private static JsonObject safeRestrictAdd(JsonObject body) {
        String nbt = str(body, "nbt");
        if (nbt.isEmpty()) {
            return err("缺少 NBT 字符串");
        }
        String item = str(body, "item");
        if (!item.isEmpty() && !"any".equalsIgnoreCase(item)
                && ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item)) == null) {
            return err("物品不存在: " + item);
        }
        String mt = str(body, "match_type");
        NbtMatcher.MatchType matchType = "exact".equalsIgnoreCase(mt)
                ? NbtMatcher.MatchType.EXACT : NbtMatcher.MatchType.CONTAINS;
        String itemId = item.isEmpty() || "any".equalsIgnoreCase(item) ? "" : item;
        int index = SafeBoxRestrictions.add(itemId, nbt, matchType);
        return ok(msg("msg.dn.safe.restrict.added", index, itemId.isEmpty() ? "any" : itemId, matchType.key()));
    }

    private static JsonObject safeRestrictRemove(JsonObject body) {
        int index = body.has("index") ? body.get("index").getAsInt() : -1;
        if (!SafeBoxRestrictions.remove(index)) {
            return err(msg("msg.dn.safe.restrict.not_found", index));
        }
        return ok(msg("msg.dn.safe.restrict.removed", index));
    }

    // ------------------------------------------------------------------
    // 权限管理（1.1.0）
    // ------------------------------------------------------------------

    /** 设置玩家权限覆盖（type: warehouse / workbench / special / all）。 */
    private static JsonObject permSet(JsonObject body) {
        String name = str(body, "name");
        String type = str(body, "type");
        if (name.isEmpty() || !body.has("allow")) {
            return err("缺少玩家名或 allow");
        }
        boolean allow = body.get("allow").getAsBoolean();
        int t = permTypeOf(type);
        if (t < -1) {
            return err("type 仅支持 warehouse/workbench/special/safe_box/all");
        }
        PermissionManager.setOverride(name, t, allow);
        return ok(msg("msg.dn.perm.set", name, type, allow ? "允许" : "拒绝"));
    }

    private static JsonObject permRemove(JsonObject body) {
        String name = str(body, "name");
        if (!PermissionManager.removeOverride(name)) {
            return err(msg("msg.dn.perm.not_found", name));
        }
        return ok(msg("msg.dn.perm.removed", name));
    }

    /** 设置全局默认权限（type: warehouse / workbench / special / safe_box / all）。 */
    private static JsonObject permDefault(JsonObject body) {
        String type = str(body, "type");
        if (!body.has("allow")) {
            return err("缺少 allow");
        }
        boolean allow = body.get("allow").getAsBoolean();
        int t = permTypeOf(type);
        if (t < -1) {
            return err("type 仅支持 warehouse/workbench/special/safe_box/all");
        }
        PermissionManager.setDefault(t, allow);
        return ok(msg("msg.dn.perm.default_set", type, allow ? "允许" : "拒绝"));
    }

    private static int permTypeOf(String type) {
        return switch (type) {
            case "warehouse" -> PermissionManager.TYPE_WAREHOUSE;
            case "workbench" -> PermissionManager.TYPE_WORKBENCH;
            case "special" -> PermissionManager.TYPE_SPECIAL;
            case "safe_box", "safebox" -> PermissionManager.TYPE_SAFE_BOX;
            case "all" -> -1;
            default -> -2;
        };
    }

    /** 设置 OP 是否豁免权限（body: {"allow": bool}）。 */
    private static JsonObject permOp(JsonObject body) {
        if (!body.has("allow")) {
            return err("缺少 allow");
        }
        PermissionManager.setOpExempt(body.get("allow").getAsBoolean());
        return ok(msg("msg.dn.perm.op_set",
                PermissionManager.opExempt() ? "豁免：OP 不受权限限制" : "不豁免：OP 同样受权限限制"));
    }

    /** 设置玩家 mod 功能开关（body: {"name": "...", "allow": bool}；禁用后 UI 恢复原版）。 */
    private static JsonObject permFeature(JsonObject body) {
        String name = str(body, "name");
        if (name.isEmpty() || !body.has("allow")) {
            return err("缺少玩家名或 allow");
        }
        PermissionManager.setFeatures(name, body.get("allow").getAsBoolean());
        // 立即同步 UI 白名单/功能开关到在线玩家（界面即时恢复原版）
        net.minecraft.server.MinecraftServer server = currentServer;
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.getGameProfile().getName().equalsIgnoreCase(name)) {
                    ManufacturingService.sendUiWhitelist(p);
                    break;
                }
            }
        }
        return ok(msg("msg.dn.feature.set", name,
                body.get("allow").getAsBoolean() ? "已启用" : "已禁用：所有 UI 恢复原版"));
    }

    private static ServerPlayer findPlayer(String name) {
        net.minecraft.server.MinecraftServer mc = currentServer;
        if (mc == null) {
            return null;
        }
        return mc.getPlayerList().getPlayerByName(name);
    }

    private static JsonObject playerLevel(JsonObject body) {
        String name = str(body, "name");
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        ServerPlayer p = findPlayer(name);
        if (p == null) {
            return err(msg("msg.dn.data.not_found", name));
        }
        int max = UpgradeConfig.get().maxLevel();
        if (level < 0 || level > max) {
            return err(msg("msg.dn.data.level_too_high", max));
        }
        IPlayerData data = ManufacturingService.data(p);
        if (data == null) {
            return err(msg("msg.dn.data.not_found", name));
        }
        ManufacturingService.ensureWarehouseCapacity(p);
        data.setWarehouseLevel(level);
        data.unlockUpTo(UpgradeConfig.get().unlockSlotsForLevel(level));
        ManufacturingService.sendSyncWarehouse(p);
        return ok(msg("msg.dn.data.level_set", name, level, data.getUnlockedSlots().cardinality(), data.getCapacity()));
    }

    /** 设置玩家安全箱等级（0 ~ 安全箱升级树最大等级；尺寸随等级联动，1.1.0）。 */
    private static JsonObject playerSafeLevel(JsonObject body) {
        String name = str(body, "name");
        int level = body.has("level") ? body.get("level").getAsInt() : -1;
        ServerPlayer p = findPlayer(name);
        if (p == null) {
            return err(msg("msg.dn.data.not_found", name));
        }
        int max = UpgradeConfig.get().safeMaxLevel();
        if (level < 0 || level > max) {
            return err(msg("msg.dn.safe.data.level_too_high", max));
        }
        if (!ManufacturingService.setPlayerSafeLevel(p, level)) {
            return err(msg("msg.dn.data.not_found", name));
        }
        IPlayerData data = ManufacturingService.data(p);
        return ok(msg("msg.dn.safe.data.level_set", name, level,
                data != null ? data.getSafeBoxWidth() + "x" + data.getSafeBoxHeight() : "?",
                data != null ? data.getSafeBoxUnlockedSlots() : 0));
    }

    private static JsonObject playerReset(JsonObject body) {
        String name = str(body, "name");
        ServerPlayer p = findPlayer(name);
        if (p == null) {
            return err(msg("msg.dn.data.not_found", name));
        }
        IPlayerData data = ManufacturingService.data(p);
        if (data == null) {
            return err(msg("msg.dn.data.not_found", name));
        }
        int base = Math.min(ModConfig.baseSlots(), data.getCapacity());
        data.setWarehouseLevel(0);
        data.setUnlockedSlots(base);
        data.setSafeBoxLevel(0);
        ManufacturingService.sendSyncWarehouse(p);
        ManufacturingService.syncSafeBox(p);
        return ok(msg("msg.dn.data.reset", name, base));
    }

    private static String str(JsonObject body, String key) {
        return body.has(key) && body.get(key).isJsonPrimitive() ? body.get(key).getAsString().trim() : "";
    }
}
