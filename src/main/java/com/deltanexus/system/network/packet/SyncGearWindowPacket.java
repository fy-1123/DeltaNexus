package com.deltanexus.system.network.packet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 -&gt; 客户端：装备窗口内容同步（0.5.0Beta 嵌套补丁）。
 *
 * <p>与 {@link SyncGearPacket} 同构：载荷是内核锚点格式（{@code GridNbt}），客户端一次
 * {@code GridNbt.read} 即可还原同构的 {@code GridStore}，占位形状与服务端逐格一致。
 * 定位三元组 {@code (rootType, rootRef, path)} 决定这是哪一个窗口。</p>
 *
 * <p>{@code close = true} 表示该定位已失效（装备被取走 / 卸下 / 越层），客户端应收起该窗口。</p>
 */
public class SyncGearWindowPacket {

    public final int rootType;
    public final int rootRef;
    public final int[] path;
    public final boolean close;
    public final int width;
    public final int height;
    /** 内核锚点格式（{@code GridNbt.write} 产物）。 */
    public final CompoundTag grid;
    /** 该窗口根装备的物品本体（标题 / 图标用）。 */
    public final ItemStack gear;

    public SyncGearWindowPacket(int rootType, int rootRef, int[] path, boolean close,
                                int width, int height, CompoundTag grid, ItemStack gear) {
        this.rootType = rootType;
        this.rootRef = rootRef;
        this.path = path == null ? new int[0] : path.clone();
        this.close = close;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.grid = grid == null ? new CompoundTag() : grid;
        this.gear = gear == null ? ItemStack.EMPTY : gear;
    }

    /** 构造一个「收起窗口」的通知。 */
    public static SyncGearWindowPacket close(int rootType, int rootRef, int[] path) {
        return new SyncGearWindowPacket(rootType, rootRef, path, true, 0, 0, null, null);
    }

    public static void encode(SyncGearWindowPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.rootType);
        buf.writeVarInt(msg.rootRef);
        buf.writeVarIntArray(msg.path);
        buf.writeBoolean(msg.close);
        buf.writeVarInt(msg.width);
        buf.writeVarInt(msg.height);
        buf.writeNbt(msg.grid);
        buf.writeItem(msg.gear);
    }

    public static SyncGearWindowPacket decode(FriendlyByteBuf buf) {
        int rootType = buf.readVarInt();
        int rootRef = buf.readVarInt();
        int[] path = buf.readVarIntArray();
        boolean close = buf.readBoolean();
        int width = buf.readVarInt();
        int height = buf.readVarInt();
        CompoundTag grid = buf.readNbt();
        ItemStack gear = buf.readItem();
        return new SyncGearWindowPacket(rootType, rootRef, path, close, width, height, grid, gear);
    }

    public static void handle(SyncGearWindowPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientHandler.handle(msg)));
        context.setPacketHandled(true);
    }

    /** 客户端处理（专用服务器不加载本类）。 */
    @OnlyIn(Dist.CLIENT)
    private static class ClientHandler {
        static void handle(SyncGearWindowPacket msg) {
            com.deltanexus.system.client.GearWindowState.receive(msg);
        }
    }
}
