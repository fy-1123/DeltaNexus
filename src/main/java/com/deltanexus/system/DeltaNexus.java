package com.deltanexus.system;

import com.deltanexus.system.config.ModConfig;
import com.deltanexus.system.config.RecipeCache;
import com.deltanexus.system.config.UpgradeConfig;
import com.deltanexus.system.init.ModMenus;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.server.CurrencyManager;
import com.deltanexus.system.server.PermissionManager;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 三角联结（DeltaNexus）主类。
 * Minecraft 1.20.1 / Forge 47.4.x 环境（兼容 Mohist 混合服务端）。
 *
 * <p>设计哲学：配置热加载（不停机修改）、时间戳驱动（零Tick依赖）、增量网络包（极致省流量）。</p>
 *
 * <p>所有全局参数（制造倍率/队列上限/货币类型等）均由配置与 /dn 指令管理，不再使用 gamerule。</p>
 */
@Mod(DeltaNexus.MODID)
public class DeltaNexus {
    public static final String MODID = "deltanexus";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @SuppressWarnings("removal")
    public DeltaNexus() {
        ModConfig.register();
        // 客户端 UI 配置（2.0.10 修复：此前 register() 从未调用，client-ui.toml 一直未生成，
        // 白名单仅靠代码默认回退值生效）。CLIENT 类型仅客户端物理端加载，服务端注册无害。
        com.deltanexus.system.config.ClientUiConfig.register();
        // 格式背包（2.0.0，集成自 expansionpack）：快捷栏规则 COMMON 配置 + 物品尺寸 JSON
        com.deltanexus.system.grid.GridConfig.register();
        com.deltanexus.system.grid.ItemSizeConfig.load();
        // 格式背包（2.0.3）：物品「类」背景色配置
        com.deltanexus.system.grid.GridClassConfig.load();

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModMenus.MENUS.register(modBus);
        com.deltanexus.system.grid.GridItems.ITEMS.register(modBus);
        com.deltanexus.system.grid.GridEnchantments.ENCHANTMENTS.register(modBus);
        modBus.addListener(DeltaNexus::commonSetup);

        // 网络通道（协议版本 "dn1"：2.0.0 新增格子格式旋转包）
        PacketHandler.register();

        // 2.0.7：注册回归测试（GameTestRegistry.register；普通服务器无害，仅 GameTestServer 执行）
        com.deltanexus.system.test.DnRegressionTests.register();
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // 首次启动写出默认配置（工作台 -> 配方 -> 升级树 -> 权限 -> 安全箱限制），存在则跳过
            com.deltanexus.system.common.WorkbenchRegistry.get().writeDefaultIfMissing();
            RecipeCache.get().writeDefaultsIfMissing();
            UpgradeConfig.get().writeDefaultIfMissing();
            com.deltanexus.system.server.PermissionManager.writeDefaultIfMissing();
            com.deltanexus.system.config.SafeBoxRestrictions.writeDefaultIfMissing();
            LOGGER.info("[DN] 配置初始化完成：倍率 {}x / 队列 {} / 货币类型 {} / 安全箱默认 {}x{}",
                    ModConfig.timeMultiplier(), ModConfig.maxQueueSize(), ModConfig.currencyType(),
                    ModConfig.safeBoxWidth(), ModConfig.safeBoxHeight());
        });
    }

    /**
     * 服务器生命周期事件（FORGE 总线）。
     * 注意：{@link #commonSetup} 在 mod 加载阶段执行，此时 Bukkit 插件尚未启用，
     * 货币类型与 Vault 提供者都不可靠（TOML 配置 2.0.10 起为 COMMON 类型，加载阶段已就绪）；
     * 因此就绪状态在 {@link ServerStartedEvent}（世界加载完毕、插件全部启用后）统一输出。
     */
    @Mod.EventBusSubscriber(modid = DeltaNexus.MODID)
    public static final class ServerEvents {

        private ServerEvents() {
        }

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            // 服务器就绪后输出货币系统状态（配置与插件均已加载）
            String type = ModConfig.currencyType();
            String status;
            switch (type) {
                case "vault" -> status = CurrencyManager.isVaultAvailable()
                        ? "vault，Vault 经济提供者已连接"
                        : "vault，未检测到经济提供者";
                case "playerpoints" -> status = CurrencyManager.isPlayerPointsAvailable()
                        ? "playerpoints，点券插件已连接"
                        : "playerpoints，插件不可用";
                case "scoreboard" -> status = "scoreboard，计分板 " + ModConfig.currencyScoreboard();
                default -> status = "item，物品 " + ModConfig.currencyItem();
            }
            LOGGER.info("[DN] 货币系统就绪：{}", status);
            if ("vault".equals(type) && !CurrencyManager.isVaultAvailable()) {
                LOGGER.warn("[DN] 当前货币类型为 vault 但未找到 Vault 经济提供者，"
                        + "请安装 Vault 及 EssentialsX/CMI 等经济插件");
            }
            // 2.0.7：启动摘要（控制台分节排版，与 /dn info 口径一致）
            LOGGER.info("[DN] 制造：倍率 {}x / 队列 {} / 模式 {}", ModConfig.timeMultiplier(),
                    ModConfig.maxQueueSize(), ModConfig.onlineMode() ? "在线" : "离线");
            LOGGER.info("[DN] 仓库：Lv{} / {} 行 / 0级解锁 {} 格 / 总容量 {} 格", UpgradeConfig.get().maxLevel(),
                    ModConfig.warehouseRows(), ModConfig.baseSlots(), ModConfig.warehouseRows() * 9);
            LOGGER.info("[DN] 安全箱：默认 {}x{} / 升级树 Lv{} / NBT限制 {} 条", ModConfig.safeBoxWidth(),
                    ModConfig.safeBoxHeight(), UpgradeConfig.get().safeMaxLevel(),
                    com.deltanexus.system.config.SafeBoxRestrictions.size());
            LOGGER.info("[DN] 权限默认：仓库={} 工作台={} 特勤处={}",
                    PermissionManager.defaultWarehouse() ? "允许" : "拒绝",
                    PermissionManager.defaultWorkbench() ? "允许" : "拒绝",
                    PermissionManager.defaultSpecial() ? "允许" : "拒绝");
            LOGGER.info("[DN] 格式背包：自定义尺寸 {} 个 / 类 {} 个 / 已归属物品 {} 个",
                    com.deltanexus.system.grid.ItemSizeConfig.allCustom().size(),
                    com.deltanexus.system.grid.GridClassConfig.allClasses().size(),
                    com.deltanexus.system.grid.GridClassConfig.allItemClasses().size());
            // Web 网页编辑器：配置 enabled=true 时自动启动（host/port/token 均来自 web-editor.yml）
            com.deltanexus.system.web.WebConfig webCfg = com.deltanexus.system.web.WebConfig.get();
            if (webCfg.enabled() && !com.deltanexus.system.web.WebEditorServer.isRunning()) {
                com.deltanexus.system.web.WebEditorServer.start();
            }
        }
    }
}
