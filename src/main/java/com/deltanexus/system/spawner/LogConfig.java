package com.deltanexus.system.spawner;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.JsonConfigWriter;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 刷兵日志配置（{@code config/deltanexus/logging.json}）。
 * 日志级别 {@code off/error/warn/info/debug}；按级别与各开关决定打印与写文件。
 */
public final class LogConfig {

    public static final String LOG_OFF = "off";
    public static final String LOG_ERROR = "error";
    public static final String LOG_WARN = "warn";
    public static final String LOG_INFO = "info";
    public static final String LOG_DEBUG = "debug";

    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("deltanexus/logging.json");

    private static volatile LogConfig INSTANCE;

    public String level = LOG_INFO;
    public boolean logSuccessfulSpawns = true;
    public boolean logFailedSpawns = true;
    public boolean logConditionRejections = true;
    public boolean logToConsole = true;
    public boolean logToFile = true;
    public String logFilePath = "logs/deltanexus_spawn.log";

    private LogConfig() {
    }

    public static LogConfig get() {
        LogConfig c = INSTANCE;
        if (c == null) {
            synchronized (LogConfig.class) {
                c = INSTANCE;
                if (c == null) {
                    c = new LogConfig();
                    INSTANCE = c;
                }
            }
        }
        return c;
    }

    public static void writeDefaultIfMissing() {
        LogConfig c = get();
        if (Files.exists(PATH)) {
            c.reload();
        } else {
            c.saveNow();
            DeltaNexus.LOGGER.info("[DN] 已生成刷兵日志配置 {}", PATH);
        }
    }

    public synchronized void reload() {
        JsonObject root = JsonConfigWriter.readJson(PATH);
        if (root == null) {
            DeltaNexus.LOGGER.warn("[DN] 刷兵日志配置缺失或非法，已重置为默认");
            return;
        }
        if (root.has("log_level")) {
            level = normalize(root.get("log_level").getAsString());
        }
        logSuccessfulSpawns = !root.has("log_successful_spawns") || root.get("log_successful_spawns").getAsBoolean();
        logFailedSpawns = !root.has("log_failed_spawns") || root.get("log_failed_spawns").getAsBoolean();
        logConditionRejections = !root.has("log_condition_rejections") || root.get("log_condition_rejections").getAsBoolean();
        logToConsole = !root.has("log_to_console") || root.get("log_to_console").getAsBoolean();
        logToFile = !root.has("log_to_file") || root.get("log_to_file").getAsBoolean();
        if (root.has("log_file_path")) {
            logFilePath = root.get("log_file_path").getAsString();
        }
    }

    public synchronized void saveNow() {
        JsonObject root = new JsonObject();
        root.addProperty("log_level", level);
        root.addProperty("log_successful_spawns", logSuccessfulSpawns);
        root.addProperty("log_failed_spawns", logFailedSpawns);
        root.addProperty("log_condition_rejections", logConditionRejections);
        root.addProperty("log_to_console", logToConsole);
        root.addProperty("log_to_file", logToFile);
        root.addProperty("log_file_path", logFilePath);
        JsonConfigWriter.writeJsonNow(PATH, root);
    }

    /** 级别转数字（off=0, error=1, warn=2, info=3, debug=4），用于比较。 */
    private int rank() {
        return switch (level) {
            case LOG_OFF -> 0;
            case LOG_ERROR -> 1;
            case LOG_WARN -> 2;
            case LOG_DEBUG -> 4;
            default -> 3; // info
        };
    }

    private static String normalize(String s) {
        return switch (s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case LOG_OFF, LOG_ERROR, LOG_WARN, LOG_DEBUG -> s.trim().toLowerCase(java.util.Locale.ROOT);
            default -> LOG_INFO;
        };
    }

    /** 是否允许输出该级别日志。 */
    public boolean allows(String lvl) {
        if (LOG_OFF.equals(level)) {
            return false;
        }
        int target = switch (lvl) {
            case LOG_OFF -> 0;
            case LOG_ERROR -> 1;
            case LOG_WARN -> 2;
            case LOG_INFO -> 3;
            case LOG_DEBUG -> 4;
            default -> 3;
        };
        return target <= rank();
    }

    /** 写一条刷兵日志：按配置打印控制台 + 追加写文件。 */
    public synchronized void log(String msg) {
        if (logToConsole) {
            DeltaNexus.LOGGER.info("[DN][刷兵] {}", msg);
        }
        if (logToFile) {
            Path p = Path.of(logFilePath);
            if (!p.isAbsolute()) {
                p = FMLPaths.GAMEDIR.get().resolve(logFilePath);
            }
            try {
                Files.createDirectories(p.getParent());
                try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write("[" + java.time.LocalDateTime.now() + "] " + msg + System.lineSeparator());
                }
            } catch (IOException e) {
                DeltaNexus.LOGGER.warn("[DN] 刷兵日志写文件失败 {}: {}", p, e.getMessage());
            }
        }
    }
}