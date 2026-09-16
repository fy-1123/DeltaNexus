package com.deltanexus.system.grid.adapter;

import com.deltanexus.system.grid.GridRegistry;
import com.deltanexus.system.grid.GridSizes;
import com.deltanexus.system.grid.core.GridContext;
import com.deltanexus.system.grid.core.GridDim;
import com.deltanexus.system.grid.core.GridTarget;
import com.deltanexus.system.grid.core.StackSnapshot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 处理器适配器（0.3.0Beta）：把裸 {@link ItemStackHandler}（仓库 / 安全箱 / 已注册的模组处理器）
 * 适配成求解器要的 {@link GridContext} + 每格快照，并实现 {@link GridTarget} 写回。
 *
 * <p>宽度取自容器规则（安全箱按解锁列数），可用格取自玩家数据（未解锁槽不可落位、不可跨越），
 * 额外限制（安全箱 NBT 限制）以谓词形式交给内核。</p>
 */
public final class HandlerGridAdapter implements GridTarget {

    private final Player player;
    private final ItemStackHandler handler;
    private final Object container;

    private HandlerGridAdapter(Player player, ItemStackHandler handler) {
        this.player = player;
        this.handler = handler;
        this.container = handler;
    }

    public static HandlerGridAdapter of(Player player, ItemStackHandler handler) {
        return new HandlerGridAdapter(player, handler);
    }

    public ItemStackHandler handler() {
        return handler;
    }

    public Object container() {
        return container;
    }

    @Override
    public int size() {
        return handler.getSlots();
    }

    @Override
    public ItemStack get(int index) {
        return index >= 0 && index < handler.getSlots() ? handler.getStackInSlot(index) : ItemStack.EMPTY;
    }

    @Override
    public void set(int index, ItemStack stack) {
        if (index >= 0 && index < handler.getSlots()) {
            handler.setStackInSlot(index, stack == null ? ItemStack.EMPTY : stack);
        }
    }

    /** 求解上下文。 */
    public GridContext context() {
        int size = size();
        int width = GridRegistry.width(player, container);
        boolean[] usable = new boolean[size];
        for (int i = 0; i < size; i++) {
            usable[i] = GridRegistry.isUsable(player, container, i);
        }
        return GridContext.builder(width, size)
                .usable(new com.deltanexus.system.grid.core.UsableMask(size, usable))
                .restriction(GridRegistry.restrictionOf(player, container))
                .build();
    }

    /** 每格快照（占位物不参与求解，尺寸记为 1x1）。 */
    public List<StackSnapshot> snapshots() {
        int size = size();
        List<StackSnapshot> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ItemStack stack = get(i);
            list.add(StackSnapshot.of(stack, GridSizes.baseDim(stack), i));
        }
        return list;
    }

    /** 是否值得求解（存在跨格物品或占位物）。 */
    public boolean needsSolve() {
        for (int i = 0; i < size(); i++) {
            ItemStack stack = get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (com.deltanexus.system.grid.core.GridTags.isSlave(stack)) {
                return true;
            }
            if (!GridSizes.baseDim(stack).is1x1()) {
                return true;
            }
        }
        return false;
    }

    /** 单件物品在容器中的实际尺寸（交付/预演用；容器不启用网格时 1x1）。 */
    public GridDim dimOf(ItemStack stack) {
        if (!GridRegistry.isGridContainer(player, container)) {
            return GridDim.ONE;
        }
        return GridSizes.baseDim(stack);
    }
}
