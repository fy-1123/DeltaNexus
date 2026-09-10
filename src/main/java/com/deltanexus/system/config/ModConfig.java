package com.deltanexus.system.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;

/**
 * 服务端配置文件（config/deltanexus/ModConfig.toml，ForgeConfigSpec）。
 *
 * <p>对应阶段 6 要求：允许服主配置仓库最大等级上限等。</p>
 *
 * <p>2.0.10：类型由 SERVER 改为 COMMON 并迁入模组统一配置文件夹 config/deltanexus/
 * （与 upgrade_tree.json 等 JSON 配置同目录）。
 * 旧版 SERVER 类型配置由 Forge 强制放在 {@code <world>/serverconfig/ModConfig.toml}
 * （每个存档一份），难以查找；COMMON 类型统一落在全局 config/ 下，
 * 服务器启动时自动把 serverconfig 旧值逐键迁移过来（旧文件改名 .migrated 保留）。</p>
 */
public final class ModConfig {

    public static final ForgeConfigSpec SERVER_SPEC;

    /** 仓库总行数（每行 9 格，总容量 = 行数 x 9，上限 64 行 = 576 格；2.0.1 由 warehouse_pages 迁移而来）。 */
    public static final ForgeConfigSpec.IntValue WAREHOUSE_ROWS;
    /** 0 级玩家初始解锁槽位数。 */
    public static final ForgeConfigSpec.IntValue BASE_SLOTS;
    /** 升级货币物品（cost_money 对应的物品），默认绿宝石。 */
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_ITEM;
    /** JsonConfigWriter 防反跳毫秒数（阶段 6）。 */
    public static final ForgeConfigSpec.IntValue SAVE_DEBOUNCE_MS;
    /** 全局制造速度倍率（浮点，替代旧 gamerule）。 */
    public static final ForgeConfigSpec.DoubleValue TIME_MULTIPLIER;
    /** 每个工作台最大并行队列数（替代旧 gamerule）。 */
    public static final ForgeConfigSpec.IntValue MAX_QUEUE_SIZE;
    /** 货币类型：item / scoreboard / vault（无 Vault 插件时自动降级不可用）。 */
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_TYPE;
    /** 计分板货币的目标名（currency_type=scoreboard 时使用）。 */
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_SCOREBOARD;
    /** 制造计时模式：offline（离线模式，默认，离线照常计时）/ online（在线模式，仅在线计时）。 */
    public static final ForgeConfigSpec.ConfigValue<String> MANUFACTURE_MODE;
    /** 安全箱默认宽度（1~3 格，1.1.0）。 */
    public static final ForgeConfigSpec.IntValue SAFE_BOX_WIDTH;
    /** 安全箱默认高度（1~3 格，1.1.0）。 */
    public static final ForgeConfigSpec.IntValue SAFE_BOX_HEIGHT;
    /** 服务端 GUI 白名单（2.0.9：与客户端白名单取并集，命中任意即用原版 GUI）。 */
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> UI_WHITELIST;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("DeltaNexus 服务端配置").push("deltanexus");

        WAREHOUSE_ROWS = b
                .comment("仓库总行数（每行 9 格，总容量 = 行数 x 9，上限 64 行；仓库界面滚轮向下滚动查看）")
                .defineInRange("warehouse_rows", migrateOldPages(), 1, 64);

        BASE_SLOTS = b
                .comment("0 级玩家初始解锁槽位数（默认 9 = 首行；升级由升级树逐级解锁更多槽位）")
                .defineInRange("base_slots", 9, 0, 576);

        CURRENCY_ITEM = b
                .comment("升级货币物品 ID（cost_money 按该物品数量扣除）")
                .define("currency_item", "minecraft:emerald");

        SAVE_DEBOUNCE_MS = b
                .comment("JSON 写入防反跳毫秒数")
                .defineInRange("save_debounce_ms", 500, 0, 5000);

        TIME_MULTIPLIER = b
                .comment("全局制造速度倍率（0.01 ~ 100，默认 1.0；只影响新开任务，旧任务快照耗时不受影响）")
                .defineInRange("time_multiplier", 1.0D, 0.01D, 100.0D);

        MAX_QUEUE_SIZE = b
                .comment("每个工作台最大并行队列数（默认 5）")
                .defineInRange("max_queue_size", 5, 1, 100);

        CURRENCY_TYPE = b
                .comment("货币类型：scoreboard（计分板，默认）/ vault（Vault 经济，需安装经济插件提供者如 EssentialsX）/ playerpoints（PlayerPoints 点券）；item 已移除（旧值自动迁移为 scoreboard）")
                .define("currency_type", "scoreboard");

        CURRENCY_SCOREBOARD = b
                .comment("计分板货币目标名（currency_type=scoreboard 时使用）")
                .define("currency_scoreboard", "dn_money");

        MANUFACTURE_MODE = b
                .comment("制造计时模式：offline = 离线模式（默认，离线照常计时）/ online = 在线模式（仅玩家在线时计时）")
                .define("manufacture_mode", "offline");

        SAFE_BOX_WIDTH = b
                .comment("安全箱默认宽度（1 ~ 3 列，1x1 最小 / 3x3 最大；默认 1x1）")
                .defineInRange("safe_box_width", 1, 1, 3);

        SAFE_BOX_HEIGHT = b
                .comment("安全箱默认高度（1 ~ 3 格，1x1 最小 / 3x3 最大；默认 1x1）")
                .defineInRange("safe_box_height", 1, 1, 3);

        UI_WHITELIST = b
                .comment("服务端 GUI 白名单（2.0.9）：界面类简单名或全限定名，忽略大小写。",
                        "命中服务端或客户端（deltanexus/client-ui.toml）任一白名单的界面均使用原版 GUI",
                        "（不替换背包界面、不做网格渲染、不渲染安全箱覆盖层）。",
                        "登录时自动同步到客户端，修改后热重载广播生效。",
                        "示例：InventoryScreen = 全员保持原版生存背包；ChestScreen = 箱子保持原版")
                .defineList("ui_whitelist",
                        java.util.List.of(),
                        o -> o instanceof String s && !s.isBlank());

        b.pop();
        SERVER_SPEC = b.build();
    }

    /**
     * 2.0.1 迁移：旧配置 ModConfig.toml 中的 warehouse_pages（1 页 = 9 行）迁移为
     * warehouse_rows 默认值；已存在新键（或文件缺失）时返回默认 6。
     * ForgeConfigSpec 对文件中的未知键会拒绝加载，故仅把旧值作为新键默认值，
     * 随后首次保存即用新 spec 重写文件（旧键自然移除）。
     * 2.0.10：优先读新位置 config/deltanexus/ModConfig.toml，旧平铺位置兜底
     * （register() 里的文件复制在静态初始化之后执行，首启动时新位置尚不存在）。
     */
    private static int migrateOldPages() {
        try {
            java.nio.file.Path cfgDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
            java.nio.file.Path p = cfgDir.resolve("deltanexus/ModConfig.toml");
            if (!java.nio.file.Files.exists(p)) {
                p = cfgDir.resolve("ModConfig.toml");
            }
            if (java.nio.file.Files.exists(p)) {
                String text = java.nio.file.Files.readString(p);
                if (text.contains("warehouse_rows")) {
                    return 12;
                }
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("warehouse_pages\\s*=\\s*(\\d+)").matcher(text);
                if (m.find()) {
                    int pages = Math.max(1, Math.min(64, Integer.parseInt(m.group(1))));
                    return Math.max(1, Math.min(64, pages * 9));
                }
            }
        } catch (Exception ignored) {
        }
        return 12;
    }

    private ModConfig() {
    }

    /**
     * 2.0.10 迁移：旧版 SERVER 类型配置位于 {@code <world>/serverconfig/ModConfig.toml}（每个存档一份），
     * 类型改 COMMON 后新配置在全局 {@code config/deltanexus/ModConfig.toml}。服务器启动时把
     * serverconfig 旧值逐键迁移到已加载的 COMMON 配置并落盘，旧文件改名 {@code .migrated} 保留原件、
     * 防止重复迁移（多存档各自迁移一次，后启动的存档覆盖先启动的——自用场景可接受，日志有记录）。
     *
     * <p>2.0.10 不再强制提升 base_slots：0 级解锁由 base_slots 配置决定，
     * 渲染与滚动按玩家实际解锁行数展示（未解锁行不渲染）。</p>
     *
     * @param worldServerConfigDir 世界 serverconfig 目录（{@code <world>/serverconfig}），可为 null
     */
    public static void migrateLegacyServerConfig(java.nio.file.Path worldServerConfigDir) {
        try {
            if (!SERVER_SPEC.isLoaded() || worldServerConfigDir == null) {
                return;
            }
            java.nio.file.Path oldFile = worldServerConfigDir.resolve("ModConfig.toml");
            java.nio.file.Path doneFile = worldServerConfigDir.resolve("ModConfig.toml.migrated");
            if (!java.nio.file.Files.exists(oldFile) || java.nio.file.Files.exists(doneFile)) {
                return;
            }
            // night-config 直接解析旧 TOML（FileConfig 按扩展名自动识别格式），无需 Forge 加载
            com.electronwill.nightconfig.core.file.FileConfig old = com.electronwill.nightconfig.core.file.FileConfig
                    .builder(oldFile).build();
            old.load();
            int migrated = 0;
            migrated += trySetInt(WAREHOUSE_ROWS, old, "warehouse_rows");
            migrated += trySetInt(BASE_SLOTS, old, "base_slots");
            migrated += trySetInt(SAVE_DEBOUNCE_MS, old, "save_debounce_ms");
            migrated += trySet(TIME_MULTIPLIER, old, "time_multiplier");
            migrated += trySetInt(MAX_QUEUE_SIZE, old, "max_queue_size");
            migrated += trySet(CURRENCY_ITEM, old, "currency_item");
            migrated += trySet(CURRENCY_TYPE, old, "currency_type");
            migrated += trySet(CURRENCY_SCOREBOARD, old, "currency_scoreboard");
            migrated += trySet(MANUFACTURE_MODE, old, "manufacture_mode");
            migrated += trySetInt(SAFE_BOX_WIDTH, old, "safe_box_width");
            migrated += trySetInt(SAFE_BOX_HEIGHT, old, "safe_box_height");
            migrated += trySetList(old, "ui_whitelist");
            old.close();
            if (migrated > 0) {
                SERVER_SPEC.save();
            }
            java.nio.file.Files.move(oldFile, doneFile);
            com.deltanexus.system.DeltaNexus.LOGGER.info(
                    "[DN] 服务端配置迁移：{} 个旧值已从 serverconfig 合并至 config/deltanexus/ModConfig.toml"
                            + "，原件已改名为 {}", migrated, doneFile.getFileName());
        } catch (Exception e) {
            com.deltanexus.system.DeltaNexus.LOGGER.debug("[DN] serverconfig 配置迁移跳过: {}", e.toString());
        }
    }

    /** 尝试从旧配置读取指定键并写入 ConfigValue（类型不匹配/键缺失时跳过，返回 1=已迁移）。 */
    @SuppressWarnings("rawtypes")
    private static int trySet(net.minecraftforge.common.ForgeConfigSpec.ConfigValue cfg,
                              com.electronwill.nightconfig.core.file.FileConfig old, String key) {
        Object v = old.get("deltanexus." + key);
        if (v == null) {
            return 0;
        }
        try {
            cfg.set(v);
            return 1;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** trySet 的整型变体：TOML 整数可能解析为 Long，统一转 int 后写入。 */
    private static int trySetInt(ForgeConfigSpec.IntValue cfg,
                                 com.electronwill.nightconfig.core.file.FileConfig old, String key) {
        Object v = old.get("deltanexus." + key);
        if (v instanceof Number n) {
            try {
                cfg.set(n.intValue());
                return 1;
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** trySet 的列表变体：ui_whitelist 为 defineList，逐项校验非空字符串。 */
    private static int trySetList(com.electronwill.nightconfig.core.file.FileConfig old, String key) {
        Object v = old.get("deltanexus." + key);
        if (!(v instanceof java.util.List<?> list)) {
            return 0;
        }
        java.util.List<String> vals = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o != null && !o.toString().isBlank()) {
                vals.add(o.toString().trim());
            }
        }
        if (vals.isEmpty()) {
            return 0;
        }
        try {
            UI_WHITELIST.set(vals);
            return 1;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * 注册配置（2.0.10：COMMON 类型，文件统一放模组文件夹 config/deltanexus/）。
     * 注册前先把旧平铺位置 config/ModConfig.toml 复制到新位置，旧值无缝衔接。
     */
    @SuppressWarnings("removal")
    public static void register() {
        java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        migrateLegacyFile(dir.resolve("ModConfig.toml"), dir.resolve("deltanexus/ModConfig.toml"));
        ModLoadingContext.get().registerConfig(Type.COMMON, SERVER_SPEC, "deltanexus/ModConfig.toml");
    }

    /**
     * 旧配置文件迁移：old 存在且 new 不存在时复制（失败仅记日志，不影响启动）。
     * 供本类与 GridConfig/ClientUiConfig 的 register() 复用（public：跨包调用）。
     */
    public static void migrateLegacyFile(java.nio.file.Path oldFile, java.nio.file.Path newFile) {
        try {
            if (java.nio.file.Files.exists(oldFile) && !java.nio.file.Files.exists(newFile)) {
                java.nio.file.Files.createDirectories(newFile.getParent());
                java.nio.file.Files.copy(oldFile, newFile);
                com.deltanexus.system.DeltaNexus.LOGGER.info("[DN] 配置迁移：{} -> {}",
                        oldFile.getFileName(), newFile);
            }
        } catch (Exception e) {
            com.deltanexus.system.DeltaNexus.LOGGER.debug("[DN] 配置迁移跳过: {}", e.toString());
        }
    }

    /** 仓库总行数（默认 12，上限 64；每行 9 格）。 */
    public static int warehouseRows() {
        return Math.max(1, Math.min(64, safeGet(WAREHOUSE_ROWS, 12)));
    }

    /**
     * 自动扩容检测：所需槽位超过当前容量（行数 x 9）时自动提升仓库行数并落盘。
     * 配置未加载（如客户端实体构造）时跳过，避免写入未加载的配置。
     *
     * @param requiredSlots 所需槽位数（升级树最大解锁槽位）
     * @return 处理后的行数（未扩容则返回当前行数）
     */
    public static int ensureRowsForSlots(int requiredSlots) {
        int rows = warehouseRows();
        if (requiredSlots <= rows * 9 || !SERVER_SPEC.isLoaded()) {
            return rows;
        }
        int need = Math.max(1, Math.min(64, (requiredSlots + 8) / 9));
        if (need <= rows) {
            return rows;
        }
        WAREHOUSE_ROWS.set(need);
        SERVER_SPEC.save();
        return need;
    }

    public static int baseSlots() {
        return safeGet(BASE_SLOTS, 9);
    }

    public static String currencyItem() {
        return CURRENCY_ITEM.get();
    }

    public static int saveDebounceMs() {
        return safeGet(SAVE_DEBOUNCE_MS, 500);
    }

    public static double timeMultiplier() {
        try {
            return Math.max(0.01, Math.min(100.0, TIME_MULTIPLIER.get()));
        } catch (Exception e) {
            return 1.0;
        }
    }

    public static int maxQueueSize() {
        return Math.max(1, safeGet(MAX_QUEUE_SIZE, 5));
    }

    /** 货币类型：scoreboard / vault / playerpoints（item 已移除，旧配置自动迁移为 scoreboard）。 */
    public static String currencyType() {
        try {
            String t = CURRENCY_TYPE.get().trim().toLowerCase(java.util.Locale.ROOT);
            // item 模式已移除：历史配置一律按 scoreboard 处理（并见 migrateCurrencyIfNeeded）
            return "item".equals(t) ? "scoreboard" : t;
        } catch (Exception e) {
            return "scoreboard";
        }
    }

    /**
     * 启动迁移：旧配置 {@code currency_type = "item"} 自动改写为 scoreboard（计分板货币）。
     * 由 ServerStartedEvent 调用（此时配置已加载）。
     */
    public static void migrateCurrencyIfNeeded() {
        try {
            if ("item".equalsIgnoreCase(CURRENCY_TYPE.get())) {
                CURRENCY_TYPE.set("scoreboard");
                SERVER_SPEC.save();
                com.deltanexus.system.DeltaNexus.LOGGER.warn(
                        "[DN] 检测到已移除的 item 货币模式，已自动迁移为 scoreboard（目标 {}）；"
                                + "如需其他货币请用 /dn setting currency", currencyScoreboard());
            }
        } catch (Exception e) {
            com.deltanexus.system.DeltaNexus.LOGGER.debug("[DN] 货币迁移检查跳过: {}", e.toString());
        }
    }

    public static String currencyScoreboard() {
        try {
            return CURRENCY_SCOREBOARD.get();
        } catch (Exception e) {
            return "dn_money";
        }
    }

    /** 制造计时模式：true = 在线模式（仅在线计时），false = 离线模式（默认）。 */
    public static boolean onlineMode() {
        try {
            return "online".equals(MANUFACTURE_MODE.get().trim().toLowerCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    /** 安全箱默认宽度（1 ~ 3）。 */
    public static int safeBoxWidth() {
        return Math.max(1, Math.min(3, safeGet(SAFE_BOX_WIDTH, 1)));
    }

    /** 安全箱默认高度（1 ~ 3）。 */
    public static int safeBoxHeight() {
        return Math.max(1, Math.min(3, safeGet(SAFE_BOX_HEIGHT, 1)));
    }

    /** 服务端 GUI 白名单（配置未加载时回退空列表 = 不额外干预）。 */
    public static java.util.List<String> serverUiWhitelist() {
        try {
            java.util.List<String> list = new java.util.ArrayList<>();
            for (Object o : UI_WHITELIST.get()) {
                if (o != null && !o.toString().isBlank()) {
                    list.add(o.toString().trim());
                }
            }
            return java.util.List.copyOf(list);
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    // ------------------------------------------------------------------
    // 安全访问：客户端实体构造时 SERVER 配置尚未加载，直接 .get() 会抛异常，
    // 这里回退默认值，保证客户端 capability 正常附加（体验优先）。
    // ------------------------------------------------------------------

    private static int safeGet(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 客户端安全取初始槽位（默认 9）。 */
    public static int safeBaseSlots() {
        return baseSlots();
    }
}
