package com.deltanexus.system.web;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.common.WorkbenchRegistry;
import com.deltanexus.system.config.JsonConfigWriter;
import com.deltanexus.system.config.ModConfig;
import com.deltanexus.system.config.Recipe;
import com.deltanexus.system.config.RecipeCache;
import com.deltanexus.system.config.UpgradeConfig;
import com.deltanexus.system.server.CurrencyManager;
import com.deltanexus.system.server.GearService;
import com.deltanexus.system.server.ManufacturingService;
import com.deltanexus.system.server.PermissionManager;
import com.deltanexus.system.config.SafeBoxRestrictions;
import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.grid.GridEntry;
import com.deltanexus.system.grid.GridStore;
import com.deltanexus.system.spawner.GlobalConfig;
import com.deltanexus.system.spawner.LogConfig;
import com.deltanexus.system.spawner.Spawner;
import com.deltanexus.system.spawner.SpawnerManager;
import com.deltanexus.system.spawner.SpawnerService;
import com.deltanexus.system.spawner.SpawnerWorldStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

    // ---- SSE（0.6.0Beta）：在线玩家实时数据推送（服务端推送，免整页刷新） ----
    /** 刷新间隔（毫秒）。 */
    private static final long SSE_TICK_MS = 3000L;
    /** 已连接订阅者。 */
    private static final java.util.Set<SseClient> SSE_CLIENTS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 最近一次玩家实时负载（无订阅者时为 ""）。 */
    private static volatile String ssePlayersPayload = "";
    private static volatile boolean sseRunning;
    private static Thread sseRefresher;

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
            server.createContext("/api/events", new SseHandler());
            server.start();
            sseRunning = true;
            startSseRefresher();
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
        sseRunning = false;
        if (sseRefresher != null) {
            sseRefresher.interrupt();
            sseRefresher = null;
        }
        SSE_CLIENTS.clear();
        ssePlayersPayload = "";
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

    // ------------------------------------------------------------------
    // SSE：在线玩家实时数据推送
    // ------------------------------------------------------------------

    /** 单个 SSE 订阅者：底层为一个长连接输出流。 */
    private static final class SseClient {
        private final HttpExchange exchange;
        private final java.io.OutputStream out;

        SseClient(HttpExchange exchange) throws IOException {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
        }

        void send(String event, String data) throws IOException {
            out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void comment(String text) throws IOException {
            out.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void close() {
            exchange.close();
        }
    }

    /**
     * SSE 处理器：{@code GET /api/events}。连接建立后由各自的处理线程按刷新间隔
     * 推送最新玩家数据（内容未变时改发心跳注释），断开即回收。
     */
    private static final class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!authorized(exchange)) {
                send(exchange, 401, err("未授权：token 无效或缺失"));
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);
            SseClient client = new SseClient(exchange);
            SSE_CLIENTS.add(client);
            try {
                client.send("hello", "{\"ok\":true}");
                String last = null;
                int idle = 0;
                while (sseRunning && server != null) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    String payload = ssePlayersPayload;
                    if (payload != null && !payload.isEmpty() && !payload.equals(last)) {
                        client.send("players", payload);
                        last = payload;
                        idle = 0;
                    } else if (++idle >= 15) {
                        client.comment("ping");
                        idle = 0;
                    }
                }
            } catch (IOException ignored) {
                // 客户端断开
            } finally {
                SSE_CLIENTS.remove(client);
                client.close();
            }
        }
    }

    /** 启动玩家数据刷新线程（无订阅者时不产生任何开销）。 */
    private static synchronized void startSseRefresher() {
        if (sseRefresher != null && sseRefresher.isAlive()) {
            return;
        }
        sseRefresher = new Thread(() -> {
            while (sseRunning) {
                try {
                    Thread.sleep(SSE_TICK_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (SSE_CLIENTS.isEmpty()) {
                    continue;
                }
                try {
                    JsonObject o = onServer(WebEditorServer::livePlayers);
                    if (o != null && o.has("ok") && o.get("ok").getAsBoolean()) {
                        ssePlayersPayload = o.toString();
                    }
                } catch (Exception ignored) {
                    // 单次失败不中断推送
                }
            }
        }, "dn-WebSSE");
        sseRefresher.setDaemon(true);
        sseRefresher.start();
    }

    /** SSE 实时负载：在线玩家「等级/安全箱 + 持有物品」合并快照（前端据此局部刷新，免整页刷新）。 */
    private static JsonObject livePlayers() {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        JsonArray players = new JsonArray();
        net.minecraft.server.MinecraftServer mc = currentServer;
        if (mc != null) {
            for (ServerPlayer p : mc.getPlayerList().getPlayers()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getGameProfile().getName());
                playerStatsInto(o, p);
                o.add("items", heldItems(p));
                players.add(o);
            }
        }
        root.add("players", players);
        return root;
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
        if ("GET".equals(method) && path.equals("/api/items")) {
            return onServer(WebEditorServer::itemsCatalog);
        }
        // 在线玩家实时位置（刷兵点位「取玩家坐标」用；不复用概览快照，保证是当前坐标）
        if ("GET".equals(method) && path.equals("/api/players/positions")) {
            return onServer(WebEditorServer::playersPositions);
        }
        // 刷兵可选项：注册表 id（实体 / 粒子 / 音效），页面首次进入时懒加载
        if ("GET".equals(method) && path.equals("/api/spawner/registry")) {
            return onServer(WebEditorServer::spawnerRegistry);
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
            case "/api/recipe/copy" -> onServer(() -> recipeCopy(body));
            case "/api/recipe/batch" -> onServer(() -> recipeBatch(body));
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
            // 装备登记表（0.5.0Beta）
            case "/api/gear/set" -> onServer(() -> gearSet(body));
            case "/api/gear/remove" -> onServer(() -> gearRemove(body));
            case "/api/player/level" -> onServer(() -> playerLevel(body));
            case "/api/player/safe/level" -> onServer(() -> playerSafeLevel(body));
            case "/api/player/reset" -> onServer(() -> playerReset(body));
            case "/api/perm/set" -> onServer(() -> permSet(body));
            case "/api/perm/remove" -> onServer(() -> permRemove(body));
            case "/api/perm/default" -> onServer(() -> permDefault(body));
            case "/api/perm/op" -> onServer(() -> permOp(body));
            case "/api/perm/feature" -> onServer(() -> permFeature(body));
            // 交易行（0.2.0Beta）
            case "/api/trade/settings" -> onServer(() -> tradeSettings(body));
            case "/api/trade/category/add" -> onServer(() -> tradeCategoryAdd(body));
            case "/api/trade/category/rename" -> onServer(() -> tradeCategoryRename(body));
            case "/api/trade/category/remove" -> onServer(() -> tradeCategoryRemove(body));
            case "/api/trade/good/save" -> onServer(() -> tradeGoodSave(body));
            case "/api/trade/good/remove" -> onServer(() -> tradeGoodRemove(body));
            case "/api/trade/stock" -> onServer(() -> tradeStock(body));
            case "/api/trade/feed/refresh" -> onServer(() -> tradeFeedRefresh());
            // 刷兵系统（0.4.0Beta；Web 配置见 0.5.0Beta）
            case "/api/spawner/global" -> onServer(() -> spawnerGlobal(body));
            case "/api/spawner/logging" -> onServer(() -> spawnerLogging(body));
            case "/api/spawner/world/save" -> onServer(() -> spawnerWorldSave(body));
            case "/api/spawner/action" -> onServer(() -> spawnerAction(body));
            case "/api/spawner/reload" -> onServer(() -> spawnerReload());
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
        // 格式背包（2.0.2Alpha/2.0.3Alpha）：物品尺寸 + 快捷栏规则 + 类配置
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
        // 装备登记表（0.5.0Beta）：物品 id -> 种类 + 尺寸
        JsonArray gearList = new JsonArray();
        for (Map.Entry<String, com.deltanexus.system.grid.GearConfig.GearSpec> e
                : com.deltanexus.system.grid.GearConfig.all().entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("item", e.getKey());
            o.addProperty("kind", e.getValue().kind().id());
            o.addProperty("w", e.getValue().size().w());
            o.addProperty("h", e.getValue().size().h());
            gearList.add(o);
        }
        settings.add("gear", gearList);
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
                ri.writeNbtJson(it);
                items.add(it);
            }
            o.add("required_items", items);
            tree.add(o);
        }
        root.add("tree", tree);
        root.addProperty("tree_max_level", UpgradeConfig.get().maxLevel());

        // 安全箱升级树（1.1.0Alpha；2.0.1Alpha 起解锁行 x 列）
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
                ri.writeNbtJson(it);
                items.add(it);
            }
            o.add("required_items", items);
            safeTree.add(o);
        }
        root.add("safe_tree", safeTree);
        root.addProperty("safe_tree_max_level", UpgradeConfig.get().safeMaxLevel());

        // 安全箱 NBT 限制（1.1.0Alpha）
        JsonArray safeRestrictions = new JsonArray();
        for (SafeBoxRestrictions.Rule r : SafeBoxRestrictions.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("item", r.item);
            r.writeNbtJson(o);
            safeRestrictions.add(o);
        }
        root.add("safe_restrictions", safeRestrictions);

        JsonArray players = new JsonArray();
        net.minecraft.server.MinecraftServer mc = currentServer;
        if (mc != null) {
            for (ServerPlayer p : mc.getPlayerList().getPlayers()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getGameProfile().getName());
                playerStatsInto(o, p);
                players.add(o);
            }
        }
        root.add("players", players);

        // 权限管理（1.1.0Alpha / 2.1Alpha）
        JsonObject perms = new JsonObject();
        perms.addProperty("op_exempt", PermissionManager.opExempt());
        perms.addProperty("default_warehouse", PermissionManager.defaultWarehouse());
        perms.addProperty("default_workbench", PermissionManager.defaultWorkbench());
        perms.addProperty("default_special", PermissionManager.defaultSpecial());
        perms.addProperty("default_safe_box", PermissionManager.defaultSafeBox());
        perms.addProperty("default_trade", PermissionManager.defaultTrade());
        perms.addProperty("default_gear", PermissionManager.defaultGear());
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
            if (v.length > PermissionManager.TYPE_TRADE && v[PermissionManager.TYPE_TRADE] != null) {
                o.addProperty("trade", v[PermissionManager.TYPE_TRADE]);
            }
            if (v.length > PermissionManager.TYPE_GEAR && v[PermissionManager.TYPE_GEAR] != null) {
                o.addProperty("gear", v[PermissionManager.TYPE_GEAR]);
            }
            // 2.1Alpha：功能开关（仅禁用玩家写出 features=false）
            if (PermissionManager.featuresDisabled(e.getKey())) {
                o.addProperty("features", false);
            }
            permPlayers.add(e.getKey(), o);
        }
        // 2.1Alpha：仅禁用功能（无权限覆盖）的玩家也列出
        for (String name : PermissionManager.disabledFeatures()) {
            if (!permPlayers.has(name)) {
                JsonObject o = new JsonObject();
                o.addProperty("features", false);
                permPlayers.add(name, o);
            }
        }
        perms.add("players", permPlayers);
        root.add("permissions", perms);
        // 交易行（0.2.0Beta）
        root.add("trade", tradeJson());
        // 刷兵系统（0.4.0Beta；Web 配置页数据源）
        root.add("spawner", spawnerJson());
        return root;
    }

    /**
     * 物品选择器目录（0.5.0Beta）：创造全集 + 在线玩家持有的物品。
     *
     * <p>前端「统一物品选择器」一次请求拿齐两个来源，避免逐物品查询：
     * 创造全集用于凭空构造任意物品；在线玩家物品用于「照抄」一件已存在的物品
     * （附带真实 NBT 与数量，省去手写 SNBT）。</p>
     */
    private static JsonObject itemsCatalog() {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);

        // 创造全集（按注册顺序；前端按命名空间分组、按 id/显示名搜索）
        JsonArray items = new JsonArray();
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", id.toString());
            o.addProperty("name", new ItemStack(item).getHoverName().getString());
            o.addProperty("ns", id.getNamespace());
            // NBT 驱动物品（如 TACZ 枪械）自带默认 NBT：一并带出，否则凭空构造会丢失关键数据
            CompoundTag defTag = item.getDefaultInstance().getTag();
            if (defTag != null && !defTag.isEmpty()) {
                o.addProperty("nbt", defTag.toString());
                o.addProperty("match_mode", com.deltanexus.system.common.NbtSpec.MatchMode.FULL_NBT.key);
            } else {
                o.addProperty("match_mode", com.deltanexus.system.common.NbtSpec.MatchMode.ID.key);
            }
            items.add(o);
        }
        root.add("items", items);
        root.add("players", playersSection());
        return root;
    }

    /** 玩家仓库/安全箱进度字段（概览与 SSE 实时负载共用）。 */
    private static void playerStatsInto(JsonObject o, ServerPlayer p) {
        IPlayerData data = ManufacturingService.data(p);
        o.addProperty("level", data == null ? 0 : data.getWarehouseLevel());
        o.addProperty("unlocked", data == null ? 0 : data.getUnlockedSlots().cardinality());
        o.addProperty("capacity", data == null ? 0 : data.getCapacity());
        o.addProperty("rows", data == null ? 1 : Math.max(1, data.getCapacity() / 9));
        o.addProperty("safe_level", data == null ? 0 : data.getSafeBoxLevel());
        o.addProperty("safe_unlocked", data == null ? 0 : data.getSafeBoxUnlockedSlots());
        o.addProperty("safe_width", data == null ? 1 : data.getSafeBoxWidth());
        o.addProperty("safe_height", data == null ? 1 : data.getSafeBoxHeight());
        // 位置信息（刷兵点位「从在线玩家取坐标」；概览与 SSE 都带，页面随时可用最新的那一份）
        positionInto(o, p);
    }

    /** 玩家当前位置字段（维度 id + 坐标 + 朝向）。包内可见：供回归测试直接断言字段。 */
    static void positionInto(JsonObject o, ServerPlayer p) {
        o.addProperty("dimension", p.level().dimension().location().toString());
        o.addProperty("x", round2(p.getX()));
        o.addProperty("y", round2(p.getY()));
        o.addProperty("z", round2(p.getZ()));
        o.addProperty("yaw", round2(p.getYRot()));
        o.addProperty("pitch", round2(p.getXRot()));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 在线玩家位置（GET {@code /api/players/positions}）——刷兵点位编辑器「取在线玩家坐标」用。
     *
     * <p>与概览里的 players 同构，但不携带物品等重字段，可随时点按钮刷新。</p>
     */
    private static JsonObject playersPositions() {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        JsonArray players = new JsonArray();
        net.minecraft.server.MinecraftServer mc = currentServer;
        if (mc != null) {
            for (ServerPlayer p : mc.getPlayerList().getPlayers()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getGameProfile().getName());
                positionInto(o, p);
                players.add(o);
            }
        }
        root.add("players", players);
        return root;
    }

    /** 在线玩家持有物品段（物品选择器「在线玩家」页签用）。 */
    private static JsonArray playersSection() {
        JsonArray players = new JsonArray();
        net.minecraft.server.MinecraftServer mc = currentServer;
        if (mc == null) {
            return players;
        }
        for (ServerPlayer p : mc.getPlayerList().getPlayers()) {
            JsonObject po = new JsonObject();
            po.addProperty("name", p.getGameProfile().getName());
            po.add("items", heldItems(p));
            players.add(po);
        }
        return players;
    }

    /** 单个玩家持有物品：原版背包/盔甲/副手 + 胸挂/背包网格 + 安全箱。 */
    private static JsonArray heldItems(ServerPlayer p) {
        JsonArray stacks = new JsonArray();
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            addCatalogStack(stacks, inv.getItem(i), "inventory");
        }
        for (GearKind kind : GearKind.values()) {
            GridStore store = GearService.store(GearService.equipped(p, kind));
            if (store == null) {
                continue;
            }
            for (GridEntry entry : store.entries().values()) {
                addCatalogStack(stacks, entry.stack(), kind.id());
            }
        }
        IPlayerData data = ManufacturingService.data(p);
        if (data != null && data.getSafeBoxHandler() != null) {
            ItemStackHandler safe = data.getSafeBoxHandler();
            for (int i = 0; i < safe.getSlots(); i++) {
                addCatalogStack(stacks, safe.getStackInSlot(i), "safe_box");
            }
        }
        return stacks;
    }

    /** 物品选择器条目：id + 显示名 + 数量 + SNBT（空串 = 无 NBT）+ 建议匹配模式 + 来源。 */
    private static void addCatalogStack(JsonArray out, ItemStack stack, String source) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", id.toString());
        o.addProperty("name", stack.getHoverName().getString());
        o.addProperty("count", stack.getCount());
        String snbt = stack.getTag() == null ? "" : stack.getTag().toString();
        o.addProperty("nbt", snbt);
        o.addProperty("match_mode", snbt.isEmpty()
                ? com.deltanexus.system.common.NbtSpec.MatchMode.ID.key
                : com.deltanexus.system.common.NbtSpec.MatchMode.FULL_NBT.key);
        o.addProperty("source", source);
        out.add(o);
    }

    /** 交易行概览段：定义 + 运行时库存 + 已解析价格（目录同源）+ 外部源。 */
    private static JsonObject tradeJson() {
        com.deltanexus.system.trade.TradeConfig cfg = com.deltanexus.system.trade.TradeConfig.get();
        JsonObject trade = new JsonObject();
        JsonObject settings = new JsonObject();
        com.deltanexus.system.trade.TradeConfig.Settings s = cfg.settings();
        settings.addProperty("multiplier", s.multiplier);
        settings.addProperty("feed_interval", s.feedRefreshIntervalS);
        settings.addProperty("timeout", s.evalTimeoutMs);
        settings.addProperty("sell_enabled", s.sellEnabled);
        settings.addProperty("spread_guard", s.sellSpreadGuard);
        trade.add("settings", settings);

        JsonArray cats = new JsonArray();
        for (com.deltanexus.system.trade.TradeCategory c : cfg.categoriesSnapshot()) {
            cats.add(c.toJson());
        }
        trade.add("categories", cats);

        JsonArray defs = new JsonArray();
        for (com.deltanexus.system.trade.TradeGood g : cfg.goodsSnapshot()) {
            JsonObject o = g.toJson();
            o.addProperty("stock", com.deltanexus.system.trade.TradeStockStore.get(g.id));
            defs.add(o);
        }
        trade.add("goods", defs);

        // 当前可购买状态（与服务端目录同源，页面直接展示价格/限购原因）
        JsonArray views = new JsonArray();
        try {
            com.deltanexus.system.network.packet.SyncTradeCatalogPacket catalog =
                    com.deltanexus.system.server.TradeService.buildCatalog();
            for (com.deltanexus.system.network.packet.SyncTradeCatalogPacket.Good v : catalog.goods) {
                JsonObject o = new JsonObject();
                o.addProperty("id", v.id);
                o.addProperty("buy_price", v.buyCode == 0 ? v.buyPrice : -1);
                o.addProperty("buy_code", v.buyCode);
                o.addProperty("buy_limit", v.buyLimit);
                o.addProperty("sell_price", v.sellCode == 0 ? v.sellPrice : -1);
                o.addProperty("market_price", v.marketCode == 0 ? v.marketPrice : -1);
                o.addProperty("stock", v.stock);
                views.add(o);
            }
        } catch (Exception e) {
            // 求价异常不应拖垮 overview
            DeltaNexus.LOGGER.warn("[DN] 交易行目录求值失败: {}", e.getMessage());
        }
        trade.add("views", views);

        JsonArray feeds = new JsonArray();
        for (com.deltanexus.system.trade.MarketFeed f : com.deltanexus.system.trade.TradeFeedRegistry.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", f.id());
            o.addProperty("desc", f.description());
            try {
                com.deltanexus.system.trade.FeedSnapshot snap = f.snapshot();
                o.addProperty("rows", snap.size());
                o.addProperty("loaded_at", snap.loadedAtEpochMs());
            } catch (Exception e) {
                o.addProperty("rows", 0);
            }
            feeds.add(o);
        }
        trade.add("feeds", feeds);
        return trade;
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
            ing.writeNbtJson(io);
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
        com.deltanexus.system.trade.TradeConfig.get().reload();
        com.deltanexus.system.trade.TradeStockStore.load();
        // 刷兵配置（0.4.0Beta：全局 + 日志 + 各世界文件；与 /dn reload 同一入口）
        SpawnerManager.reloadAll(currentServer);
        boolean expanded = ManufacturingService.autoExpandRows();
        if (expanded) {
            ManufacturingService.applyRowsToOnline(currentServer);
        }
        com.deltanexus.system.server.TradeService.sendSyncToAll(currentServer);
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
        ing.readNbtJson(body);
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

    /** 复制配方为新 id（保留同工作台与全部字段，1.0.4 网页批编辑）。 */
    private static JsonObject recipeCopy(JsonObject body) {
        String id = str(body, "recipe_id");
        String newId = str(body, "new_recipe_id");
        if (newId.isBlank()) {
            return err("新配方 id 不能为空");
        }
        RecipeCache cache = RecipeCache.get();
        Recipe src = cache.get(id);
        if (src == null) {
            return err(msg("msg.dn.recipe.not_found", id));
        }
        newId = newId.trim().toLowerCase();
        if (!newId.matches("[a-z0-9_\\-.]+")) {
            return err("配方 id 只能含小写字母/数字/下划线/连字符/点: " + newId);
        }
        if (cache.get(newId) != null) {
            return err("配方 id 已存在: " + newId);
        }
        Recipe copy = new Recipe();
        copy.type = src.type;
        copy.recipeId = newId;
        copy.displayName = src.displayName;
        copy.requiredLevel = src.requiredLevel;
        copy.baseDuration = src.baseDuration;
        copy.maxParallel = src.maxParallel;
        for (Recipe.Ingredient ing : src.input) {
            Recipe.Ingredient c = new Recipe.Ingredient();
            c.item = ing.item;
            c.count = ing.count;
            c.nbt = ing.nbt;
            c.matchMode = ing.matchMode;
            c.matchKeys.putAll(ing.matchKeys);
            copy.input.add(c);
        }
        for (Recipe.Output o : src.output) {
            Recipe.Output c = new Recipe.Output();
            c.item = o.item;
            c.count = o.count;
            c.nbt = o.nbt;
            copy.output.add(c);
        }
        cache.saveSingleRecipe(copy);
        return ok("已复制配方 " + id + " → " + newId);
    }

    /** 批量改参数（耗时/等级），一次落盘并重载。 */
    private static JsonObject recipeBatch(JsonObject body) {
        JsonArray ids = body.has("ids") ? body.getAsJsonArray("ids") : null;
        if (ids == null || ids.size() == 0) {
            return err("未选择任何配方");
        }
        boolean hasTime = body.has("seconds") && !body.get("seconds").isJsonNull() && body.get("seconds").getAsLong() >= 1;
        boolean hasLevel = body.has("level") && !body.get("level").isJsonNull() && body.get("level").getAsInt() >= 0;
        if (!hasTime && !hasLevel) {
            return err("请至少填写耗时或等级一项");
        }
        long seconds = hasTime ? body.get("seconds").getAsLong() : -1;
        int level = hasLevel ? body.get("level").getAsInt() : -1;
        RecipeCache cache = RecipeCache.get();
        int n = 0;
        for (JsonElement e : ids) {
            Recipe r = cache.get(e.getAsString());
            if (r == null) {
                continue;
            }
            if (hasTime) {
                r.baseDuration = Math.max(1, seconds);
            }
            if (hasLevel) {
                r.requiredLevel = Math.max(0, level);
            }
            cache.saveSingleRecipe(r);
            n++;
        }
        if (n == 0) {
            return err("没有有效的配方可更新");
        }
        return ok("已批量更新 " + n + " 个配方");
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
        UpgradeConfig.RequiredItem req = new UpgradeConfig.RequiredItem();
        req.item = item;
        req.count = count;
        req.readNbtJson(body);
        UpgradeConfig.get().addRequiredItem(level, req);
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
                return err("type 仅支持 scoreboard/vault/playerpoints（item 已移除）");
            }
        }
    }

    private static JsonObject settingRows(JsonObject body) {
        int v = body.has("rows") ? body.get("rows").getAsInt() : -1;
        if (v < 1 || v > ModConfig.WAREHOUSE_ROWS_MAX) {
            return err("行数范围 1 ~ " + ModConfig.WAREHOUSE_ROWS_MAX);
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

    /** 设置安全箱默认尺寸（1 ~ 3 x 1 ~ 3，1.1.0Alpha）。 */
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
    // 格式背包配置（2.0.2Alpha：物品尺寸 + 快捷栏规则）
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
    // 装备登记表（0.5.0Beta）
    // ------------------------------------------------------------------

    private static JsonObject gearSet(JsonObject body) {
        String item = str(body, "item");
        String kind = str(body, "kind");
        int w = body.has("w") ? body.get("w").getAsInt() : -1;
        int h = body.has("h") ? body.get("h").getAsInt() : -1;
        if (item.isEmpty() || kind.isEmpty() || w < 1 || w > 9 || h < 1 || h > 9) {
            return err("参数不合法: " + item);
        }
        if (!com.deltanexus.system.grid.GearConfig.set(item, kind, w, h)) {
            return err("登记失败，物品或种类不存在: " + item + " / " + kind);
        }
        com.deltanexus.system.grid.GearConfig.GearSpec spec =
                com.deltanexus.system.grid.GearConfig.all().get(item);
        int cw = spec == null ? w : spec.size().w();
        int ch = spec == null ? h : spec.size().h();
        return ok("已登记 " + item + " 为 " + kind + " " + cw + "×" + ch
                + (cw != w || ch != h ? "（超出上限已夹取）" : ""));
    }

    private static JsonObject gearRemove(JsonObject body) {
        String item = str(body, "item");
        if (!com.deltanexus.system.grid.GearConfig.remove(item)) {
            return err("该物品未被登记为装备: " + item);
        }
        return ok("已取消登记 " + item);
    }

    // ------------------------------------------------------------------
    // 格式背包类配置（2.0.3Alpha）
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
    // 安全箱升级树（1.1.0Alpha，与仓库升级树同构）
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
        UpgradeConfig.RequiredItem req = new UpgradeConfig.RequiredItem();
        req.item = item;
        req.count = count;
        req.readNbtJson(body);
        UpgradeConfig.get().safeAddRequiredItem(level, req);
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
    // 安全箱 NBT 限制（1.1.0Alpha）
    // ------------------------------------------------------------------

    /** 添加安全箱 NBT 限制规则：{item?(空=任意), nbt?, match_mode?, match_keys?}。 */
    private static JsonObject safeRestrictAdd(JsonObject body) {
        String item = str(body, "item");
        if (!item.isEmpty() && !"any".equalsIgnoreCase(item)
                && ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(item)) == null) {
            return err("物品不存在: " + item);
        }
        SafeBoxRestrictions.Rule rule = new SafeBoxRestrictions.Rule();
        rule.item = item.isEmpty() || "any".equalsIgnoreCase(item) ? "" : item;
        rule.readNbtJson(body);
        if ((rule.nbt == null || rule.nbt.isBlank()) && rule.matchKeys.isEmpty()) {
            return err("缺少 NBT 字符串");
        }
        if (rule.matchMode == com.deltanexus.system.common.NbtSpec.MatchMode.ID) {
            rule.matchMode = com.deltanexus.system.common.NbtSpec.MatchMode.PARTIAL_NBT;
        }
        int index = SafeBoxRestrictions.add(rule);
        return ok(msg("msg.dn.safe.restrict.added", index, rule.item.isEmpty() ? "any" : rule.item, rule.matchMode.key));
    }

    private static JsonObject safeRestrictRemove(JsonObject body) {
        int index = body.has("index") ? body.get("index").getAsInt() : -1;
        if (!SafeBoxRestrictions.remove(index)) {
            return err(msg("msg.dn.safe.restrict.not_found", index));
        }
        return ok(msg("msg.dn.safe.restrict.removed", index));
    }

    // ------------------------------------------------------------------
    // 权限管理（1.1.0Alpha）
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
            return err("type 仅支持 warehouse/workbench/special/safe_box/trade/gear/all");
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
            return err("type 仅支持 warehouse/workbench/special/safe_box/trade/gear/all");
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
            case "trade" -> PermissionManager.TYPE_TRADE;
            case "gear" -> PermissionManager.TYPE_GEAR;
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

    /** 设置玩家安全箱等级（0 ~ 安全箱升级树最大等级；尺寸随等级联动，1.1.0Alpha）。 */
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
        // 0.2.0Beta：重置即清除数据备份（与 /dn data reset 一致）
        com.deltanexus.system.capability.CapabilityAttacher.clearBackupFor(p.getUUID());
        ManufacturingService.sendSyncWarehouse(p);
        ManufacturingService.syncSafeBox(p);
        return ok(msg("msg.dn.data.reset", name, base));
    }

    // ------------------------------------------------------------------
    // 交易行（0.2.0Beta）Web 管理
    // ------------------------------------------------------------------

    private static void tradeBroadcast() {
        com.deltanexus.system.server.TradeService.sendSyncToAll(currentServer);
    }

    private static JsonObject tradeSettings(JsonObject body) {
        com.deltanexus.system.trade.TradeConfig cfg = com.deltanexus.system.trade.TradeConfig.get();
        com.deltanexus.system.trade.TradeConfig.Settings s = cfg.settings();
        if (body.has("multiplier")) {
            s.multiplier = Math.max(0.01, body.get("multiplier").getAsDouble());
        }
        if (body.has("feed_interval")) {
            s.feedRefreshIntervalS = Math.max(1, body.get("feed_interval").getAsInt());
        }
        if (body.has("timeout")) {
            s.evalTimeoutMs = Math.max(1, Math.min(1000, body.get("timeout").getAsInt()));
        }
        if (body.has("sell_enabled")) {
            s.sellEnabled = body.get("sell_enabled").getAsBoolean();
        }
        if (body.has("spread_guard")) {
            String m = body.get("spread_guard").getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            if (m.equals("warn") || m.equals("block") || m.equals("off")) {
                s.sellSpreadGuard = m;
            }
        }
        cfg.saveDebounced();
        tradeBroadcast();
        return ok("交易行全局设置已保存（倍率 " + s.multiplier + " / feed " + s.feedRefreshIntervalS
                + "s / 超时 " + s.evalTimeoutMs + "ms / 回收 " + (s.sellEnabled ? "开" : "关")
                + " / 价差保护 " + s.sellSpreadGuard + "）");
    }

    private static JsonObject tradeCategoryAdd(JsonObject body) {
        String id = str(body, "id");
        String name = str(body, "name");
        if (id.isEmpty()) {
            return err("缺少分类 id");
        }
        if (com.deltanexus.system.trade.TradeConfig.get().hasCategory(id)) {
            return err("分类 " + id + " 已存在");
        }
        com.deltanexus.system.trade.TradeConfig.get().categoriesMutable()
                .put(id, new com.deltanexus.system.trade.TradeCategory(id, name.isEmpty() ? id : name));
        com.deltanexus.system.trade.TradeConfig.get().saveDebounced();
        tradeBroadcast();
        return ok("已新增分类 " + id);
    }

    private static JsonObject tradeCategoryRename(JsonObject body) {
        String id = str(body, "id");
        String name = str(body, "name");
        com.deltanexus.system.trade.TradeCategory c = com.deltanexus.system.trade.TradeConfig.get().category(id);
        if (c == null) {
            return err("分类 " + id + " 不存在");
        }
        c.name = name.isEmpty() ? id : name;
        com.deltanexus.system.trade.TradeConfig.get().saveDebounced();
        tradeBroadcast();
        return ok("分类 " + id + " 已重命名");
    }

    private static JsonObject tradeCategoryRemove(JsonObject body) {
        String id = str(body, "id");
        if (!com.deltanexus.system.trade.TradeConfig.get().hasCategory(id)) {
            return err("分类 " + id + " 不存在");
        }
        com.deltanexus.system.trade.TradeConfig.get().categoriesMutable().remove(id);
        com.deltanexus.system.trade.TradeConfig.get().saveDebounced();
        tradeBroadcast();
        return ok("已删除分类 " + id + "（其下商品已停显，请到商品内移除/改分类）");
    }

    private static JsonObject tradeGoodSave(JsonObject body) {
        if (!body.has("good") || !body.get("good").isJsonObject()) {
            return err("缺少 good 对象");
        }
        com.deltanexus.system.trade.TradeConfig cfg = com.deltanexus.system.trade.TradeConfig.get();
        com.deltanexus.system.trade.TradeGood g =
                com.deltanexus.system.trade.TradeGood.fromJson(body.getAsJsonObject("good"));
        if (g.id.isEmpty()) {
            return err("商品缺少 id");
        }
        if (g.categoryId.isEmpty()) {
            if (cfg.categoriesSnapshot().isEmpty()) {
                return err("请先创建至少一个分类");
            }
            g.categoryId = cfg.categoriesSnapshot().get(0).id;
        }
        if (!cfg.hasCategory(g.categoryId)) {
            return err("分类 " + g.categoryId + " 不存在");
        }
        String issue = g.validate(cfg);
        if (issue != null) {
            return err("商品配置不合法: " + issue);
        }
        cfg.goodsMutable().put(g.id, g);
        cfg.saveDebounced();
        tradeBroadcast();
        return ok("商品 " + g.id + " 已保存");
    }

    private static JsonObject tradeGoodRemove(JsonObject body) {
        String id = str(body, "id");
        if (!com.deltanexus.system.trade.TradeConfig.get().hasGood(id)) {
            return err("商品 " + id + " 不存在");
        }
        com.deltanexus.system.trade.TradeConfig.get().goodsMutable().remove(id);
        com.deltanexus.system.trade.TradeConfig.get().saveDebounced();
        tradeBroadcast();
        return ok("已删除商品 " + id);
    }

    private static JsonObject tradeStock(JsonObject body) {
        String id = str(body, "id");
        String op = str(body, "op");
        int count = body.has("count") ? Math.max(0, body.get("count").getAsInt()) : 0;
        if (!com.deltanexus.system.trade.TradeConfig.get().hasGood(id)) {
            return err("商品 " + id + " 不存在");
        }
        int after;
        if ("set".equalsIgnoreCase(op)) {
            after = com.deltanexus.system.trade.TradeStockStore.set(id, count);
        } else {
            after = com.deltanexus.system.trade.TradeStockStore.add(id, count);
        }
        tradeBroadcast();
        return ok("商品 " + id + " 库存现为 " + after);
    }

    private static JsonObject tradeFeedRefresh() {
        int n = 0;
        for (com.deltanexus.system.trade.MarketFeed f : com.deltanexus.system.trade.TradeFeedRegistry.all()) {
            try {
                f.refreshIfStale(0);
                n++;
            } catch (Exception e) {
                DeltaNexus.LOGGER.warn("[DN] Web 请求刷新交易行源 '{}' 异常: {}", f.id(), e.getMessage());
            }
        }
        return ok("已请求刷新 " + n + " 个外部价格源");
    }

    private static String str(JsonObject body, String key) {
        return body.has(key) && body.get(key).isJsonPrimitive() ? body.get(key).getAsString().trim() : "";
    }

    private static boolean boolOf(JsonObject body, String key, boolean dflt) {
        return body.has(key) && body.get(key).isJsonPrimitive() ? body.get(key).getAsBoolean() : dflt;
    }

    private static int intOf(JsonObject body, String key, int dflt, int min, int max) {
        if (!body.has(key) || !body.get(key).isJsonPrimitive()) {
            return dflt;
        }
        try {
            return Math.max(min, Math.min(max, body.get(key).getAsInt()));
        } catch (Exception e) {
            return dflt;
        }
    }

    private static double doubleOf(JsonObject body, String key, double dflt, double min, double max) {
        if (!body.has(key) || !body.get(key).isJsonPrimitive()) {
            return dflt;
        }
        try {
            return Math.max(min, Math.min(max, body.get(key).getAsDouble()));
        } catch (Exception e) {
            return dflt;
        }
    }

    // ------------------------------------------------------------------
    // 刷兵系统（0.4.0Beta；Web 配置页，0.5.0Beta 加入）
    // ------------------------------------------------------------------

    /**
     * 刷兵配置快照（概览载荷的 {@code spawner} 段）。
     *
     * <p>结构：{@code global}（全局保护）/ {@code logging}（日志）/
     * {@code world}（本存档的刷兵器 + 点位组 + 当前维度列表）/ {@code point_names}（全存档点名，
     * 供点位组下拉使用）。刷兵器条目里附带 {@code _effective_points}
     * （按 {@code point} 引用解析出的实际点位名，含 {@code group:} 展开）与 {@code _point_count}，
     * 前端不必重复实现解析逻辑。</p>
     *
     * <p>包内可见：回归测试直接断言字段结构。</p>
     */
    static JsonObject spawnerJson() {
        JsonObject root = new JsonObject();

        // 全局保护
        GlobalConfig g = GlobalConfig.get();
        JsonObject global = new JsonObject();
        global.addProperty("enabled", g.enabled);
        JsonArray blacklist = new JsonArray();
        for (String p : g.blacklistedWorldPatterns) {
            blacklist.add(p);
        }
        global.add("blacklisted_world_patterns", blacklist);
        global.add("limits", g.limits.toJson());
        root.add("global", global);

        // 日志
        LogConfig l = LogConfig.get();
        JsonObject logging = new JsonObject();
        logging.addProperty("log_level", l.level);
        logging.addProperty("log_successful_spawns", l.logSuccessfulSpawns);
        logging.addProperty("log_failed_spawns", l.logFailedSpawns);
        logging.addProperty("log_condition_rejections", l.logConditionRejections);
        logging.addProperty("log_to_console", l.logToConsole);
        logging.addProperty("log_to_file", l.logToFile);
        logging.addProperty("log_file_path", l.logFilePath);
        logging.add("levels", levelsArray());
        root.add("logging", logging);

        // 世界层（本存档一份：saves/<世界>/deltanexus/spawners.json + pointgroups.json）
        JsonObject world = new JsonObject();
        JsonArray dimensions = new JsonArray();
        JsonObject spawners = new JsonObject();
        JsonObject groups = new JsonObject();
        JsonArray pointNames = new JsonArray();
        net.minecraft.server.MinecraftServer mc = currentServer;
        if (mc != null) {
            for (ServerLevel level : mc.getAllLevels()) {
                JsonObject d = new JsonObject();
                ResourceLocation key = level.dimension().location();
                d.addProperty("id", key.toString());
                d.addProperty("name", key.getPath());
                dimensions.add(d);
            }
            SpawnerWorldStore store = SpawnerManager.world(mc);
            for (Map.Entry<String, Spawner> e : store.spawnersMutable().entrySet()) {
                Spawner s = e.getValue();
                JsonObject o = s.toJson();
                List<String> effective = store.resolvePointTargets(s);
                JsonArray ep = new JsonArray();
                for (String p : effective) {
                    ep.add(p);
                }
                o.add("_effective_points", ep);
                o.addProperty("_point_count", effective.size());
                o.addProperty("_has_entity", (s.entity != null && !s.entity.isBlank())
                        || (s.entityPool != null && !s.entityPool.isEmpty()));
                spawners.add(e.getKey(), o);
            }
            for (Map.Entry<String, List<String>> e : store.groups().entrySet()) {
                JsonArray arr = new JsonArray();
                for (String p : e.getValue()) {
                    arr.add(p);
                }
                groups.add(e.getKey(), arr);
            }
            for (String p : store.collectPointNames("*")) {
                pointNames.add(p);
            }
            world.addProperty("folder", store.dir().toString());
        }
        world.add("dimensions", dimensions);
        world.add("spawners", spawners);
        world.add("groups", groups);
        root.add("world", world);
        root.add("point_names", pointNames);
        return root;
    }

    private static JsonArray levelsArray() {
        JsonArray arr = new JsonArray();
        for (String lvl : new String[]{LogConfig.LOG_OFF, LogConfig.LOG_ERROR, LogConfig.LOG_WARN,
                LogConfig.LOG_INFO, LogConfig.LOG_DEBUG}) {
            arr.add(lvl);
        }
        return arr;
    }

    /** 保存刷兵全局保护配置（enabled / 黑名单世界 / 全局上限）。 */
    private static JsonObject spawnerGlobal(JsonObject body) {
        GlobalConfig g = GlobalConfig.get();
        g.enabled = boolOf(body, "enabled", g.enabled);
        if (body.has("blacklisted_world_patterns") && body.get("blacklisted_world_patterns").isJsonArray()) {
            g.blacklistedWorldPatterns.clear();
            for (JsonElement el : body.getAsJsonArray("blacklisted_world_patterns")) {
                if (el.isJsonPrimitive()) {
                    String s = el.getAsString().trim();
                    if (!s.isEmpty() && !g.blacklistedWorldPatterns.contains(s)) {
                        g.blacklistedWorldPatterns.add(s);
                    }
                }
            }
        }
        if (body.has("limits") && body.get("limits").isJsonObject()) {
            JsonObject lim = body.getAsJsonObject("limits");
            g.limits.maxTotalEntitiesPerWorld = intOf(lim, "max_total_entities_per_world",
                    g.limits.maxTotalEntitiesPerWorld, 0, 1_000_000);
            g.limits.maxEntitiesPerChunk = intOf(lim, "max_entities_per_chunk",
                    g.limits.maxEntitiesPerChunk, 0, 100_000);
            g.limits.minTpsToAllowSpawn = doubleOf(lim, "min_tps_to_allow_spawn",
                    g.limits.minTpsToAllowSpawn, 0, 20);
            g.limits.minFreeMemoryPercent = intOf(lim, "min_free_memory_percent",
                    g.limits.minFreeMemoryPercent, 0, 100);
        }
        g.saveNow();
        return ok("刷兵全局保护配置已保存（enabled=" + g.enabled + "，黑名单 "
                + g.blacklistedWorldPatterns.size() + " 条）");
    }

    /** 保存刷兵日志配置（级别 / 各类开关 / 文件路径）。 */
    private static JsonObject spawnerLogging(JsonObject body) {
        LogConfig l = LogConfig.get();
        String level = str(body, "log_level").toLowerCase(java.util.Locale.ROOT);
        if (!level.isEmpty()) {
            boolean valid = false;
            for (JsonElement el : levelsArray()) {
                if (el.getAsString().equals(level)) {
                    valid = true;
                    break;
                }
            }
            if (!valid) {
                return err("日志级别应为 " + String.join(" / ", LogConfig.LOG_OFF, LogConfig.LOG_ERROR,
                        LogConfig.LOG_WARN, LogConfig.LOG_INFO, LogConfig.LOG_DEBUG));
            }
            l.level = level;
        }
        l.logSuccessfulSpawns = boolOf(body, "log_successful_spawns", l.logSuccessfulSpawns);
        l.logFailedSpawns = boolOf(body, "log_failed_spawns", l.logFailedSpawns);
        l.logConditionRejections = boolOf(body, "log_condition_rejections", l.logConditionRejections);
        l.logToConsole = boolOf(body, "log_to_console", l.logToConsole);
        l.logToFile = boolOf(body, "log_to_file", l.logToFile);
        String path = str(body, "log_file_path");
        if (!path.isEmpty()) {
            l.logFilePath = path;
        }
        l.saveNow();
        return ok("刷兵日志配置已保存（level=" + l.level + "，控制台=" + l.logToConsole
                + "，文件=" + l.logToFile + "）");
    }

    /**
     * 世界层保存（按名字<b>合并</b>，不整份覆盖）。
     *
     * <p>请求体：{@code spawners}（名字 → 刷兵器 JSON）、{@code groups}（组名 → 点名数组）、
     * {@code removed_spawners} / {@code removed_groups}（显式删除列表）。
     * 采用合并语义是为了安全：两个管理员（或指令 + 网页）交替编辑时，不会因为一方持有旧快照
     * 就把另一方新建的刷兵器抹掉；删除只能通过显式的删除列表发生。</p>
     */
    private static JsonObject spawnerWorldSave(JsonObject body) {
        net.minecraft.server.MinecraftServer mc = currentServer;
        if (mc == null || !mc.isRunning()) {
            return err("服务器未运行");
        }
        SpawnerWorldStore store = SpawnerManager.world(mc);
        JsonObject result = applyWorldConfig(store, body);
        if (!result.get("ok").getAsBoolean()) {
            return result;
        }
        store.saveNow();
        result.addProperty("msg", result.get("msg").getAsString() + "（" + store.dir() + "）");
        return result;
    }

    /**
     * 世界层合并（<b>与运行中的服务器无关</b>，便于回归测试）：按名字合并/更新刷兵器与点位组，
     * 只删除 {@code removed_spawners} / {@code removed_groups} 里显式列出的条目。
     *
     * <p>合并语义是为了安全：两个管理员（或指令 + 网页）交替编辑时，不会因为一方持有旧快照
     * 就把另一方新建的刷兵器抹掉。写盘由调用方负责（{@link #spawnerWorldSave} 会调
     * {@link SpawnerWorldStore#saveNow()}）。</p>
     */
    static JsonObject applyWorldConfig(SpawnerWorldStore store, JsonObject body) {
        int added = 0;
        int updated = 0;
        int removed = 0;

        if (body.has("removed_spawners") && body.get("removed_spawners").isJsonArray()) {
            for (JsonElement el : body.getAsJsonArray("removed_spawners")) {
                if (el.isJsonPrimitive() && store.spawnersMutable().remove(el.getAsString().trim()) != null) {
                    removed++;
                }
            }
        }
        if (body.has("removed_groups") && body.get("removed_groups").isJsonArray()) {
            for (JsonElement el : body.getAsJsonArray("removed_groups")) {
                if (el.isJsonPrimitive() && store.groups().remove(el.getAsString().trim()) != null) {
                    removed++;
                }
            }
        }

        if (body.has("spawners") && body.get("spawners").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : body.getAsJsonObject("spawners").entrySet()) {
                String name = e.getKey() == null ? "" : e.getKey().trim();
                if (!validSpawnerName(name)) {
                    return err("非法刷兵器名：" + name + "（允许字母/数字/下划线/连字符/点/冒号，1~64 字）");
                }
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                Spawner parsed = Spawner.fromJson(stripInternal(e.getValue().getAsJsonObject()));
                if (store.has(name)) {
                    updated++;
                } else {
                    added++;
                }
                store.spawnersMutable().put(name, parsed);
            }
        }

        if (body.has("groups") && body.get("groups").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : body.getAsJsonObject("groups").entrySet()) {
                String gname = e.getKey() == null ? "" : e.getKey().trim();
                if (gname.isEmpty() || !e.getValue().isJsonArray()) {
                    continue;
                }
                List<String> list = new ArrayList<>();
                for (JsonElement el : e.getValue().getAsJsonArray()) {
                    if (el.isJsonPrimitive()) {
                        String p = el.getAsString().trim();
                        if (!p.isEmpty() && !list.contains(p)) {
                            list.add(p);
                        }
                    }
                }
                store.groups().put(gname, list);
            }
        }

        return ok("刷兵配置已保存：新增 " + added + " / 更新 " + updated + " / 删除 " + removed);
    }

    /** 刷兵器名合法性（与指令口径一致：非空、无空格、长度受限）。 */
    private static boolean validSpawnerName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 64
                && name.matches("[A-Za-z0-9_\\-.:]+");
    }

    /** 去掉编辑器附带的展示字段（以 {@code _} 开头），避免写进配置文件。 */
    private static JsonObject stripInternal(JsonObject src) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : src.entrySet()) {
            if (e.getKey() != null && !e.getKey().startsWith("_")) {
                out.add(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** 全量重载刷兵配置（等同于 {@code /dn reload} 里的那一步）。 */
    private static JsonObject spawnerReload() {
        SpawnerManager.reloadAll(currentServer);
        return ok("刷兵配置已重载（全局 + 日志 + 各世界文件）");
    }

    /**
     * 预览 / 干跑 / 执行（{@code action = preview | dry-run | run}）。
     *
     * <p>遵守「零活动」设计：只有这次显式请求会刷一次，不注册任何定时/冷却。
     * {@code dimension} 缺省用主世界；返回刷兵服务产出的文本（成功/拒绝原因）。</p>
     */
    private static JsonObject spawnerAction(JsonObject body) {
        net.minecraft.server.MinecraftServer mc = currentServer;
        if (mc == null || !mc.isRunning()) {
            return err("服务器未运行");
        }
        String name = str(body, "name");
        if (name.isEmpty()) {
            return err("缺少刷兵器名");
        }
        SpawnerWorldStore store = SpawnerManager.world(mc);
        if (!store.has(name)) {
            return err("刷兵器不存在：" + name);
        }
        String action = str(body, "action").toLowerCase(java.util.Locale.ROOT);
        ServerLevel level = resolveLevel(mc, str(body, "dimension"));
        if (level == null) {
            return err("未知维度：" + str(body, "dimension"));
        }
        CommandSourceStack source = mc.createCommandSourceStack().withLevel(level);
        try {
            String out = switch (action) {
                case "preview" -> SpawnerService.preview(source, name, level).getString();
                case "dry-run" -> SpawnerService.run(source, name, level, true).getString();
                case "run" -> SpawnerService.run(source, name, level, false).getString();
                default -> null;
            };
            if (out == null) {
                return err("未知动作：" + action + "（可用 preview / dry-run / run）");
            }
            // 指令文本带 § 颜色码：网页是纯文本，去掉后再回传
            String clean = out.replaceAll("\u00A7.", "").trim();
            return ok(clean.isEmpty() ? (action + " " + name + " 已完成") : clean);
        } catch (Throwable t) {
            return err("执行失败：" + t);
        }
    }

    /** 维度 id → ServerLevel（空/未知返回主世界；确实未知时返回 null）。 */
    private static ServerLevel resolveLevel(net.minecraft.server.MinecraftServer mc, String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return mc.overworld();
        }
        ResourceLocation key = ResourceLocation.tryParse(dimensionId.trim());
        if (key == null) {
            return null;
        }
        ServerLevel level = mc.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, key));
        return level;
    }

    /** 刷兵可选项：注册表 id（实体 / 粒子 / 音效），供前端 datalist 建议。 */
    private static JsonObject spawnerRegistry() {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.add("entity", registryIds(ForgeRegistries.ENTITY_TYPES.getKeys()));
        root.add("particle", registryIds(ForgeRegistries.PARTICLE_TYPES.getKeys()));
        root.add("sound", registryIds(ForgeRegistries.SOUND_EVENTS.getKeys()));
        return root;
    }

    private static JsonArray registryIds(java.util.Set<ResourceLocation> keys) {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation key : keys) {
            ids.add(key.toString());
        }
        java.util.Collections.sort(ids);
        JsonArray arr = new JsonArray();
        for (String id : ids) {
            arr.add(id);
        }
        return arr;
    }
}
