package com.deltanexus.system.mixin;

import com.deltanexus.system.grid.GearPolicy;
import com.deltanexus.system.server.GearService;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 菜单注入（0.5.0Beta 格子背包装备化）——全局兜住「点被屏蔽格」与「快捷移动撞被屏蔽格」。
 *
 * <p>与 sakura 的 {@code SakuraMenuMixin} 对齐，两处：</p>
 * <ul>
 *   <li>{@code clicked} —— 目标是装备已取代的格时直接取消（<b>对所有菜单全局生效</b>：
 *       原版背包、箱子、潜影盒、其他模组的界面都拦得住，不必逐个界面处理）；</li>
 *   <li>{@code moveItemStackTo} —— 目的地区间含被屏蔽格时，改走装备感知的搬运
 *       （{@link GearService#moveToAllowed}）：跳过被屏蔽格，溢出进胸挂/背包。</li>
 * </ul>
 *
 * <p>注入目标按 official 名书写，编译期由 refmap 映射为生产环境的 SRG 名
 * （见 {@code deltanexus.refmap.json}），因此使用默认 {@code remap = true}。</p>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class MenuMixin {

    @Inject(method = "clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"), cancellable = true)
    private void dn$click(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (slotId >= 0 && slotId < menu.slots.size() && GearPolicy.isBlockedSlot(menu.slots.get(slotId))) {
            ci.cancel();
        }
    }

    @Inject(method = "moveItemStackTo(Lnet/minecraft/world/item/ItemStack;IIZ)Z",
            at = @At("HEAD"), cancellable = true)
    private void dn$move(ItemStack stack, int start, int end, boolean reverse, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (start >= 0 && end <= menu.slots.size() && GearService.hasBlockedInRange(menu, start, end)) {
            cir.setReturnValue(GearService.moveToAllowed(menu, stack, start, end, reverse));
        }
    }
}