package com.deltanexus.system.grid;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;

import java.util.List;

/**
 * 快捷栏分级规则（格式背包，2.0.0Alpha 集成自 expansionpack；COMMON 配置，客户端服务端均加载）。
 *
 * <p>规则格式：{@code '起始-结束:模式'}（索引 0-8 对应快捷栏键位 1-9），模式：
 * ANY = 无视尺寸（按 1x1 处理，任意大小物品均可放入）、GRID = 按格子尺寸
 * （口袋区仅 1x1 物品留存，大件自动重排至背包区）、
 * FOOD = 食物按 1x1（非食物同 GRID）。
 * 2.0.9Alpha 默认：0-3 任意（1-4 号格允许任意大小物品，左列竖排区）、
 * 4-8 格子（5-9 号格仅支持 1x1 尺寸物品，中列口袋区）。</p>
 *
 * <p>2.0.2Alpha：支持指令运行时修改，并随 {@code SyncGridSizesPacket} 同步到客户端
 * （客户端以运行时规则覆盖本地配置）。</p>
 */
public class GridConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> HOTBAR_RULES;

    /** 0.3.0Beta：显式注册的模组容器类名（未注册的模组容器不再默认启用网格）。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> REGISTERED_CONTAINERS;

    /** 0.3.0Beta：兼容开关——true = 恢复 0.2.x「所有 ≥9 格容器一律接管」的行为。 */
    public static final ForgeConfigSpec.BooleanValue LEGACY_ANY_CONTAINER;

    /** 2.0.9Alpha 默认规则（1-4 号格 ANY 任意大小 / 5-9 号格 GRID 仅 1x1 留存）。 */
    public static final List<String> DEFAULT_RULES = List.of("0-3:ANY", "4-8:GRID");

    /** 客户端运行时覆盖（来自服务端同步包，会话内有效）。 */
    private static volatile List<String> RUNTIME_RULES;

    static {
        BUILDER.push("hotbar_settings");

        HOTBAR_RULES = BUILDER
                .comment("定义快捷栏规则。格式: '起始-结束:模式'（索引 0-8 = 键位 1-9）",
                        "模式: ANY = 无视尺寸(按1x1放置,任意大小可留存), GRID = 按格子尺寸(口袋区仅1x1留存,大件重排至背包), FOOD = 食物按1x1(非食物同GRID)",
                        "默认: 0-3:ANY(1-4号格任意大小,左列), 4-8:GRID(5-9号格仅1x1,口袋)")
                .defineList("hotbar_rules",
                        DEFAULT_RULES,
                        obj -> obj instanceof String);

        REGISTERED_CONTAINERS = BUILDER
                .comment("0.3.0Beta：显式注册的模组容器类名（容器类或菜单类全名/简单名均可，逐级向上匹配父类）",
                        "原版容器（net.minecraft.* 且 ≥9 格）与玩家背包/仓库/安全箱为内置注册，无需在此登记",
                        "示例: [\"com.example.mymod.blocks.BigChestContainer\"]")
                .defineList("registered_containers", List.of(), obj -> obj instanceof String);

        LEGACY_ANY_CONTAINER = BUILDER
                .comment("兼容开关：true = 恢复 0.2.x 行为（所有 ≥9 格容器，含其他模组的，一律纳入网格）",
                        "false（默认）= 只接管原版容器 + 显式注册的模组容器")
                .define("legacy_any_container", false);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    /**
     * 注册配置（2.0.10Alpha：文件统一放模组文件夹 config/deltanexus/common.toml，
     * 注册前自动把旧平铺位置 config/deltanexus-common.toml 迁移过来）。
     */
    @SuppressWarnings("removal")
    public static void register() {
        java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        com.deltanexus.system.config.ModConfig.migrateLegacyFile(
                dir.resolve("deltanexus-common.toml"), dir.resolve("deltanexus/common.toml"));
        ModLoadingContext.get().registerConfig(Type.COMMON, SPEC, "deltanexus/common.toml");
    }

    /** 当前生效的快捷栏规则（运行时覆盖优先）。 */
    public static List<? extends String> rules() {
        List<String> rt = RUNTIME_RULES;
        if (rt != null) {
            return rt;
        }
        return SPEC.isLoaded() ? HOTBAR_RULES.get() : DEFAULT_RULES;
    }

    /** 2.0.2Alpha：设置快捷栏规则并落盘（服务端）。 */
    public static synchronized boolean setRules(String rulesText) {
        if (rulesText == null || rulesText.isBlank()) {
            return false;
        }
        String[] parts = rulesText.split(",");
        List<String> list = new java.util.ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (!s.matches("\\d+-\\d+:\\w+")) {
                return false;
            }
            list.add(s);
        }
        HOTBAR_RULES.set(list);
        SPEC.save();
        return true;
    }

    /** 2.0.2Alpha：应用客户端运行时覆盖（服务端同步包；null 回退本地配置）。 */
    public static void applyRuntime(List<String> rules) {
        RUNTIME_RULES = rules;
    }

    // ------------------------------------------------------------------
    // 容器注册（0.3.0Beta）
    // ------------------------------------------------------------------

    /** 已注册的模组容器类名。 */
    public static List<? extends String> registeredContainers() {
        return SPEC.isLoaded() ? REGISTERED_CONTAINERS.get() : List.of();
    }

    /** 兼容开关：是否恢复「所有 ≥9 格容器一律接管」。 */
    public static boolean legacyAnyContainer() {
        return SPEC.isLoaded() && LEGACY_ANY_CONTAINER.get();
    }

    /** 注册一个模组容器类名并落盘。 */
    public static synchronized boolean registerContainer(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }
        String name = className.trim();
        List<String> list = new java.util.ArrayList<>(registeredContainers());
        if (list.contains(name)) {
            return false;
        }
        list.add(name);
        REGISTERED_CONTAINERS.set(list);
        SPEC.save();
        return true;
    }

    /** 取消注册。 */
    public static synchronized boolean unregisterContainer(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }
        List<String> list = new java.util.ArrayList<>(registeredContainers());
        if (!list.remove(className.trim())) {
            return false;
        }
        REGISTERED_CONTAINERS.set(list);
        SPEC.save();
        return true;
    }
}
