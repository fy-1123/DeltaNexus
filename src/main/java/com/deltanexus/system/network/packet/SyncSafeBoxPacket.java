package com.deltanexus.system.network.packet;

import com.deltanexus.system.client.gui.SafeBoxOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：安全箱状态同步（1.1.0Alpha，背包界面覆盖层数据源）。
 *
 * <p>包含：权限标记（allowed=false 时客户端隐藏覆盖层）、等级/解锁格数/尺寸、
 * 全部 9 格物品（仅交互/打开时发送，非每 tick 推送）与当前光标栈
 * （点击交互后服务端回发，客户端同步光标显示）。</p>
 */
public class SyncSafeBoxPacket {

    /** 是否有权使用安全箱（false 时客户端不渲染覆盖层）。 */
    public final boolean allowed;
    public final int safeLevel;
    public final int safeMaxLevel;
    public final int unlockedSlots;
    public final int width;
    public final int height;
    /** 9 格物品（未解锁槽位可能为空）。 */
    public final ItemStack[] items;
    /** 交互后的服务端光标栈。 */
    public final ItemStack carried;
    /**
     * {@link #carried} 所属的菜单容器 id（-1 = 不适用，客户端不要套用）。
     *
     * <p>0.5.0Beta：安全箱覆盖层可以出现在背包界面以外的界面（容器界面等），而此前
     * 服务端只在背包菜单下打包光标物品、其余情况打包空栈——客户端却无条件套用，
     * 于是容器界面里正在被光标拿着的物品会被"抹掉"，看起来就是物品丢失。
     * 现在带上容器 id，客户端只在 id 对得上时套用。</p>
     */
    public final int carriedMenuId;

    public SyncSafeBoxPacket(boolean allowed, int safeLevel, int safeMaxLevel,
                             int unlockedSlots, int width, int height,
                             ItemStack[] items, ItemStack carried) {
        this(allowed, safeLevel, safeMaxLevel, unlockedSlots, width, height, items, carried, -1);
    }

    public SyncSafeBoxPacket(boolean allowed, int safeLevel, int safeMaxLevel,
                             int unlockedSlots, int width, int height,
                             ItemStack[] items, ItemStack carried, int carriedMenuId) {
        this.allowed = allowed;
        this.safeLevel = safeLevel;
        this.safeMaxLevel = safeMaxLevel;
        this.unlockedSlots = unlockedSlots;
        this.width = width;
        this.height = height;
        this.items = items;
        this.carried = carried;
        this.carriedMenuId = carriedMenuId;
    }

    public static void encode(SyncSafeBoxPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.allowed);
        buf.writeVarInt(msg.safeLevel);
        buf.writeVarInt(msg.safeMaxLevel);
        buf.writeVarInt(msg.unlockedSlots);
        buf.writeVarInt(msg.width);
        buf.writeVarInt(msg.height);
        for (int i = 0; i < 9; i++) {
            buf.writeItem(msg.items == null || i >= msg.items.length ? ItemStack.EMPTY : msg.items[i]);
        }
        buf.writeItem(msg.carried == null ? ItemStack.EMPTY : msg.carried);
        buf.writeVarInt(msg.carriedMenuId);
    }

    public static SyncSafeBoxPacket decode(FriendlyByteBuf buf) {
        boolean allowed = buf.readBoolean();
        int safeLevel = buf.readVarInt();
        int safeMaxLevel = buf.readVarInt();
        int unlockedSlots = buf.readVarInt();
        int width = buf.readVarInt();
        int height = buf.readVarInt();
        ItemStack[] items = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            items[i] = buf.readItem();
        }
        ItemStack carried = buf.readItem();
        int carriedMenuId = buf.readVarInt();
        return new SyncSafeBoxPacket(allowed, safeLevel, safeMaxLevel,
                unlockedSlots, width, height, items, carried, carriedMenuId);
    }

    public static void handle(SyncSafeBoxPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.handle(msg)));
        context.setPacketHandled(true);
    }

    /** 客户端处理（2.0.7Alpha 拆分：专用服务器不加载本类）。 */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static class ClientHandler {
        static void handle(SyncSafeBoxPacket msg) {
            SafeBoxOverlay.receive(msg);
        }
    }
}
