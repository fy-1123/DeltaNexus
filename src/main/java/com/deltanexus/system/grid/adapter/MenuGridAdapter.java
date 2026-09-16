package com.deltanexus.system.grid.adapter;

import com.deltanexus.system.grid.GridRegistry;
import com.deltanexus.system.grid.GridSizes;
import com.deltanexus.system.grid.InventoryGridHandler;
import com.deltanexus.system.grid.core.GridContext;
import com.deltanexus.system.grid.core.GridTarget;
import com.deltanexus.system.grid.core.StackSnapshot;
import com.deltanexus.system.grid.core.UsableMask;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单适配器（0.3.0Beta）：把「一个菜单里的某一组同容器槽位」适配成网格视图。
 *
 * <p>分组规则（与旧引擎一致）：</p>
 * <ul>
 *   <li>玩家背包：只取 {@code containerSlot < 36} 的槽（不含盔甲/副手），
 *       顺序为背包 9..35 在前、快捷栏 0..8 在后（与菜单槽位顺序一致，形成 9 列 4 行视图）；</li>
 *   <li>其他容器：仅在容器已注册（{@link GridRegistry}）且菜单不是原版 {@link InventoryMenu} 时参与；</li>
 *   <li>分组单位是<b>容器对象身份</b>（{@code SlotItemHandler} 的处理器 / 原版 {@code Container}）。</li>
 * </ul>
 *
 * <p>索引口径（重写关键修正）：组内下标 {@code i} 用于布局，
 * {@link #containerIndex(int)} 给出容器索引（占位物 {@code master_slot} 记录它，<b>不随视口滚动失配</b>），
 * {@link #menuSlotIndex(int)} 给出菜单槽位索引（网络与点击重定向用）。</p>
 */
public final class MenuGridAdapter implements GridTarget {

    private final Player player;
    private final AbstractContainerMenu menu;
    private final Object container;
    private final int[] slotIds;
    private final Slot[] slots;

    private MenuGridAdapter(Player player, AbstractContainerMenu menu, Object container, int[] slotIds) {
        this.player = player;
        this.menu = menu;
        this.container = container;
        this.slotIds = slotIds;
        this.slots = new Slot[slotIds.length];
        for (int i = 0; i < slotIds.length; i++) {
            this.slots[i] = menu.slots.get(slotIds[i]);
        }
    }

    /** 把一个菜单按容器切分成若干网格组（只返回启用网格的组）。 */
    public static List<MenuGridAdapter> groups(Player player, AbstractContainerMenu menu) {
        List<MenuGridAdapter> out = new ArrayList<>();
        if (menu == null) {
            return out;
        }
        Map<Object, List<Integer>> byContainer = new LinkedHashMap<>();
        boolean menuOpen = !(menu instanceof InventoryMenu);
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            Object container = InventoryGridHandler.gridContainerOf(slot);
            if (container instanceof Inventory inv) {
                // 玩家背包：只取真实背包 + 快捷栏（盔甲/副手位不参与网格）
                if (inv == slot.container && slot.getContainerSlot() < 36) {
                    byContainer.computeIfAbsent(container, k -> new ArrayList<>()).add(i);
                }
                continue;
            }
            // 创造模式物品标签页列表（CreativeModeInventoryMenu 内部容器）不是玩家存储，绝不接管
            if (container instanceof net.minecraft.world.Container c0
                    && c0.getClass().getName().startsWith("net.minecraft.world.inventory.CreativeModeInventoryMenu")) {
                continue;
            }
            if (menuOpen && GridRegistry.isGridContainer(player, container)) {
                byContainer.computeIfAbsent(container, k -> new ArrayList<>()).add(i);
            }
        }
        for (Map.Entry<Object, List<Integer>> e : byContainer.entrySet()) {
            List<Integer> ids = e.getValue();
            int[] arr = new int[ids.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = ids.get(i);
            }
            out.add(new MenuGridAdapter(player, menu, e.getKey(), arr));
        }
        return out;
    }

    public Object container() {
        return container;
    }

    public AbstractContainerMenu menu() {
        return menu;
    }

    public Slot slot(int index) {
        return index >= 0 && index < slots.length ? slots[index] : null;
    }

    @Override
    public int size() {
        return slots.length;
    }

    @Override
    public ItemStack get(int index) {
        Slot slot = slot(index);
        return slot == null ? ItemStack.EMPTY : slot.getItem();
    }

    @Override
    public void set(int index, ItemStack stack) {
        Slot slot = slot(index);
        if (slot != null) {
            slot.set(stack == null ? ItemStack.EMPTY : stack);
        }
    }

    @Override
    public int containerIndex(int index) {
        Slot slot = slot(index);
        return slot == null ? -1 : slot.getSlotIndex();
    }

    @Override
    public int menuSlotIndex(int index) {
        return index >= 0 && index < slotIds.length ? slotIds[index] : -1;
    }

    /** 菜单槽位索引 → 组内下标（不属于本组返回 -1）。 */
    public int localIndexOf(int menuSlotId) {
        for (int i = 0; i < slotIds.length; i++) {
            if (slotIds[i] == menuSlotId) {
                return i;
            }
        }
        return -1;
    }

    /** 容器索引 → 组内下标（不属于本组返回 -1）。 */
    public int localIndexOfContainerIndex(int containerIndex) {
        for (int i = 0; i < slots.length; i++) {
            if (containerIndex(i) == containerIndex) {
                return i;
            }
        }
        return -1;
    }

    /** 求解上下文。 */
    public GridContext context() {
        int size = size();
        int width = GridRegistry.width(player, container);
        boolean[] usable = new boolean[size];
        boolean[] hotbarZone = new boolean[size];
        for (int i = 0; i < size; i++) {
            usable[i] = GridRegistry.isUsable(player, container, containerIndex(i));
            hotbarZone[i] = GridSizes.isHotbarSlot(slots[i]);
        }
        return GridContext.builder(width, size)
                .usable(new UsableMask(size, usable))
                .hotbarZone(hotbarZone)
                .restriction(GridRegistry.restrictionOf(player, container))
                .build();
    }

    /** 每格快照（含快捷栏分级折算后的实际尺寸）。 */
    public List<StackSnapshot> snapshots() {
        int size = size();
        List<StackSnapshot> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Slot slot = slots[i];
            ItemStack stack = slot.getItem();
            list.add(StackSnapshot.of(stack, GridSizes.actualDim(stack, slot, false, player, container),
                    i, containerIndex(i)));
        }
        return list;
    }

    /** 是否值得求解（存在跨格物品或占位物）。 */
    public boolean needsSolve() {
        for (int i = 0; i < size(); i++) {
            ItemStack stack = slots[i].getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (com.deltanexus.system.grid.core.GridTags.isSlave(stack)) {
                return true;
            }
            if (!GridSizes.actualDim(stack, slots[i], false, player, container).is1x1()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 主格解析（点击重定向）
    // ------------------------------------------------------------------

    /**
     * 把点击到的槽位解析成它的主格槽位。
     *
     * <p>占位物的 {@code master_slot} 记录的是<b>容器索引</b>，因此这里在同一容器内按容器索引反查菜单槽位，
     * 仓库滚动（菜单索引变化）后依然正确。命中不到主格时返回原槽位（防御）。</p>
     */
    public static Slot resolveMaster(AbstractContainerMenu menu, Slot clicked) {
        if (menu == null || clicked == null) {
            return null;
        }
        ItemStack stack = clicked.getItem();
        if (!com.deltanexus.system.grid.core.GridTags.isSlave(stack)) {
            return clicked;
        }
        int masterIndex = com.deltanexus.system.grid.core.GridTags.masterOf(stack);
        Object container = InventoryGridHandler.gridContainerOf(clicked);
        // 1) 同容器内按容器索引查找
        for (Slot s : menu.slots) {
            if (InventoryGridHandler.gridContainerOf(s) == container && s.getSlotIndex() == masterIndex) {
                ItemStack master = s.getItem();
                if (!master.isEmpty() && !com.deltanexus.system.grid.core.GridTags.isSlave(master)) {
                    return s;
                }
                return clicked;
            }
        }
        // 2) 兼容：旧数据把主格写成菜单槽位索引
        if (masterIndex >= 0 && masterIndex < menu.slots.size()) {
            Slot legacy = menu.slots.get(masterIndex);
            if (InventoryGridHandler.gridContainerOf(legacy) == container
                    && !legacy.getItem().isEmpty()
                    && !com.deltanexus.system.grid.core.GridTags.isSlave(legacy.getItem())) {
                return legacy;
            }
        }
        return clicked;
    }

    /** 解析后的主格在该容器中的索引（-1 = 无法解析）。 */
    public static int masterContainerIndex(AbstractContainerMenu menu, Slot clicked) {
        Slot master = resolveMaster(menu, clicked);
        if (master == null || master == clicked) {
            return -1;
        }
        return master.getSlotIndex();
    }
}
