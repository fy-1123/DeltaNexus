package com.deltanexus.system.web;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.JsonConfigWriter;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Web 网页编辑器配置（config/deltanexus/web-editor.yml，YAML 格式）。
 *
 * <pre>{@code
 * web-editor:
 *   enabled: true      # 服务器启动时是否自动开启网页编辑器（/dn web on|off 会改写此项）
 *   host: 0.0.0.0      # 监听地址（默认 0.0.0.0，监听所有网卡）
 *   port: 21003        # 监听端口（默认 21003）
 *   public-url: ''     # 对外访问地址（公网 IP/域名/反向代理场景；留空则按 host:port 拼接）
 *   token_auth: true   # 是否启用 token 鉴权（默认 true；token 仅存内存，重启即失效，绝不落盘）
 * }</pre>
 *
 * <p>安全约束：监听地址/端口/鉴权只能由服主手动编辑本文件，
 * 指令不可修改（防社会工程学/防注入）。{@code /dn web on|off} 仅切换 enabled 并启停服务。</p>
 *
 * <p>访问令牌（token）只在内存中生成，不写入任何配置文件；每次启动 Web 服务重新生成，
 * 服务停止或服务器重启后旧 token 立即失效。</p>
 *
 * <p>实现说明：本文件使用极简 YAML 子集解析/写出（顶层一节 + 缩进键值标量），
 * 不依赖 SnakeYAML —— Forge 的模块化类加载使 org.yaml.snakeyaml 对 mod 不可见
 * （NoClassDefFoundError），零依赖实现可同时兼容纯 Forge 端与 Mohist 混合端。</p>
 */
public final class WebConfig {

    public static final int DEFAULT_PORT = 21003;
    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final boolean DEFAULT_TOKEN_AUTH = true;
    public static final boolean DEFAULT_ENABLED = true;

    /** 旧版 JSON 配置文件（web_config.json），仅首次迁移读取，之后不再使用。 */
    private static final String LEGACY_JSON_NAME = "web_config.json";

    private final Path path;
    private boolean enabled = DEFAULT_ENABLED;
    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;
    private boolean tokenAuth = DEFAULT_TOKEN_AUTH;
    private String publicUrl = "";

    private static WebConfig INSTANCE;

    private WebConfig(Path path) {
        this.path = path;
    }

    public static synchronized WebConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new WebConfig(FMLPaths.CONFIGDIR.get().resolve("deltanexus/web-editor.yml"));
            INSTANCE.load();
        }
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // 读取 / 写入（极简 YAML 子集）
    // ------------------------------------------------------------------

    private void load() {
        if (!Files.exists(path)) {
            migrateFromLegacyJson();
            save();
            DeltaNexus.LOGGER.info("[DN] 已生成 Web 编辑器配置 {}", path);
            return;
        }
        try {
            Map<String, String> kv = parseSimpleYaml(Files.readString(path, StandardCharsets.UTF_8));
            enabled = boolOf(kv.get("enabled"), DEFAULT_ENABLED);
            host = strOf(kv.get("host"), DEFAULT_HOST);
            port = intOf(kv.get("port"), DEFAULT_PORT);
            tokenAuth = boolOf(kv.get("token_auth"), DEFAULT_TOKEN_AUTH);
            publicUrl = strOf(kv.get("public-url"), "");
            DeltaNexus.LOGGER.info("[DN] Web 编辑器配置加载：{}:{}，enabled={}，token 鉴权 {}",
                    host, port, enabled, tokenAuth ? "开" : "关");
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] Web 编辑器配置解析失败，使用默认值: {}", e.toString());
        }
    }

    /** 保存为 YAML（键序固定，与参考格式一致，便于人工审阅）。 */
    public synchronized void save() {
        String content = "web-editor:\n"
                + "  enabled: " + enabled + "\n"
                + "  host: " + host + "\n"
                + "  port: " + port + "\n"
                + "  public-url: '" + publicUrl.replace("'", "''") + "'\n"
                + "  token_auth: " + tokenAuth + "\n";
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            DeltaNexus.LOGGER.error("[DN] Web 编辑器配置写入失败 {}: {}", path, e.getMessage());
        }
    }

    /**
     * 解析极简 YAML 子集：忽略空行与 # 注释；顶层键（无缩进）视为节名；
     * 节下「缩进 key: value」解析为键值（支持 true/false、整数、单/双引号与裸字符串）。
     * 本文件固定为 web-editor 一节，其余结构（锚点/列表/嵌套）不做支持。
     */
    private static Map<String, String> parseSimpleYaml(String content) {
        Map<String, String> out = new HashMap<>();
        String section = null;
        for (String raw : content.split("\r?\n")) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                // 顶层键：期望形如 "web-editor:"
                section = line.endsWith(":") ? line.substring(0, line.length() - 1).trim() : null;
                continue;
            }
            if (section == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            // 去掉内联注释（# 前导，且不在引号内——本配置均为简单值，直接截断）
            int hash = value.indexOf('#');
            if (hash >= 0) {
                value = value.substring(0, hash).trim();
            }
            // 去单/双引号
            if (value.length() >= 2
                    && ((value.startsWith("'") && value.endsWith("'"))
                    || (value.startsWith("\"") && value.endsWith("\"")))) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }

    /**
     * 旧版 JSON 配置（web_config.json）迁移：读取 host/port/token_auth 作为初始值。
     * token 一律不迁移（改为仅内存，旧 token 作废）。旧文件保留不动，仅作备份。
     */
    private void migrateFromLegacyJson() {
        Path legacy = path.getParent().resolve(LEGACY_JSON_NAME);
        if (!Files.exists(legacy)) {
            return;
        }
        JsonObject root = JsonConfigWriter.readJson(legacy);
        if (root == null) {
            return;
        }
        if (root.has("host")) {
            host = root.get("host").getAsString().trim();
        }
        if (root.has("port")) {
            port = clamp(root.get("port").getAsInt(), 1, 65535);
        }
        if (root.has("token_auth")) {
            tokenAuth = root.get("token_auth").getAsBoolean();
        }
        DeltaNexus.LOGGER.info("[DN] 已从旧配置 {} 迁移 host/port/token_auth，token 不再落盘", legacy);
    }

    private static boolean boolOf(String v, boolean def) {
        if (v == null) {
            return def;
        }
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v)) {
            return false;
        }
        return def;
    }

    private static int intOf(String v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return clamp(Integer.parseInt(v), 1, 65535);
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static String strOf(String v, String def) {
        if (v == null || v.isEmpty()) {
            return def;
        }
        return v;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    /** 服务器启动时是否自动开启网页编辑器（/dn web on|off 会改写并保存）。 */
    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean tokenAuth() {
        return tokenAuth;
    }

    /** 对外访问地址（public-url 非空时优先，用于公网 IP/域名/反向代理；留空则按 host:port 拼接）。 */
    public String publicUrl() {
        return publicUrl;
    }

    public Path path() {
        return path;
    }
}
