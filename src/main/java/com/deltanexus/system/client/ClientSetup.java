package com.deltanexus.system.client;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.client.gui.WarehouseScreen;
import com.deltanexus.system.init.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端 MOD 总线事件（屏幕绑定、按键注册、冲突检测）。
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        KeyBindings.checkConflicts();
        MenuScreens.register(ModMenus.WAREHOUSE.get(), WarehouseScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        KeyBindings.register(event);
    }
}
