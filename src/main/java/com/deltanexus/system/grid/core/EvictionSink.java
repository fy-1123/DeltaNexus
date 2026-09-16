package com.deltanexus.system.grid.core;

import net.minecraft.world.item.ItemStack;

/**
 * 出不来物品的处置出口（0.3.0Beta）：由调用方（服务端网格服务）实现，
 * 内核只提建议，不直接碰光标与世界。
 */
public interface EvictionSink {

    /**
     * 物品进入光标（光标为空时才应接收）。
     *
     * @return 实际接收的数量（0 = 没收；用于「光标装不下剩余部分」的精确回退）
     */
    int toCursor(ItemStack stack);

    /** 物品合并进光标（同类且未满）；返回实际接收数量。 */
    int mergeToCursor(ItemStack stack);

    /** 掉落兜底（绝不静默吞物品）；返回是否全部接手。 */
    boolean drop(ItemStack stack);

    /** 无出口（用于纯预测/测试：物品保持原位）。 */
    EvictionSink NONE = new EvictionSink() {
        @Override
        public int toCursor(ItemStack stack) {
            return 0;
        }

        @Override
        public int mergeToCursor(ItemStack stack) {
            return 0;
        }

        @Override
        public boolean drop(ItemStack stack) {
            return false;
        }
    };
}
