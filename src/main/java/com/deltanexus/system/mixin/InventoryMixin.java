package com.deltanexus.system.mixin;

import com.deltanexus.system.grid.GearPolicy;
import com.deltanexus.system.server.GearService;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家背包注入（0.5.0Beta 格子背包装备化）——物品「进背包」时改走装备感知路径。
 *
 * <p>与 sakura 的 {@code SakuraInventoryMixin} 对齐，三处：</p>
 * <ul>
 *   <li>{@code getFreeSlot} —— 只在前 {@link GearPolicy#KEEP_SLOTS} 格里找空位（不再往被屏蔽格塞）；</li>
 *   <li>{@code getSlotWithRemainingSpace} —— 同上，找可继续堆叠的格；</li>
 *   <li>{@code add(int, ItemStack)} —— 接管插入：合并 → 首选位 → 空位 → <b>溢出进已装备的胸挂/背包</b>。</li>
 * </ul>
 *
 * <p>只对受装备策略限制的玩家生效（创造/旁观者走原版逻辑）。</p>
 *
 * <p>注入目标按 official 名书写，编译期由 refmap 映射为生产环境的 SRG 名
 * （见 {@code deltanexus.refmap.json}），因此使用默认 {@code remap = true}。</p>
 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {

    @Inject(method = "getFreeSlot()I", at = @At("HEAD"), cancellable = true)
    private void dn$freeSlot(CallbackInfoReturnable<Integer> cir) {
        Inventory inventory = (Inventory) (Object) this;
        if (GearPolicy.restricted(inventory.player)) {
            cir.setReturnValue(GearService.firstFreeSlot(inventory.player));
        }
    }

    @Inject(method = "getSlotWithRemainingSpace(Lnet/minecraft/world/item/ItemStack;)I",
            at = @At("HEAD"), cancellable = true)
    private void dn$mergeSlot(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        Inventory inventory = (Inventory) (Object) this;
        if (GearPolicy.restricted(inventory.player)) {
            cir.setReturnValue(GearService.firstMergeSlot(inventory.player, stack));
        }
    }

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void dn$add(int index, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        Inventory inventory = (Inventory) (Object) this;
        if (GearPolicy.restricted(inventory.player)) {
            cir.setReturnValue(GearService.insertInventory(inventory.player, stack, index));
        }
    }
}