package com.deltanexus.system.grid;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.grid.enchantment.ExtraDamageEnchantment;
import com.deltanexus.system.grid.enchantment.RedstoneOverdriveEnchantment;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 附魔注册（2.0.0Alpha）。
 *
 * <ul>
 *   <li>{@code fixed_strike}（固定打击）：武器固定 +2 伤害；</li>
 *   <li>{@code redstone_overdrive}（充能核心）：工具/护甲红石充能、护盾与加速。</li>
 * </ul>
 */
public class GridEnchantments {
    public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, DeltaNexus.MODID);
    public static final RegistryObject<Enchantment> FIXED_STRIKE = ENCHANTMENTS.register("fixed_strike", ExtraDamageEnchantment::new);
    public static final RegistryObject<Enchantment> REDSTONE_OVERDRIVE = ENCHANTMENTS.register("redstone_overdrive", RedstoneOverdriveEnchantment::new);
    public static void register(IEventBus bus) { ENCHANTMENTS.register(bus); }
}
