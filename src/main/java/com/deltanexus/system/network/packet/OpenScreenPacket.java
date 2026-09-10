package com.deltanexus.system.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：指示客户端打开制造相关 GUI。
 * <ul>
 *   <li>{@link #SCREEN_MANUFACTURE} —— 单个工作台制造界面（旧路径，保留兼容）；</li>
 *   <li>{@link #SCREEN_WORKBENCH}   —— 工作台总览（G 键同款全屏 UI）；</li>
 *   <li>{@link #SCREEN_SPECIAL}     —— 特勤处（2.0.4：/dn open special 指令路径）。</li>
 * </ul>
 *
 * <p>2.0.7：客户端处理移入 {@link ClientHandler}（@OnlyIn(Dist.CLIENT)），
 * 本类仅以方法引用委托 —— 专用服务器加载本类时验证器不会解析客户端类型，
 * 修复 "Attempted to load class Screen for invalid dist DEDICATED_SERVER" 启动崩溃。</p>
 */
public class OpenScreenPacket {

    public static final int SCREEN_MANUFACTURE = 0;
    public static final int SCREEN_WORKBENCH = 1;
    /** 特勤处（2.0.4）。 */
    public static final int SCREEN_SPECIAL = 2;
    /** 交易行（0.2.0Beta；目录经 SyncTradeCatalogPacket 先行下发）。 */
    public static final int SCREEN_TRADE = 3;

    public final int screenType;
    public final String workbenchId;
    public final String workbenchDisplay;
    public final boolean isAdmin;

    public OpenScreenPacket(int screenType, String workbenchId, String workbenchDisplay, boolean isAdmin) {
        this.screenType = screenType;
        this.workbenchId = workbenchId;
        this.workbenchDisplay = workbenchDisplay;
        this.isAdmin = isAdmin;
    }

    public static void encode(OpenScreenPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.screenType);
        buf.writeUtf(msg.workbenchId == null ? "" : msg.workbenchId);
        buf.writeUtf(msg.workbenchDisplay == null ? "" : msg.workbenchDisplay);
        buf.writeBoolean(msg.isAdmin);
    }

    public static OpenScreenPacket decode(FriendlyByteBuf buf) {
        return new OpenScreenPacket(buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }

    public static void handle(OpenScreenPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.handle(msg)));
        context.setPacketHandled(true);
    }

    /** 客户端处理（专用服务器不加载）。 */
    @OnlyIn(Dist.CLIENT)
    private static class ClientHandler {
        static void handle(OpenScreenPacket msg) {
            if (msg.screenType == SCREEN_MANUFACTURE) {
                com.deltanexus.system.client.gui.ManufactureScreen.open(msg.workbenchId, msg.workbenchDisplay, msg.isAdmin);
            } else if (msg.screenType == SCREEN_WORKBENCH) {
                com.deltanexus.system.client.gui.WorkbenchScreen.open();
            } else if (msg.screenType == SCREEN_SPECIAL) {
                // 2.0.4：特勤处（/dn open special 指令路径）；仅本地打开（数据同步包已先送达），
                // 不重新请求，避免与 V 键的请求形成循环
                net.minecraft.client.Minecraft.getInstance().setScreen(
                        new com.deltanexus.system.client.gui.SpecialOpsScreen());
            } else if (msg.screenType == SCREEN_TRADE) {
                // 交易行（0.2.0Beta）：目录已由 SyncTradeCatalogPacket 先行送达
                com.deltanexus.system.client.gui.TradeScreen.open();
            }
        }
    }
}
