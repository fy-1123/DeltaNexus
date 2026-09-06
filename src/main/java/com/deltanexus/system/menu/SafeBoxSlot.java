package com.deltanexus.system.menu;

import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.config.SafeBoxRestrictions;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.server.ManufacturingService;
import com.deltanexus.system.server.PermissionManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 安全箱槽位（1.1.0）：内嵌于仓库/背包界面的安全箱面板共用。
 *
 * <p>服务端校验四重限制：安全箱权限（{@link PermissionManager#canOpenSafeBox}，2.1）、
 * 解锁格数（isSafeSlotUnlocked）、NBT 限制（{@link SafeBoxRestrictions}，命中规则的物品禁止放入）、
 * 物品有效性；客户端不做预测拦截（避免普通点击被拒），由服务端最终校验。</p>
 */
public class SafeBoxSlot extends SlotItemHandler {

    private final Player player;

    public SafeBoxSlot(ItemStackHandler handler, int index, int x, int y, Player player) {
        super(handler, index, x, y);
        this.player = player;
    }

    private boolean unlocked() {
        IPlayerData data = ManufacturingService.data(player);
        return data == null || data.isSafeSlotUnlocked(getSlotIndex());
    }

    /** 2.1：安全箱权限（服务端权威；仓库界面嵌入安全箱同样受权限控制；仅服务端调用）。 */
    private boolean permitted() {
        return player == null
                || PermissionManager.canOpenSafeBox(player instanceof net.minecraft.server.level.ServerPlayer sp
                        ? sp : null);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        if (this.player.level().isClientSide()) {
            return super.mayPlace(stack);
        }
        if (!permitted() || !unlocked() || SafeBoxRestrictions.isRestricted(stack) || !super.mayPlace(stack)) {
            return false;
        }
        // 2.0.10：安全箱仅 1 格可用时只能放 1x1——大于 1x1 的物品放不下，拒绝放入（回光标）
        IPlayerData data = ManufacturingService.data(player);
        if (data != null && data.getSafeBoxUnlockedSlots() <= 1
                && !InventoryGridHandler.getBaseDim(stack).is1x1()) {
            return false;
        }
        return true;
    }

    @Override
    public boolean mayPickup(Player p) {
        if (this.player.level().isClientSide()) {
            return super.mayPickup(p);
        }
        return permitted() && unlocked() && super.mayPickup(p);
    }

    /**
     * 2.1：客户端被禁用（安全箱权限拒绝）时隐藏槽位——AbstractContainerScreen 渲染
     * 跳过 isActive()=false 的槽位（物品、悬停高亮均不渲染），配合界面「安全箱被禁用」提示。
     */
    @Override
    public boolean isActive() {
        if (this.player.level().isClientSide()) {
            com.deltanexus.system.network.packet.SyncSafeBoxPacket st =
                    com.deltanexus.system.client.gui.SafeBoxOverlay.lastState();
            if (st != null && !st.allowed) {
                return false;
            }
        }
        return super.isActive();
    }
}
