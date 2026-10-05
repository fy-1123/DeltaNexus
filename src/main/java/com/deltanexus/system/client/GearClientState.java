package com.deltanexus.system.client;

import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.grid.GridNbt;
import com.deltanexus.system.grid.GridSize;
import com.deltanexus.system.grid.GridStore;
import com.deltanexus.system.grid.StoreContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * 客户端装备几何缓存（0.5.0Beta）——只保存服务端下发的那份真相。
 *
 * <p>每个装备种类一个影子容器（{@link StoreContainer}，不带落盘回调 = 只读），
 * 内容由 {@code SyncGearPacket} 整份替换：客户端因此与服务端<b>逐格一致</b>，
 * 占位形状、可放置判定都不需要客户端自己猜。</p>
 *
 * <p>菜单构造只读这里：服务端「先发几何、再开菜单」，同通道有序，构造时必定已就绪。</p>
 *
 * <p>另存<b>装备物品本体</b>（{@link #equipped}）：装备不在任何原版槽位里，
 * 界面要画胸挂/背包槽图标只能靠这份同步。装备、卸下、登录三个时点各下发一次。</p>
 */
public final class GearClientState {

    private static final Map<GearKind, StoreContainer> VIEWS = new EnumMap<>(GearKind.class);

    private static final Map<GearKind, ItemStack> EQUIPPED = new EnumMap<>(GearKind.class);

    private static GearKind lastKind = GearKind.BACKPACK;

    private GearClientState() {
    }

    /** 收到几何同步（整份替换）。 */
    public static void receive(GearKind kind, int width, int height, CompoundTag tag, ItemStack gear) {
        GridStore store = GridNbt.read(tag, width, height, null);
        if (store == null) {
            store = new GridStore(width, height);
        }
        lastKind = kind;
        StoreContainer view = VIEWS.get(kind);
        if (view == null || view.store().width() != width || view.store().rows() != height) {
            VIEWS.put(kind, new StoreContainer(store));
        } else {
            view.reset(store);
        }
        EQUIPPED.put(kind, gear == null ? ItemStack.EMPTY : gear);
    }

    /** 最近一次同步的种类（菜单构造用）。 */
    public static GearKind lastKind() {
        return lastKind;
    }

    /** 已装备的指定种类装备（未装备 = 空栈）。 */
    public static ItemStack equipped(GearKind kind) {
        ItemStack stack = EQUIPPED.get(kind);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /** 影子容器（不存在时按该种类默认尺寸建空容器）。 */
    public static StoreContainer container(GearKind kind) {
        StoreContainer view = VIEWS.get(kind);
        if (view != null) {
            return view;
        }
        GridSize size = kind.defaultSize();
        StoreContainer created = new StoreContainer(new GridStore(size.w(), size.h()));
        VIEWS.put(kind, created);
        return created;
    }
}