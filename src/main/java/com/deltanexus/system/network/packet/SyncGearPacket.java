package com.deltanexus.system.network.packet;

import com.deltanexus.system.grid.GearKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：装备（背包/胸挂）内容几何同步（0.5.0Beta）。
 *
 * <p>为什么需要它：原版槽位同步只搬「每格一个物品」，而多格占用是<b>几何</b>信息
 * （哪个格是锚点、占几格、什么姿态）。几何只在服务端算得出来，因此每次内容变化
 * （打开菜单前、菜单内每次点击后）都由服务端整份下发，客户端据此重建同构的
 * {@code GridStore}，占位形状、可交互格与服务端逐格一致。</p>
 *
 * <p>载荷就是内核的存档形态（{@code GridNbt} 锚点格式），因此客户端只需一次
 * {@code GridNbt.read} 即可还原，不存在第二套几何协议。</p>
 *
 * <p>另外携带<b>装备物品本体</b>（{@code gear}）：装备存在玩家数据里而不是任何一个
 * 原版槽位中，客户端要画出「胸挂/背包槽里的图标」就必须拿到它。数量变化仍由原版槽位
 * 同步负责，这里只要一个用于渲染的副本。</p>
 */
public class SyncGearPacket {

    public final int kindOrdinal;
    public final int width;
    public final int height;
    /** 内核锚点格式（{@code GridNbt.write} 产物）。 */
    public final CompoundTag grid;
    /** 装备物品本体（未装备 = 空栈）。 */
    public final ItemStack gear;

    public SyncGearPacket(GearKind kind, int width, int height, CompoundTag grid, ItemStack gear) {
        this.kindOrdinal = kind == null ? 0 : kind.ordinal();
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.grid = grid == null ? new CompoundTag() : grid;
        this.gear = gear == null ? ItemStack.EMPTY : gear;
    }

    public static void encode(SyncGearPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.kindOrdinal);
        buf.writeVarInt(msg.width);
        buf.writeVarInt(msg.height);
        buf.writeNbt(msg.grid);
        buf.writeItem(msg.gear);
    }

    public static SyncGearPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        int width = buf.readVarInt();
        int height = buf.readVarInt();
        CompoundTag tag = buf.readNbt();
        ItemStack gear = buf.readItem();
        GearKind kind = ordinal >= 0 && ordinal < GearKind.values().length
                ? GearKind.values()[ordinal] : GearKind.BACKPACK;
        return new SyncGearPacket(kind, width, height, tag, gear);
    }

    public static void handle(SyncGearPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientHandler.handle(msg)));
        context.setPacketHandled(true);
    }

    /** 客户端处理（专用服务器不加载本类）。 */
    @OnlyIn(Dist.CLIENT)
    private static class ClientHandler {
        static void handle(SyncGearPacket msg) {
            GearKind kind = GearKind.values()[msg.kindOrdinal];
            com.deltanexus.system.client.GearClientState.receive(
                    kind, msg.width, msg.height, msg.grid, msg.gear);
            // 已装备几何刷新后校验窗口是否仍然有效（装备卸下 / 下层装备被取走则收起）
            com.deltanexus.system.client.GearWindowState.onGearSync(kind);
        }
    }
}