package com.deltanexus.system.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：服务端 GUI 白名单同步（2.0.9）+ 玩家功能开关（2.1）。
 *
 * <p>服务端白名单定义在 config/deltanexus/ModConfig.toml（ui_whitelist，服务端权威）；
 * 登录时同步到客户端，与客户端白名单（deltanexus/client-ui.toml）取并集——
 * 界面命中任意一份白名单即使用原版 GUI。配置热重载后广播刷新。</p>
 *
 * <p>2.1：新增 {@code featuresEnabled}——该玩家是否被禁用全部 mod 功能
 * （config/deltanexus/permissions.json players.&lt;name&gt;.features=false，/dn feature 管理）。
 * 禁用后客户端不替换任何界面（背包/容器恢复原版）、不渲染网格与安全箱。</p>
 */
public class SyncServerUiPacket {

    public final List<String> whitelist;
    /** 2.1：该玩家是否可用 mod 功能（false = 禁用全部，UI 恢复原版）。 */
    public final boolean featuresEnabled;

    public SyncServerUiPacket(List<String> whitelist) {
        this(whitelist, true);
    }

    public SyncServerUiPacket(List<String> whitelist, boolean featuresEnabled) {
        this.whitelist = whitelist != null ? List.copyOf(whitelist) : List.of();
        this.featuresEnabled = featuresEnabled;
    }

    public static void encode(SyncServerUiPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.featuresEnabled);
        buf.writeVarInt(msg.whitelist.size());
        for (String entry : msg.whitelist) {
            buf.writeUtf(entry, 256);
        }
    }

    public static SyncServerUiPacket decode(FriendlyByteBuf buf) {
        boolean featuresEnabled = buf.readBoolean();
        int size = buf.readVarInt();
        List<String> list = new ArrayList<>(Math.min(size, 64));
        for (int i = 0; i < size; i++) {
            list.add(buf.readUtf(256));
        }
        return new SyncServerUiPacket(list, featuresEnabled);
    }

    public static void handle(SyncServerUiPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        // 仅客户端消费（专用服务器不会收到 PLAY_TO_CLIENT 包的 handle 调用）
        context.enqueueWork(() ->
                com.deltanexus.system.config.ClientUiConfig.applyServerUi(msg.whitelist, msg.featuresEnabled));
        context.setPacketHandled(true);
    }
}
