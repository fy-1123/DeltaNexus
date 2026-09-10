package com.deltanexus.system.server;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.config.ModConfig;
import net.minecraft.server.level.ServerPlayer;

/**
 * 货币管理器：支持三种货币来源（0.2.0Beta 起移除 item 物品货币）。
 *
 * <ul>
 *   <li>{@code scoreboard}    —— 计分板货币（config currency_scoreboard 目标，默认 dn_money；
 *                               服务器启动时自动创建目标）</li>
 *   <li>{@code vault}         —— Vault 经济（Mohist 混合端 Bukkit API，反射访问；
 *                                Vault 本身不提供货币，需 EssentialsX/CMI 等插件注册经济提供者）</li>
 *   <li>{@code playerpoints}  —— PlayerPoints 点券（Bukkit 插件，反射访问 PlayerPointsAPI，
 *                                与 WarZDM 动态商店点券同一来源）</li>
 * </ul>
 *
 * <p>扣款 {@link #spend} 与收款 {@link #pay} 成对；计分板为 int 分数，收/扣均做溢出与负值保护。</p>
 */
public final class CurrencyManager {

    /** 计分板分数上限（避免加减溢出变负数）。 */
    private static final long SCORE_MAX = Integer.MAX_VALUE;
    private static final long SCORE_MIN = Integer.MIN_VALUE;

    private CurrencyManager() {
    }

    public static String type() {
        return ModConfig.currencyType();
    }

    /** 当前货币类型是否可用（vault/playerpoints 需对应插件就绪）。 */
    public static boolean isUsable() {
        String t = type();
        if ("vault".equals(t)) {
            return VaultBridge.hasProvider();
        }
        if ("playerpoints".equals(t)) {
            return PlayerPointsBridge.isAvailable();
        }
        return true;
    }

    /** Vault 经济是否可用（API 可见 + 已注册经济提供者）。 */
    public static boolean isVaultAvailable() {
        return VaultBridge.hasProvider();
    }

    /** Vault API 是否可见（仅检测类存在，不要求提供者；用于区分两类故障）。 */
    public static boolean isVaultApiPresent() {
        return VaultBridge.isApiAvailable();
    }

    /** PlayerPoints 点券是否可用（插件已安装且 API 可调用）。 */
    public static boolean isPlayerPointsAvailable() {
        return PlayerPointsBridge.isAvailable();
    }

    /** 当前货币余额。 */
    public static long getBalance(ServerPlayer player) {
        return switch (type()) {
            case "vault" -> VaultBridge.getBalance(player);
            case "playerpoints" -> PlayerPointsBridge.look(player);
            default -> getScore(player);
        };
    }

    /** 是否足够支付 amount。 */
    public static boolean canAfford(ServerPlayer player, long amount) {
        return getBalance(player) >= amount;
    }

    /** 扣除 amount，成功返回 true。 */
    public static boolean spend(ServerPlayer player, long amount) {
        if (amount <= 0) {
            return true;
        }
        return switch (type()) {
            case "vault" -> VaultBridge.withdraw(player, amount);
            case "playerpoints" -> PlayerPointsBridge.take(player, amount);
            default -> spendScore(player, amount);
        };
    }

    /** 收款能力预检（vault/playerpoints 需插件可用；计分板恒可用）。 */
    public static boolean canPay(ServerPlayer player, long amount) {
        if (amount < 0) {
            return false;
        }
        if (amount == 0) {
            return true;
        }
        return switch (type()) {
            case "vault" -> VaultBridge.hasProvider();
            case "playerpoints" -> PlayerPointsBridge.isAvailable();
            default -> true;
        };
    }

    /**
     * 给玩家加钱（卖出/奖励用），成功返回 true。
     * <p>计分板：直接加分（溢出保护）；Vault：depositPlayer；PlayerPoints：give。</p>
     */
    public static boolean pay(ServerPlayer player, long amount) {
        if (amount <= 0) {
            return true;
        }
        return switch (type()) {
            case "vault" -> VaultBridge.deposit(player, amount);
            case "playerpoints" -> PlayerPointsBridge.give(player, amount);
            default -> addScore(player, amount);
        };
    }

    // ------------------------------------------------------------------
    // 通用：多类加载器查找（混合端下 Bukkit/插件类可能只对部分加载器可见）
    // ------------------------------------------------------------------

    /** 依次用多个类加载器查找类。 */
    private static Class<?> findClass(String name) {
        ClassLoader[] loaders = {
                CurrencyManager.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 经 PluginManager 查找插件实例（名称不区分大小写）。 */
    private static Object findPlugin(String name) {
        try {
            Class<?> bukkitClass = findClass("org.bukkit.Bukkit");
            if (bukkitClass == null) {
                return null;
            }
            Object server = bukkitClass.getMethod("getServer").invoke(null);
            if (server == null) {
                return null;
            }
            var m1 = server.getClass().getMethod("getPluginManager");
            m1.setAccessible(true);
            Object pluginManager = m1.invoke(server);
            var m2 = pluginManager.getClass().getMethod("getPlugin", String.class);
            m2.setAccessible(true);
            return m2.invoke(pluginManager, name);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 计分板货币
    // ------------------------------------------------------------------

    private static net.minecraft.world.scores.Objective scoreObjective(ServerPlayer player) {
        var scoreboard = player.getScoreboard();
        String name = ModConfig.currencyScoreboard();
        var objective = scoreboard.getObjective(name);
        if (objective == null) {
            // 目标不存在时自动创建（不违反计分板规则）
            try {
                objective = scoreboard.addObjective(name,
                        net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
                        net.minecraft.network.chat.Component.literal(name),
                        net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER);
            } catch (Exception e) {
                return null;
            }
        }
        return objective;
    }

    private static long getScore(ServerPlayer player) {
        var objective = scoreObjective(player);
        if (objective == null) {
            return 0;
        }
        return player.getScoreboard().getOrCreatePlayerScore(player.getScoreboardName(), objective).getScore();
    }

    private static boolean spendScore(ServerPlayer player, long amount) {
        var objective = scoreObjective(player);
        if (objective == null) {
            return false;
        }
        var score = player.getScoreboard().getOrCreatePlayerScore(player.getScoreboardName(), objective);
        long newValue = (long) score.getScore() - amount;
        if (newValue < 0) {
            return false;
        }
        score.setScore((int) newValue);
        return true;
    }

    /** 加分（卖出/奖励）；溢出（超过 int 上限）返回 false。 */
    private static boolean addScore(ServerPlayer player, long amount) {
        var objective = scoreObjective(player);
        if (objective == null) {
            return false;
        }
        var score = player.getScoreboard().getOrCreatePlayerScore(player.getScoreboardName(), objective);
        long newValue = (long) score.getScore() + amount;
        if (newValue > SCORE_MAX) {
            return false;
        }
        score.setScore((int) newValue);
        return true;
    }

    /**
     * 服务器启动就绪：显式创建计分板货币目标（已存在则跳过）并打日志。
     * 由 ServerStartedEvent 调用；非 scoreboard 货币时仅记录当前类型。
     */
    public static void ensureReady(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!"scoreboard".equals(type())) {
            DeltaNexus.LOGGER.info("[DN] 货币类型 {}，跳过计分板目标创建", type());
            return;
        }
        String name = ModConfig.currencyScoreboard();
        try {
            var scoreboard = server.getScoreboard();
            if (scoreboard.getObjective(name) == null) {
                scoreboard.addObjective(name,
                        net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
                        net.minecraft.network.chat.Component.literal(name),
                        net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER);
                DeltaNexus.LOGGER.info("[DN] 已创建计分板货币目标 '{}'", name);
            } else {
                DeltaNexus.LOGGER.info("[DN] 计分板货币目标 '{}' 已存在", name);
            }
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] 创建计分板货币目标 '{}' 失败: {}", name, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Vault（Bukkit 经济，Mohist 混合端；纯反射访问，未安装则不可用）
    // ------------------------------------------------------------------

    /**
     * Vault 桥接层（反射，不依赖编译期 Bukkit/Vault 类，纯 Forge 端亦安全）。
     *
     * <p>兼容要点：</p>
     * <ul>
     *   <li>多类加载器探测：mod 自身 / 线程上下文 / 系统加载器，覆盖混合端类可见性差异；</li>
     *   <li>类身份一致：ServicesManager 按 Class 对象身份匹配注册，混合端下插件与 mod
     *       可能各自加载一份 Vault API。必须经 Vault 插件自身 classloader 解析 Economy 类
     *       （与各经济插件注册时同一份），失败时再按类名遍历注册表兜底；</li>
     *   <li>提供者感知：仅类存在不算可用，必须经 ServicesManager 拿到 Economy 实现
     *       （Vault 本身不提供经济，需经济插件注册提供者；本服使用 Vault 2.0
     *       （modrinth vault-2.0-economy-plugins）的内置经济，同样经此注册表）；</li>
     *   <li>负结果不永久缓存：未就绪时每 {@link #RECHECK_MS} 重试；失败时输出一次
     *       逐步诊断日志（WARN），直接定位是缺类、缺插件还是缺提供者。</li>
     * </ul>
     */
    private static final class VaultBridge {

        /** 未就绪时的重试间隔（毫秒）。 */
        private static final long RECHECK_MS = 5000L;

        private static volatile boolean apiFound = false;
        private static volatile boolean providerFound = false;
        private static volatile long lastCheck = 0L;
        private static volatile boolean noProviderLogged = false;
        /** 已确认与注册键同身份的 Economy 类（命中后缓存，避免每次全量探测）。 */
        private static volatile Class<?> economyClassCache;

        private VaultBridge() {
        }

        /**
         * 解析 Vault Economy 类。
         * 优先经 Vault 插件自身 classloader（与插件体系同一份类，避免类身份不匹配）；
         * 拿不到插件加载器时回退 {@link CurrencyManager#findClass}。
         */
        private static Class<?> resolveEconomyClass() {
            Object vaultPlugin = findPlugin("Vault");
            if (vaultPlugin != null) {
                try {
                    Object loader = vaultPlugin.getClass().getMethod("getClassLoader").invoke(vaultPlugin);
                    if (loader instanceof ClassLoader cl) {
                        try {
                            return Class.forName("net.milkbowl.vault.economy.Economy", false, cl);
                        } catch (Throwable t) {
                            DeltaNexus.LOGGER.debug("[DN] Vault Economy 类经插件类加载器解析失败: {}", t.toString());
                        }
                    }
                } catch (Throwable t) {
                    DeltaNexus.LOGGER.debug("[DN] Vault 插件类加载器获取失败: {}", t.toString());
                }
            }
            return findClass("net.milkbowl.vault.economy.Economy");
        }

        /** Vault API 是否可见（Vault 插件已安装，或 Economy 类可解析）。 */
        static synchronized boolean isApiAvailable() {
            if (apiFound) {
                return true;
            }
            apiFound = findClass("org.bukkit.Bukkit") != null
                    && (findPlugin("Vault") != null || resolveEconomyClass() != null);
            return apiFound;
        }

        /** 是否有可用的经济提供者（API 可见 + ServicesManager 中已注册 Economy 实现）。 */
        static synchronized boolean hasProvider() {
            long now = System.currentTimeMillis();
            if (providerFound || now - lastCheck < RECHECK_MS) {
                return providerFound;
            }
            lastCheck = now;
            Probe probe = probe();
            providerFound = probe.provider() != null;
            if (!providerFound && !noProviderLogged) {
                noProviderLogged = true;
                DeltaNexus.LOGGER.warn("[DN] Vault 检测详情: {}", probe.detail());
            }
            return providerFound;
        }

        /** 探测结果（provider 为 null 时 detail 给出逐步诊断）。 */
        private record Probe(Object provider, String detail) {
        }

        /** 反射调用：{@code target.method(args)}，参数类型按 class 数组精确匹配。 */
        private static Object invoke(Object target, String method, Class<?>[] paramTypes, Object... args) throws Exception {
            var m = target.getClass().getMethod(method, paramTypes);
            m.setAccessible(true);
            return m.invoke(target, args);
        }

        /** 取 RegisteredServiceProvider 的提供者。 */
        private static Object providerOf(Object registration) throws Exception {
            return invoke(registration, "getProvider", new Class<?>[0]);
        }

        /** 按类身份查注册（类对象必须与注册键同一份，否则返回 null）。 */
        private static Object getRegistration(Object services, Class<?> clazz) throws Exception {
            return invoke(services, "getRegistration", new Class<?>[]{Class.class}, clazz);
        }

        /**
         * 完整探测：逐步收集诊断信息，返回提供者与细节。
         *
         * <p>三级查找（任一命中即返回）：</p>
         * <ol>
         *   <li>直接按类身份查询：Economy 类解析成功（插件加载器/缓存）时走
         *       {@code getRegistration(Class)}；</li>
         *   <li>{@code getKnownServices()} 名称匹配：该方法返回的正是注册表
         *       的 Class 键本身，再 {@code getRegistration(该类)} 身份必然一致
         *       （解决加载器隔离导致的类身份不匹配）；</li>
         *   <li>{@code getRegistrations(plugin)} 按 Vault 插件枚举其注册的服务，
         *       按服务类名匹配（完全不需要 Economy 类）。</li>
         * </ol>
         */
        private static Probe probe() {
            StringBuilder d = new StringBuilder();
            try {
                Class<?> bukkitClass = findClass("org.bukkit.Bukkit");
                d.append("Bukkit=").append(bukkitClass != null).append(", ");
                if (bukkitClass == null) {
                    return new Probe(null, d.toString());
                }
                Object vaultPlugin = findPlugin("Vault");
                d.append("Vault插件=").append(vaultPlugin != null).append(", ");
                Object server = bukkitClass.getMethod("getServer").invoke(null);
                var m1 = server.getClass().getMethod("getServicesManager");
                m1.setAccessible(true);
                Object services = m1.invoke(server);

                // 1) 直接按类身份查询（缓存类 / 插件加载器解析类）
                Class<?> economyClass = economyClassCache;
                if (economyClass == null) {
                    economyClass = resolveEconomyClass();
                }
                if (economyClass != null) {
                    d.append("Economy类=").append(economyClass.getName())
                            .append("@").append(economyClass.getClassLoader()).append(", ");
                    try {
                        Object registration = getRegistration(services, economyClass);
                        if (registration != null) {
                            economyClassCache = economyClass;
                            Object provider = providerOf(registration);
                            d.append("直接查询=true, provider=").append(provider != null);
                            return new Probe(provider, d.toString());
                        }
                    } catch (Throwable t) {
                        d.append("直接查询异常=").append(t).append(", ");
                    }
                } else {
                    d.append("Economy类=null, ");
                }

                // 2) getKnownServices() 名称匹配 → getRegistration(该类)（身份必然一致）
                Object known = invoke(services, "getKnownServices", new Class<?>[0]);
                if (known instanceof Iterable<?> it) {
                    for (Object c : it) {
                        if (c instanceof Class<?> clazz
                                && "net.milkbowl.vault.economy.Economy".equals(clazz.getName())) {
                            Object registration = getRegistration(services, clazz);
                            if (registration != null) {
                                economyClassCache = clazz;
                                Object provider = providerOf(registration);
                                d.append("已知服务匹配=").append(clazz.getName())
                                        .append(", provider=").append(provider != null);
                                return new Probe(provider, d.toString());
                            }
                        }
                    }
                }

                // 3) getRegistrations(Vault插件) 按插件枚举注册服务，类名匹配
                if (vaultPlugin != null) {
                    Class<?> pluginClass = findClass("org.bukkit.plugin.Plugin");
                    if (pluginClass != null) {
                        Object regs = invoke(services, "getRegistrations",
                                new Class<?>[]{pluginClass}, vaultPlugin);
                        if (regs instanceof Iterable<?> it) {
                            for (Object reg : it) {
                                if (reg == null) {
                                    continue;
                                }
                                try {
                                    Object service = invoke(reg, "getService", new Class<?>[0]);
                                    if (service instanceof Class<?> c
                                            && "net.milkbowl.vault.economy.Economy".equals(c.getName())) {
                                        Object provider = providerOf(reg);
                                        economyClassCache = c;
                                        d.append("插件注册匹配=").append(c.getName())
                                                .append(", provider=").append(provider != null);
                                        return new Probe(provider, d.toString());
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }

                d.append("未找到经济提供者");
                return new Probe(null, d.toString());
            } catch (Throwable t) {
                d.append("异常=").append(t);
                return new Probe(null, d.toString());
            }
        }

        /** 反射获取 Vault Economy 实例（优先走缓存类快速路径，避免每次全量探测）。 */
        private static Object economy() {
            try {
                Class<?> bukkitClass = findClass("org.bukkit.Bukkit");
                if (bukkitClass == null) {
                    return null;
                }
                Object server = bukkitClass.getMethod("getServer").invoke(null);
                var m1 = server.getClass().getMethod("getServicesManager");
                m1.setAccessible(true);
                Object services = m1.invoke(server);
                if (economyClassCache != null) {
                    Object registration = getRegistration(services, economyClassCache);
                    if (registration != null) {
                        return providerOf(registration);
                    }
                }
                return probe().provider();
            } catch (Throwable t) {
                return null;
            }
        }

        /** 反射获取 Bukkit OfflinePlayer（按玩家 UUID）。 */
        private static Object bukkitPlayer(ServerPlayer player) {
            try {
                Class<?> bukkitClass = findClass("org.bukkit.Bukkit");
                if (bukkitClass == null) {
                    return null;
                }
                var m = bukkitClass.getMethod("getOfflinePlayer", java.util.UUID.class);
                m.setAccessible(true);
                return m.invoke(null, player.getUUID());
            } catch (Throwable t) {
                return null;
            }
        }

        /** OfflinePlayer 类：优先取提供者自身的加载器副本（与提供者方法签名一致），失败回退查找。 */
        private static Class<?> offlinePlayerClass(Object eco) {
            try {
                return Class.forName("org.bukkit.OfflinePlayer", false, eco.getClass().getClassLoader());
            } catch (Throwable t) {
                return findClass("org.bukkit.OfflinePlayer");
            }
        }

        static long getBalance(ServerPlayer player) {
            try {
                Object eco = economy();
                Object target = bukkitPlayer(player);
                if (eco == null || target == null) {
                    return 0;
                }
                Class<?> offlineClass = offlinePlayerClass(eco);
                var m = eco.getClass().getMethod("getBalance", offlineClass);
                m.setAccessible(true);
                Object result = m.invoke(eco, target);
                return ((Number) result).longValue();
            } catch (Throwable t) {
                DeltaNexus.LOGGER.debug("[DN] Vault 余额读取失败: {}", t.toString());
                return 0;
            }
        }

        static boolean withdraw(ServerPlayer player, long amount) {
            try {
                Object eco = economy();
                Object target = bukkitPlayer(player);
                if (eco == null || target == null) {
                    return false;
                }
                Class<?> offlineClass = offlinePlayerClass(eco);
                var m = eco.getClass().getMethod("withdrawPlayer", offlineClass, double.class);
                m.setAccessible(true);
                Object result = m.invoke(eco, target, (double) amount);
                if (result == null) {
                    return false;
                }
                var m2 = result.getClass().getMethod("transactionSuccess");
                m2.setAccessible(true);
                return (Boolean) m2.invoke(result);
            } catch (Throwable t) {
                DeltaNexus.LOGGER.debug("[DN] Vault 扣款失败: {}", t.toString());
                return false;
            }
        }

        /** 存入（卖出/奖励收款）。 */
        static boolean deposit(ServerPlayer player, long amount) {
            try {
                Object eco = economy();
                Object target = bukkitPlayer(player);
                if (eco == null || target == null) {
                    return false;
                }
                Class<?> offlineClass = offlinePlayerClass(eco);
                var m = eco.getClass().getMethod("depositPlayer", offlineClass, double.class);
                m.setAccessible(true);
                Object result = m.invoke(eco, target, (double) amount);
                if (result == null) {
                    return false;
                }
                var m2 = result.getClass().getMethod("transactionSuccess");
                m2.setAccessible(true);
                return (Boolean) m2.invoke(result);
            } catch (Throwable t) {
                DeltaNexus.LOGGER.debug("[DN] Vault 存款失败: {}", t.toString());
                return false;
            }
        }
    }

    // ------------------------------------------------------------------
    // PlayerPoints（点券，Bukkit 插件；反射访问 PlayerPointsAPI，与 WarZDM 点券同源）
    // ------------------------------------------------------------------

    /** PlayerPoints 桥接层：经 PluginManager 获取插件实例并调用 getAPI()。 */
    private static final class PlayerPointsBridge {

        private static volatile boolean apiFound = false;
        private static volatile long lastCheck = 0L;

        private PlayerPointsBridge() {
        }

        /** 获取 PlayerPointsAPI 实例（插件未安装/不可用返回 null）。 */
        private static synchronized Object api() {
            long now = System.currentTimeMillis();
            if (apiFound || now - lastCheck < 5000L) {
                return apiFound ? apiCached : null;
            }
            lastCheck = now;
            apiCached = fetch();
            apiFound = apiCached != null;
            if (!apiFound) {
                DeltaNexus.LOGGER.debug("[DN] PlayerPoints 未就绪：插件缺失或 API 调用失败");
            }
            return apiCached;
        }

        private static volatile Object apiCached;

        private static Object fetch() {
            try {
                Object plugin = findPlugin("PlayerPoints");
                if (plugin == null) {
                    return null;
                }
                var m = plugin.getClass().getMethod("getAPI");
                m.setAccessible(true);
                return m.invoke(plugin);
            } catch (Throwable t) {
                DeltaNexus.LOGGER.debug("[DN] PlayerPoints API 获取失败: {}", t.toString());
                return null;
            }
        }

        static boolean isAvailable() {
            return api() != null;
        }

        /** 查询点券余额。 */
        static long look(ServerPlayer player) {
            try {
                Object a = api();
                if (a == null) {
                    return 0;
                }
                var m = a.getClass().getMethod("look", java.util.UUID.class);
                m.setAccessible(true);
                Object r = m.invoke(a, player.getUUID());
                return ((Number) r).longValue();
            } catch (Throwable t) {
                DeltaNexus.LOGGER.debug("[DN] PlayerPoints 余额读取失败: {}", t.toString());
                return 0;
            }
        }

        /** 扣除点券，成功返回 true。 */
        static boolean take(ServerPlayer player, long amount) {
            try {
                Object a = api();
                if (a == null) {
                    return false;
                }
                var m = a.getClass().getMethod("take", java.util.UUID.class, int.class);
                m.setAccessible(true);
                return (Boolean) m.invoke(a, player.getUUID(), (int) amount);
            } catch (Throwable t) {
                DeltaNexus.LOGGER.debug("[DN] PlayerPoints 扣款失败: {}", t.toString());
                return false;
            }
        }

        /** 发放点券（卖出/奖励收款）。 */
        static boolean give(ServerPlayer player, long amount) {
            try {
                Object a = api();
                if (a == null) {
                    return false;
                }
                var m = a.getClass().getMethod("give", java.util.UUID.class, int.class);
                m.setAccessible(true);
                m.invoke(a, player.getUUID(), (int) amount);
                return true;
            } catch (Throwable t) {
                DeltaNexus.LOGGER.debug("[DN] PlayerPoints 发放失败: {}", t.toString());
                return false;
            }
        }
    }
}
