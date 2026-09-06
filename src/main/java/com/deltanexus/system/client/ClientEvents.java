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
 * 客户端 FORGE 总线事件：B 键打开仓库，G 键打开工作台总览，
 * V 键打开特勤处，R 键旋转光标物品（2.0.4 可绑定按键）。
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
                // 2.0.4 修复：特勤处必须本地打开界面（此前仅发包导致无法打开）
                SpecialOpsScreen.open();
            }
        }
    }
}
