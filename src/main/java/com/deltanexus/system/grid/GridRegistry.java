package com.deltanexus.system.grid;

import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.config.SafeBoxRestrictions;
import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 网格容器注册表（0.3.0Beta）：<b>显式注册</b>才启用格子格式。
 *
 * <p>内置注册（无需配置，永远启用）：</p>
 * <ul>
 *   <li>玩家背包 {@link Inventory}；</li>
 *   <li>本模组仓库 / 安全箱处理器（{@link IPlayerData#getWarehouseHandler()} /
 *       {@link IPlayerData#getSafeBoxHandler()}）；</li>
 *   <li>原版容器 {@link Container}（实现类位于 {@code net.minecraft.*}）且格数 ≥ 9，合成格除外。</li>
 * </ul>
 *
 * <p>此外的模组容器需经 {@code /dn grid register <类名>}、Web 编辑器或
 * {@code common.toml} 的 {@code registered_containers} 显式注册；
 * 兼容开关 {@code legacy_any_container = true} 可恢复 0.2.x 的「所有 ≥9 格容器一律接管」行为。</p>
 *
 * <p>0.2.x 行为差异说明：旧实现把任何 ≥9 格的 {@link Container}（含其他模组）都纳入网格，
 * 新版只接管原版容器 + 显式注册者——这正是重写文档要求的唯一行为变化。</p>
 */
public final class GridRegistry {

    private GridRegistry() {
    }

    /** 是否对该容器启用格子格式。 */
    public static boolean isGridContainer(Player player, Object container) {
        if (container == null) {
            return false;
        }
        if (container instanceof Inventory) {
            return true;
        }
        if (container == InventoryGridHandler.CLIENT_SAFE_HANDLER
                || container == InventoryGridHandler.CLIENT_WAREHOUSE_HANDLER) {
            return true;
        }
        // 合成格（3x3 工作台）永不接管，避免破坏原版合成
        if (container instanceof net.minecraft.world.inventory.CraftingContainer) {
            return false;
        }
        IPlayerData data = ManufacturingService.data(player);
        if (data != null && (container == data.getWarehouseHandler() || container == data.getSafeBoxHandler())) {
            return true;
        }
        if (container instanceof ItemStackHandler handler) {
            // 模组容器处理器：仅显式注册（或兼容开关）才接管
            return isExplicitlyRegistered(handler);
        }
        if (container instanceof Container c) {
            if (isVanilla(c)) {
                return c.getContainerSize() >= 9;
            }
            return isExplicitlyRegistered(c);
        }
        return false;
    }

    /** 网格宽度（列数）：安全箱按实际解锁列数（行列形状），其余 9。 */
    public static int width(Player player, Object container) {
        if (container == null) {
            return 9;
        }
        if (container == InventoryGridHandler.CLIENT_SAFE_HANDLER) {
            return Math.max(1, Math.min(3, InventoryGridHandler.CLIENT_SAFE_WIDTH));
        }
        IPlayerData data = ManufacturingService.data(player);
        if (data != null && container == data.getSafeBoxHandler()) {
            return Math.max(1, Math.min(3, data.getSafeBoxWidth()));
        }
        return 9;
    }

    /** 容器内第 {@code containerIndex} 格是否可用（仓库未解锁槽 / 安全箱未解锁格 = 不可用）。 */
    public static boolean isUsable(Player player, Object container, int containerIndex) {
        IPlayerData data = ManufacturingService.data(player);
        if (data == null) {
            return true;
        }
        if (container == data.getWarehouseHandler()) {
            return data.isSlotUnlocked(containerIndex);
        }
        if (container == data.getSafeBoxHandler()) {
            return data.isSafeSlotUnlocked(containerIndex);
        }
        return true;
    }

    /** 容器的额外限制（当前仅安全箱 NBT 限制）。 */
    public static Predicate<ItemStack> restrictionOf(Player player, Object container) {
        IPlayerData data = ManufacturingService.data(player);
        if (data != null && container == data.getSafeBoxHandler()) {
            return SafeBoxRestrictions::isRestricted;
        }
        return stack -> false;
    }

    private static boolean isVanilla(Object container) {
        return container.getClass().getName().startsWith("net.minecraft.");
    }

    private static boolean isExplicitlyRegistered(Object container) {
        if (GridConfig.legacyAnyContainer()) {
            return true;
        }
        Set<String> names = registeredNames();
        if (names.isEmpty()) {
            return false;
        }
        for (Class<?> c = container.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (names.contains(c.getName()) || names.contains(c.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /** 已注册的模组容器类名（配置 + 运行时）。 */
    public static Set<String> registeredNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String s : GridConfig.registeredContainers()) {
            if (s != null && !s.isBlank()) {
                names.add(s.trim());
            }
        }
        return names;
    }

    /** 注册一个模组容器类名（服务端，落盘）。 */
    public static boolean register(String className) {
        return GridConfig.registerContainer(className);
    }

    /** 取消注册。 */
    public static boolean unregister(String className) {
        return GridConfig.unregisterContainer(className);
    }

    /** 该容器是否属于「原版内置注册」（用于 /dn info 展示）。 */
    public static boolean isBuiltIn(Object container) {
        if (container instanceof Inventory) {
            return true;
        }
        if (container instanceof Container c && !(container instanceof net.minecraft.world.inventory.CraftingContainer)) {
            return isVanilla(c) && c.getContainerSize() >= 9;
        }
        return false;
    }
}
