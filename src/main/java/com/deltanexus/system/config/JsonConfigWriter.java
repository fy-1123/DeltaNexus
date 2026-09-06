package com.deltanexus.system.config;

import com.deltanexus.system.DeltaNexus;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * JSON 配置写入器。
 *
 * <p>约束：</p>
 * <ul>
 *   <li>禁止在数据包接收线程中直接读写文件 —— 所有写入均提交到独立线程池；</li>
 *   <li>同一文件的连续写入请求做 500ms 防反跳（阶段 6），期间新请求重置计时器；</li>
 *   <li>写入采用「临时文件 + 原子移动」，避免写一半的损坏文件。</li>
 * </ul>
 */
public final class JsonConfigWriter {

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dn-JsonWriter");
        t.setDaemon(true);
        return t;
    });

    private static final Map<Path, ScheduledFuture<?>> PENDING = new ConcurrentHashMap<>();

    private JsonConfigWriter() {
    }

    /**
     * 防反跳写入：同路径 500ms 内的连续请求只保留最后一次。
     * 默认防反跳时长取 {@link ModConfig#saveDebounceMs()}。
     */
    public static void saveJsonDebounced(Path path, JsonObject json) {
        saveJsonDebounced(path, json, ModConfig.saveDebounceMs());
    }

    public static void saveJsonDebounced(Path path, JsonObject json, long debounceMs) {
        if (debounceMs <= 0) {
            saveJson(path, json);
            return;
        }
        ScheduledFuture<?> previous = PENDING.remove(path);
        if (previous != null) {
            previous.cancel(false);
        }
        ScheduledFuture<?> future = SCHEDULER.schedule(() -> {
            PENDING.remove(path);
            writeJson(path, json);
        }, debounceMs, TimeUnit.MILLISECONDS);
        PENDING.put(path, future);
    }

    /** 立即异步写入（仍不阻塞调用线程）。 */
    public static void saveJson(Path path, JsonObject json) {
        SCHEDULER.execute(() -> writeJson(path, json));
    }

    /** 同步写入（仅供启动时生成默认配置使用）。 */
    public static void writeJsonNow(Path path, JsonObject json) {
        writeJson(path, json);
    }

    private static void writeJson(Path path, JsonObject json) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            DeltaNexus.LOGGER.error("[DN] JSON 写入失败 {}: {}", path, e.getMessage());
        }
    }

    /** 读取 JSON 文件；不存在或非法返回 null。 */
    public static JsonObject readJson(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] JSON 读取失败 {}: {}", path, e.getMessage());
            return null;
        }
    }
}
