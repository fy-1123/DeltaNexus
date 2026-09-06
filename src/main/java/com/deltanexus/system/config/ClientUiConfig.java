package com.deltanexus.system.config;

import com.deltanexus.system.DeltaNexus;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 UI 配置（config/deltanexus/client-ui.toml，ForgeConfigSpec，2.0.8 UI 重构）。
 *
 * <p>2.0.10 修复：register() 此前从未被主类调用，配置文件一直未生成，白名单仅靠
 * 代码默认回退值（CreativeModeInventoryScreen）生效；现随主类构造注册并迁入模组统一配置文件夹。</p>
 *
 * <p>界面白名单系统（2.0.9 双端化）：白名单内的界面保持原版 GUI 样式与交互逻辑——
 * dn 不替换背包界面、不做网格渲染、不渲染安全箱覆盖层。
 * 白名单有两份，命中任意一份即生效（取并集）：
 * <ul>
 *   <li>客户端白名单（本文件 vanillaUiWhitelist，默认含创造模式背包）；</li>
 *   <li>服务端白名单（deltanexus/ModConfig.toml ui_whitelist，登录时经 SyncServerUiPacket 同步）。</li>
 * </ul></p>
 */
public final class ClientUiConfig {

    public static final ForgeConfigSpec SPEC;

    /** 保持原版 GUI 的界面白名单（类简单名或全限定名，忽略大小写）。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> VANILLA_UI_WHITELIST;
    /** 是否用 dn 背包界面（三列布局）替换原版背包（E 键）。 */
    public static final ForgeConfigSpec.BooleanValue REPLACE_INVENTORY_SCREEN;
    /** 是否用 dn 容器界面（三列布局）替换原版纯槽位容器（箱/桶/潜影盒/发射器/漏斗）。 */
    public static final ForgeConfigSpec.BooleanValue REPLACE_CONTAINER_SCREEN;

    /** 白名单缓存（配置热重载时失效重建；volatile 保证跨线程可见）。 */
    private static volatile List<String> cachedWhitelist;
    /** 服务端白名单缓存（SyncServerUiPacket 同步；空列表 = 服务端未配置）。 */
    private static volatile List<String> serverWhitelist = List.of();
    /** 玩家功能开关（2.1：服务端同步；false = 禁用全部 mod 功能，UI 恢复原版）。 */
    private static volatile boolean featuresEnabled = true;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("DeltaNexus 客户端 UI 配置").push("ui");
        VANILLA_UI_WHITELIST = b
                .comment("保持原版 GUI 的界面白名单（填写界面类简单名或全限定名，忽略大小写）。",
                        "白名单内的界面完全保持原版样式与交互：不替换、不做网格渲染。",
                        "默认：CreativeModeInventoryScreen（创造模式背包）。",
                        "示例：加入 InventoryScreen 恢复原版生存背包；加入 ChestScreen 让箱子保持原版。")
                .defineList("vanillaUiWhitelist",
                        List.of("CreativeModeInventoryScreen"),
                        o -> o instanceof String s && !s.isBlank());
        REPLACE_INVENTORY_SCREEN = b
                .comment("是否用 dn 背包界面（左中列布局：口袋/背包/安全箱）替换原版背包界面（E 键）。",
                        "原版背包（InventoryScreen）加入白名单时本项自动失效。")
                .define("replaceInventoryScreen", true);
        REPLACE_CONTAINER_SCREEN = b
                .comment("是否用 dn 容器界面（三列布局：快捷栏列/口袋背包安全箱/容器网格）",
                        "替换原版纯槽位容器：箱子/木桶/潜影盒/发射器/投掷器/漏斗。",
                        "复用原版菜单逻辑，全部交互（点击/shift 移动/拖拽）与原版一致。",
                        "容器界面（如 ChestScreen）加入白名单时本项对该界面自动失效。",
                        "熔炉/工作台等特殊渲染界面不在此范围。")
                .define("replaceContainerScreen", true);
        b.pop();
        SPEC = b.build();
    }

    private ClientUiConfig() {
    }

    /**
     * 注册配置（2.0.10：文件统一放模组文件夹 config/deltanexus/client-ui.toml，
     * 注册前自动把旧平铺位置 config/deltanexus-client.toml 迁移过来）。
     */
    public static void register() {
        java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        // 全限定名：本类 import 了 Forge 的 ModConfig（Type.CLIENT 用），直接写 ModConfig 会解析到 Forge 类
        com.deltanexus.system.config.ModConfig.migrateLegacyFile(dir.resolve("deltanexus-client.toml"),
                dir.resolve("deltanexus/client-ui.toml"));
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SPEC, "deltanexus/client-ui.toml");
    }

    /**
     * 指定界面类是否在白名单中（类简单名/全限定名匹配，忽略大小写）。
     * 2.0.9：客户端白名单与服务端同步白名单取并集，命中任意一份即使用原版 GUI。
     * 注意：刻意接收 {@code Class<?>} 而非 Screen 对象，避免本类在专用服务器上
     * 触发客户端类加载。配置未加载时回退默认白名单（创造模式保持原版）。
     */
    public static boolean isVanillaUi(Class<?> screenClass) {
        if (screenClass == null) {
            return false;
        }
        String simple = screenClass.getSimpleName();
        String full = screenClass.getName();
        for (String entry : currentWhitelist()) {
            if (entry.equalsIgnoreCase(simple) || entry.equalsIgnoreCase(full)) {
                return true;
            }
        }
        // 服务端白名单（登录同步；空列表零开销跳过）
        for (String entry : serverWhitelist) {
            if (entry.equalsIgnoreCase(simple) || entry.equalsIgnoreCase(full)) {
                return true;
            }
        }
        return false;
    }

    /** 应用服务端 UI 同步（SyncServerUiPacket 到达时调用；null/空 = 服务端未配置）。 */
    public static void applyServerWhitelist(List<String> whitelist) {
        serverWhitelist = whitelist == null ? List.of() : List.copyOf(whitelist);
    }

    /** 2.1：应用服务端 UI 同步（白名单 + 玩家功能开关）。 */
    public static void applyServerUi(List<String> whitelist, boolean features) {
        serverWhitelist = whitelist == null ? List.of() : List.copyOf(whitelist);
        featuresEnabled = features;
    }

    /** 2.1：该玩家是否可用 mod 功能（false = 禁用全部，所有界面恢复原版）。 */
    public static boolean featuresEnabled() {
        return featuresEnabled;
    }

    /** 是否启用 dn 背包界面替换（配置未加载时默认 true）。 */
    public static boolean replaceInventoryScreen() {
        try {
            return REPLACE_INVENTORY_SCREEN.get();
        } catch (Exception e) {
            return true;
        }
    }

    /** 是否启用 dn 容器界面替换（配置未加载时默认 true）。 */
    public static boolean replaceContainerScreen() {
        try {
            return REPLACE_CONTAINER_SCREEN.get();
        } catch (Exception e) {
            return true;
        }
    }

    private static List<String> currentWhitelist() {
        List<String> cached = cachedWhitelist;
        if (cached != null) {
            return cached;
        }
        List<String> list = new ArrayList<>();
        try {
            for (Object o : VANILLA_UI_WHITELIST.get()) {
                if (o != null && !o.toString().isBlank()) {
                    list.add(o.toString());
                }
            }
        } catch (Exception e) {
            // 配置未加载（客户端启动早期等）：回退默认，保证创造模式界面始终原版
            list.add("CreativeModeInventoryScreen");
        }
        cachedWhitelist = List.copyOf(list);
        return cachedWhitelist;
    }

    /** 使白名单缓存失效（配置重载后重建）。 */
    public static void refresh() {
        cachedWhitelist = null;
    }

    /**
     * 配置事件：本模组客户端配置加载/重载时刷新白名单缓存（支持热修改）。
     * 2.0.10 修复：ModConfigEvent 是 MOD 总线事件，此前未指定 bus（默认 FORGE）
     * 导致订阅器从未被调用——游戏内改配置文件后白名单缓存永不刷新，
     * 需重启游戏才生效。现挂 MOD 总线，Forge 文件监听触发热重载即时刷新。
     */
    @Mod.EventBusSubscriber(modid = DeltaNexus.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ConfigEvents {
        private ConfigEvents() {
        }

        @SubscribeEvent
        public static void onConfigReload(ModConfigEvent.Reloading event) {
            if (event.getConfig().getSpec() == SPEC) {
                refresh();
            }
        }

        /** 修复：首次加载走 Loading 事件（非 Reloading），同样须刷新缓存——
         *  否则启动早期构建的默认白名单缓存会一直生效到下次手动重载。 */
        @SubscribeEvent
        public static void onConfigLoading(ModConfigEvent.Loading event) {
            if (event.getConfig().getSpec() == SPEC) {
                refresh();
            }
        }
    }
}
