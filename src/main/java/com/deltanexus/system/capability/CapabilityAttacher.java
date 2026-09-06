package com.deltanexus.system.capability;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.config.ModConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Capability 绑定器。
 *
 * <p>阶段 6 要求：不依赖 {@code @CapabilityInject} 注解，
 * 改用 {@link AttachCapabilityEvent} 事件绑定 Capability 到玩家实体 ——
 * 这是最兼容 Forge 和 Mohist 混合服务端的方式。</p>
 *
 * <p>2.0.7 重构（死亡数据安全）：死亡 → 重生 → 登录三层防护统一收口到
 * {@link #restoreFromBackup}。修复旧版 {@code isEmptyData} 把「登录补齐的解锁位」
 * 误判为有数据、导致死亡备份恢复永不触发的问题；备份同时落盘
 * {@code config/deltanexus/backup/<uuid>.dat}（SNBT），服务器重启亦不丢失。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID)
public final class CapabilityAttacher {

    public static final Capability<IPlayerData> PLAYER_DATA =
            CapabilityManager.get(new CapabilityToken<>() { });

    private CapabilityAttacher() {
    }

    /** MOD 总线：注册能力类型。 */
    @Mod.EventBusSubscriber(modid = DeltaNexus.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class Register {
        @SubscribeEvent
        public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
            event.register(IPlayerData.class);
        }
    }

    /** FORGE 总线：绑定到玩家实体。 */
    @SubscribeEvent
    public static void onAttachCapability(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            event.addCapability(ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "player_data"), new PlayerDataImpl());
        }
    }

    /**
     * 死亡重生 / 维度切换时保留仓库与任务数据（强制完整序列化，避免空标签丢数据）。
     * 源数据为空（异常路径）时不再复制空数据，而是优先从死亡备份恢复。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        event.getOriginal().getCapability(PLAYER_DATA).ifPresent(old -> {
            boolean oldEmpty = old instanceof PlayerDataImpl impl && isEmptyData(impl);
            if (oldEmpty) {
                // 源数据为空（Clone 触发时机异常/源实体能力已被清空）：直接尝试从备份恢复，
                // 避免把空数据写入新实体（重生/登录兜底随后也会处理）
                restoreFromBackup(event.getEntity());
                return;
            }
            CompoundTag tag = old instanceof PlayerDataImpl impl
                    ? impl.serializeFull()
                    : old.serializeNBT();
            event.getEntity().getCapability(PLAYER_DATA).ifPresent(newData ->
                    newData.deserializeNBT(tag));
        });
    }

    // ------------------------------------------------------------------
    // 死亡数据备份（2.0.4 引入；2.0.7 重构：内存 + 磁盘双份，重启不丢）
    // ------------------------------------------------------------------

    /** 死亡备份目录（存档级持久化：死亡即写盘，服务器重启不丢失）。 */
    private static final Path BACKUP_DIR = FMLPaths.CONFIGDIR.get().resolve("deltanexus/backup");

    /** 死亡实体数据备份（内存快照；磁盘文件为持久兜底）。 */
    private static final java.util.Map<java.util.UUID, net.minecraft.nbt.CompoundTag> DEATH_BACKUP =
            new ConcurrentHashMap<>();

    private static Path backupPath(UUID uuid) {
        return BACKUP_DIR.resolve(uuid + ".dat");
    }

    /** 玩家死亡：备份 capability 完整数据（内存 + 磁盘，供重生/登录兜底恢复）。 */
    @SubscribeEvent
    public static void onPlayerDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            player.getCapability(PLAYER_DATA).ifPresent(d -> {
                try {
                    net.minecraft.nbt.CompoundTag tag = d instanceof PlayerDataImpl impl
                            ? impl.serializeFull() : d.serializeNBT();
                    if (tag != null && !tag.isEmpty()) {
                        DEATH_BACKUP.put(player.getUUID(), tag);
                        writeBackupFile(player.getUUID(), tag);
                        DeltaNexus.LOGGER.info("[DN] 玩家 {} 数据已备份：等级 {}，安全箱 Lv{}，磁盘 {}",
                                player.getGameProfile().getName(),
                                d.getWarehouseLevel(), d.getSafeBoxLevel(),
                                backupPath(player.getUUID()));
                    }
                } catch (Exception e) {
                    DeltaNexus.LOGGER.warn("[DN] 死亡备份失败: {}", e.getMessage());
                }
            });
        }
    }

    /** 写磁盘备份（SNBT；失败仅告警，不影响内存备份）。 */
    private static void writeBackupFile(UUID uuid, CompoundTag tag) {
        try {
            Files.createDirectories(BACKUP_DIR);
            Files.writeString(backupPath(uuid), tag.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] 死亡备份写盘失败: {}", e.getMessage());
        }
    }

    /** 读取备份：内存优先，磁盘兜底（磁盘文件可读则回填内存）。 */
    private static CompoundTag readBackup(UUID uuid) {
        CompoundTag mem = DEATH_BACKUP.get(uuid);
        if (mem != null && !mem.isEmpty()) {
            return mem;
        }
        try {
            Path p = backupPath(uuid);
            if (Files.exists(p)) {
                Tag parsed = TagParser.parseTag(Files.readString(p, StandardCharsets.UTF_8));
                if (parsed instanceof CompoundTag c && !c.isEmpty()) {
                    DEATH_BACKUP.put(uuid, c);
                    return c;
                }
            }
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] 死亡备份磁盘读取失败: {}", e.getMessage());
        }
        return null;
    }

    /** 清除备份（内存 + 磁盘）。恢复成功后调用，避免陈旧备份复活旧数据。 */
    private static void clearBackup(UUID uuid) {
        DEATH_BACKUP.remove(uuid);
        try {
            Files.deleteIfExists(backupPath(uuid));
        } catch (Exception ignored) {
        }
    }

    /**
     * 数据是否「没有玩家进度」（未初始化 / 被异常清空 = true）。
     *
     * <p>2.0.7 修复：不再检查解锁位 —— 新实体构造时即按 base_slots 预解锁 108 格、
     * 登录还会补齐 12 行，解锁位恒非空；旧实现因此永远判定「有数据」，
     * 导致死亡备份恢复永不触发（Mohist 等 Clone 不触发环境直接丢光数据）。
     * 判定只看等级/物品/任务等真实进度，与登录补齐的解锁位无关。</p>
     */
    private static boolean isEmptyData(PlayerDataImpl impl) {
        return impl.getWarehouseLevel() <= 0
                && impl.getSafeBoxLevel() <= 0
                && impl.getAllTasks().values().stream().allMatch(Deque::isEmpty)
                && IntStream.range(0, impl.getWarehouseHandler().getSlots())
                .allMatch(i -> impl.getWarehouseHandler().getStackInSlot(i).isEmpty())
                && IntStream.range(0, impl.getSafeBoxHandler().getSlots())
                .allMatch(i -> impl.getSafeBoxHandler().getStackInSlot(i).isEmpty());
    }

    /**
     * 统一恢复入口：当前数据无进度且存在备份时，从备份恢复完整数据
     * （仓库/安全箱/任务一体）。返回是否恢复成功。
     */
    private static boolean restoreFromBackup(Entity entity) {
        if (entity == null) {
            return false;
        }
        final boolean[] restored = {false};
        entity.getCapability(PLAYER_DATA).ifPresent(d -> {
            if (d instanceof PlayerDataImpl impl && isEmptyData(impl)) {
                CompoundTag backup = readBackup(entity.getUUID());
                if (backup != null) {
                    try {
                        impl.deserializeNBT(backup);
                        clearBackup(entity.getUUID());
                        restored[0] = true;
                        DeltaNexus.LOGGER.info("[DN] 已从死亡备份恢复玩家 {}：等级 {}，安全箱 Lv{}",
                                entity instanceof Player p ? p.getGameProfile().getName() : entity.getUUID(),
                                impl.getWarehouseLevel(), impl.getSafeBoxLevel());
                    } catch (Exception e) {
                        DeltaNexus.LOGGER.warn("[DN] 死亡备份恢复失败: {}", e.getMessage());
                    }
                }
            }
        });
        return restored[0];
    }

    /** 2.0.5：玩家重生后立即从死亡备份恢复（比登录兜底更早，覆盖 Clone 未触发的场景）。 */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        restoreFromBackup(event.getEntity());
    }

    /**
     * 玩家登录：在线模式下重置任务在线段开始时间（离线时间不计入制造计时）。
     * 登录事件在玩家数据加载完成后触发，capability 已可用。
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // 2.0.2：登录即推送格式背包配置（物品尺寸 + 快捷栏规则 + 类配置），客户端渲染与服务端一致
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.deltanexus.system.server.ManufacturingService.sendGridConfig(sp);
            // 2.0.9：登录即推送服务端 GUI 白名单（与客户端白名单取并集，命中任意即用原版 GUI）
            com.deltanexus.system.server.ManufacturingService.sendUiWhitelist(sp);
        }
        // 2.0.10：0 级玩家解锁由 base_slots 配置决定（默认 9 = 首行），
        // 升级通过升级树 unlockUpTo 逐步解锁更多行；渲染/滚动按玩家实际解锁行数展示，
        // 未解锁行完全不渲染。用 setUnlockedSlots（可缩小）纠正旧版本把 0 级强制解锁到
        // 视口 12 行（108 格）的历史数据——看玩家解锁了多少，而非仓库总行数/视口行数。
        event.getEntity().getCapability(PLAYER_DATA).ifPresent(data -> {
            if (data.getWarehouseLevel() <= 0) {
                data.setUnlockedSlots(Math.min(ModConfig.baseSlots(), data.getCapacity()));
            }
        });
        // 2.0.4/2.0.7：死亡备份兜底恢复（Clone 未触发且新数据无进度时；登录后无论是否恢复都清除陈旧备份）
        restoreFromBackup(event.getEntity());
        clearBackup(event.getEntity().getUUID());
        if (!com.deltanexus.system.config.ModConfig.onlineMode()) {
            return;
        }
        event.getEntity().getCapability(PLAYER_DATA).ifPresent(data -> {
            long now = System.currentTimeMillis();
            for (var deque : data.getAllTasks().values()) {
                for (com.deltanexus.system.common.Task t : deque) {
                    if (t.status == com.deltanexus.system.common.TaskStatus.WAITING) {
                        t.startTime = now;
                    }
                }
            }
        });
    }
}
