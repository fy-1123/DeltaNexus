package com.deltanexus.system.spawner;

import com.deltanexus.system.DeltaNexus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 刷兵系统总管理器。
 *
 * <p>遵守「零主动」：不监听加载/卸载事件；配置以「世界文件夹路径」为键缓存于
 * {@link #stores}，首次访问时懒加载，{@code /dn reload} 时全量重读。</p>
 */
public final class SpawnerManager {

    private static final Map<String, SpawnerWorldStore> STORES = new ConcurrentHashMap<>();

    private SpawnerManager() {
    }

    /** 启动生成全局配置与日志配置（全球层）；世界层配置懒加载。 */
    public static void writeDefaultsIfMissing() {
        GlobalConfig.writeDefaultIfMissing();
        LogConfig.writeDefaultIfMissing();
    }

    // ------------------------------------------------------------------
    // 世界层
    // ------------------------------------------------------------------

    private static String keyOf(MinecraftServer server) {
        return normalize(server.getWorldPath(LevelResource.ROOT));
    }

    private static String normalize(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    /** 根据世界获取（懒加载并缓存）配置。以世界文件夹路径为键。 */
    public static SpawnerWorldStore world(ServerLevel level) {
        return world(level.getServer());
    }

    public static SpawnerWorldStore world(MinecraftServer server) {
        String key = keyOf(server);
        return STORES.computeIfAbsent(key, k -> {
            SpawnerWorldStore store = new SpawnerWorldStore(Path.of(k));
            store.load();
            store.logLoadSummary();
            return store;
        });
    }

    /** 获取某世界文件夹路径对应的存储；不存在则返回 null。 */
    public static SpawnerWorldStore byFolder(Path worldFolderPath) {
        return STORES.get(normalize(worldFolderPath));
    }

    // ------------------------------------------------------------------
    // 重载
    // ------------------------------------------------------------------

    /** 全量重载：全球层配置 + 已加载世界的世界层配置（/dn reload 触发）。 */
    public static void reloadAll(MinecraftServer server) {
        synchronized (STORES) {
            GlobalConfig.get().reload();
            LogConfig.get().reload();
            // 刷新世界里已知存储
            for (SpawnerWorldStore store : STORES.values()) {
                store.load();
            }
            // 确保当前已加载的世界都有缓存（单人/服务器），并为空世界种子默认文件
            if (server != null) {
                String key = keyOf(server);
                if (!STORES.containsKey(key)) {
                    SpawnerWorldStore store = new SpawnerWorldStore(Path.of(key));
                    store.load();
                    store.saveNow();
                    STORES.put(key, store);
                    store.logLoadSummary();
                }
            }
        }
        DeltaNexus.LOGGER.info("[DN] 刷兵系统配置热加载完成：刷兵文件见各世界 deltanexus/ 目录");
    }

    /** 世界卸载时清理缓存（文件保留，符合设计：世界名后缀无关、不持有世界引用）。 */
    public static void clearAll() {
        STORES.clear();
    }
}