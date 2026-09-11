package com.deltanexus.system.server;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.JsonConfigWriter;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限管理（1.1.0Alpha / 2.0.3Alpha / 2.1Alpha）：控制玩家能否通过指令/按键打开仓库、配方工作台、特勤处与安全箱。
 *
 * <p>配置文件 {@code config/deltanexus/permissions.json}（热加载，/dn reload 生效）：</p>
 * <pre>{@code
 * {
 *   "op_exempt": true,             // OP（权限等级 4）是否豁免权限判定（2.1Alpha）
 *   "default_warehouse": true,     // 全局默认：是否允许打开仓库
 *   "default_workbench": true,     // 全局默认：是否允许打开配方工作台
 *   "default_special": true,       // 全局默认：是否允许打开特勤处
 *   "default_safe_box": true,      // 全局默认：是否允许打开安全箱
 *   "players": {                   // 按玩家覆盖（键名为小写玩家名）
 *     "steve": { "warehouse": false, "workbench": true, "special": false, "safe_box": false, "features": false }
 *   }
 * }
 * }</pre>
 *
 * <p>规则：默认 OP（权限等级 4）始终允许（op_exempt=false 时 OP 同样受权限限制）；
 * 否则先查玩家覆盖，再查全局默认。权限判定在服务端打开仓库/工作台/特勤处/安全箱交互时强制执行
 * （指令与按键同源）。玩家 {@code features=false}（2.1Alpha）为硬开关：禁用该玩家全部 mod 功能，
 * 客户端 UI 恢复原版（由 SyncServerUiPacket 同步）。</p>
 */
public final class PermissionManager {

    /** 权限类型索引。 */
    public static final int TYPE_WAREHOUSE = 0;
    public static final int TYPE_WORKBENCH = 1;
    /** 特勤处。 */
    public static final int TYPE_SPECIAL = 2;
    /** 安全箱（2.1Alpha：独立权限，不再跟随仓库）。 */
    public static final int TYPE_SAFE_BOX = 3;
    /** 交易行（0.2.0Beta）。 */
    public static final int TYPE_TRADE = 4;
    /** 权限类型键（与下标一一对应；指令/Web 展示用）。 */
    public static final String[] TYPE_KEYS = {"warehouse", "workbench", "special", "safe_box", "trade"};

    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("deltanexus/permissions.json");

    /** OP（权限等级 4）是否豁免权限判定（2.1Alpha：默认豁免；关闭后 OP 同样受权限限制）。 */
    private static volatile boolean opExempt = true;

    private static volatile boolean defaultWarehouse = true;
    private static volatile boolean defaultWorkbench = true;
    private static volatile boolean defaultSpecial = true;
    /** 安全箱。 */
    private static volatile boolean defaultSafeBox = true;
    /** 交易行。 */
    private static volatile boolean defaultTrade = true;
    /** 玩家名（小写） -> {warehouse, workbench, special, safe_box, trade}；null = 跟随全局默认（部分覆盖不冻结另一项）。 */
    private static final Map<String, Boolean[]> OVERRIDES = new ConcurrentHashMap<>();
    /** 禁用 mod 功能的玩家名（小写）（2.1Alpha：禁用后无法使用任何 mod 功能，UI 恢复原版）。 */
    private static final Set<String> FEATURES_DISABLED = ConcurrentHashMap.newKeySet();

    private PermissionManager() {
    }

    // ------------------------------------------------------------------
    // 加载 / 保存
    // ------------------------------------------------------------------

    public static synchronized void reload() {
        OVERRIDES.clear();
        FEATURES_DISABLED.clear();
        JsonObject root = JsonConfigWriter.readJson(PATH);
        if (root == null) {
            opExempt = true;
            defaultWarehouse = true;
            defaultWorkbench = true;
            defaultSpecial = true;
            defaultSafeBox = true;
            defaultTrade = true;
            return;
        }
        opExempt = root.has("op_exempt") ? root.get("op_exempt").getAsBoolean() : true;
        defaultWarehouse = boolOf(root, "default_warehouse", true);
        defaultWorkbench = boolOf(root, "default_workbench", true);
        defaultSpecial = boolOf(root, "default_special", true);
        defaultSafeBox = boolOf(root, "default_safe_box", true);
        defaultTrade = boolOf(root, "default_trade", true);
        if (root.has("players") && root.get("players").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
                JsonElement v = e.getValue();
                if (!v.isJsonObject()) {
                    continue;
                }
                JsonObject o = v.getAsJsonObject();
                OVERRIDES.put(e.getKey().toLowerCase(Locale.ROOT), new Boolean[]{
                        o.has("warehouse") ? o.get("warehouse").getAsBoolean() : null,
                        o.has("workbench") ? o.get("workbench").getAsBoolean() : null,
                        o.has("special") ? o.get("special").getAsBoolean() : null,
                        o.has("safe_box") ? o.get("safe_box").getAsBoolean() : null,
                        o.has("trade") ? o.get("trade").getAsBoolean() : null});
                if (o.has("features") && !o.get("features").getAsBoolean()) {
                    FEATURES_DISABLED.add(e.getKey().toLowerCase(Locale.ROOT));
                }
            }
        }
        DeltaNexus.LOGGER.info("[DN] 权限配置热加载完成：OP豁免={} 默认 仓库={} 工作台={} 特勤处={} 安全箱={} 交易行={}，覆盖 {} 人，禁用功能 {} 人",
                opExempt, defaultWarehouse, defaultWorkbench, defaultSpecial, defaultSafeBox, defaultTrade,
                OVERRIDES.size(), FEATURES_DISABLED.size());
    }

    /** 首次启动生成默认权限文件（全允许）。 */
    public static void writeDefaultIfMissing() {
        if (Files.exists(PATH)) {
            reload();
            return;
        }
        saveNow();
        DeltaNexus.LOGGER.info("[DN] 已生成默认权限配置 {}", PATH);
    }

    public static synchronized void saveNow() {
        JsonConfigWriter.writeJsonNow(PATH, toJson());
    }

    public static Path path() {
        return PATH;
    }

    public static JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("op_exempt", opExempt);
        root.addProperty("default_warehouse", defaultWarehouse);
        root.addProperty("default_workbench", defaultWorkbench);
        root.addProperty("default_special", defaultSpecial);
        root.addProperty("default_safe_box", defaultSafeBox);
        root.addProperty("default_trade", defaultTrade);
        JsonObject players = new JsonObject();
        for (Map.Entry<String, Boolean[]> e : OVERRIDES.entrySet()) {
            JsonObject o = new JsonObject();
            Boolean[] v = e.getValue();
            if (v.length > TYPE_WAREHOUSE && v[TYPE_WAREHOUSE] != null) {
                o.addProperty("warehouse", v[TYPE_WAREHOUSE]);
            }
            if (v.length > TYPE_WORKBENCH && v[TYPE_WORKBENCH] != null) {
                o.addProperty("workbench", v[TYPE_WORKBENCH]);
            }
            if (v.length > TYPE_SPECIAL && v[TYPE_SPECIAL] != null) {
                o.addProperty("special", v[TYPE_SPECIAL]);
            }
            if (v.length > TYPE_SAFE_BOX && v[TYPE_SAFE_BOX] != null) {
                o.addProperty("safe_box", v[TYPE_SAFE_BOX]);
            }
            if (v.length > TYPE_TRADE && v[TYPE_TRADE] != null) {
                o.addProperty("trade", v[TYPE_TRADE]);
            }
            // 2.1Alpha：禁用 mod 功能的玩家标记 features=false
            if (FEATURES_DISABLED.contains(e.getKey())) {
                o.addProperty("features", false);
            }
            players.add(e.getKey(), o);
        }
        // 仅禁用功能但无权限覆盖的玩家也要落盘
        for (String name : FEATURES_DISABLED) {
            if (!players.has(name)) {
                JsonObject o = new JsonObject();
                o.addProperty("features", false);
                players.add(name, o);
            }
        }
        root.add("players", players);
        return root;
    }

    private static boolean boolOf(JsonObject o, String key, boolean def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
    }

    // ------------------------------------------------------------------
    // 判定（服务端调用；OP 豁免由 opExempt 配置控制，2.1Alpha）
    // ------------------------------------------------------------------

    /** OP 是否豁免权限判定（config/deltanexus/permissions.json op_exempt，默认 true）。 */
    public static boolean opExempt() {
        return opExempt;
    }

    /** 设置 OP 豁免开关并落盘。 */
    public static synchronized void setOpExempt(boolean exempt) {
        opExempt = exempt;
        saveNow();
    }

    private static boolean opAllowed(ServerPlayer player) {
        return player != null && opExempt && player.hasPermissions(4);
    }

    public static boolean canOpenWarehouse(ServerPlayer player) {
        if (player == null || opAllowed(player)) {
            return true;
        }
        Boolean v = overrideOf(player, TYPE_WAREHOUSE);
        return v != null ? v : defaultWarehouse;
    }

    public static boolean canOpenWorkbench(ServerPlayer player) {
        if (player == null || opAllowed(player)) {
            return true;
        }
        Boolean v = overrideOf(player, TYPE_WORKBENCH);
        return v != null ? v : defaultWorkbench;
    }

    /** 特勤处：独立权限，默认允许。 */
    public static boolean canOpenSpecial(ServerPlayer player) {
        if (player == null || opAllowed(player)) {
            return true;
        }
        Boolean v = overrideOf(player, TYPE_SPECIAL);
        return v != null ? v : defaultSpecial;
    }

    /** 安全箱：独立权限，默认允许（不再跟随仓库权限）。 */
    public static boolean canOpenSafeBox(ServerPlayer player) {
        if (player == null || opAllowed(player)) {
            return true;
        }
        Boolean v = overrideOf(player, TYPE_SAFE_BOX);
        return v != null ? v : defaultSafeBox;
    }

    /** 交易行：独立权限，默认允许（与仓库权限同策略，可被 /dn perm 覆盖）。 */
    public static boolean canOpenTrade(ServerPlayer player) {
        if (player == null || opAllowed(player)) {
            return true;
        }
        Boolean v = overrideOf(player, TYPE_TRADE);
        return v != null ? v : defaultTrade;
    }

    /**
     * 玩家是否可使用 mod 功能（2.1Alpha）：禁用后无法使用任何 mod 功能，UI 恢复原版。
     * OP 同样受限（功能禁用为硬开关，不受 opExempt 影响）。
     */
    public static boolean canUseFeatures(ServerPlayer player) {
        return player == null || !FEATURES_DISABLED.contains(normalize(player.getGameProfile().getName()));
    }

    /** 玩家是否禁用 mod 功能（按名字，指令/Web 展示用）。 */
    public static boolean featuresDisabled(String name) {
        return FEATURES_DISABLED.contains(normalize(name));
    }

    /** 全部被禁用 mod 功能的玩家名（小写，指令/Web 展示用）。 */
    public static Set<String> disabledFeatures() {
        return Set.copyOf(FEATURES_DISABLED);
    }

    /** 设置玩家 mod 功能开关（enabled=false 禁用全部 mod 功能）。 */
    public static synchronized void setFeatures(String name, boolean enabled) {
        String key = normalize(name);
        if (enabled) {
            FEATURES_DISABLED.remove(key);
        } else {
            FEATURES_DISABLED.add(key);
        }
        saveNow();
    }

    /** 玩家覆盖值（null = 无覆盖，跟随全局默认）。 */
    public static Boolean overrideOf(ServerPlayer player, int type) {
        return getOverride(player.getGameProfile().getName(), type);
    }

    public static Boolean getOverride(String name, int type) {
        Boolean[] v = OVERRIDES.get(normalize(name));
        if (v == null) {
            return null;
        }
        return type >= 0 && type < v.length ? v[type] : null;
    }

    public static boolean hasOverride(String name) {
        return OVERRIDES.containsKey(normalize(name));
    }

    // ------------------------------------------------------------------
    // 修改（指令 / Web 调用）
    // ------------------------------------------------------------------

    /** 设置玩家权限覆盖（type = TYPE_WAREHOUSE / TYPE_WORKBENCH / TYPE_SPECIAL / TYPE_SAFE_BOX / TYPE_TRADE / -1 表示全部；
     *  未指定的类型保持 null（跟随全局默认，不冻结）。 */
    public static synchronized void setOverride(String name, int type, boolean allow) {
        String key = normalize(name);
        Boolean[] v = OVERRIDES.computeIfAbsent(key, k -> new Boolean[]{null, null, null, null, null});
        if (v.length < 5) {
            v = java.util.Arrays.copyOf(v, 5);
            OVERRIDES.put(key, v);
        }
        if (type == TYPE_WAREHOUSE || type < 0) {
            v[TYPE_WAREHOUSE] = allow;
        }
        if (type == TYPE_WORKBENCH || type < 0) {
            v[TYPE_WORKBENCH] = allow;
        }
        if (type == TYPE_SPECIAL || type < 0) {
            v[TYPE_SPECIAL] = allow;
        }
        if (type == TYPE_SAFE_BOX || type < 0) {
            v[TYPE_SAFE_BOX] = allow;
        }
        if (type == TYPE_TRADE || type < 0) {
            v[TYPE_TRADE] = allow;
        }
        saveNow();
    }

    /** 移除玩家权限覆盖（恢复全局默认）。 */
    public static synchronized boolean removeOverride(String name) {
        boolean removed = OVERRIDES.remove(normalize(name)) != null;
        if (removed) {
            saveNow();
        }
        return removed;
    }

    /** 设置全局默认（type = TYPE_WAREHOUSE / TYPE_WORKBENCH / TYPE_SPECIAL / TYPE_SAFE_BOX / TYPE_TRADE / -1 表示全部）。 */
    public static synchronized void setDefault(int type, boolean allow) {
        if (type == TYPE_WAREHOUSE || type < 0) {
            defaultWarehouse = allow;
        }
        if (type == TYPE_WORKBENCH || type < 0) {
            defaultWorkbench = allow;
        }
        if (type == TYPE_SPECIAL || type < 0) {
            defaultSpecial = allow;
        }
        if (type == TYPE_SAFE_BOX || type < 0) {
            defaultSafeBox = allow;
        }
        if (type == TYPE_TRADE || type < 0) {
            defaultTrade = allow;
        }
        saveNow();
    }

    public static boolean defaultOf(int type) {
        return switch (type) {
            case TYPE_WAREHOUSE -> defaultWarehouse;
            case TYPE_SPECIAL -> defaultSpecial;
            case TYPE_SAFE_BOX -> defaultSafeBox;
            case TYPE_TRADE -> defaultTrade;
            default -> defaultWorkbench;
        };
    }

    public static boolean defaultWarehouse() {
        return defaultWarehouse;
    }

    public static boolean defaultWorkbench() {
        return defaultWorkbench;
    }

    public static boolean defaultSpecial() {
        return defaultSpecial;
    }

    /** 安全箱。 */
    public static boolean defaultSafeBox() {
        return defaultSafeBox;
    }

    /** 交易行。 */
    public static boolean defaultTrade() {
        return defaultTrade;
    }

    /** 覆盖玩家名集合（小写）。 */
    public static Map<String, Boolean[]> overrides() {
        return OVERRIDES;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
