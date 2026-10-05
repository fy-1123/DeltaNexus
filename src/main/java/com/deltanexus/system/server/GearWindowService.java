package com.deltanexus.system.server;

import com.deltanexus.system.grid.GearConfig;
import com.deltanexus.system.grid.GearData;
import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.grid.GearNest;
import com.deltanexus.system.grid.GridEntry;
import com.deltanexus.system.grid.GridNbt;
import com.deltanexus.system.grid.GridStore;
import com.deltanexus.system.grid.StoreContainer;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SOpenGearWindowPacket;
import com.deltanexus.system.network.packet.SyncGearWindowPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 装备窗口服务（0.5.0Beta 嵌套补丁）——「窗口式」查看 / 操作任意一层装备内容。
 *
 * <p>窗口位置由三元组 {@code (rootType, rootRef, path)} 唯一定位：{@code path} 是从根装备
 * 逐层下沉的格号列表（空 = 根装备本身）。服务端<b>无状态</b>：每次点击都携带完整路径，
 * 现场解析整条链路，不做任何会话级记录。</p>
 *
 * <p>写回是逐层向上的：最深一层的 {@link GridStore} 先写回它所属的物品 NBT，
 * 再逐层向上写回，最后落到根位置（已装备 = 玩家数据；菜单槽 = 该槽位），
 * 因此深层改动不会在中间层被覆盖。</p>
 */
public final class GearWindowService {

    private GearWindowService() {
    }

    /** 解析出的链路：{@code levels[i]} 为第 i 层装备物品，{@code stores[i]} 为其内容网格。 */
    private record Chain(ItemStack[] levels, GridStore[] stores) {
    }

    /** 请求打开（或刷新）一个装备窗口；解析失败时通知客户端收起该窗口。 */
    public static void open(ServerPlayer player, int rootType, int rootRef, int[] path) {
        if (!allowed(player)) {
            return;
        }
        Chain chain = resolve(player, rootType, rootRef, path);
        if (chain == null) {
            close(player, rootType, rootRef, path);
            return;
        }
        sendWindow(player, rootType, rootRef, path, chain);
    }

    /** 窗口内网格点击：服务端权威裁决 + 逐层写回 + 刷新窗口与内嵌网格。 */
    public static void click(ServerPlayer player, int rootType, int rootRef, int[] path,
                             int cell, int button, boolean shift) {
        if (!allowed(player)) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return;
        }
        Chain chain = resolve(player, rootType, rootRef, path);
        if (chain == null) {
            close(player, rootType, rootRef, path);
            return;
        }
        int depth = (path == null ? 0 : path.length) + 1;
        GridStore store = chain.stores()[depth - 1];
        if (cell < 0 || cell >= store.size()) {
            return;
        }
        int anchor = store.anchorAt(cell);
        int targetCell = anchor >= 0 ? anchor : cell;
        StoreContainer box = new StoreContainer(store);
        // 该网格自身所属装备的嵌套深度 = 路径长度 + 1；装备嵌套规则据此判定能否放入
        GearGridService.applyGridClick(player, menu, box, store, targetCell, anchor, button, shift, depth);
        writeBack(player, rootType, rootRef, chain);
        sendWindow(player, rootType, rootRef, path, chain);
        if (rootType == C2SOpenGearWindowPacket.ROOT_EQUIPPED) {
            GearKind kind = kindOf(rootRef);
            if (kind != null) {
                GearService.sendSync(player, kind);
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 功能硬开关 + 装备容器权限（与 {@link GearService} 同口径）。 */
    private static boolean allowed(ServerPlayer player) {
        return player != null && PermissionManager.canUseFeatures(player)
                && PermissionManager.canOpenGear(player);
    }

    /** 解析路径链路；任一层缺失 / 越界 / 非装备都返回 {@code null}。 */
    private static Chain resolve(ServerPlayer player, int rootType, int rootRef, int[] path) {
        ItemStack root = rootStack(player, rootType, rootRef);
        if (root.isEmpty() || !GearConfig.isGear(root)) {
            return null;
        }
        int n = path == null ? 0 : path.length;
        // 最深可查看/操作的装备深度为 MAX_DEPTH，其路径长度 = MAX_DEPTH - 1
        if (n >= GearNest.MAX_DEPTH) {
            return null;
        }
        ItemStack[] levels = new ItemStack[n + 1];
        GridStore[] stores = new GridStore[n + 1];
        levels[0] = root;
        for (int i = 0; i < n; i++) {
            GridStore store = GearData.read(levels[i]);
            if (store == null) {
                return null;
            }
            stores[i] = store;
            int cell = path[i];
            if (cell < 0 || cell >= store.size()) {
                return null;
            }
            int anchor = store.anchorAt(cell);
            GridEntry entry = anchor < 0 ? null : store.entryAt(anchor);
            if (entry == null || entry.isEmpty() || !GearConfig.isGear(entry.stack())) {
                return null;
            }
            // 深层物品是所属网格里的活引用：对它写 NBT 后再逐层写回即可持久化
            levels[i + 1] = entry.stack();
        }
        stores[n] = GearData.read(levels[n]);
        return stores[n] == null ? null : new Chain(levels, stores);
    }

    /** 根装备物品（已装备种类 / 当前菜单槽位）。 */
    private static ItemStack rootStack(ServerPlayer player, int rootType, int rootRef) {
        if (rootType == C2SOpenGearWindowPacket.ROOT_MENU_SLOT) {
            Slot slot = slotAt(player, rootRef);
            return slot == null ? ItemStack.EMPTY : slot.getItem();
        }
        GearKind kind = kindOf(rootRef);
        return kind == null ? ItemStack.EMPTY : GearService.equipped(player, kind);
    }

    /** 逐层向上写回：先最深，后逐层向上，最后落根位置。 */
    private static void writeBack(ServerPlayer player, int rootType, int rootRef, Chain chain) {
        ItemStack[] levels = chain.levels();
        GridStore[] stores = chain.stores();
        for (int i = levels.length - 1; i >= 0; i--) {
            GearData.write(levels[i], stores[i]);
        }
        if (rootType == C2SOpenGearWindowPacket.ROOT_MENU_SLOT) {
            Slot slot = slotAt(player, rootRef);
            if (slot != null) {
                slot.set(levels[0]);
                slot.setChanged();
            }
            return;
        }
        GearKind kind = kindOf(rootRef);
        if (kind != null) {
            GearService.setEquipped(player, kind, levels[0]);
        }
    }

    /** 下发窗口内容（网格几何 + 该层装备本体）。 */
    private static void sendWindow(ServerPlayer player, int rootType, int rootRef, int[] path, Chain chain) {
        int n = path == null ? 0 : path.length;
        GridStore store = chain.stores()[n];
        PacketHandler.sendToPlayer(player, new SyncGearWindowPacket(rootType, rootRef, path, false,
                store.width(), store.rows(), GridNbt.write(store), chain.levels()[n].copy()));
    }

    /** 通知客户端收起该窗口（定位已失效）。 */
    private static void close(ServerPlayer player, int rootType, int rootRef, int[] path) {
        if (player == null) {
            return;
        }
        PacketHandler.sendToPlayer(player, SyncGearWindowPacket.close(rootType, rootRef, path));
    }

    private static GearKind kindOf(int ref) {
        GearKind[] values = GearKind.values();
        return ref >= 0 && ref < values.length ? values[ref] : null;
    }

    private static Slot slotAt(ServerPlayer player, int index) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || index < 0 || index >= menu.slots.size()) {
            return null;
        }
        return menu.slots.get(index);
    }
}
