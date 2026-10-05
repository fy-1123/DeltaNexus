package com.deltanexus.system.mixin;

import com.deltanexus.system.grid.GearPolicy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 槽位注入（0.5.0Beta 格子背包装备化）——让被装备取代的原版格「物理不可用」。
 *
 * <p>与 sakura 的 {@code SakuraSlotMixin} 对齐，四处全部封死：</p>
 * <ul>
 *   <li>{@code mayPlace} false —— 放不进去；</li>
 *   <li>{@code mayPickup} false —— 拿不出来；</li>
 *   <li>{@code isActive} false —— 界面不绘制、原版快速移动也会跳过；</li>
 *   <li>{@code getMaxStackSize} 0 —— 作为最后一道保险。</li>
 * </ul>
 *
 * <p>只作用于「玩家背包容器 + 受装备策略限制的槽位」（见 {@link GearPolicy}）：
 * 创造/旁观者模式玩家、其他容器、盔甲与副手格一律不受影响。</p>
 *
 * <p>注意 refmap：本项目源码使用 <b>official 映射</b>，但生产 jar 经 reobf 后为 <b>SRG 名</b>，
 * 两者并不一致，必须由 {@code deltanexus.refmap.json} 在注入时重映射。
 * 故此处使用默认 {@code remap = true}（而非 {@code remap = false}）。</p>
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

    @Inject(method = "mayPlace(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void dn$mayPlace(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (GearPolicy.isBlockedSlot((Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mayPickup(Lnet/minecraft/world/entity/player/Player;)Z", at = @At("HEAD"), cancellable = true)
    private void dn$mayPickup(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (GearPolicy.isBlockedSlot((Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isActive()Z", at = @At("HEAD"), cancellable = true)
    private void dn$isActive(CallbackInfoReturnable<Boolean> cir) {
        if (GearPolicy.isBlockedSlot((Slot) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getMaxStackSize()I", at = @At("HEAD"), cancellable = true)
    private void dn$maxStack(CallbackInfoReturnable<Integer> cir) {
        if (GearPolicy.isBlockedSlot((Slot) (Object) this)) {
            cir.setReturnValue(0);
        }
    }
}