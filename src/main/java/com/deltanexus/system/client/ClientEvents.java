package com.deltanexus.system.client;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.client.gui.SpecialOpsScreen;
import com.deltanexus.system.client.gui.WorkbenchScreen;
import com.deltanexus.system.network.PacketHandler;
import com.deltanexus.system.network.packet.C2SOpenWarehousePacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端 FORGE 总线事件：R 键旋转光标物品。
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (KeyBindings.OPEN_WAREHOUSE.consumeClick()) {
                PacketHandler.sendToServer(new C2SOpenWarehousePacket());
            }
            if (KeyBindings.OPEN_WORKBENCH.consumeClick()) {
                WorkbenchScreen.open();
            }
            if (KeyBindings.OPEN_SPECIAL.consumeClick()) {
                // 2.0.4alpha 修复：特勤处必须本地打开界面（此前仅发包导致无法打开）
                SpecialOpsScreen.open();
            }
            if (KeyBindings.OPEN_TRADE.consumeClick()) {
                // 交易行（0.2.0Beta）：只发请求，服务端校验权限后先下发目录再发 OpenScreenPacket
                PacketHandler.sendToServer(new com.deltanexus.system.network.packet.C2STradeOpenPacket());
            }
        }
    }
}
