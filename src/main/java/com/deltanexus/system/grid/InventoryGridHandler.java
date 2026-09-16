package com.deltanexus.system.grid;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.grid.adapter.MenuGridAdapter;
import com.deltanexus.system.grid.core.GridDim;
import com.deltanexus.system.grid.core.GridService;
import com.deltanexus.system.grid.core.GridTags;
import com.deltanexus.system.menu.GridAwareSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 格子格式（格式背包）<b>门面</b>（0.3.0Beta 重写）。
 *
 * <p>0.2.x 的 758 行单体引擎已拆分为：</p>
 * <ul>
 *   <li>{@code grid.core} —— 纯函数内核：{@link GridDim} / {@link com.deltanexus.system.grid.core.GridContext}
 *       / {@link com.deltanexus.system.grid.core.StackSnapshot} / {@link com.deltanexus.system.grid.core.GridSolver}
 *       / {@link com.deltanexus.system.grid.core.SolvePlan} / {@link com.deltanexus.system.grid.core.GridMutation}
 *       / {@link com.deltanexus.system.grid.core.GridLockManager} / {@link GridService}（编排与生命周期）；</li>
 *   <li>{@code grid.adapter} —— 适配层：{@link MenuGridAdapter}（菜单槽位 ↔ 网格视图）、
 *       {@link com.deltanexus.system.grid.adapter.HandlerGridAdapter}（裸处理器）、
 *       {@link com.deltanexus.system.grid.adapter.InputGate}（出售模式/白名单/功能开关统一门闸）、
 *       {@link com.deltanexus.system.grid.adapter.GridRenderAdapter}（客户端渲染）；</li>
 *   <li>本类只保留<b>旧静态入口</b>与三个事件订阅，内部一律委托新引擎。</li>
 * </ul>
 *
 * <p>行为与索引口径的重要修正：占位物 {@code deltanexus.master_slot} 现在记录
 * <b>容器索引</b>（旧版混用菜单索引，仓库滚动一行后全部失配 → 每滚一次重写整片占位物）。
 * 需要菜单槽位时用 {@link #resolveMasterSlot(AbstractContainerMenu, Slot)} 换算。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID)
public class InventoryGridHandler {

    /** 占位物标记。 */
    public static final String IS_SLAVE = GridTags.IS_SLAVE;
    /** 占位物记录的主格（<b>容器索引</b>）。 */
    public static final String MASTER_SLOT = GridTags.MASTER_SLOT;
    /** 旋转标记（新版键，0.3.0Beta 起写入这个）。 */
    public static final String ROTATED = GridTags.ROTATED;
    /** 客户端菜单影子容器（菜单构造时记录；服务端以玩家能力身份判断网格容器）。 */
    public static volatile ItemStackHandler CLIENT_SAFE_HANDLER;
    public static volatile ItemStackHandler CLIENT_WAREHOUSE_HANDLER;
    /** 客户端安全箱当前宽度（按行列形状渲染与求解；菜单构造时记录）。 */
    public static volatile int CLIENT_SAFE_WIDTH = 3;

    public record ItemDim(int w, int h) {
        public boolean is1x1() {
            return w == 1 && h == 1;
        }
    }

    private InventoryGridHandler() {
    }

    // ------------------------------------------------------------------
    // 容器识别（委托 GridRegistry：0.3.0Beta 起「显式注册」）
    // ------------------------------------------------------------------

    /** 槽位背后的真实容器（SlotItemHandler 的 container 是占位空容器，须取处理器）。 */
    public static Object gridContainerOf(Slot slot) {
        if (slot instanceof SlotItemHandler sih) {
            IItemHandler handler = sih.getItemHandler();
            return handler != null ? handler : slot.container;
        }
        return slot.container;
    }

    /** 该容器是否启用格子格式（玩家背包 / 仓库 / 安全箱 / 原版 ≥9 格容器 / 显式注册容器）。 */
    public static boolean isGridContainer(Player player, Object container) {
        return GridRegistry.isGridContainer(player, container);
    }

    /** 网格宽度（列数）：安全箱按实际解锁列数，其余 9。 */
    public static int gridWidth(Player player, Object container) {
        return GridRegistry.width(player, container);
    }

    // ------------------------------------------------------------------
    // 物品尺寸（委托 GridSizes）
    // ------------------------------------------------------------------

    /** 基础占用尺寸（配置驱动，未配置一律 1x1；已含旋转交换宽高）。 */
    public static ItemDim getBaseDim(ItemStack stack) {
        return toItemDim(GridSizes.baseDim(stack));
    }

    /** 实际占用尺寸（含快捷栏分级规则；非网格容器一律 1x1）。 */
    public static ItemDim getActualDim(ItemStack stack, Slot slot, boolean isCreative, Player player) {
        return toItemDim(GridSizes.actualDim(stack, slot, isCreative, player));
    }

    private static ItemDim toItemDim(GridDim dim) {
        return new ItemDim(dim.w(), dim.h());
    }

    /** 是否处于旋转姿态（新旧 NBT 键都认）。 */
    public static boolean isRotated(ItemStack stack) {
        return GridTags.isRotated(stack);
    }

    /** 写入旋转姿态（只写新键，清除旧键）。 */
    public static void setRotated(ItemStack stack, boolean rotated) {
        GridTags.setRotated(stack, rotated);
    }

    /**
     * 手动放置到槽位前的尺寸预校验：口袋区（快捷栏 5-9 号格 = containerSlot 4-8）仅 1x1 留存。
     * 放不下的物品直接拒绝放置、回到鼠标指针，而不是交给求解器自动重排。
     */
    public static boolean fitsManualPlacement(Player player, Slot slot, ItemStack stack) {
        if (stack == null || stack.isEmpty() || slot == null) {
            return true;
        }
        if (GridSizes.isPocketSlot(slot)) {
            return getActualDim(stack, slot, false, player).is1x1(); // 创造模式同样接管：按真实尺寸校验
        }
        return true;
    }

    /**
     * 口袋区尺寸守卫：把普通快捷栏槽幂等替换为 {@link GridAwareSlot}（保留原槽位 index，
     * 协议按 index 对齐，替换无副作用）。
     */
    public static void ensurePocketGuards(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null) {
            return;
        }
        java.util.List<Slot> slots = menu.slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s == null || s instanceof GridAwareSlot) {
                continue;
            }
            if (s.container instanceof Inventory && s.getContainerSlot() >= 4 && s.getContainerSlot() <= 8) {
                // 必须保留原槽位 index（协议以 index 为槽位 ID，clicked/quickMove 均依赖）
                GridAwareSlot gs = new GridAwareSlot((Inventory) s.container, s.getContainerSlot(), s.x, s.y, player);
                gs.index = s.index;
                slots.set(i, gs);
            }
        }
    }

    // ------------------------------------------------------------------
    // 求解入口（委托 GridService）
    // ------------------------------------------------------------------

    /** 整理独立容器（无菜单上下文：仓库 / 安全箱 / 已注册处理器）。 */
    public static void arrange(Player player, ItemStackHandler container) {
        GridService.arrange(player, container);
    }

    /**
     * 把物品放入玩家仓库（系统商店买入交付、外部交付统一入口）。
     *
     * @param simulate true = 纯预测，不改动仓库
     * @return 放不下的剩余量（{@link ItemStack#EMPTY} = 全部可放入/已放入）
     */
    public static ItemStack placeIntoWarehouse(Player player, ItemStack incoming, boolean simulate) {
        com.deltanexus.system.api.IPlayerData data =
                com.deltanexus.system.server.ManufacturingService.data(player);
        if (data == null) {
            return incoming == null ? ItemStack.EMPTY : incoming.copy();
        }
        return GridService.placeInto(player, data.getWarehouseHandler(), incoming, simulate);
    }

    /** 立即求解该玩家当前菜单（点击后同 Tick 收敛）。 */
    public static void markDirty(Player player) {
        GridService.markDirty(player);
    }

    /** 占位物解析：把点击到的槽位换成它的主格槽位（客户端与服务端共用）。 */
    public static Slot resolveMasterSlot(AbstractContainerMenu menu, Slot clicked) {
        return MenuGridAdapter.resolveMaster(menu, clicked);
    }

    /** 该槽位是否为占位物，且其主格可在当前菜单中解析出来。 */
    public static boolean isSlave(ItemStack stack) {
        return GridTags.isSlave(stack);
    }

    // ------------------------------------------------------------------
    // 生命周期钩子（供 CapabilityAttacher / 包处理器调用）
    // ------------------------------------------------------------------

    /** 保存 / 登出 / 维度切换前：清掉运行态占位物（占位物绝不落盘）。 */
    public static void sanitizePlayerData(Player player) {
        GridService.sanitizePlayerData(player);
    }

    /** 登录加载：清除历史残留占位物。 */
    public static void onPlayerLoaded(Player player) {
        GridService.onPlayerLoaded(player);
    }

    /** 玩家掉线清理（脏标记 / 哈希缓存）。 */
    public static void onPlayerLoggedOut(Player player) {
        GridService.onPlayerLoggedOut(player);
    }

    // ------------------------------------------------------------------
    // 事件订阅（对外契约：禁止删除，否则「每 tick 整理 / 关闭清占位物 / 禁止丢出占位物」静默失效）
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (isSlave(event.getEntity().getItem())) {
            event.setCanceled(true);
            event.getEntity().discard();
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) {
            return;
        }
        Player player = event.player;
        // 玩家功能被禁用（/dn feature deny）→ 网格引擎完全不介入该玩家
        if (!com.deltanexus.system.server.PermissionManager.canUseFeatures(
                player instanceof net.minecraft.server.level.ServerPlayer sp ? sp : null)) {
            return;
        }
        // 0.3.0Beta 稳定性：网格异常一律不得外溢——否则同一 tick 的其它系统
        // （交易目录下发、出售结算等）会被一起带崩，表现为“完全不相关的功能也坏了”
        try {
            GridService.serverTick(player);
        } catch (Throwable t) {
            GridService.reportTickFailure(player, t);
        }
    }

    @SubscribeEvent
    public static void onContainerClosed(PlayerContainerEvent.Close event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) {
            return;
        }
        try {
            GridService.onMenuClosed(player, event.getContainer());
        } catch (Throwable t) {
            GridService.reportTickFailure(player, t);
        }
    }

    /**
     * 菜单打开：立即收敛一次并下发网格布局（0.3.0Beta 第二阶段）。
     *
     * <p>客户端打开界面后拿到的是服务端权威布局，不再自己推导几何——避免两侧口径不同造成的
     * 「看起来复制了 / 重叠了」。</p>
     */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) {
            return;
        }
        try {
            GridService.markDirty(player);
            GridService.sendLayout(player, event.getContainer());
            // 交易行目录兜底下发：保证仓库界面随时能识别可回收物品（不依赖是否开过交易行）
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.deltanexus.system.server.TradeService.sendSync(sp);
            }
        } catch (Throwable t) {
            GridService.reportTickFailure(player, t);
        }
    }
}
