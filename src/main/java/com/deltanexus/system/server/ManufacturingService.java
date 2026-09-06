package com.deltanexus.system.server;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.capability.CapabilityAttacher;
import com.deltanexus.system.common.Task;
import com.deltanexus.system.common.TaskStatus;
import com.deltanexus.system.common.WorkbenchRegistry;
import com.deltanexus.system.config.ModConfig;
import com.deltanexus.system.config.Recipe;
import com.deltanexus.system.config.RecipeCache;
import com.deltanexus.system.config.UpgradeConfig;
import com.deltanexus.system.menu.WarehouseMenu;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SSafeBoxClickPacket;
import com.deltanexus.system.network.packet.GiveItemPacket;
import com.deltanexus.system.network.packet.OpenScreenPacket;
import com.deltanexus.system.network.packet.SyncManufacturePacket;
import com.deltanexus.system.network.packet.SyncSafeBoxPacket;
import com.deltanexus.system.network.packet.SyncWarehousePacket;
import com.deltanexus.system.network.packet.SyncWorkbenchDataPacket;
import com.google.gson.JsonArray;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 制造 / 仓库核心服务（服务端）。
 *
 * <p>被动计算核心：严禁使用 ServerTickEvent 轮询。所有时间计算
 * 仅在玩家打开 GUI 或点击「刷新」时调用 {@link #updateTasks} 触发，
 * 剩余毫秒 = (startTime + cachedDuration) - now；进度由客户端本地渲染。</p>
 *
 * <p>体验优先（IO/网络优化）：</p>
 * <ul>
 *   <li>升级校验改为单次背包遍历同时统计货币与全部材料，杜绝多次全包扫描；</li>
 *   <li>配方完整 JSON 仅管理员可见时发送，普通玩家制造台包体大幅缩小；</li>
 *   <li>配方图标不携带 NBT；仓库打开/升级只发一次轻量同步包。</li>
 * </ul>
 */
public final class ManufacturingService {

    private ManufacturingService() {
    }

    // ------------------------------------------------------------------
    // 数据访问
    // ------------------------------------------------------------------

    public static IPlayerData data(net.minecraft.world.entity.player.Player player) {
        return player.getCapability(CapabilityAttacher.PLAYER_DATA).resolve().orElse(null);
    }

    private static void msg(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }

    /** 聊天框消息（开始/完成提示，任务三要求）。 */
    private static void chat(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), false);
    }

    /** 配方显示名（未找到配方时回退 recipeId）。 */
    private static String recipeDisplayName(String recipeId) {
        Recipe r = RecipeCache.get().get(recipeId);
        return r != null ? r.displayName() : recipeId;
    }

    // ------------------------------------------------------------------
    // 仓库
    // ------------------------------------------------------------------

    /** 打开仓库第 0 行（服务端打开 Menu + 发送轻量同步包）。 */
    public static void openWarehouse(ServerPlayer player) {
        openWarehouse(player, 0);
    }

    /**
     * 「特勤处」（2.0.2）：仓库/安全箱升级独立界面。不打开容器菜单，
     * 仅发送仓库同步包（含升级数据：等级/费用/材料/货币），客户端自行渲染。
     * 2.0.3：独立权限校验（special）。
     * 2.0.4：指令路径追加 OpenScreenPacket（SCREEN_SPECIAL），客户端据此打开界面。
     */
    public static void openSpecialOps(ServerPlayer player) {
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        if (!PermissionManager.canOpenSpecial(player)) {
            msg(player, "msg.dn.perm.denied.special");
            return;
        }
        sendSyncWarehouse(player);
        PacketHandler.sendToPlayer(player, new OpenScreenPacket(
                OpenScreenPacket.SCREEN_SPECIAL, "", "", player.hasPermissions(4)));
    }

    /** 打开仓库指定起始行（2.0.1 滚动渲染，替代翻页）。先发同步包（含起始行）再开菜单：
     *  同一通道有序送达，客户端菜单构造可读到正确起始行。 */
    public static void openWarehouse(ServerPlayer player, int scrollRow) {        // 权限校验（1.1.0）：指令/按键同源；OP 与全局默认/玩家覆盖见 PermissionManager
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        if (!PermissionManager.canOpenWarehouse(player)) {
            msg(player, "msg.dn.perm.denied.warehouse");
            return;
        }
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        // 会话内扩容：行数配置扩容后在线玩家无需重登，打开仓库即获得新容量
        ensureWarehouseCapacity(player);
        int rows = ModConfig.warehouseRows();
        // 起始行三重上限防御：配置行数 + 玩家实际容量行数 + 已解锁行数
        // （2.0.9：未解锁行不可滚动不可见；防菜单槽位越界 + 防伪造包滚入未解锁区）
        int effectiveRows = effectiveRows(data, rows);
        int maxScroll = Math.max(0, Math.min(unlockedRows(data), effectiveRows)
                - WarehouseMenu.WAREHOUSE_ROWS);
        int clamped = net.minecraft.util.Mth.clamp(scrollRow, 0, maxScroll);
        sendSyncWarehouse(player, clamped);
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new WarehouseMenu(id, inv, data.getWarehouseHandler(), clamped),
                Component.translatable("gui.dn.warehouse")));
    }

    /** 玩家实际可用行数 = min(配置行数, 容量/9)，容量不足时以玩家容量为准。 */
    private static int effectiveRows(IPlayerData data, int configRows) {
        int capacityRows = Math.max(1, data.getCapacity() / WarehouseMenu.WAREHOUSE_COLS);
        return Math.max(1, Math.min(configRows, capacityRows));
    }

    /**
     * 已解锁行数（2.0.9）：解锁为前缀式位图，最高置位格即已解锁格数，
     * 向上取整换算行数。与服务端渲染层（WarehouseScreen#unlockedRows）口径一致，
     * 用于限制滚动上限——未解锁行不可滚入。
     */
    private static int unlockedRows(IPlayerData data) {
        java.util.BitSet bits = data.getUnlockedSlots();
        int unlockedSlots = bits.length(); // 前缀式：最高置位+1 = 已解锁格数
        return Math.max(1, (unlockedSlots + WarehouseMenu.WAREHOUSE_COLS - 1)
                / WarehouseMenu.WAREHOUSE_COLS);
    }

    /**
     * 2.0.8 原位滚动：仓库菜单已打开时直接替换视口槽位（不重建菜单）。
     * 修复旧实现（重开菜单）导致的：光标物品掉落、鼠标指针/悬停状态重置、界面闪烁。
     * 菜单已关闭等边缘场景回退 {@link #openWarehouse}。
     */
    public static void scrollWarehouse(ServerPlayer player, int scrollRow) {
        if (!PermissionManager.canUseFeatures(player)) {
            return;
        }
        if (!PermissionManager.canOpenWarehouse(player)) {
            return;
        }
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        int effectiveRows = effectiveRows(data, ModConfig.warehouseRows());
        // 2.0.9：滚动上限同样限定在已解锁行内（防伪造包滚入未解锁区，与客户端口径一致）
        int maxScroll = Math.max(0, Math.min(unlockedRows(data), effectiveRows)
                - WarehouseMenu.WAREHOUSE_ROWS);
        int clamped = net.minecraft.util.Mth.clamp(scrollRow, 0, maxScroll);
        if (player.containerMenu instanceof WarehouseMenu wm) {
            wm.scrollTo(clamped);
            // 先广播槽位内容，再同步起始行（同通道有序：客户端行信息与格子内容一致到达）
            wm.broadcastChanges();
            sendSyncWarehouse(player, clamped);
        }
        // 2.0.8 修复：菜单未打开（界面关闭瞬间的在途滚轮包）时静默丢弃——
        // 旧行为会重开仓库界面，导致用户刚关闭的界面被意外弹出
    }

    /** 按当前配置行数扩容玩家仓库存储（仅增大，保留物品）；容量未变则无操作。 */
    public static void ensureWarehouseCapacity(ServerPlayer player) {
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        data.resizeWarehouse(ModConfig.warehouseRows() * WarehouseMenu.WAREHOUSE_COLS);
    }

    /** 行数配置变更生效：在线玩家 handler 按新配置扩容 + 重发同步包（打开中的仓库界面立即刷新行数与滚动范围）。 */
    public static void applyRowsToOnline(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ensureWarehouseCapacity(p);
            sendSyncWarehouse(p);
        }
    }

    /** 发送仓库轻量同步包（容量 + 解锁位图 + 等级 + 货币 + 下一级费用/材料明细 + 起始行）。 */
    public static void sendSyncWarehouse(ServerPlayer player) {
        sendSyncWarehouse(player, 0);
    }

    public static void sendSyncWarehouse(ServerPlayer player, int scrollRow) {
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        UpgradeConfig.UpgradeLevel next = UpgradeConfig.get().next(data.getWarehouseLevel());
        // 统计升级所需材料（支持 NBT 匹配）
        List<SyncWarehousePacket.Material> materials = new ArrayList<>();
        if (next != null) {
            for (UpgradeConfig.RequiredItem req : next.requiredItems) {
                materials.add(new SyncWarehousePacket.Material(req.item, req.count, req.nbt,
                        countMatching(player, req), req.matchType.key()));
            }
        }
        // 安全箱（1.1.0）：等级/尺寸/下一级费用与材料
        int safeLevel = data.getSafeBoxLevel();
        UpgradeConfig.UpgradeLevel safeNext = UpgradeConfig.get().safeNext(safeLevel);
        List<SyncWarehousePacket.Material> safeMaterials = new ArrayList<>();
        if (safeNext != null) {
            for (UpgradeConfig.RequiredItem req : safeNext.requiredItems) {
                safeMaterials.add(new SyncWarehousePacket.Material(req.item, req.count, req.nbt,
                        countMatching(player, req), req.matchType.key()));
            }
        }
        int rows = ModConfig.warehouseRows();
        // 同步包中的总行数同样取玩家实际可用行数（容量防御，与 openWarehouse 一致）
        int effectiveRows = effectiveRows(data, rows);
        // 货币按类型统计（物品/计分板/Vault），Vault 未安装时标记不可用
        boolean currencyUsable = CurrencyManager.isUsable();
        long currencyHeld = currencyUsable ? CurrencyManager.getBalance(player) : 0;
        PacketHandler.sendToPlayer(player, new SyncWarehousePacket(
                data.getCapacity(),
                data.getUnlockedSlots().toLongArray(),
                data.getWarehouseLevel(),
                UpgradeConfig.get().maxLevel(),
                (int) currencyHeld,
                ModConfig.currencyItem(),
                player.hasPermissions(4),
                next == null ? 0 : next.level,
                next == null ? 0 : next.costMoney,
                next == null ? 0 : next.unlockSlots,
                materials,
                Math.max(0, Math.min(scrollRow, Math.max(0, effectiveRows - WarehouseMenu.WAREHOUSE_ROWS))),
                effectiveRows,
                CurrencyManager.type(),
                currencyUsable,
                safeLevel,
                UpgradeConfig.get().safeMaxLevel(),
                data.getSafeBoxUnlockedSlots(),
                data.getSafeBoxWidth(),
                data.getSafeBoxHeight(),
                safeNext == null ? 0 : safeNext.level,
                safeNext == null ? 0 : safeNext.costMoney,
                safeNext == null ? 0 : safeNext.unlockSlots,
                safeMaterials));
    }

    /**
     * 仓库升级：货币走 CurrencyManager（物品/计分板/Vault），材料为物品；
     * 校验通过后一次性扣除。
     */
    public static void upgradeWarehouse(ServerPlayer player) {
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        int currentLevel = data.getWarehouseLevel();
        if (currentLevel >= UpgradeConfig.get().maxLevel()) {
            msg(player, "msg.dn.upgrade.max_level");
            return;
        }
        UpgradeConfig.UpgradeLevel next = UpgradeConfig.get().next(currentLevel);
        if (next == null) {
            msg(player, "msg.dn.upgrade.no_next");
            return;
        }
        // 货币校验（按类型）；Vault 未安装时拒绝
        if (!CurrencyManager.isUsable()) {
            msg(player, "msg.dn.upgrade.no_currency_system");
            return;
        }
        if (!CurrencyManager.canAfford(player, next.costMoney)) {
            msg(player, "msg.dn.upgrade.no_money", next.costMoney);
            return;
        }
        // 材料校验（支持 NBT 匹配）
        for (UpgradeConfig.RequiredItem req : next.requiredItems) {
            int held = countMatching(player, req);
            if (held < req.count) {
                msg(player, "msg.dn.upgrade.no_items", req.item + " " + held + "/" + req.count);
                return;
            }
        }
        // 扣除（货币 + 材料）
        if (!CurrencyManager.spend(player, next.costMoney)) {
            msg(player, "msg.dn.upgrade.no_money", next.costMoney);
            return;
        }
        for (UpgradeConfig.RequiredItem req : next.requiredItems) {
            removeMatching(player, req);
        }
        data.setWarehouseLevel(next.level);
        data.unlockUpTo(next.unlockSlots);
        msg(player, "msg.dn.upgrade.done", next.level, next.unlockSlots);
        sendSyncWarehouse(player);
    }

    // ------------------------------------------------------------------
    // 格式背包配置（2.0.2：指令/Web 修改 + 客户端同步）
    // ------------------------------------------------------------------

    /** 向单个玩家推送格式背包配置（物品尺寸 + 快捷栏规则 + 类配置）。 */
    public static void sendGridConfig(ServerPlayer player) {
        PacketHandler.sendToPlayer(player, new com.deltanexus.system.network.packet.SyncGridSizesPacket(
                com.deltanexus.system.grid.ItemSizeConfig.allCustom(),
                new java.util.ArrayList<>(com.deltanexus.system.grid.GridConfig.rules()),
                com.deltanexus.system.grid.GridClassConfig.allClasses(),
                com.deltanexus.system.grid.GridClassConfig.allItemClasses()));
    }

    /** 向指定玩家同步服务端 GUI 白名单与功能开关（2.0.9 / 2.1：登录、重载、功能开关变更时调用）。 */
    public static void sendUiWhitelist(ServerPlayer player) {
        PacketHandler.sendToPlayer(player, new com.deltanexus.system.network.packet.SyncServerUiPacket(
                com.deltanexus.system.config.ModConfig.serverUiWhitelist(),
                PermissionManager.canUseFeatures(player)));
    }

    /** 向全体在线玩家广播格式背包配置（修改后调用）。 */
    public static void broadcastGridConfig() {
        if (currentServer() == null) {
            return;
        }
        for (ServerPlayer p : currentServer().getPlayerList().getPlayers()) {
            sendGridConfig(p);
        }
    }

    private static net.minecraft.server.MinecraftServer currentServer() {
        return net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
    }

    /**
     * 服务端配置热重载：广播 GUI 白名单（2.0.9，在线玩家即时生效）。
     * 2.0.10 修复：ModConfigEvent 是 MOD 总线事件，此前未指定 bus（默认 FORGE）
     * 导致订阅器从未被调用，服务端白名单热重载广播失效。现挂 MOD 总线。
     */
    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(
            modid = DeltaNexus.MODID, bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD)
    public static final class ConfigEvents {
        private ConfigEvents() {
        }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onConfigReload(net.minecraftforge.fml.event.config.ModConfigEvent.Reloading event) {
            // 仅 ModConfig（2.0.10 起为 COMMON）且服务器运行中才广播：
            // 纯客户端重载 currentServer() 为 null 跳过；单机集成服务器正常广播给本机玩家
            if (event.getConfig().getSpec() == com.deltanexus.system.config.ModConfig.SERVER_SPEC
                    && currentServer() != null) {
                for (ServerPlayer p : currentServer().getPlayerList().getPlayers()) {
                    sendUiWhitelist(p);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 安全箱（1.1.0）
    // ------------------------------------------------------------------

    /** 安全箱升级：货币走 CurrencyManager，材料为物品；校验通过后一次性扣除（与仓库升级一致）。 */
    public static void upgradeSafeBox(ServerPlayer player) {
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        int currentLevel = data.getSafeBoxLevel();
        if (currentLevel >= UpgradeConfig.get().safeMaxLevel()) {
            msg(player, "msg.dn.safe.upgrade.max_level");
            return;
        }
        UpgradeConfig.UpgradeLevel next = UpgradeConfig.get().safeNext(currentLevel);
        if (next == null) {
            msg(player, "msg.dn.upgrade.no_next");
            return;
        }
        if (!CurrencyManager.isUsable()) {
            msg(player, "msg.dn.upgrade.no_currency_system");
            return;
        }
        if (!CurrencyManager.canAfford(player, next.costMoney)) {
            msg(player, "msg.dn.upgrade.no_money", next.costMoney);
            return;
        }
        for (UpgradeConfig.RequiredItem req : next.requiredItems) {
            int held = countMatching(player, req);
            if (held < req.count) {
                msg(player, "msg.dn.upgrade.no_items", req.item + " " + held + "/" + req.count);
                return;
            }
        }
        if (!CurrencyManager.spend(player, next.costMoney)) {
            msg(player, "msg.dn.upgrade.no_money", next.costMoney);
            return;
        }
        for (UpgradeConfig.RequiredItem req : next.requiredItems) {
            removeMatching(player, req);
        }
        data.setSafeBoxLevel(next.level);
        msg(player, "msg.dn.safe.upgrade.done", next.level, data.getSafeBoxHeight(), data.getSafeBoxWidth());
        sendSyncWarehouse(player);
        syncSafeBox(player);
    }

    /** 发送安全箱状态（背包/容器/仓库界面数据源；含权限判定，无权时 allowed=false）。 */
    public static void syncSafeBox(ServerPlayer player) {
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        boolean allowed = PermissionManager.canUseFeatures(player)
                && PermissionManager.canOpenSafeBox(player);
        if (!allowed) {
            msg(player, "msg.dn.perm.denied.safe_box");
        }
        ItemStack[] items = new ItemStack[9];
        ItemStackHandler safe = data.getSafeBoxHandler();
        for (int i = 0; i < 9; i++) {
            items[i] = safe.getStackInSlot(i).copy();
        }
        ItemStack carried = player.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu
                ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        PacketHandler.sendToPlayer(player, new SyncSafeBoxPacket(
                allowed,
                data.getSafeBoxLevel(),
                UpgradeConfig.get().safeMaxLevel(),
                data.getSafeBoxUnlockedSlots(),
                data.getSafeBoxWidth(),
                data.getSafeBoxHeight(),
                items,
                carried));
    }

    /**
     * 安全箱槽位交互（服务端权威执行，背包/容器/仓库界面通用）：
     * 0 = 点击（光标与槽位交换/合并），1 = 潜行点击（槽位物品整体移入背包）。
     * 2.1：不再限定仅背包菜单（InventoryMenu）——dn 容器界面（DnContainerScreen）
     * 同样渲染安全箱面板并发送本包；服务端按权限与解锁状态权威判定。
     */
    public static void safeBoxClick(ServerPlayer player, int slot, int action) {
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        if (!PermissionManager.canOpenSafeBox(player)) {
            msg(player, "msg.dn.perm.denied.safe_box");
            syncSafeBox(player);
            return;
        }
        if (slot < 0 || slot >= 9) {
            return;
        }
        ItemStackHandler safe = data.getSafeBoxHandler();
        if (!data.isSafeSlotUnlocked(slot)) {
            msg(player, "msg.dn.safe.locked");
            syncSafeBox(player);
            return;
        }
        if (action == C2SSafeBoxClickPacket.ACTION_SHIFT) {
            // 潜行点击：槽位物品整体移入背包（放不下则掉落）
            ItemStack stack = safe.getStackInSlot(slot);
            // 格式背包（2.0.0）：占位物格不可交互（整件物品由主格代表）
            if (com.deltanexus.system.grid.InventoryGridHandler.isSlave(stack)) {
                syncSafeBox(player);
                return;
            }
            if (stack.isEmpty()) {
                syncSafeBox(player);
                return;
            }
            safe.setStackInSlot(slot, ItemStack.EMPTY);
            ItemStack moved = stack.copy();
            if (!player.getInventory().add(moved)) {
                if (!moved.isEmpty()) {
                    player.drop(moved, false);
                }
            }
            player.inventoryMenu.broadcastChanges();
            // 格式背包（2.0.0）：整理网格（重建占位物）
            com.deltanexus.system.grid.InventoryGridHandler.arrange(player, safe);
            syncSafeBox(player);
            return;
        }
        // 点击：光标与槽位交换/合并（与容器点击语义一致）
        ItemStack cursor = player.containerMenu.getCarried();
        ItemStack inSlot = safe.getStackInSlot(slot);
        // 格式背包（2.0.0）：占位物格视为不可交互（整件物品由主格代表）
        if (com.deltanexus.system.grid.InventoryGridHandler.isSlave(inSlot)) {
            syncSafeBox(player);
            return;
        }
        if (cursor.isEmpty() && inSlot.isEmpty()) {
            return;
        }
        // NBT 限制（1.1.0）：命中限制规则的物品禁止放入安全箱
        if (com.deltanexus.system.config.SafeBoxRestrictions.isRestricted(cursor)) {
            msg(player, "msg.dn.safe.restricted");
            syncSafeBox(player);
            return;
        }
        // 2.0.10：1x1 安全箱（仅 1 格）不能塞入大于 1x1 的物品——
        // 拒绝放入，物品回到鼠标指针（与仓库界面 SafeBoxSlot 口径一致）
        if (!cursor.isEmpty()
                && data.getSafeBoxUnlockedSlots() <= 1
                && !com.deltanexus.system.grid.InventoryGridHandler.getBaseDim(cursor).is1x1()) {
            syncSafeBox(player);
            return;
        }
        if (inSlot.isEmpty()) {
            safe.setStackInSlot(slot, cursor.copy());
            player.containerMenu.setCarried(ItemStack.EMPTY);
        } else if (cursor.isEmpty()) {
            player.containerMenu.setCarried(inSlot.copy());
            safe.setStackInSlot(slot, ItemStack.EMPTY);
        } else if (ItemStack.isSameItemSameTags(cursor, inSlot)) {
            int add = Math.min(cursor.getCount(), inSlot.getMaxStackSize() - inSlot.getCount());
            if (add > 0) {
                inSlot.grow(add);
                cursor.shrink(add);
                safe.setStackInSlot(slot, inSlot);
                player.containerMenu.setCarried(cursor);
            }
        } else {
            safe.setStackInSlot(slot, cursor.copy());
            player.containerMenu.setCarried(inSlot.copy());
        }
        player.inventoryMenu.broadcastChanges();
        // 格式背包（2.0.0）：点击后立即整理网格（冲突重排/补占位物）
        com.deltanexus.system.grid.InventoryGridHandler.arrange(player, safe);
        syncSafeBox(player);
    }

    /** 统计背包与仓库中满足 NBT 要求的材料总数（2.0.3：特勤处升级/制作台制造识别仓库）。 */
    private static int countMatching(ServerPlayer player, UpgradeConfig.RequiredItem req) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(req.item));
        if (item == null) {
            return 0;
        }
        net.minecraft.nbt.CompoundTag expected = com.deltanexus.system.common.NbtMatcher.parseTag(req.nbt);
        int[] total = {0};
        forEachMaterialSource(player, (stack, idx) -> {
            if (stack.is(item) && com.deltanexus.system.common.NbtMatcher.matchesNbt(stack.getTag(), expected, req.matchType)) {
                total[0] += stack.getCount();
            }
        });
        return total[0];
    }

    /** 扣除背包与仓库中满足 NBT 要求的材料（遍历扣减直到扣够；先背包后仓库）。 */
    private static void removeMatching(ServerPlayer player, UpgradeConfig.RequiredItem req) {
        int need = req.count;
        if (need <= 0) {
            return;
        }
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(req.item));
        if (item == null) {
            return;
        }
        net.minecraft.nbt.CompoundTag expected = com.deltanexus.system.common.NbtMatcher.parseTag(req.nbt);
        int[] remaining = {need};
        forEachMaterialSource(player, (stack, idx) -> {
            if (remaining[0] <= 0) {
                return;
            }
            if (!stack.is(item) || !com.deltanexus.system.common.NbtMatcher.matchesNbt(stack.getTag(), expected, req.matchType)) {
                return;
            }
            int take = Math.min(remaining[0], stack.getCount());
            stack.shrink(take);
            remaining[0] -= take;
            if (stack.isEmpty()) {
                setSourceEmpty(player, idx);
            }
        });
    }

    /**
     * 材料来源遍历：玩家背包 + 仓库已解锁槽（跳过占位物）。
     * 2.0.3：特勤处升级与制作台制造均可使用仓库中的材料。
     */
    private static void forEachMaterialSource(ServerPlayer player, java.util.function.ObjIntConsumer<ItemStack> consumer) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || com.deltanexus.system.grid.InventoryGridHandler.isSlave(stack)) {
                continue;
            }
            consumer.accept(stack, i);
        }
        IPlayerData d = data(player);
        if (d != null) {
            ItemStackHandler wh = d.getWarehouseHandler();
            for (int i = 0; i < wh.getSlots(); i++) {
                if (!d.isSlotUnlocked(i)) {
                    continue;
                }
                ItemStack stack = wh.getStackInSlot(i);
                if (stack.isEmpty() || com.deltanexus.system.grid.InventoryGridHandler.isSlave(stack)) {
                    continue;
                }
                consumer.accept(stack, WAREHOUSE_SOURCE_OFFSET + i);
            }
        }
    }

    /** 仓库来源索引偏移（与背包索引区分）。 */
    private static final int WAREHOUSE_SOURCE_OFFSET = 100000;

    private static void setSourceEmpty(ServerPlayer player, int idx) {
        if (idx >= WAREHOUSE_SOURCE_OFFSET) {
            IPlayerData d = data(player);
            if (d != null) {
                d.getWarehouseHandler().setStackInSlot(idx - WAREHOUSE_SOURCE_OFFSET, ItemStack.EMPTY);
            }
        } else {
            player.getInventory().setItem(idx, ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------
    // 制造台（工作台 id 动态化）
    // ------------------------------------------------------------------

    /** 打开制造台：被动计算 + 发送同步包 + 打开屏幕（显示名来自工作台配置）。 */
    public static void openManufacture(ServerPlayer player, String workbenchId) {
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        if (!PermissionManager.canOpenWorkbench(player)) {
            msg(player, "msg.dn.perm.denied.workbench");
            return;
        }
        WorkbenchRegistry.Workbench wb = WorkbenchRegistry.get().getById(workbenchId);
        if (wb == null) {
            msg(player, "msg.dn.workbench.not_found", workbenchId);
            return;
        }
        syncManufacture(player, wb.id);
        PacketHandler.sendToPlayer(player, new OpenScreenPacket(
                OpenScreenPacket.SCREEN_MANUFACTURE, wb.id, wb.display, player.hasPermissions(4)));
    }

    /** 打开工作台总览（与 G 键一致）：客户端本地打开全屏总览并请求数据。 */
    public static void openWorkbenchOverview(ServerPlayer player) {
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        if (!PermissionManager.canOpenWorkbench(player)) {
            msg(player, "msg.dn.perm.denied.workbench");
            return;
        }
        PacketHandler.sendToPlayer(player, new OpenScreenPacket(
                OpenScreenPacket.SCREEN_WORKBENCH, "", "", player.hasPermissions(4)));
    }

    /** 全量同步制造台状态。 */
    public static void syncManufacture(ServerPlayer player, String workbenchId) {
        if (!PermissionManager.canUseFeatures(player)) {
            return;
        }
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        WorkbenchRegistry.Workbench wb = WorkbenchRegistry.get().getById(workbenchId);
        if (wb == null) {
            return;
        }
        updateTasks(player, wb.id);
        Deque<Task> deque = data.getTasks(wb.id);
        long now = System.currentTimeMillis();
        boolean online = ModConfig.onlineMode();

        List<SyncManufacturePacket.TaskInfo> tasks = new ArrayList<>();
        for (Task t : deque) {
            long remaining = online ? t.remainingMsOnline(now) : t.remainingMs(now);
            tasks.add(new SyncManufacturePacket.TaskInfo(
                    t.taskId,
                    t.recipeId,
                    remaining <= 0 ? 0 : (int) ((remaining + 999) / 1000),
                    (int) Math.max(1, t.cachedDurationMs / 1000),
                    t.status == TaskStatus.COMPLETED));
        }

        int maxQueue = ModConfig.maxQueueSize();
        List<SyncManufacturePacket.RecipeInfo> recipes = new ArrayList<>();
        for (Recipe r : RecipeCache.get().getByWorkbench(wb.id)) {
            // 1.1.0：图标保留 NBT（改名物品显示改名后的名字，与总览/仓库升级展示一致）
            ItemStack icon = r.iconItem();
            recipes.add(new SyncManufacturePacket.RecipeInfo(
                    r.recipeId, r.type, icon, r.baseDuration,
                    r.requiredLevel, r.input.size(), r.maxParallel, r.displayName()));
        }

        PacketHandler.sendToPlayer(player, new SyncManufacturePacket(
                wb.id, wb.display, player.hasPermissions(4), data.getWarehouseLevel(),
                deque.size(), maxQueue, tasks, recipes));
    }

    /** 刷新（客户端点击刷新按钮）。 */
    public static void refreshTasks(ServerPlayer player, String workbenchId) {
        syncManufacture(player, workbenchId);
    }

    /** 开始制造任务：校验等级/队列/材料 -> 消耗输入 -> 快照耗时（乘配置倍率）-> 入队。 */
    public static void startTask(ServerPlayer player, String workbenchId, String recipeId) {
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        Recipe recipe = RecipeCache.get().get(recipeId);
        if (recipe == null) {
            msg(player, "msg.dn.recipe.not_found");
            return;
        }
        if (!workbenchId.equals(recipe.workbenchId())) {
            msg(player, "msg.dn.recipe.wrong_workbench");
            return;
        }
        if (data.getWarehouseLevel() < recipe.requiredLevel) {
            msg(player, "msg.dn.task.level_required", recipe.requiredLevel);
            return;
        }
        Deque<Task> deque = data.getTasks(workbenchId);
        int maxQueue = ModConfig.maxQueueSize();
        long waiting = deque.stream().filter(t -> t.status == TaskStatus.WAITING).count();
        if (waiting >= maxQueue) {
            msg(player, "msg.dn.task.queue_full", maxQueue);
            return;
        }
        // 配方同时制作上限（max_parallel）
        long runningThis = deque.stream()
                .filter(t -> t.status == TaskStatus.WAITING && t.recipeId.equals(recipeId)).count();
        if (runningThis >= recipe.maxParallel) {
            msg(player, "msg.dn.task.parallel_full", recipeId, recipe.maxParallel);
            return;
        }
        // 校验并消耗输入材料（NbtMatcher 按 exact/contains/ignore 匹配）
        if (!consumeInputs(player, recipe)) {
            msg(player, "msg.dn.task.no_materials");
            return;
        }
        // 新任务耗时 = 配方 base_duration x 配置倍率（配置热加载，只影响新任务）
        double mult = ModConfig.timeMultiplier();
        long durationMs = Math.max(1000L, (long) (recipe.baseDurationMs() * mult));

        Task task = new Task(data.nextTaskId(), recipeId, System.currentTimeMillis(), durationMs, TaskStatus.WAITING);
        deque.addLast(task);
        chat(player, "msg.dn.task.started", recipe.displayName(), durationMs / 1000);
        syncManufacture(player, workbenchId);
    }

    /** 校验所有输入材料是否充足（逐栈 NBT 匹配），充足则一次性扣除。 */
    private static boolean consumeInputs(ServerPlayer player, Recipe recipe) {
        // 第一遍：校验
        for (Recipe.Ingredient ing : recipe.input) {
            if (ing.item == null || ing.item.isBlank()) {
                return false;
            }
            if (!hasEnough(player, ing)) {
                return false;
            }
        }
        // 第二遍：扣除
        for (Recipe.Ingredient ing : recipe.input) {
            removeMatching(player, ing);
        }
        return true;
    }

    /** 材料是否充足（2.0.3：背包 + 仓库已解锁槽）。 */
    private static boolean hasEnough(net.minecraft.world.entity.player.Player player, Recipe.Ingredient ing) {
        int[] need = {ing.count};
        if (!(player instanceof ServerPlayer sp)) {
            return false;
        }
        forEachMaterialSource(sp, (stack, idx) -> {
            if (need[0] <= 0) {
                return;
            }
            if (com.deltanexus.system.common.NbtMatcher.matchesItem(
                    stack, ing.item, 1, ing.matchType, ing.nbt)) {
                need[0] -= stack.getCount();
            }
        });
        return need[0] <= 0;
    }

    /** 扣除制造材料（2.0.3：背包 + 仓库已解锁槽，先背包后仓库）。 */
    private static void removeMatching(net.minecraft.world.entity.player.Player player, Recipe.Ingredient ing) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        int[] remaining = {ing.count};
        forEachMaterialSource(sp, (stack, idx) -> {
            if (remaining[0] <= 0) {
                return;
            }
            if (com.deltanexus.system.common.NbtMatcher.matchesItem(
                    stack, ing.item, 1, ing.matchType, ing.nbt)) {
                int take = Math.min(remaining[0], stack.getCount());
                stack.shrink(take);
                remaining[0] -= take;
                if (stack.isEmpty()) {
                    setSourceEmpty(sp, idx);
                }
            }
        });
        player.inventoryMenu.broadcastChanges();
        // 仓库材料扣减后即时网格整理（补占位物）
        IPlayerData d = data(sp);
        if (d != null) {
            com.deltanexus.system.grid.InventoryGridHandler.arrange(sp, d.getWarehouseHandler());
        }
    }

    /** 领取任务：服务端二次校验 COMPLETED -> 发放物品 -> 移除任务。 */
    public static void claimTask(ServerPlayer player, String workbenchId, int taskId) {
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        updateTasks(player, workbenchId);
        Deque<Task> deque = data.getTasks(workbenchId);
        Task found = null;
        Iterator<Task> it = deque.iterator();
        while (it.hasNext()) {
            Task t = it.next();
            if (t.taskId == taskId) {
                found = t;
                it.remove();
                break;
            }
        }
        if (found == null) {
            msg(player, "msg.dn.claim.not_found");
            return;
        }
        if (found.status != TaskStatus.COMPLETED) {
            msg(player, "msg.dn.claim.not_ready");
            return;
        }
        data.markTasksDirty();
        Recipe recipe = RecipeCache.get().get(found.recipeId);
        List<ItemStack> given = new ArrayList<>();
        if (recipe != null) {
            for (Recipe.Output out : recipe.output) {
                ItemStack stack = out.toItemStack();
                if (stack.isEmpty()) {
                    continue;
                }
                given.add(stack.copy());
                if (!player.getInventory().add(stack.copy())) {
                    player.drop(stack.copy(), false);
                }
            }
            player.inventoryMenu.broadcastChanges();
        }
        String claimName = recipe != null ? recipe.displayName() : found.recipeId;
        msg(player, "msg.dn.claim.done", claimName);
        PacketHandler.sendToPlayer(player, new GiveItemPacket(taskId, true,
                Component.translatable("msg.dn.claim.done", claimName).getString(), given));
        syncManufacture(player, workbenchId);
    }

    /** 取消制造中任务：移除任务并退还输入材料（已完成任务不可取消）。 */
    public static void cancelTask(ServerPlayer player, String workbenchId, int taskId) {
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        updateTasks(player, workbenchId);
        Deque<Task> deque = data.getTasks(workbenchId);
        Task found = null;
        for (Task t : deque) {
            if (t.taskId == taskId) {
                found = t;
                break;
            }
        }
        if (found == null) {
            msg(player, "msg.dn.claim.not_found");
            return;
        }
        if (found.status == TaskStatus.COMPLETED) {
            // 已完成应领取而非取消
            msg(player, "msg.dn.cancel.completed");
            return;
        }
        deque.remove(found);
        data.markTasksDirty();
        // 退还输入材料（按配方原始 input；NBT 不还原，避免复杂匹配）
        Recipe recipe = RecipeCache.get().get(found.recipeId);
        if (recipe != null) {
            for (Recipe.Ingredient ing : recipe.input) {
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(ing.item));
                if (item != null && ing.count > 0) {
                    ItemStack stack = new ItemStack(item, ing.count);
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                }
            }
            player.inventoryMenu.broadcastChanges();
        }
        msg(player, "msg.dn.cancel.done", recipe != null ? recipe.displayName() : found.recipeId);
        // 刷新工作台总览（按钮状态立即更新）
        sendWorkbenchData(player);
    }

    /**
     * 被动计算核心：遍历队列按模式计算剩余时间，<=0 标记 COMPLETED。
     * 仅由打开 GUI / 刷新触发，绝不注册 ServerTickEvent。
     */
    public static void updateTasks(ServerPlayer player, String workbenchId) {
        IPlayerData data = data(player);
        if (data == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean online = ModConfig.onlineMode();
        boolean changed = false;
        for (Task t : data.getTasks(workbenchId)) {
            long remaining = online ? t.remainingMsOnline(now) : t.remainingMs(now);
            if (t.status == TaskStatus.WAITING && remaining <= 0) {
                t.status = TaskStatus.COMPLETED;
                changed = true;
                // 完成提醒（聊天框；错过不影响核心逻辑）
                chat(player, "msg.dn.task.complete", recipeDisplayName(t.recipeId));
            }
        }
        if (changed) {
            data.markTasksDirty();
        }
    }

    // ------------------------------------------------------------------
    // 工作台总览（任务二全屏 UI）
    // ------------------------------------------------------------------

    /** 发送工作台总览数据：所有工作台 + 全部配方（输入/输出物品明细，保留 NBT 以显示改名等自定义名称）。 */
    public static void sendWorkbenchData(ServerPlayer player) {
        if (!PermissionManager.canUseFeatures(player)) {
            msg(player, "msg.dn.feature.disabled");
            return;
        }
        // 权限校验（1.1.0）：G 键客户端本地打开界面，服务端在此拒绝数据下发
        if (!PermissionManager.canOpenWorkbench(player)) {
            msg(player, "msg.dn.perm.denied.workbench");
            return;
        }
        List<SyncWorkbenchDataPacket.WorkbenchInfo> wbs = new ArrayList<>();
        List<SyncWorkbenchDataPacket.RecipeDetail> details = new ArrayList<>();
        IPlayerData pdata = data(player);
        long now = System.currentTimeMillis();
        boolean online = ModConfig.onlineMode();
        for (WorkbenchRegistry.Workbench wb : WorkbenchRegistry.get().all()) {
            wbs.add(new SyncWorkbenchDataPacket.WorkbenchInfo(wb.id, wb.display));
            updateTasks(player, wb.id);  // 被动结算：请求总览时先更新任务状态（完成检测）
            Deque<Task> queue = pdata != null ? pdata.getTasks(wb.id) : new java.util.ArrayDeque<>();
            for (Recipe r : RecipeCache.get().getByWorkbench(wb.id)) {
                // 1.1.0：输入物品保留配方 NBT（如改名的纸显示改名后的名字，与仓库升级物品展示一致）
                List<ItemStack> inputs = new ArrayList<>();
                for (Recipe.Ingredient ing : r.input) {
                    Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(ing.item));
                    if (item != null) {
                        ItemStack stack = new ItemStack(item, Math.max(1, ing.count));
                        net.minecraft.nbt.CompoundTag tag = com.deltanexus.system.common.NbtMatcher.parseTag(ing.nbt);
                        if (tag != null) {
                            stack.setTag(tag);
                        }
                        inputs.add(stack);
                    }
                }
                // 1.1.0：输出/图标保留 NBT（toItemStack 已含 NBT，不再剥离）
                List<ItemStack> outputs = new ArrayList<>();
                for (Recipe.Output out : r.output) {
                    ItemStack stack = out.toItemStack();
                    if (!stack.isEmpty()) {
                        outputs.add(stack);
                    }
                }
                ItemStack icon = r.iconItem();
                // 按钮三状态所需任务信息（按模式计算剩余时间）
                int runningCount = 0;
                boolean hasDone = false;
                int claimTaskId = -1;
                float progress01 = -1F;
                int runningTaskId = -1;
                for (Task t : queue) {
                    if (!t.recipeId.equals(r.recipeId)) {
                        continue;
                    }
                    if (t.status == TaskStatus.COMPLETED) {
                        hasDone = true;
                        if (claimTaskId < 0) {
                            claimTaskId = t.taskId;
                        }
                        continue;
                    }
                    runningCount++;
                    if (runningTaskId < 0) {
                        runningTaskId = t.taskId;
                    }
                    if (progress01 < 0F) {
                        long remaining = online ? t.remainingMsOnline(now) : t.remainingMs(now);
                        long total = t.cachedDurationMs;
                        progress01 = total <= 0 ? 0F : 1F - (float) Math.max(0, remaining) / (float) total;
                    }
                }
                details.add(new SyncWorkbenchDataPacket.RecipeDetail(
                        wb.id, r.recipeId, icon, r.baseDuration, r.requiredLevel, inputs, outputs,
                        r.maxParallel, runningCount, hasDone, claimTaskId, progress01, runningTaskId,
                        r.displayName()));
            }
        }
        PacketHandler.sendToPlayer(player, new SyncWorkbenchDataPacket(wbs, details));
    }

    // ------------------------------------------------------------------
    // 管理操作（由 /dn 指令调用，指令树已 requires(4)）
    // ------------------------------------------------------------------

    /** 保存配方（/dn recipe add 等 -> 落盘 -> 重载缓存）。 */
    public static void saveRecipe(String recipeJson) {
        Recipe recipe = Recipe.fromJsonString(recipeJson);
        if (recipe == null || !recipe.isValid()) {
            throw new IllegalArgumentException("invalid recipe");
        }
        RecipeCache.get().saveSingleRecipe(recipe);
    }

    /** 创建空配方骨架（无 input/output，后续用 addinput/addoutput 补充）。 */
    public static void createRecipe(String workbenchId, String recipeId) {
        WorkbenchRegistry.Workbench wb = WorkbenchRegistry.get().getById(workbenchId.toLowerCase(java.util.Locale.ROOT));
        if (wb == null) {
            throw new IllegalArgumentException("workbench not found");
        }
        Recipe r = new Recipe();
        r.recipeId = recipeId;
        r.type = wb.recipesDir;
        RecipeCache.get().saveSingleRecipe(r);
    }

    /** 主手物品添加为配方原料（含 NBT，精确匹配）。 */
    public static boolean addRecipeInput(ServerPlayer player, String recipeId) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null) return false;
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) return false;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(hand.getItem());
        if (key == null) return false;
        Recipe.Ingredient ing = new Recipe.Ingredient();
        ing.item = key.toString();
        ing.count = hand.getCount();
        net.minecraft.nbt.CompoundTag tag = hand.getTag();
        if (tag != null) {
            ing.matchType = com.deltanexus.system.common.NbtMatcher.MatchType.EXACT;
            ing.nbt = tag.toString();
        } else {
            ing.matchType = com.deltanexus.system.common.NbtMatcher.MatchType.IGNORE;
            ing.nbt = "";
        }
        r.input.add(ing);
        RecipeCache.get().saveSingleRecipe(r);
        return true;
    }

    /** 主手物品添加为配方产物（含 NBT）。 */
    public static boolean addRecipeOutput(ServerPlayer player, String recipeId) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null) return false;
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) return false;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(hand.getItem());
        if (key == null) return false;
        Recipe.Output out = new Recipe.Output();
        out.item = key.toString();
        out.count = hand.getCount();
        net.minecraft.nbt.CompoundTag tag = hand.getTag();
        out.nbt = tag != null ? tag.toString() : "";
        r.output.add(out);
        RecipeCache.get().saveSingleRecipe(r);
        return true;
    }

    /** 删除配方原料（按索引）。 */
    public static boolean removeRecipeInput(String recipeId, int index) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null || index < 0 || index >= r.input.size()) return false;
        r.input.remove(index);
        RecipeCache.get().saveSingleRecipe(r);
        return true;
    }

    /** 删除配方产物（按索引）。 */
    public static boolean removeRecipeOutput(String recipeId, int index) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null || index < 0 || index >= r.output.size()) return false;
        r.output.remove(index);
        RecipeCache.get().saveSingleRecipe(r);
        return true;
    }

    /** 设置配方基础耗时（秒）；仅影响新任务，旧任务快照不变。 */
    public static boolean setRecipeTime(String recipeId, long seconds) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null || seconds < 1) return false;
        r.baseDuration = seconds;
        RecipeCache.get().saveSingleRecipe(r);
        return true;
    }

    /** 设置配方所需仓库等级。 */
    public static boolean setRecipeLevel(String recipeId, int level) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null || level < 0) return false;
        r.requiredLevel = level;
        RecipeCache.get().saveSingleRecipe(r);
        return true;
    }

    /** 删除配方（/dn recipe remove）。 */
    public static boolean removeRecipe(String recipeId) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null) {
            return false;
        }
        java.nio.file.Path file = RecipeCache.get().recipesDir().resolve(r.type).resolve(recipeId + ".json");
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (Exception e) {
            com.deltanexus.system.DeltaNexus.LOGGER.warn("[DN] 删除配方文件失败: {}", e.getMessage());
        }
        RecipeCache.get().reload();
        return true;
    }

    /** 设置配方同时制作上限（1 ~ 100，/dn recipe parallel）。 */
    public static boolean setRecipeParallel(String recipeId, int maxParallel) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null || maxParallel < 1 || maxParallel > 100) {
            return false;
        }
        r.maxParallel = maxParallel;
        RecipeCache.get().saveSingleRecipe(r);
        return true;
    }

    /** 修改配方原料数量（按索引，1.0.4 /dn recipe setinput）。 */
    public static boolean setRecipeInputCount(String recipeId, int index, int count) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null || index < 0 || index >= r.input.size() || count < 1) {
            return false;
        }
        r.input.get(index).count = count;
        RecipeCache.get().saveSingleRecipe(r);
        return true;
    }

    /** 修改配方产物数量（按索引，1.0.4 /dn recipe setoutput）。 */
    public static boolean setRecipeOutputCount(String recipeId, int index, int count) {
        Recipe r = RecipeCache.get().get(recipeId);
        if (r == null || index < 0 || index >= r.output.size() || count < 1) {
            return false;
        }
        r.output.get(index).count = count;
        RecipeCache.get().saveSingleRecipe(r);
        return true;
    }

    /** 修改升级所需材料数量（按索引，1.0.4 /dn tree setitem）。 */
    public static boolean setTreeItemCount(int level, int index, int count) {
        return UpgradeConfig.get().updateRequiredItem(level, index, count);
    }

    /** 保存升级树（/dn tree set/remove）。 */
    public static void saveUpgradeTree(String treeJson) {
        UpgradeConfig.get().save(treeJson);
    }

    /** 设置升级等级费用（不存在则创建）。 */
    public static void setTreeCost(int level, int cost) {
        UpgradeConfig.UpgradeLevel u = UpgradeConfig.get().getOrCreate(level);
        u.costMoney = cost;
        UpgradeConfig.get().saveNow();
    }

    /** 设置升级解锁槽位数（不存在则创建）。 */
    public static void setTreeSlots(int level, int slots) {
        UpgradeConfig.UpgradeLevel u = UpgradeConfig.get().getOrCreate(level);
        u.unlockSlots = slots;
        UpgradeConfig.get().saveNow();
    }

    /**
     * 升级树容量检测（自动扩容行数）：若树中开放的最大解锁槽位超过当前最大容量
     * （行数 x 9），自动提升仓库行数并落盘。
     *
     * @return 是否发生了扩容
     */
    public static boolean autoExpandRows() {
        int before = ModConfig.warehouseRows();
        int after = ModConfig.ensureRowsForSlots(UpgradeConfig.get().maxUnlockSlots());
        return after > before;
    }

    /** 主手物品添加为升级所需材料（含 NBT，精确匹配）。 */
    public static boolean addTreeItem(ServerPlayer player, int level) {
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) return false;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(hand.getItem());
        if (key == null) return false;
        net.minecraft.nbt.CompoundTag tag = hand.getTag();
        String nbt = tag != null ? tag.toString() : "";
        com.deltanexus.system.common.NbtMatcher.MatchType mt = tag != null
                ? com.deltanexus.system.common.NbtMatcher.MatchType.EXACT
                : com.deltanexus.system.common.NbtMatcher.MatchType.IGNORE;
        UpgradeConfig.get().addRequiredItem(level, key.toString(), hand.getCount(), nbt, mt);
        return true;
    }

    /** 删除升级所需材料（按索引）。 */
    public static boolean removeTreeItem(int level, int index) {
        return UpgradeConfig.get().removeRequiredItem(level, index);
    }

    // ------------------------------------------------------------------
    // 安全箱升级树管理（1.1.0，/dn safe 与 Web 调用）
    // ------------------------------------------------------------------

    /** 设置安全箱升级费用（不存在则创建）。 */
    public static void setSafeTreeCost(int level, int cost) {
        UpgradeConfig.UpgradeLevel u = UpgradeConfig.get().safeGetOrCreate(level);
        u.costMoney = cost;
        UpgradeConfig.get().saveNow();
    }

    /** 设置安全箱升级解锁行 x 列（各 1 ~ 3，2.0.1 行列制；不存在则创建）。 */
    public static void setSafeTreeDims(int level, int rows, int cols) {
        UpgradeConfig.UpgradeLevel u = UpgradeConfig.get().safeGetOrCreate(level);
        u.unlockRows = Math.max(1, Math.min(3, rows));
        u.unlockCols = Math.max(1, Math.min(3, cols));
        u.unlockSlots = u.unlockRows * u.unlockCols;
        UpgradeConfig.get().saveNow();
    }

    /** 主手物品添加为安全箱升级所需材料（含 NBT，精确匹配）。 */
    public static boolean addSafeTreeItem(ServerPlayer player, int level) {
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) return false;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(hand.getItem());
        if (key == null) return false;
        net.minecraft.nbt.CompoundTag tag = hand.getTag();
        String nbt = tag != null ? tag.toString() : "";
        com.deltanexus.system.common.NbtMatcher.MatchType mt = tag != null
                ? com.deltanexus.system.common.NbtMatcher.MatchType.EXACT
                : com.deltanexus.system.common.NbtMatcher.MatchType.IGNORE;
        UpgradeConfig.get().safeAddRequiredItem(level, key.toString(), hand.getCount(), nbt, mt);
        return true;
    }

    /** 删除安全箱升级所需材料（按索引）。 */
    public static boolean removeSafeTreeItem(int level, int index) {
        return UpgradeConfig.get().safeRemoveRequiredItem(level, index);
    }

    /** 修改安全箱升级所需材料数量（按索引）。 */
    public static boolean setSafeTreeItemCount(int level, int index, int count) {
        return UpgradeConfig.get().safeUpdateRequiredItem(level, index, count);
    }

    /** 删除安全箱升级等级。 */
    public static boolean removeSafeLevel(int level) {
        return UpgradeConfig.get().safeRemove(level);
    }

    /** 设置玩家安全箱等级（0 ~ 安全箱升级树最大等级；同步通知与刷新）。 */
    public static boolean setPlayerSafeLevel(ServerPlayer player, int level) {
        IPlayerData data = data(player);
        if (data == null) {
            return false;
        }
        data.setSafeBoxLevel(level);
        player.displayClientMessage(Component.translatable("msg.dn.safe.data.notify.level", level), true);
        sendSyncWarehouse(player);
        syncSafeBox(player);
        return true;
    }
}
