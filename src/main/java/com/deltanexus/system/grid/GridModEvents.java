package com.deltanexus.system.grid;

import com.deltanexus.system.DeltaNexus;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * 充能核心附魔玩法事件（2.0.0，集成自 expansionpack）：
 * 红石充能（主手工具+副手红石 / 护甲+主手红石）、能量护盾、挖掘加速、能量 tooltip。
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID)
public class GridModEvents {

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide) return;
        Player p = event.getEntity();
        ItemStack main = p.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack off = p.getItemInHand(InteractionHand.OFF_HAND);
        long time = p.level().getGameTime();

        // 1. 工具充能 (主手工具，副手红石)
        int lvl = main.getEnchantmentLevel(GridEnchantments.REDSTONE_OVERDRIVE.get());
        if (lvl > 0 && !(main.getItem() instanceof ArmorItem) && off.is(Items.REDSTONE)) {
            if (doCharge(p, main, off, lvl, false, time)) event.setCanceled(true);
        }
        // 2. 护甲充能 (穿身上，主手红石)
        else if (main.is(Items.REDSTONE)) {
            boolean ok = false;
            for (ItemStack armor : p.getArmorSlots()) {
                int alvl = armor.getEnchantmentLevel(GridEnchantments.REDSTONE_OVERDRIVE.get());
                if (alvl > 0 && time - OverdriveUtils.getLastRechargeTime(armor) >= 1200) {
                    if (doCharge(p, armor, main, alvl, true, time)) ok = true;
                }
            }
            if (ok) {
                p.level().playSound(null, p.blockPosition(), SoundEvents.ARMOR_EQUIP_DIAMOND, SoundSource.PLAYERS, 1f, 1f);
                event.setCanceled(true);
            }
        }
    }

    private static boolean doCharge(Player p, ItemStack target, ItemStack res, int lvl, boolean isArmor, long time) {
        float cur = OverdriveUtils.getEnergy(target);
        int max = OverdriveUtils.getMaxEnergy(target, lvl);
        if (cur >= max) return false;
        int need = (int) Math.ceil((max - cur) / 3.0f);
        int consume = Math.min(res.getCount(), need);
        if (consume <= 0) return false;
        OverdriveUtils.setEnergy(target, cur + consume * 3.0f);
        if (isArmor) OverdriveUtils.setLastRechargeTime(target, time);
        if (!p.isCreative()) res.shrink(consume);
        p.displayClientMessage(Component.literal("§c⚡ 能量已补充"), true);
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDefend(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player p) || p.level().isClientSide || event.getAmount() <= 0) return;
        long time = p.level().getGameTime();
        List<ItemStack> set = new ArrayList<>();
        float total = 0;
        boolean ready = false;

        for (ItemStack armor : p.getArmorSlots()) {
            int lvl = armor.getEnchantmentLevel(GridEnchantments.REDSTONE_OVERDRIVE.get());
            if (lvl > 0) {
                total += OverdriveUtils.getEnergy(armor);
                set.add(armor);
                if (time - OverdriveUtils.getLastShieldTime(armor) >= 100) ready = true; // 5秒CD
            }
        }

        if (!set.isEmpty() && ready && total >= event.getAmount()) {
            float cost = event.getAmount() / set.size();
            for (ItemStack a : set) {
                OverdriveUtils.setEnergy(a, OverdriveUtils.getEnergy(a) - cost);
                OverdriveUtils.setLastShieldTime(a, time);
            }
            event.setAmount(0); event.setCanceled(true);
            p.level().playSound(null, p.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.5f, 1.5f);
            if (p.level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.POOF, p.getX(), p.getY()+1, p.getZ(), 10, 0.2, 0.2, 0.2, 0.1);
        }
    }

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        ItemStack s = event.getEntity().getMainHandItem();
        int lvl = s.getEnchantmentLevel(GridEnchantments.REDSTONE_OVERDRIVE.get());
        if (lvl > 0 && OverdriveUtils.getEnergy(s) > 0) {
            float r = OverdriveUtils.getEnergy(s) / OverdriveUtils.getMaxEnergy(s, lvl);
            event.setNewSpeed(event.getOriginalSpeed() * (1.1f + 0.65f * r));
        }
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack s = event.getItemStack();
        int lvl = s.getEnchantmentLevel(GridEnchantments.REDSTONE_OVERDRIVE.get());
        if (lvl > 0) {
            float e = OverdriveUtils.getEnergy(s);
            int m = OverdriveUtils.getMaxEnergy(s, lvl);
            event.getToolTip().add(Component.literal("§c⚡ 能量: " + String.format("%.1f", e) + " / " + m).withStyle(ChatFormatting.RED));
        }
    }
}
