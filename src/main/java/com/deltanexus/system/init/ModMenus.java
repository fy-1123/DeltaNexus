package com.deltanexus.system.init;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.menu.GearMenu;
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

    /** 装备容器（0.5.0Beta：背包/胸挂的格子容器）。 */
    public static final RegistryObject<MenuType<GearMenu>> GEAR =
            MENUS.register("gear",
                    () -> new MenuType<>((id, inv) -> new GearMenu(id, inv), FeatureFlags.VANILLA_SET));

    private ModMenus() {
    }
}
