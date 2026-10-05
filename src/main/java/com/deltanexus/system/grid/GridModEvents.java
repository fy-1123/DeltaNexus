package com.deltanexus.system.grid;

import com.deltanexus.system.DeltaNexus;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 格子背包玩法事件（0.5.0Beta）。
 *
 * <p>当前只保留装备物品的右键入口：登记为背包/胸挂的物品，右键「装备并打开」。
 * 物品自身有右键行为（食物/弓/桶等）时不接管，只能从装备槽打开（与设计约定一致）。</p>
 */
@Mod.EventBusSubscriber(modid = DeltaNexus.MODID)
public class GridModEvents {

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide) {
            return;
        }
        Player p = event.getEntity();
        ItemStack main = p.getItemInHand(InteractionHand.MAIN_HAND);
        if (GearConfig.isGear(main)
                && !com.deltanexus.system.server.GearService.hasOwnUse(main)
                && com.deltanexus.system.server.GearService.equip(p, main, event.getHand())) {
            event.setCanceled(true);
        }
    }
}
