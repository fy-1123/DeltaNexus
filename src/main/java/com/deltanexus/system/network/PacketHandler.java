package com.deltanexus.system.network;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.network.packet.C2SCancelTaskPacket;
import com.deltanexus.system.network.packet.C2SClaimTaskPacket;
import com.deltanexus.system.network.packet.C2SOpenSpecialOpsPacket;
import com.deltanexus.system.network.packet.C2SOpenWarehousePacket;
import com.deltanexus.system.network.packet.C2SPickupGridStackPacket;
import com.deltanexus.system.network.packet.C2SRefreshTasksPacket;
import com.deltanexus.system.network.packet.C2SRequestWorkbenchDataPacket;
import com.deltanexus.system.network.packet.C2SRequestSafeBoxPacket;
import com.deltanexus.system.network.packet.C2SSafeBoxClickPacket;
import com.deltanexus.system.network.packet.C2SStartTaskPacket;
import com.deltanexus.system.network.packet.C2STradeBuyPacket;
import com.deltanexus.system.network.packet.C2STradeOpenPacket;
import com.deltanexus.system.network.packet.C2STradeSellPacket;
import com.deltanexus.system.network.packet.C2SUpgradeSafeBoxPacket;
import com.deltanexus.system.network.packet.C2SUpgradeWarehousePacket;
import com.deltanexus.system.network.packet.C2SWarehouseScrollPacket;
import com.deltanexus.system.network.packet.GiveItemPacket;
import com.deltanexus.system.network.packet.OpenScreenPacket;
import com.deltanexus.system.network.packet.SyncGridSizesPacket;
import com.deltanexus.system.network.packet.SyncManufacturePacket;
import com.deltanexus.system.network.packet.SyncSafeBoxPacket;
import com.deltanexus.system.network.packet.SyncServerUiPacket;
import com.deltanexus.system.network.packet.SyncTradeCatalogPacket;
import com.deltanexus.system.network.packet.SyncWarehousePacket;
import com.deltanexus.system.network.packet.SyncWorkbenchDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 网络通道（通道协议版本 "dn1"）。
 *
 * <p>协议规定：所有同步包仅发送 int taskId / int remainingSeconds 等轻量字段，
 * 禁止发送完整 ItemStack 序列化数据（除领取瞬间的 {@link GiveItemPacket} 与配方图标外）。</p>
 */
public final class PacketHandler {

    /** 2.0.8：协议升级（移除 C2SOpenSafeBoxPacket，旧客户端不兼容）。
     *  2.2：SyncServerUiPacket 新增功能开关字段（featuresEnabled），旧客户端不兼容。
     *  0.2.0Beta：交易行（SyncTradeCatalogPacket 增补 match_mode/match_keys；新增 C2STradeSellPacket），
     *       协议升至 dn2，旧客户端不兼容。 */
    public static final String PROTOCOL = "dn2";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(DeltaNexus.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private static int nextId = 0;

    private PacketHandler() {
    }

    public static void register() {
        // 客户端 -> 服务端
        register(C2SOpenWarehousePacket.class,
                C2SOpenWarehousePacket::encode, C2SOpenWarehousePacket::decode, C2SOpenWarehousePacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        // 特勤处（2.0.2）：打开仓库/安全箱升级独立界面
        register(C2SOpenSpecialOpsPacket.class,
                C2SOpenSpecialOpsPacket::encode, C2SOpenSpecialOpsPacket::decode, C2SOpenSpecialOpsPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2SStartTaskPacket.class,
                C2SStartTaskPacket::encode, C2SStartTaskPacket::decode, C2SStartTaskPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2SClaimTaskPacket.class,
                C2SClaimTaskPacket::encode, C2SClaimTaskPacket::decode, C2SClaimTaskPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2SCancelTaskPacket.class,
                C2SCancelTaskPacket::encode, C2SCancelTaskPacket::decode, C2SCancelTaskPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2SRefreshTasksPacket.class,
                C2SRefreshTasksPacket::encode, C2SRefreshTasksPacket::decode, C2SRefreshTasksPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2SUpgradeWarehousePacket.class,
                C2SUpgradeWarehousePacket::encode, C2SUpgradeWarehousePacket::decode, C2SUpgradeWarehousePacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2SWarehouseScrollPacket.class,
                C2SWarehouseScrollPacket::encode, C2SWarehouseScrollPacket::decode, C2SWarehouseScrollPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2SRequestWorkbenchDataPacket.class,
                C2SRequestWorkbenchDataPacket::encode, C2SRequestWorkbenchDataPacket::decode, C2SRequestWorkbenchDataPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2SUpgradeSafeBoxPacket.class,
                C2SUpgradeSafeBoxPacket::encode, C2SUpgradeSafeBoxPacket::decode, C2SUpgradeSafeBoxPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2SRequestSafeBoxPacket.class,
                C2SRequestSafeBoxPacket::encode, C2SRequestSafeBoxPacket::decode, C2SRequestSafeBoxPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2SSafeBoxClickPacket.class,
                C2SSafeBoxClickPacket::encode, C2SSafeBoxClickPacket::decode, C2SSafeBoxClickPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        // 格式背包（2.0.0）：旋转光标物品（R 键）
        register(com.deltanexus.system.grid.network.RotationPacket.class,
                com.deltanexus.system.grid.network.RotationPacket::encode,
                com.deltanexus.system.grid.network.RotationPacket::decode,
                com.deltanexus.system.grid.network.RotationPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        // 格式背包（2.0.3）：点击跨格物品非左上角格 -> 捡起整件到光标
        register(C2SPickupGridStackPacket.class,
                C2SPickupGridStackPacket::encode, C2SPickupGridStackPacket::decode, C2SPickupGridStackPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        // 交易行（0.2.0Beta）：打开 / 买入
        register(C2STradeOpenPacket.class,
                C2STradeOpenPacket::encode, C2STradeOpenPacket::decode, C2STradeOpenPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        register(C2STradeBuyPacket.class,
                C2STradeBuyPacket::encode, C2STradeBuyPacket::decode, C2STradeBuyPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        // 交易行（0.2.0Beta）：仓库界面卖出（多槽位）
        register(C2STradeSellPacket.class,
                C2STradeSellPacket::encode, C2STradeSellPacket::decode, C2STradeSellPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);

        // 服务端 -> 客户端
        register(OpenScreenPacket.class,
                OpenScreenPacket::encode, OpenScreenPacket::decode, OpenScreenPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        register(SyncWarehousePacket.class,
                SyncWarehousePacket::encode, SyncWarehousePacket::decode, SyncWarehousePacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        register(SyncManufacturePacket.class,
                SyncManufacturePacket::encode, SyncManufacturePacket::decode, SyncManufacturePacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        register(SyncWorkbenchDataPacket.class,
                SyncWorkbenchDataPacket::encode, SyncWorkbenchDataPacket::decode, SyncWorkbenchDataPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        register(GiveItemPacket.class,
                GiveItemPacket::encode, GiveItemPacket::decode, GiveItemPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        register(SyncSafeBoxPacket.class,
                SyncSafeBoxPacket::encode, SyncSafeBoxPacket::decode, SyncSafeBoxPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        // 格式背包配置同步（2.0.2：物品尺寸 + 快捷栏规则，登录与修改后推送）
        register(SyncGridSizesPacket.class,
                SyncGridSizesPacket::encode, SyncGridSizesPacket::decode, SyncGridSizesPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        // 服务端 GUI 白名单同步（2.0.9：登录与热重载时推送，与客户端白名单取并集）
        register(SyncServerUiPacket.class,
                SyncServerUiPacket::encode, SyncServerUiPacket::decode, SyncServerUiPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        // 交易行目录（0.2.0Beta：打开前/成交后/补货后下发）
        register(SyncTradeCatalogPacket.class,
                SyncTradeCatalogPacket::encode, SyncTradeCatalogPacket::decode, SyncTradeCatalogPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
    }

    private static <MSG> void register(Class<MSG> clazz,
                                       BiConsumer<MSG, net.minecraft.network.FriendlyByteBuf> encoder,
                                       Function<net.minecraft.network.FriendlyByteBuf, MSG> decoder,
                                       BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler,
                                       NetworkDirection direction) {
        CHANNEL.registerMessage(nextId++, clazz, encoder, decoder, handler, Optional.of(direction));
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendToPlayer(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
