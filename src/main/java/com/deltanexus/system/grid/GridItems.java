package com.deltanexus.system.grid;

import com.deltanexus.system.DeltaNexus;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 格子格式物品注册（2.0.0，集成自 expansionpack）。
 *
 * <p>{@code blocked_slot}：网格占位物（格式背包核心），用于填充跨格物品足迹内的
 * 非主格；服务端每 Tick 重算，客户端渲染为白色半透明墙。</p>
 */
public class GridItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, DeltaNexus.MODID);

    public static final RegistryObject<Item> BLOCKED_SLOT = ITEMS.register("blocked_slot",
            () -> new Item(new Item.Properties().stacksTo(64)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
