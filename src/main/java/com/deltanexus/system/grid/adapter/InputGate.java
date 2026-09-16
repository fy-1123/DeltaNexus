package com.deltanexus.system.grid.adapter;

import com.deltanexus.system.config.ClientUiConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 统一交互门闸（0.3.0Beta）：出售模式、界面白名单、玩家功能开关——所有网格交互入口
 * （点击重定向、跨格拾取、旋转包、菜单快捷移动）都只问这里，不再各自判断。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>{@code grid → client.gui} 的反向依赖被彻底切断：出售模式由 {@link SellModeSource}
 *       在客户端注册进来（{@code WarehouseScreen} 实现该接口），grid 包不再引用任何屏幕类；</li>
 *   <li>客户端判断只影响体验一致性，<b>不是安全边界</b>；服务端在
 *       {@link #serverSellMode(Player)} 处另有门闸（当前由菜单实现，详见 {@link SellModeAware}）。</li>
 * </ul>
 */
public final class InputGate {

    /** 出售模式来源（客户端屏幕注册；未注册 = 非出售模式）。 */
    public interface SellModeSource {
        boolean isSellMode();
    }

    /** 服务端出售模式门闸（菜单实现；无实现 = 不拦截）。 */
    public interface SellModeAware {
        boolean isSellMode();
    }

    private static volatile SellModeSource SELL_MODE_SOURCE;

    private InputGate() {
    }

    /** 客户端注册出售模式来源（屏幕 init 时调用；屏幕移除时置 null）。 */
    public static void setSellModeSource(SellModeSource source) {
        SELL_MODE_SOURCE = source;
    }

    /** 客户端当前是否处于出售模式。 */
    public static boolean clientSellMode() {
        SellModeSource src = SELL_MODE_SOURCE;
        return src != null && src.isSellMode();
    }

    /** 服务端当前是否处于出售模式（由菜单标志决定，客户端状态不参与判定）。 */
    public static boolean serverSellMode(Player player) {
        if (player == null) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        return menu instanceof SellModeAware aware && aware.isSellMode();
    }

    /** 该界面是否启用网格交互（客户端）：功能开关 + 白名单。 */
    public static boolean clientGridInteractive(Screen screen) {
        if (screen == null || !ClientUiConfig.featuresEnabled()) {
            return false;
        }
        return !ClientUiConfig.isVanillaUi(screen.getClass());
    }

    /** 客户端网格交互是否被冻结（出售模式 = 冻结一切物品移动）。 */
    public static boolean clientFrozen() {
        return clientSellMode();
    }
}
