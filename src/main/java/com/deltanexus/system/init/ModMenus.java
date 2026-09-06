package com.deltanexus.system.init;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.menu.WarehouseMenu;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 菜单类型注册（DeferredRegister）。
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, DeltaNexus.MODID);

    public static final RegistryObject<MenuType<WarehouseMenu>> WAREHOUSE =
            MENUS.register("warehouse",
                    () -> new MenuType<>((id, inv) -> new WarehouseMenu(id, inv), FeatureFlags.VANILLA_SET));

    private ModMenus() {
    }
}
