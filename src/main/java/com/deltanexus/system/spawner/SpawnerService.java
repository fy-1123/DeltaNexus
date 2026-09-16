package com.deltanexus.system.spawner;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.spawner.Spawner.PoolEntry;
import com.deltanexus.system.spawner.Spawner.SpawnerPoint;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 刷兵执行服务。
 *
 * <p>单次执行：{@link #run} 只刷一次、执行完即结束；无冷却、无波次、无定时。
 * {@code dryRun} 走完整流程但不生成实体；{@link #preview} 只做静态推算。</p>
 */
public final class SpawnerService {

    /** count 解析结果。 */
    private record CountSpec(boolean perPoint, int min, int max) {
        int resolve(RandomSource r) {
            if (min >= max) {
                return min;
            }
            return min + r.nextInt(max - min + 1);
        }
    }

    private SpawnerService() {
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 执行刷兵一次。返回给执行者的反馈文本。 */
    public static Component run(CommandSourceStack source, String name, ServerLevel level, boolean dryRun) {
        SpawnerWorldStore store = SpawnerManager.world(level);
        Spawner s = store.get(name);
        if (s == null) {
            return Component.literal("§c刷兵器不存在：" + name);
        }

        // 生成本可用的实体类型（校验）——用于提前报错与日志
        String entityId = resolveEntityId(s, level.random);
        if (entityId == null) {
            return Component.literal("§c刷兵器 " + name + " 未配置 entity 或 entity_pool");
        }
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        if (id == null) {
            return Component.literal("§c非法实体 ID：" + entityId);
        }
        // 严格按注册键校验：注册表 getValue 对未知键会回退默认值（如首项 minecraft:pig），
        // containsKey 才能判定该 ID 是否真实存在，防止乱填/漏填被静默成猪。
        if (!ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
            return Component.literal("§c未知实体类型：" + entityId);
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);

        GlobalConfig global = GlobalConfig.get();
        StringBuilder sb = new StringBuilder();

        // 全局限制（enabled=false 时全部不生效）
        if (global.enabled) {
            String reject = globalBlocked(source, level, global, type);
            if (reject != null) {
                logReject(name, level, reject);
                return Component.literal("§c全局限制拦截：" + reject);
            }
        }

        // 条件
        String condReject = conditionBlocked(level, s);
        if (condReject != null) {
            String msg = "条件不满足：" + condReject;
            logReject(name, level, msg);
            return Component.literal("§c" + msg);
        }

        String dim = level.dimension().location().toString();

        // 目标点位 + 数量
        List<String> targetNames = resolveTargets(store, s);
        List<SpawnerPoint> targets = new ArrayList<>();
        for (String n : targetNames) {
            SpawnerPoint p = s.points.get(n);
            if (p != null && p.enabled) {
                targets.add(p);
            }
        }
        CountSpec count = parseCount(s.count);
        int total = count.perPoint ? count.resolve(level.random) * targets.size()
                : count.resolve(level.random);

        sb.append("§7[").append(dim).append("] 刷兵器 §a").append(name)
                .append(" §7将刷 §e").append(total).append("§7 个 §f").append(entityId);
        if (dryRun) {
            sb.append(" §7(试运行，不生成)");
        }
        sb.append("\n");

        if (targets.isEmpty()) {
            String msg = "没有可用点位（未配置或全部禁用）";
            logReject(name, level, msg);
            return Component.literal(sb + "§c" + msg);
        }

        List<SpawnerPoint> round = new ArrayList<>(targets);
        // 打乱点位顺序：count < 点位时随机抽 different 点位（不再固定落在前几个）；
        // count > 点位时也打乱后轮询，分布更均匀。
        Collections.shuffle(round);
        int spawned = 0;
        int skipped = 0;
        for (int i = 0; i < total; i++) {
            SpawnerPoint pt = round.get(i % round.size());
            Entity e = trySpawnOne(level, type, s, pt, dryRun);
            if (e == null && !dryRun && i == 0) {
                // 第一个就失败通常是点位整体问题，仍继续尝试其余点
            }
            if (e != null) {
                spawned++;
            } else {
                skipped++;
            }
        }

        if (dryRun) {
            sb.append("§7试运行完成：将生成 ").append(spawned).append(" / ").append(total).append(" 个\n");
        } else {
            sb.append("§7执行完成：生成 ").append(spawned).append(" 个");
            if (skipped > 0) {
                sb.append("（跳过 ").append(skipped).append(" 个，安全/限制不通过）");
            }
        }

        logSpawn(name, level, entityId, spawned, dryRun);
        return Component.literal(sb.toString());
    }

    /** 预览：静态推算会刷什么、刷多少、在哪刷，不做任何世界检查。 */
    public static Component preview(CommandSourceStack source, String name, ServerLevel level) {
        SpawnerWorldStore store = SpawnerManager.world(level);
        Spawner s = store.get(name);
        if (s == null) {
            return Component.literal("§c刷兵器不存在：" + name);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§6[预览] §f").append(name).append("\n");

        if (s.entity != null) {
            sb.append("  §7实体: §f").append(s.entity);
            if (s.entityPool != null && !s.entityPool.isEmpty()) {
                sb.append("  §7(含随机池)");
            }
            sb.append("\n");
        } else if (s.entityPool != null && !s.entityPool.isEmpty()) {
            sb.append("  §7实体池: ");
            for (int k = 0; k < s.entityPool.size(); k++) {
                PoolEntry p = s.entityPool.get(k);
                if (k > 0) {
                    sb.append(", ");
                }
                sb.append(p.id).append("§7(").append(p.weight).append(")");
            }
            sb.append("\n");
        } else {
            sb.append("  §7实体: §c未配置\n");
        }

        sb.append("  §7数量: §f").append(s.count).append("\n");
        if (s.nbt != null) {
            sb.append("  §7NBT: §f").append(s.nbt.size()).append(" §7键\n");
        }

        List<String> targets = resolveTargets(store, s);
        sb.append("  §7目标点位: §f").append(targets.isEmpty() ? "（无）" : targets.size() + " 个");
        if (s.point != null && !s.point.isBlank()) {
            sb.append(" §7[引用: ").append(s.point).append("]");
        }
        sb.append("\n");

        for (String t : targets) {
            SpawnerPoint p = s.points.get(t);
            if (p == null) {
                sb.append("    §8(").append(t).append(" 不存在)\n");
                continue;
            }
            sb.append("    §7").append(t).append(" §f")
                    .append((int) p.x).append(", ").append((int) p.y).append(", ").append((int) p.z)
                    .append(" §7半径 ").append(p.radius)
                    .append(p.enabled ? "" : " §c(禁用)").append("\n");
        }

        if (s.point != null && !s.point.isBlank() && !s.point.startsWith("group:")) {
            // 单个点名覆盖，展示它
        }

        sb.append("  §7条件: §f").append(s.condition.difficulty.isEmpty() ? "难度不限" : s.condition.difficulty)
                .append(s.condition.playerOnlineMin > 0
                        ? " §7/ 在线≥" + s.condition.playerOnlineMin : "").append("\n");
        sb.append("  §7安全: §f")
                .append(s.safety.requireSolidGround ? "地面 " : "")
                .append(s.safety.avoidLava ? "避岩浆 " : "")
                .append(s.safety.avoidWater ? "避水 " : "")
                .append("尝试").append(s.safety.maxAttemptsPerEntity).append("次\n");
        sb.append("  §7行为: ");
        if (s.behavior.spawnEffect.particle != null || s.behavior.spawnEffect.sound != null) {
            sb.append("特效 ");
        }
        if (!s.behavior.onSpawnCommands.isEmpty()) {
            sb.append("命令").append(s.behavior.onSpawnCommands.size()).append("条 ");
        }
        sb.append("发光=").append(s.behavior.glowing ? "开" : "关")
                .append(" 持久=").append(s.behavior.persistent ? "开" : "关")
                .append(" 静默=").append(s.behavior.silent ? "开" : "关")
                .append(" 无AI=").append(s.behavior.noAi ? "开" : "关").append("\n");
        return Component.literal(sb.toString());
    }

    // ------------------------------------------------------------------
    // 目标点位 / 数量
    // ------------------------------------------------------------------

    private static List<String> resolveTargets(SpawnerWorldStore store, Spawner s) {
        return store.resolvePointTargets(s);
    }

    static CountSpec parseCount(String raw) {
        String v = raw == null ? "1" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        // per-point 与 per_point 两种写法都接受
        boolean perPoint = v.contains("per-point") || v.contains("per_point");
        if (perPoint) {
            v = v.replace("per-point", "").replace("per_point", "").trim().replaceAll("\\s+", "");
        }
        if (v.isEmpty()) {
            return new CountSpec(perPoint, 1, 1);
        }
        if (v.contains("-")) {
            String[] parts = v.split("-", 2);
            int a = parseInt(parts[0].trim(), 1);
            int b = parseInt(parts[1].trim(), a);
            if (b < a) {
                b = a;
            }
            return new CountSpec(perPoint, a, b);
        }
        int n = parseInt(v, 1);
        return new CountSpec(perPoint, n, n);
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Math.max(1, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ------------------------------------------------------------------
    // 实体选择
    // ------------------------------------------------------------------

    /** 解析本次使用的实体 ID：entity 优先，否则按权重从池抽。 */
    private static String resolveEntityId(Spawner s, RandomSource r) {
        if (s.entity != null && !s.entity.isBlank()) {
            return s.entity;
        }
        if (s.entityPool != null && !s.entityPool.isEmpty()) {
            int total = 0;
            for (PoolEntry p : s.entityPool) {
                total += Math.max(1, p.weight);
            }
            int roll = r.nextInt(total);
            int acc = 0;
            for (PoolEntry p : s.entityPool) {
                acc += Math.max(1, p.weight);
                if (roll < acc) {
                    return p.id;
                }
            }
            return s.entityPool.get(s.entityPool.size() - 1).id;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 单实体生成
    // ------------------------------------------------------------------

    private static Entity trySpawnOne(ServerLevel level, EntityType<?> type, Spawner s,
                                      SpawnerPoint pt, boolean dryRun) {
        MinecraftServer server = level.getServer();
        GlobalConfig global = GlobalConfig.get();
        RandomSource r = level.random;

        for (int attempt = 0; attempt < s.safety.maxAttemptsPerEntity; attempt++) {
            double ox = (r.nextDouble() * 2 - 1) * pt.radius;
            double oz = (r.nextDouble() * 2 - 1) * pt.radius;
            double x = pt.x + ox;
            double z = pt.z + oz;
            double y = pt.y + attempt; // 地面往上抬升尝试

            if (!safetyOk(level, s, x, y, z)) {
                continue;
            }
            // 全局每区块限制（中途逐实体检查）
            if (global.enabled && chunkLimitBlocked(level, x, z, global)) {
                continue;
            }
            if (global.enabled && worldLimitBlocked(level, global)) {
                continue;
            }

            Entity e = type.create(level);
            if (e == null) {
                continue;
            }
            e.moveTo(x, y + 0.5, z, r.nextFloat() * 360f, 0);
            if (s.nbt != null) {
                CompoundTag w = s.nbt.copy();
                w.remove("Pos");
                w.remove("Rotation");
                w.remove("Dimension");
                try {
                    e.load(w);
                } catch (Exception ex) {
                    DeltaNexus.LOGGER.warn("[DN] 刷兵 NBT 应用失败: {}", ex.getMessage());
                }
            }
            applyBehavior(level, e, s);

            if (dryRun) {
                return e; // 仅计数，不真正加入世界
            }

            level.addFreshEntity(e);
            applySpawnEffect(level, s, e);
            runOnSpawnCommands(level, e, s);
            return e;
        }
        return null;
    }

    private static boolean safetyOk(ServerLevel level, Spawner s, double x, double y, double z) {
        BlockPos pos = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        FluidState feetFluid = level.getFluidState(pos);
        if (s.safety.avoidLava && feetFluid.is(FluidTags.LAVA)) {
            return false;
        }
        if (s.safety.avoidWater && feetFluid.is(FluidTags.WATER)) {
            return false;
        }
        if (s.safety.requireSolidGround) {
            BlockPos below = pos.below();
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                return false;
            }
        }
        // 落点自身不能是固体（防止卡进方块）
        if (level.getBlockState(pos).isSolid()) {
            return false;
        }
        return true;
    }

    private static void applyBehavior(ServerLevel level, Entity e, Spawner s) {
        if (e instanceof Mob mob) {
            mob.setNoAi(s.behavior.noAi);
            if (s.behavior.silent) {
                mob.setSilent(true);
            }
            if (s.behavior.glowing) {
                mob.setGlowingTag(true);
            }
            if (s.behavior.persistent) {
                mob.setPersistenceRequired();
            }
        }
    }

    private static void applySpawnEffect(ServerLevel level, Spawner s, Entity e) {
        var eff = s.behavior.spawnEffect;
        if (eff == null) {
            return;
        }
        double x = e.getX();
        double y = e.getY();
        double z = e.getZ();
        if (eff.particle != null && !eff.particle.isBlank()) {
            try {
                ParticleType<?> pt = ForgeRegistries.PARTICLE_TYPES.getValue(ResourceLocation.tryParse(eff.particle));
                if (pt instanceof ParticleOptions po) {
                    level.sendParticles(po, x, y + 1, z, 1, 0, 0, 0, 0);
                }
            } catch (Exception ex) {
                DeltaNexus.LOGGER.warn("[DN] 刷兵粒子失败 {}: {}", eff.particle, ex.getMessage());
            }
        }
        if (eff.sound != null && !eff.sound.isBlank()) {
            SoundEvent se = ForgeRegistries.SOUND_EVENTS.getValue(ResourceLocation.tryParse(eff.sound));
            if (se != null) {
                level.playSound(null, x, y, z, se, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
        }
    }

    private static void runOnSpawnCommands(ServerLevel level, Entity e, Spawner s) {
        if (s.behavior.onSpawnCommands == null || s.behavior.onSpawnCommands.isEmpty()) {
            return;
        }
        var cmds = level.getServer().getCommands();
        net.minecraft.commands.CommandSourceStack src = e.createCommandSourceStack();
        for (String c : s.behavior.onSpawnCommands) {
            try {
                cmds.performPrefixedCommand(src, c);
            } catch (Exception ex) {
                DeltaNexus.LOGGER.warn("[DN] 刷兵命令执行失败 '{}': {}", c, ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // 全局限制
    // ------------------------------------------------------------------

    /** run 前的整体拦截检查；放行返回 null。 */
    private static String globalBlocked(CommandSourceStack source, ServerLevel level,
                                        GlobalConfig global, EntityType<?> type) {
        MinecraftServer server = level.getServer();
        String dim = level.dimension().location().toString();
        String folder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .getFileName().toString();
        for (String pat : global.blacklistedWorldPatterns) {
            if (GlobalConfig.matches(pat, dim) || GlobalConfig.matches(pat, folder)) {
                return "世界在黑名单 " + pat;
            }
        }
        // TPS
        double avg = server.getAverageTickTime();
        if (avg > 0) {
            double tps = 1000.0 / avg;
            if (tps < global.limits.minTpsToAllowSpawn) {
                return "TPS " + String.format("%.1f", tps) + " 低于阈值 " + global.limits.minTpsToAllowSpawn;
            }
        }
        // 内存
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        double freePercent = total > 0 ? free * 100.0 / total : 100;
        if (freePercent < global.limits.minFreeMemoryPercent) {
            return "剩余内存 " + String.format("%.0f", freePercent) + "% 低于阈值 " + global.limits.minFreeMemoryPercent + "%";
        }
        // 世界实体总数
        if (countEntities(level) >= global.limits.maxTotalEntitiesPerWorld) {
            return "世界实体数已达上限 " + global.limits.maxTotalEntitiesPerWorld;
        }
        return null;
    }

    private static boolean chunkLimitBlocked(ServerLevel level, double x, double z, GlobalConfig global) {
        int cx = Math.floorDiv((int) Math.floor(x), 16);
        int cz = Math.floorDiv((int) Math.floor(z), 16);
        LevelChunk chunk = level.getChunk(cx, cz);
        AABB box = new AABB(cx * 16, level.getMinBuildHeight(), cz * 16,
                cx * 16 + 16, level.getMaxBuildHeight(), cz * 16 + 16);
        return level.getEntitiesOfClass(Entity.class, box).size() >= global.limits.maxEntitiesPerChunk;
    }

    private static boolean worldLimitBlocked(ServerLevel level, GlobalConfig global) {
        return countEntities(level) >= global.limits.maxTotalEntitiesPerWorld;
    }

    private static int countEntities(ServerLevel level) {
        int n = 0;
        for (Entity ignored : level.getEntities().getAll()) {
            n++;
        }
        return n;
    }

    private static String conditionBlocked(ServerLevel level, Spawner s) {
        if (!s.condition.difficulty.isEmpty()) {
            String cur = level.getDifficulty().name().toLowerCase(java.util.Locale.ROOT);
            if (!s.condition.difficulty.contains(cur)) {
                return "难度 " + cur + " 不在允许列表 " + s.condition.difficulty;
            }
        }
        if (s.condition.playerOnlineMin > 0
                && level.getServer().getPlayerList().getPlayers().size() < s.condition.playerOnlineMin) {
            return "在线玩家不足 " + s.condition.playerOnlineMin;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    private static void logReject(String name, ServerLevel level, String msg) {
        LogConfig log = LogConfig.get();
        if (log.allows(LogConfig.LOG_INFO) && log.logConditionRejections) {
            log.log("拒绝刷兵 [" + name + "] @" + level.dimension().location() + ": " + msg);
        }
    }

    private static void logSpawn(String name, ServerLevel level, String entityId, int count, boolean dryRun) {
        LogConfig log = LogConfig.get();
        if (log.allows(LogConfig.LOG_INFO) && (dryRun || log.logSuccessfulSpawns)) {
            log.log((dryRun ? "[试运行] " : "[成功] ") + name + " @" + level.dimension().location()
                    + " 实体=" + entityId + " 数量=" + count);
        }
    }
}