package com.deltanexus.system.grid;

import com.deltanexus.system.grid.core.GridDim;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 尺寸规则（0.3.0Beta）：把「物品配置尺寸 + 旋转标记 + 快捷栏分级规则」折算成实际占用尺寸。
 *
 * <p>内核不读配置：尺寸全部由这里算好后以 {@link GridDim} 交给
 * {@link com.deltanexus.system.grid.core.GridSolver}。</p>
 */
public final class GridSizes {

    private GridSizes() {
    }

    /** 基础尺寸（配置驱动：仅配置了尺寸的物品才有占用尺寸，未配置一律 1x1；已含旋转交换宽高）。 */
    public static GridDim baseDim(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return GridDim.ONE;
        }
        InventoryGridHandler.ItemDim cfg = ItemSizeConfig.getSize(stack.getItem());
        if (cfg == null) {
            return GridDim.ONE;
        }
        GridDim dim = new GridDim(cfg.w(), cfg.h());
        return isRotated(stack) ? dim.rotated() : dim;
    }

    /** 是否处于旋转姿态（新旧键兼容）。 */
    public static boolean isRotated(ItemStack stack) {
        return com.deltanexus.system.grid.core.GridTags.isRotated(stack);
    }

    /**
     * 实际尺寸：创造模式或非网格容器一律 1x1；快捷栏按 {@code common.toml} 的 hotbar_rules 分级。
     */
    public static GridDim actualDim(ItemStack stack, Slot slot, boolean creative, Player player) {
        return actualDim(stack, slot, creative, player, null);
    }

    /**
     * 实际尺寸（可显式指定容器，供不持有 Slot 的入口复用）。
     *
     * @param container 容器身份（null = 由 slot 推导）
     */
    public static GridDim actualDim(ItemStack stack, Slot slot, boolean creative, Player player, Object container) {
        if (stack == null || stack.isEmpty() || slot == null) {
            return GridDim.ONE;
        }
        Object box = container != null ? container : InventoryGridHandler.gridContainerOf(slot);
        if (creative || !GridRegistry.isGridContainer(player, box)) {
            return GridDim.ONE;
        }
        // 口袋（containerSlot 9..13）：UI 的 5 个口袋格，只放 1x1 的普通物品——
        // 几何上按 1x1 存放，落位许可由 {@link #pocketAccepts} 在菜单/搬运入口统一裁决。
        if (isPocketSlot(slot)) {
            return GridDim.ONE;
        }
        // 快捷栏（containerSlot 0..8）：按 common.toml 的 hotbar_rules 折算。
        // 默认 0-8:ANY = 无视尺寸（任意大小都能放进快捷栏，按 1x1 处理）。
        if (isHotbarSlot(slot)) {
            return applyHotbarRules(stack, slot.getContainerSlot(), baseDim(stack));
        }
        return baseDim(stack);
    }

    /**
     * 口袋区准入（0.5.0Beta 修正：口袋只收 1x1 普通物品）。
     *
     * <p>此前口径是反的——口袋按 1x1 存放（因此什么都能塞），快捷栏却按真实尺寸判定
     * （快捷栏只有一行，于是大于 1x1 的物品都放不进去）。现在：</p>
     * <ul>
     *   <li><b>口袋</b>：仅 1x1 的普通物品；胸挂/背包（装备）与大于 1x1 的物品一律拒绝；</li>
     *   <li><b>快捷栏</b>：按 {@code hotbar_rules}（默认 ANY）——任意大小都能放进去。</li>
     * </ul>
     *
     * <p>口袋与快捷栏是同一格子系统里的两段（口袋 = 主背包前 5 格、快捷栏 = 末行 9 格），
     * 因此这里只判定「物品本身能不能进这种格」，尺寸/分区几何由内核另行推导。</p>
     */
    public static boolean pocketAccepts(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        if (com.deltanexus.system.grid.GearConfig.isGear(stack)) {
            return false; // 胸挂/背包不进任何口袋
        }
        return baseDim(stack).is1x1();
    }

    /** 是否属于玩家快捷栏（containerSlot 0-8）。 */
    public static boolean isHotbarSlot(Slot slot) {
        return slot != null && slot.container instanceof Inventory && slot.getContainerSlot() < 9;
    }

    /** 口袋区（主背包前 5 格 = containerSlot 9..13，即 UI 的 pockets）：仅 1x1 留存。 */
    public static boolean isPocketSlot(Slot slot) {
        return slot != null && slot.container instanceof Inventory
                && slot.getContainerSlot() >= 9 && slot.getContainerSlot() <= 13;
    }

    /** 快捷栏分级规则：ANY = 1x1；FOOD = 食物或本就 1x1 则 1x1，否则 base；GRID = base。 */
    public static GridDim applyHotbarRules(ItemStack stack, int hotbarIndex, GridDim base) {
        List<? extends String> rules = GridConfig.rules();
        for (String rule : rules) {
            try {
                String[] parts = rule.split(":");
                if (parts.length < 2) {
                    continue;
                }
                String[] range = parts[0].split("-");
                int start = Integer.parseInt(range[0].trim());
                int end = Integer.parseInt(range[1].trim());
                if (hotbarIndex < start || hotbarIndex > end) {
                    continue;
                }
                String mode = parts[1].trim().toUpperCase();
                switch (mode) {
                    case "ANY" -> {
                        return GridDim.ONE;
                    }
                    case "FOOD" -> {
                        return (stack.getItem().isEdible() || base.is1x1()) ? GridDim.ONE : base;
                    }
                    case "GRID" -> {
                        return base;
                    }
                    default -> {
                        // 未知模式：忽略该条规则
                    }
                }
            } catch (Exception ignored) {
                // 规则格式错误：跳过
            }
        }
        return base;
    }
}
