package com.deltanexus.system.menu;

import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.grid.adapter.InputGate;
import com.deltanexus.system.grid.core.GridService;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 网格感知菜单（0.3.0Beta）：在<b>菜单入口</b>统一把占位物格重定向到主格。
 *
 * <p>原版把所有点击（左/右键、Shift 快捷移动、数字键换位、Q 丢弃、拖拽）都收敛到
 * {@link #clicked(int, int, ClickType, Player)}，快速移动收敛到 {@link #quickMoveStack(Player, int)}，
 * 因此在这两处重定向即可一次性覆盖全部路径——不再依赖客户端单点拦截。</p>
 *
 * <p>重定向后点击「占位物格」与点击「主格格」走的是<b>同一条原版代码路径</b>，
 * 字节级一致，彻底消除「点击非主格格子导致主格瞬移」的现象。</p>
 *
 * <p>提交屏障：每次点击/快捷移动后调用 {@link GridService#markDirty(Player)}，
 * 同 Tick 内完成求解并广播，客户端不会看到中间态（占位物悬空/物品未落位）。</p>
 */
public abstract class GridAwareMenu extends AbstractContainerMenu implements InputGate.SellModeAware {

    protected GridAwareMenu(MenuType<?> type, int id) {
        super(type, id);
    }

    /**
     * 占位物格 → 主格槽位索引。
     *
     * <p>占位物记录的是<b>容器索引</b>，这里在同一容器内反查菜单槽位，
     * 因此仓库视口滚动（菜单索引整体平移）后依然指向正确的主格。</p>
     */
    public int resolveMaster(int slotId) {
        if (slotId < 0 || slotId >= this.slots.size()) {
            return slotId;
        }
        // 0.3.0Beta 第三阶段：布局优先（服务端权威推导），历史占位物作为兜底
        int byLayout = GridService.anchorSlotOf(this, slotId);
        if (byLayout != slotId) {
            return byLayout;
        }
        Slot clicked = this.slots.get(slotId);
        Slot master = InventoryGridHandler.resolveMasterSlot(this, clicked);
        return master == null ? slotId : master.index;
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player player) {
        // 出售模式（服务端门闸）：冻结一切物品移动，交由客户端 UI 完成选中语义
        if (isSellMode()) {
            return;
        }
        super.clicked(resolveMaster(slotId), button, type, player);
        GridService.markDirty(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (isSellMode()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = quickMoveRedirected(player, resolveMaster(index));
        GridService.markDirty(player);
        return result;
    }

    /**
     * 重定向后的实际快捷移动（子类实现自己的路由）。
     *
     * <p>原版 {@code AbstractContainerMenu#quickMoveStack} 是抽象方法，无法用 {@code super} 调用，
     * 因此这里定义钩子，由子类把 {@code resolvedIndex} 当作「真实点击的槽位」处理。</p>
     */
    protected abstract ItemStack quickMoveRedirected(Player player, int resolvedIndex);

    /**
     * 服务端出售模式门闸（0.3.0Beta：已由 {@code C2SSellModePacket} 落地，协议 dn3）。
     *
     * <p>子类（仓库菜单）持有真实状态；本基类默认 false。开启时本类的
     * {@link #clicked} / {@link #quickMoveStack} 会直接返回，服务端因此冻结一切物品移动——
     * 客户端冻结只负责体验一致，服务端才是权威。</p>
     */
    public boolean isSellMode() {
        return false;
    }
}
