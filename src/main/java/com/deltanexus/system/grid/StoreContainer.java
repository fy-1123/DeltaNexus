package com.deltanexus.system.grid;

import com.deltanexus.system.DeltaNexus;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 网格容器门面（0.5.0Beta）——把 {@link GridStore} 的「只存锚点」模型暴露成原版 {@link Container}。
 *
 * <p>菜单槽位只能认识 {@code Container}（按下标读写），而本模组的唯一存储是锚点表。
 * 本类就是这两者之间<b>唯一的适配层</b>，语义严格保持内核的四条保证：</p>
 * <ul>
 *   <li>下标 = 格号；{@link #getItem(int)} 只在<b>锚点格</b>返回值，足迹内的非锚点格一律返回空
 *       —— 不往容器里塞任何占位物，因此内核里也不会出现占位物；</li>
 *   <li>{@link #getItem(int)} 返回的是条目内部栈的<b>活引用</b>：原版槽位会就地
 *       {@code grow/shrink} 改数量（数量本就是条目的一部分），这是刻意为之，
 *       不能改成拷贝，否则数量变化会丢；</li>
 *   <li>写入统一走内核写入口（{@code place/take}），失败时先尝试 {@link GridStore#placeForced} 兜底，
 *       <b>绝不静默丢弃</b>；</li>
 *   <li>{@link #setChanged()} 触发外部落盘回调（例如写回物品 NBT），客户端影子容器不传回调即为只读。</li>
 * </ul>
 *
 * <p>复用范围：装备（背包/胸挂）与本类无关——它只认 {@link GridStore}，
 * 因此仓库/安全箱等任何网格容器接新内核时都直接复用本类。</p>
 */
public class StoreContainer implements Container {

    /** 内容变化回调（服务端写回物品 NBT / 存档；客户端传 null）。 */
    @FunctionalInterface
    public interface Flusher {
        void flush(GridStore store);
    }

    @Nullable
    private final Flusher flusher;
    private GridStore store;

    public StoreContainer(GridStore store) {
        this(store, null);
    }

    public StoreContainer(GridStore store, @Nullable Flusher flusher) {
        this.store = store == null ? new GridStore(1, 1) : store;
        this.flusher = flusher;
    }

    public GridStore store() {
        return store;
    }

    /**
     * 整体替换内核（<b>仅客户端影子容器用</b>）：服务端下发几何后，客户端按<b>逐格一致</b>的
     * 锚点表整体换掉，保证占位形状与服务端完全同步。
     */
    public void reset(GridStore next) {
        if (next != null) {
            this.store = next;
        }
    }

    /** 该格是否为锚点（物品本体所在格）。 */
    public boolean isAnchor(int cell) {
        return store.entryAt(cell) != null;
    }

    /** 该格是否被「别的锚点的足迹」覆盖：多格物品的非锚点格，界面隐藏、交互拒绝。 */
    public boolean isCovered(int cell) {
        int anchor = store.anchorAt(cell);
        return anchor >= 0 && anchor != cell;
    }

    // ------------------------------------------------------------------
    // Container
    // ------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return store.size();
    }

    @Override
    public boolean isEmpty() {
        for (GridEntry entry : store.entries().values()) {
            if (!entry.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int cell) {
        GridEntry entry = store.entryAt(cell);
        return entry == null ? ItemStack.EMPTY : entry.stack();
    }

    @Override
    public ItemStack removeItem(int cell, int count) {
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        int anchor = store.anchorAt(cell);
        GridEntry entry = anchor < 0 ? null : store.entryAt(anchor);
        if (entry == null || entry.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int take = Math.min(count, entry.count());
        ItemStack out = entry.stackForWrite(take);
        if (take >= entry.count()) {
            store.take(anchor);
        } else {
            entry.stack().shrink(take);
        }
        setChanged();
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int cell) {
        ItemStack current = getItem(cell);
        ItemStack out = current.isEmpty() ? ItemStack.EMPTY : current.copy();
        setItem(cell, ItemStack.EMPTY);
        return out;
    }

    /**
     * 写入一格。原版契约是「调用方负责原内容」——交换语义下原内容已先被原版取进光标，
     * 因此这里替换即丢弃条目是安全的（不复制、不搬运，避免凭空多出物品）。
     */
    @Override
    public void setItem(int cell, ItemStack stack) {
        if (cell < 0 || cell >= store.size()) {
            return;
        }
        ItemStack next = stack == null ? ItemStack.EMPTY : stack;
        int anchor = store.anchorAt(cell);

        // 目标是别人的足迹格：先把整块腾空（正常路径已被 mayPlace 拦住，这里只是兜底）
        if (anchor >= 0 && anchor != cell) {
            store.take(anchor);
            anchor = -1;
        }

        if (anchor == cell) {
            GridEntry current = store.entryAt(cell);
            if (next.isEmpty()) {
                store.take(cell);
            } else if (current != null && ItemStack.isSameItemSameTags(current.stack(), next)) {
                // 同类就地改数量：保持姿态与尺寸（旋转是内核操作，不通过容器写入）
                current.stack().setCount(next.getCount());
            } else {
                store.take(cell);
                placeAt(cell, next);
            }
        } else if (!next.isEmpty()) {
            placeAt(cell, next);
        }
        setChanged();
    }

    @Override
    public void setChanged() {
        if (flusher != null) {
            flusher.flush(store);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        store.clear();
        setChanged();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 落位（尺寸按物品尺寸表推导）；普通落位失败时强制兜底，确保物品不丢。 */
    private void placeAt(int cell, ItemStack stack) {
        GridEntry entry = GridEntry.of(stack.copy(), false);
        GridStore.Result result = store.place(cell, entry);
        if (result.failed()) {
            result = store.placeForced(cell, entry);
        }
        if (result.failed()) {
            DeltaNexus.LOGGER.error("[DN] 网格容器写入失败（格 {}，物品 {}）：{}",
                    cell, stack.getItem(), result.reason());
        }
    }
}
