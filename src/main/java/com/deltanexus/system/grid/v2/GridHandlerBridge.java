package com.deltanexus.system.grid.v2;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.grid.core.GridDim;
import com.deltanexus.system.grid.core.GridTags;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/**
 * 网格处理器桥接（重写版内核，v2 接线层）。
 *
 * <p>作用：让 <b>{@link GridInventory} 成为唯一存储</b>，同时把旧的 {@link ItemStackHandler} 形态
 * 暴露给既有调用点（菜单槽位、制造扣料、交易交付、指令、Web…）——**调用点零改动**。</p>
 *
 * <p>映射语义（与旧的“锚点 + 占位物”模型的关键差异）：</p>
 * <ul>
 *   <li>{@code getStackInSlot(i)} → 锚点格返回物品，覆盖格返回<b>空</b>（覆盖格本来就是空格）；</li>
 *   <li>{@code setStackInSlot(i, stack)} → 翻译成网格操作：空 = 取出；非空 = 落位（放不下时依次尝试
 *       原位 → 首个空位 → 退化为 1x1 保留，<b>绝不丢物品</b>）；</li>
 *   <li>{@code insertItem / extractItem} → 同样翻译成落位/取出，并按 Forge 约定返回剩余/取出的数量；</li>
 *   <li>{@code deserializeNBT} → 先按新格式读；不是新格式则走 {@link GridStorage#migrateFrom} 迁移旧平铺数据；</li>
 *   <li>{@code serializeNBT} → 只写锚点（新格式），占位物之类派生态从此不可能落盘。</li>
 * </ul>
 */
public final class GridHandlerBridge extends ItemStackHandler {

    /** 容器上下文（解锁位图 + 物品准入 + 变更通知），由持有者提供。 */
    public interface Access {
        boolean usable(int containerIndex);

        /** 该物品是否允许进入本容器（安全箱 NBT 限制等）；迁移旧数据时不走这里。 */
        default boolean accepts(ItemStack stack) {
            return true;
        }

        default void onChanged() {
        }
    }

    private final int width;
    private final Access access;
    private GridInventory grid;

    public GridHandlerBridge(int width, int rows, Access access) {
        super(Math.max(1, width * rows)); // 仅用于满足父类构造；实际读写全部走 grid
        this.width = Math.max(1, width);
        this.access = access;
        // 注意：构造期不得回调 access（持有者此时尚未完成字段赋值，问它解锁位会 NPE）。
        // 先按“全部可用”建立，持有者构造完成后调用 refreshUsable() 同步真实解锁状态。
        int size = this.width * Math.max(1, rows);
        boolean[] allUsable = new boolean[size];
        java.util.Arrays.fill(allUsable, true);
        this.grid = new GridInventory(this.width, Math.max(1, rows), allUsable);
    }

    public GridInventory grid() {
        return grid;
    }

    private boolean[] usableMask(int size) {
        boolean[] mask = new boolean[size];
        for (int i = 0; i < size; i++) {
            mask[i] = access == null || access.usable(i);
        }
        return mask;
    }

    /** 行数变化（扩容/迁移）后重建网格，并尽力把原有条目安置回去。 */
    public void resizeRows(int rows) {
        int newRows = Math.max(1, rows);
        if (newRows == grid.rows()) {
            return;
        }
        java.util.List<GridEntry> entries = new java.util.ArrayList<>(grid.entries().values());
        GridInventory bigger = new GridInventory(width, newRows, usableMask(newRows * width));
        GridInventory.Result result = bigger.replaceAll(entries);
        if (result.failed()) {
            DeltaNexus.LOGGER.warn("[DN] 网格扩容安置失败（保持原尺寸）: {}", result.reason());
            return;
        }
        this.grid = bigger;
        onContentsChanged(0);
    }

    /** 解锁位图变化后刷新可用格（锁定被占用的格会被拒绝，保持容器合法）。 */
    public void refreshUsable() {
        for (int i = 0; i < grid.size(); i++) {
            boolean want = access == null || access.usable(i);
            if (grid.usable(i) != want) {
                grid.setUsable(i, want);
            }
        }
    }

    // ------------------------------------------------------------------
    // Forge ItemStackHandler 覆写：全部走网格
    // ------------------------------------------------------------------

    @Override
    public int getSlots() {
        return grid.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= grid.size()) {
            return ItemStack.EMPTY;
        }
        GridEntry entry = grid.entryAt(slot);
        return entry == null ? ItemStack.EMPTY : entry.stackForWrite();
    }

    /**
     * 写入某格（<b>替换语义</b>）：这一格写完之后就装 {@code stack}，<b>绝不改写到别的格子</b>。
     *
     * <p>这是修「A 点 B → 光标 A 变 B、网格凭空多出 A」的关键：原版交换物品的流程是
     * 「先读走本格内容 → 再 set 光标物品 → 光标拿到读走的那件」。如果这里发现本格已被占用就
     * “换个空位放”，那么原版读走的物品<b>同时</b>留在网格里（复制）、光标物品又出现在别的格（多出 A）。
     * 因此必须严格：本格被占 → 先取走；本格属于别人足迹 → 拒绝写入（不改写别处）。</p>
     */
    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (slot < 0 || slot >= grid.size()) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            if (grid.take(slot).ok()) {
                changed();
            }
            return;
        }
        boolean rotated = GridTags.isRotated(stack);
        GridEntry entry = GridEntry.of(GridTags.stripped(stack), rotated);

        boolean isAnchor = grid.entryAt(slot) != null;
        if (!isAnchor && grid.anchorCovering(slot) >= 0) {
            // 目标格属于别的跨格物品的足迹：拒绝写入（不挤占、也不改写到别处）
            DeltaNexus.LOGGER.warn("[DN] 拒绝写入：格 {} 属于其它跨格物品的足迹（{}）", slot, stack);
            return;
        }
        // 关键：**先判定可行性，再动任何物品**。否则“先腾空、再发现放不下”会让
        // 原版把光标换成腾出来的物品、而新物品无处安放 → 物品消失。
        boolean canStay = grid.canPlaceAfterRemoving(slot, entry.dim());
        int spot = canStay ? -1 : grid.findFreeSpot(entry.dim());
        // 目标格是“锁定格”时不停手：旧处理器允许写锁定格（管理员降级 / 旧档 / 内部代码路径依赖它），
        // 由后面的 placeForced 兜底；只有普通格子才在“腾空后放不下且无空位”时拒绝。
        if (!canStay && spot < 0 && grid.usable(slot)) {
            DeltaNexus.LOGGER.warn("[DN] 放不下：格 {} 腾空后仍放不下 {}，且无其它空位 → 拒绝本次写入（物品留在光标）",
                    slot, stack);
            return;
        }
        if (isAnchor) {
            grid.take(slot); // 腾空原物品（原版已把它读进光标，交换语义一致）
        }
        if (canStay && grid.place(slot, entry).ok()) {
            changed();
            return;
        }
        if (spot >= 0 && grid.place(spot, entry).ok()) {
            changed();
            return;
        }
        // 兜底：锁定格按旧行为允许写入（管理员降级/旧档/内部路径依赖）
        if (!grid.usable(slot) && grid.placeForced(slot, entry).ok()) {
            changed();
            return;
        }
        // 最后兜底：把物品强制落在本格（仅忽略解锁状态；越界/重叠仍会被 validate 拦下）——
        // 宁可落在锁定格上，也绝不因为“放不下”而把已经离开原格的物品丢掉。
        if (grid.placeForced(slot, entry).ok()) {
            changed();
            return;
        }
        DeltaNexus.LOGGER.error("[DN] 写入失败（格 {}）：{} —— 已尽力避免丢失，请上报此日志", slot, stack);
    }
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (slot >= 0 && slot < grid.size()) {
            GridEntry target = grid.entryAt(slot);
            if (target != null && target.sameKind(GridEntry.of(stack, GridTags.isRotated(stack)))) {
                int space = target.maxStackSize() - target.count();
                if (space > 0) {
                    int add = Math.min(space, stack.getCount());
                    if (!simulate) {
                        grow(slot, add);
                    }
                    ItemStack remain = stack.copy();
                    remain.shrink(add);
                    return remain.isEmpty() ? ItemStack.EMPTY : remain;
                }
            }
        }
        if (simulate) {
            GridEntry probe = GridEntry.of(stack, GridTags.isRotated(stack));
            if (grid.canPlace(slot, probe.dim()) == null || grid.findFreeSpot(probe.dim()) >= 0) {
                return ItemStack.EMPTY;
            }
            return stack;
        }
        ItemStack remain = placeAnywhere(slot, stack);
        return remain;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot < 0 || slot >= grid.size() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        GridEntry entry = grid.entryAt(slot);
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        int take = Math.min(amount, entry.count());
        ItemStack out = entry.stackForWrite(take);
        if (simulate) {
            return out;
        }
        if (take >= entry.count()) {
            if (grid.take(slot).ok()) {
                changed();
            }
            return out;
        }
        // 部分取出：数量变化同样通过网格操作表达（place 会做不变式与守恒之外的合法性校验）
        GridInventory.Result rewrote = grid.place(slot, entry.withCount(entry.count() - take));
        if (rewrote.ok()) {
            changed();
            return out;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !grid.usable(slot)) {
            return false;
        }
        if (access != null && !access.accepts(stack)) {
            return false;
        }
        var dim = GridEntry.of(stack, GridTags.isRotated(stack)).dim();
        // 精确判定：①本格本身能放；②腾空本格（交换会腾空）后能放；③网格内还有其它空位。
        // 三者都不行 → 返回 false，让原版直接中止这次交换（物品留在光标，绝不消失、绝不复制）。
        return grid.canPlace(slot, dim) == null
                || grid.canPlaceAfterRemoving(slot, dim)
                || grid.findFreeSpot(dim) >= 0;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    @Override
    public CompoundTag serializeNBT() {
        return GridStorage.write(grid);
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return;
        }
        // 载入失败绝不能让玩家数据变成“无效”（那会直接进不去世界）：出错就保持空容器并打完整堆栈
        try {
            GridInventory loaded = GridStorage.read(nbt, width, grid.rows(), usableMask(grid.size()));
            if (loaded != null) {
                this.grid = loaded;
                return;
            }
            ItemStackHandler legacy = new ItemStackHandler(grid.size());
            legacy.deserializeNBT(nbt);
            this.grid = GridStorage.migrateFrom(legacy, width, grid.rows(), usableMask(grid.size()));
            changed();
        } catch (Throwable t) {
            DeltaNexus.LOGGER.error("[DN] 网格容器载入失败（已降级为空容器，请把堆栈发给开发者）", t);
        }
    }

    // ------------------------------------------------------------------
    // 外部交付入口（交易买入、指令给物等）
    // ------------------------------------------------------------------

    /**
     * 把一件物品放进网格：先并入同类且未满的条目，再行优先找空位；返回放不下的剩余。
     *
     * <p>这是「外部交付」的正确入口——旧路径会为足迹写占位物，而 v2 没有占位物，
     * 于是那些占位物会被当成普通物品落位，往仓库里塞进垃圾 `blocked_slot`。
     * 交付必须走内核操作（{@link GridInventory#place}），由内核保证不变式与守恒。</p>
     */
    public ItemStack insertIntoGrid(ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (access != null && !access.accepts(stack)) {
            return stack.copy();
        }
        boolean rotated = GridTags.isRotated(stack);
        ItemStack clean = GridTags.stripped(stack);
        int remain = clean.getCount();

        // 1) 并入同类条目
        for (var e : grid.entries().entrySet()) {
            if (remain <= 0) {
                break;
            }
            GridEntry cur = e.getValue();
            if (!cur.sameKind(GridEntry.of(clean, rotated))) {
                continue;
            }
            int space = cur.maxStackSize() - cur.count();
            if (space <= 0) {
                continue;
            }
            int add = Math.min(space, remain);
            if (!simulate) {
                grid.place(e.getKey(), cur.withCount(cur.count() + add));
            }
            remain -= add;
        }
        // 2) 新落点（每个落点 ≤ 最大堆叠）
        GridEntry proto = GridEntry.of(clean, rotated);
        while (remain > 0) {
            int chunk = Math.min(proto.maxStackSize(), remain);
            int spot = grid.findFreeSpot(proto.dim());
            if (spot < 0) {
                break;
            }
            if (!simulate) {
                GridInventory.Result placed = grid.place(spot, proto.withCount(chunk));
                if (placed.failed()) {
                    break;
                }
            }
            remain -= chunk;
        }
        if (!simulate && remain < clean.getCount()) {
            changed();
        }
        if (remain <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack left = clean.copy();
        left.setCount(remain);
        return left;
    }

    // ------------------------------------------------------------------
    // 内部：物品进入网格
    // ------------------------------------------------------------------

    /** 落位（空 = 放入容器之外来的物品）：原位 → 首个空位 → 退化 1x1，绝不丢物品。 */
    private void ingest(int slot, ItemStack stack) {
        if (access != null && !access.accepts(stack)) {
            return; // 准入被拒（如安全箱 NBT 限制）：保持原状，物品仍由调用方持有
        }
        boolean rotated = GridTags.isRotated(stack);
        GridEntry entry = GridEntry.of(GridTags.stripped(stack), rotated);
        GridInventory.Result placed = grid.place(slot, entry);
        if (placed.ok()) {
            changed();
            return;
        }
        int spot = grid.findFreeSpot(entry.dim());
        if (spot >= 0 && grid.place(spot, entry).ok()) {
            changed();
            return;
        }
        GridEntry one = GridEntry.of(entry.stackForWrite(), GridDim.ONE, false);
        if (grid.place(slot, one).ok()) {
            changed();
            return;
        }
        int anywhere = grid.findFreeSpot(GridDim.ONE);
        if (anywhere >= 0 && grid.place(anywhere, one).ok()) {
            changed();
            return;
        }
        // 兼容兜底：旧处理器允许写“锁定格”（管理员降级/旧档/内部代码路径都依赖这一点），
        // 但<b>绝不允许</b>写进别的跨格物品的足迹——那正是「非锚点格也能放」的根因。
        if (grid.anchorCovering(slot) < 0 && grid.placeForced(slot, entry).ok()) {
            changed();
            return;
        }
        DeltaNexus.LOGGER.warn("[DN] 网格没有合法落点，已拒绝本次写入（槽位 {}，{}）：该位置属于其它跨格物品的足迹",
                slot, stack);
    }

    /** 落位（返回剩余），供 insertItem 使用。 */
    private ItemStack placeAnywhere(int slot, ItemStack stack) {
        if (access != null && !access.accepts(stack)) {
            return stack; // 准入被拒：原样退回
        }
        boolean rotated = GridTags.isRotated(stack);
        ItemStack clean = GridTags.stripped(stack);
        GridEntry entry = GridEntry.of(clean, rotated);
        GridInventory.Result placed = slot >= 0 ? grid.place(slot, entry) : GridInventory.Result.fail("无目标格", grid.revision());
        if (placed.ok()) {
            changed();
            return ItemStack.EMPTY;
        }
        int spot = grid.findFreeSpot(entry.dim());
        if (spot >= 0 && grid.place(spot, entry).ok()) {
            changed();
            return ItemStack.EMPTY;
        }
        GridEntry one = GridEntry.of(clean, GridDim.ONE, false);
        int anyCell = grid.findFreeSpot(GridDim.ONE);
        if (anyCell >= 0 && grid.place(anyCell, one).ok()) {
            changed();
            return ItemStack.EMPTY;
        }
        // 同理：允许“锁定格”兼容兜底，但绝不挤占别人的足迹
        if (slot >= 0 && grid.anchorCovering(slot) < 0 && grid.placeForced(slot, entry).ok()) {
            changed();
            return ItemStack.EMPTY;
        }
        return stack;
    }

    private void grow(int slot, int add) {
        GridEntry entry = grid.entryAt(slot);
        if (entry == null) {
            return;
        }
        GridInventory.Result result = grid.place(slot, entry.withCount(entry.count() + add));
        if (result.ok()) {
            changed();
        }
    }

    private void changed() {
        if (access != null) {
            access.onChanged();
        }
    }
}
