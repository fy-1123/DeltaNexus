package com.deltanexus.system.grid.core;

import net.minecraft.world.item.ItemStack;

/**
 * 网格写入目标（0.3.0Beta）：把「菜单槽位」与「裸 ItemHandler」统一成同一组读写接口，
 * 使 {@link GridMutation} 无需关心数据来自哪种容器。
 */
public interface GridTarget {

    int size();

    ItemStack get(int index);

    void set(int index, ItemStack stack);

    /**
     * 该格在<b>所属容器</b>中的索引——占位物 {@code deltanexus.master_slot} 记录的就是它。
     *
     * <p>0.3.0Beta 起统一为容器索引（旧版混用菜单索引，仓库滚动一行全部失配 → 每滚一次重写整片占位物）。
     * 容器索引不随视口滚动变化；需要菜单槽位时用 {@link #menuSlotIndex(int)} 换算。</p>
     */
    default int containerIndex(int index) {
        return index;
    }

    /**
     * 该格对应的菜单槽位索引（客户端点击重定向 / 网络包用）；无菜单上下文返回 -1。
     */
    default int menuSlotIndex(int index) {
        return -1;
    }

    /** 只读包装（写入抛异常）：用于纯预测/求解。 */
    static GridTarget readOnly(java.util.List<ItemStack> cells) {
        return new GridTarget() {
            @Override
            public int size() {
                return cells.size();
            }

            @Override
            public ItemStack get(int index) {
                return index >= 0 && index < cells.size() ? cells.get(index) : ItemStack.EMPTY;
            }

            @Override
            public void set(int index, ItemStack stack) {
                throw new UnsupportedOperationException("read-only target");
            }
        };
    }
}
