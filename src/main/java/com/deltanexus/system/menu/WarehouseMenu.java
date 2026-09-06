package com.deltanexus.system.menu;

import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.client.gui.PlayerLayout;
import com.deltanexus.system.init.ModMenus;
import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 仓库菜单（2.0.8 UI 重构：三列布局 + 原位滚动）。
 *
 * <p>布局遵循 UI 设计规范（{@link PlayerLayout}）：
 * 左列 = 盔甲(4)/快捷栏 1-4 号格(竖排)/副手；中列 = 口袋(快捷栏 5-9 号格)/背包(3x9)/安全箱；
 * 右列 = 仓库视口（12 行 x 9 列，滚轮滚动起始行）。
 * 2.0.9：移除 Curios 兼容（仓库 UI 不再显示饰品槽）。</p>
 *
 * <p>槽位索引布局（保持稳定，网络同步兼容）：
 * 0-107 仓库视口 / 108-134 背包 / 135-143 快捷栏（135-138 左列 1-4 号格 + 139-143 口袋 5-9 号格）/
 * 144-147 盔甲 / 148 副手 / 149+ 安全箱（按解锁数量，最多 9 格）。</p>
 *
 * <p>2.0.8 原位滚动：{@link #scrollTo(int)} 直接替换视口槽位的全局索引（不重建菜单），
 * 光标栈、悬停状态与屏幕实例全部保留——修复滚动时光标物品掉落、指针/界面重置问题。</p>
 */
public class WarehouseMenu extends AbstractContainerMenu {

    public static final int WAREHOUSE_COLS = 9;
    /** 视口行数（2.0.4：12 行 = 108 格；滚轮滚动查看全部行；总行数见配置 warehouse_rows）。 */
    public static final int WAREHOUSE_ROWS = 12;
    public static final int WAREHOUSE_SLOTS = WAREHOUSE_COLS * WAREHOUSE_ROWS;

    /** 玩家区槽位起点（背包 27 + 快捷栏 9 + 盔甲 4 + 副手 1 = 41）。 */
    public static final int PLAYER_START = WAREHOUSE_SLOTS;
    public static final int PLAYER_COUNT = 41;

    /** 安全箱最大槽位数（3x3，1.1.0）。 */
    public static final int SAFE_SLOTS = 9;

    private final ItemStackHandler handler;
    /** 客户端安全箱影子容器（构造时记录，网格引擎识别用）。 */
    private final ItemStackHandler safeHandler;
    /** 当前视口起始行（2.0.8：scrollTo 原位更新，不再重建菜单）。 */
    private int scrollRow;
    /** 安全箱已添加槽位数（2.0.9：按解锁数，不再固定 9 格）。 */
    private final int safeCount;
    /** 安全箱槽位起点（玩家区之后；中列背包下方）。 */
    public final int safeStart;
    /** 安全箱当前列数（2.0.8：槽位坐标按实际列数排布，修复 w<3 时格子与物品错位）。 */
    public final int safeW;
    /** 玩家引用（scrollTo 重建视口槽位时校验解锁状态用）。 */
    private final Player player;

    /** 客户端构造（MenuSupplier 签名）：起始行取自最近同步包（服务端先发包再开菜单，通道有序）；
     *  安全箱宽度取自最近安全箱同步包；影子容器注册到网格引擎。 */
    public WarehouseMenu(int id, Inventory inv) {
        this(id, inv, resolveClientHandler(inv),
                com.deltanexus.system.client.gui.WarehouseScreen.lastSync != null
                        ? com.deltanexus.system.client.gui.WarehouseScreen.lastSync.scrollRow : 0,
                PlayerLayout.compute(net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth(),
                        net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight(), true),
                resolveClientSafeHandler(inv),
                clientSafeWidth());
        com.deltanexus.system.grid.InventoryGridHandler.CLIENT_WAREHOUSE_HANDLER = this.handler;
        com.deltanexus.system.grid.InventoryGridHandler.CLIENT_SAFE_HANDLER = this.safeHandler;
        com.deltanexus.system.grid.InventoryGridHandler.CLIENT_SAFE_WIDTH = this.safeW;
    }

    /** 服务端构造（默认第 0 行，默认布局）。 */
    public WarehouseMenu(int id, Inventory inv, ItemStackHandler handler) {
        this(id, inv, handler, 0);
    }

    /** 服务端构造（指定起始行，默认布局；坐标仅占位，客户端以其自身布局为准）。 */
    public WarehouseMenu(int id, Inventory inv, ItemStackHandler handler, int scrollRow) {
        this(id, inv, handler, scrollRow, PlayerLayout.compute(400, 240, true),
                resolveServerSafeHandler(inv), serverSafeWidth(inv));
    }

    private WarehouseMenu(int id, Inventory inv, ItemStackHandler handler, int scrollRow,
                          PlayerLayout layout, ItemStackHandler safeHandler, int safeW) {
        super(ModMenus.WAREHOUSE.get(), id);
        this.handler = handler;
        this.safeHandler = safeHandler;
        this.scrollRow = Math.max(0, scrollRow);
        this.player = inv.player;
        this.safeW = Math.max(1, Math.min(3, safeW));

        // 仓库视口（右列；全局索引 = 起始行 x 9 + 格位）
        for (int r = 0; r < WAREHOUSE_ROWS; r++) {
            for (int c = 0; c < WAREHOUSE_COLS; c++) {
                int local = r * WAREHOUSE_COLS + c;
                int global = this.scrollRow * WAREHOUSE_COLS + local;
                addSlot(new LockedAwareSlot(handler, global, layout.whX + c * 18, layout.whY + r * 18, inv.player));
            }
        }
        // 玩家背包 27（inv 9..35）—— 中列
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(inv, c + r * 9 + 9, layout.midX + c * 18, layout.invY + r * 18));
            }
        }
        // 快捷栏 9（inv 0..8，键位 1-9）：1-4 号格 = 左列竖排，5-9 号格 = 中列口袋（横排）
        for (int c = 0; c < 9; c++) {
            int x = c < 4 ? layout.leftX : layout.midX + (c - 4) * 18;
            int y = c < 4 ? layout.hotbarColY + c * 18 : layout.pocketY;
            // 2.0.10：网格感知槽位——放不下（口袋区塞大件）直接拒绝回光标，不做自动重排
            addSlot(new GridAwareSlot(inv, c, x, y, inv.player));
        }
        // 盔甲栏 4（inv 36..39），左列竖排（顶部=头盔，底部=靴子；槽位与部位映射 + 装备校验）
        addSlot(new ArmorValidSlot(inv, 39, layout.leftX, layout.armorY, net.minecraft.world.entity.EquipmentSlot.HEAD));
        addSlot(new ArmorValidSlot(inv, 38, layout.leftX, layout.armorY + 18, net.minecraft.world.entity.EquipmentSlot.CHEST));
        addSlot(new ArmorValidSlot(inv, 37, layout.leftX, layout.armorY + 36, net.minecraft.world.entity.EquipmentSlot.LEGS));
        addSlot(new ArmorValidSlot(inv, 36, layout.leftX, layout.armorY + 54, net.minecraft.world.entity.EquipmentSlot.FEET));
        // 副手栏（inv 40，原版允许任意物品）—— 左列
        addSlot(new Slot(inv, 40, layout.leftX, layout.offhandY));
        // 2.0.9：移除 Curios 兼容——仓库 UI 不再显示饰品槽（饰品管理走 Curios 自身界面）
        // 安全箱（中列背包下方）：仅添加已解锁数量的槽位
        // （2.0.9 修复：等级低时应只显示 1 格，旧实现固定 9 格全渲染）
        this.safeStart = PLAYER_START + PLAYER_COUNT;
        int safeSlots = resolveSafeSlots(inv, this.safeW);
        for (int i = 0; i < safeSlots; i++) {
            addSlot(new SafeBoxSlot(safeHandler, i,
                    layout.midX + (i % this.safeW) * 18,
                    layout.safeY + (i / this.safeW) * 18, inv.player));
        }
        this.safeCount = safeSlots;
    }

    public int scrollRow() {
        return scrollRow;
    }

    /** 安全箱已添加槽位数（按解锁数）。 */
    public int safeCount() {
        return safeCount;
    }

    /**
     * 2.0.8 原位滚动：以新起始行替换视口槽位（菜单实例、光标栈、屏幕均不变）。
     * 仅服务端调用；随后须调用 {@code broadcastChanges()} 同步客户端。
     */
    public void scrollTo(int newScrollRow) {
        int clamped = Math.max(0, newScrollRow);
        this.scrollRow = clamped;
        for (int local = 0; local < WAREHOUSE_SLOTS && local < this.slots.size(); local++) {
            Slot old = this.slots.get(local);
            this.slots.set(local, new LockedAwareSlot(handler, clamped * WAREHOUSE_COLS + local,
                    old.x, old.y, player));
        }
    }

    /** 客户端影子容器：容量取总行数上限（64 行 x 9 = 576），
     *  客户端无法读取服务端 rows 配置；真实数据以服务端槽位同步为准。 */
    private static ItemStackHandler resolveClientHandler(Inventory inv) {
        return new ItemStackHandler(64 * WAREHOUSE_COLS);
    }

    /** 客户端安全箱影子容器：固定 9 格，真实数据以服务端槽位同步为准。 */
    private static ItemStackHandler resolveClientSafeHandler(Inventory inv) {
        return new ItemStackHandler(SAFE_SLOTS);
    }

    /**
     * 安全箱应添加的槽位数（2.0.9：按解锁数，0 ~ 9）。
     * 服务端取玩家数据；客户端优先仓库同步包（打开仓库时服务端先发包再开菜单，
     * 同通道有序到达），回退安全箱覆盖层状态，最终回退默认配置尺寸。
     */
    private static int resolveSafeSlots(Inventory inv, int safeW) {
        if (inv.player.level().isClientSide) {
            com.deltanexus.system.network.packet.SyncWarehousePacket sync =
                    com.deltanexus.system.client.gui.WarehouseScreen.lastSync;
            if (sync != null) {
                return Math.max(0, Math.min(SAFE_SLOTS, sync.safeUnlockedSlots));
            }
            com.deltanexus.system.network.packet.SyncSafeBoxPacket st =
                    com.deltanexus.system.client.gui.SafeBoxOverlay.lastState();
            if (st != null) {
                return Math.max(0, Math.min(SAFE_SLOTS, st.unlockedSlots));
            }
            return Math.max(0, Math.min(SAFE_SLOTS,
                    safeW * com.deltanexus.system.config.ModConfig.safeBoxHeight()));
        }
        IPlayerData data = ManufacturingService.data(inv.player);
        if (data != null) {
            return Math.max(0, Math.min(SAFE_SLOTS, data.getSafeBoxUnlockedSlots()));
        }
        return Math.max(0, Math.min(SAFE_SLOTS,
                safeW * com.deltanexus.system.config.ModConfig.safeBoxHeight()));
    }

    /** 服务端安全箱容器：玩家 capability 中的真实存储（未加载时新建空容器占位）。 */
    private static ItemStackHandler resolveServerSafeHandler(Inventory inv) {
        IPlayerData data = ManufacturingService.data(inv.player);
        return data != null ? data.getSafeBoxHandler() : new ItemStackHandler(SAFE_SLOTS);
    }

    /** 客户端安全箱宽度（优先最近同步包，回退配置）。 */
    private static int clientSafeWidth() {
        com.deltanexus.system.network.packet.SyncSafeBoxPacket st =
                com.deltanexus.system.client.gui.SafeBoxOverlay.lastState();
        return st != null ? Math.max(1, Math.min(3, st.width))
                : com.deltanexus.system.config.ModConfig.safeBoxWidth();
    }

    /** 服务端安全箱宽度（按玩家数据，回退配置）。 */
    private static int serverSafeWidth(Inventory inv) {
        if (!inv.player.level().isClientSide) {
            IPlayerData data = ManufacturingService.data(inv.player);
            if (data != null) {
                return Math.max(1, Math.min(3, data.getSafeBoxWidth()));
            }
        }
        return com.deltanexus.system.config.ModConfig.safeBoxWidth();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack1 = slot.getItem();
            // 格式背包（2.0.0）：占位物不可快捷移动（由网格引擎每 Tick 自愈）
            if (com.deltanexus.system.grid.InventoryGridHandler.isSlave(stack1)) {
                return ItemStack.EMPTY;
            }
            itemstack = stack1.copy();
            int totalSlots = this.slots.size();
            if (index < WAREHOUSE_SLOTS) {
                // 仓库 -> 玩家区（背包/快捷/盔甲/副手）
                if (!this.moveItemStackTo(stack1, PLAYER_START, totalSlots, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (index >= safeStart) {
                // 安全箱 -> 玩家区（不进入仓库）
                if (!this.moveItemStackTo(stack1, PLAYER_START, safeStart, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 玩家区 -> 仓库；仓库满时 -> 安全箱（按解锁槽位数，未解锁槽位不进菜单）
                if (!this.moveItemStackTo(stack1, 0, WAREHOUSE_SLOTS, false)) {
                    if (!this.moveItemStackTo(stack1, safeStart, safeStart + safeCount, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            }
            if (stack1.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack1.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack1);
        }
        return itemstack;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /** 未解锁槽位：客户端按同步位图拦截（不可放入/不可拾取），服务端权威兜底。 */
    private static class LockedAwareSlot extends SlotItemHandler {
        private final Player player;

        LockedAwareSlot(ItemStackHandler handler, int index, int x, int y, Player player) {
            super(handler, index, x, y);
            this.player = player;
        }

        private boolean unlocked() {
            IPlayerData data = ManufacturingService.data(player);
            return data == null || data.isSlotUnlocked(getSlotIndex());
        }

        /**
         * 客户端解锁判定（2.0.10）：按最近同步包的解锁位图逐位判定。
         * 打开仓库时服务端先发同步包再开菜单（同通道有序），位图必定就绪；
         * 位图缺失/越界时一律视为未解锁（保守回退 false）——渲染上宁可少画不误画，
         * 交互上即便首帧误拒也会在同步到达后恢复，杜绝「未解锁区域仍渲染」。
         */
        private boolean clientUnlocked() {
            com.deltanexus.system.network.packet.SyncWarehousePacket sync =
                    com.deltanexus.system.client.gui.WarehouseScreen.lastSync;
            if (sync == null || sync.unlocked == null || sync.unlocked.length == 0) {
                return false;
            }
            int idx = getSlotIndex();
            int word = idx / 64;
            if (word >= sync.unlocked.length) {
                return false;
            }
            return (sync.unlocked[word] & (1L << (idx % 64))) != 0;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            // 2.0.9：客户端同样拦截未解锁格（位图已同步，无预测回滚问题）
            if (this.player.level().isClientSide()) {
                return clientUnlocked() && super.mayPlace(stack);
            }
            return unlocked() && super.mayPlace(stack);
        }

        @Override
        public boolean mayPickup(Player p) {
            if (this.player.level().isClientSide()) {
                return clientUnlocked() && super.mayPickup(p);
            }
            return unlocked() && super.mayPickup(p);
        }

        /**
         * 2.0.10：未解锁槽位对客户端渲染隐藏——AbstractContainerScreen.render
         * 遍历时跳过 isActive=false 的槽位（物品不渲染、hover 高亮不渲染），
         * 与 renderBg 的底图跳过配合，实现「未解锁格完全不渲染且不可交互」。
         */
        @Override
        public boolean isActive() {
            if (this.player.level().isClientSide()) {
                return clientUnlocked();
            }
            return unlocked();
        }
    }

    /**
     * 盔甲槽：仅允许对应装备部位的物品放入（防具校验，任意物品不可穿戴）。
     * 等价于原版 InventoryMenu ArmorSlot 行为（LivingEntity.getEquipmentSlotForItem）。
     */
    private static class ArmorValidSlot extends Slot {
        private final net.minecraft.world.entity.EquipmentSlot equipmentSlot;

        ArmorValidSlot(Inventory inv, int index, int x, int y, net.minecraft.world.entity.EquipmentSlot equipmentSlot) {
            super(inv, index, x, y);
            this.equipmentSlot = equipmentSlot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty()
                    && super.mayPlace(stack)
                    && net.minecraft.world.entity.LivingEntity.getEquipmentSlotForItem(stack) == equipmentSlot;
        }
    }
}
