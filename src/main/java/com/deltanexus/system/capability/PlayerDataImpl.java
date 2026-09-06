package com.deltanexus.system.capability;

import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.common.Task;
import com.deltanexus.system.common.TaskStatus;
import com.deltanexus.system.common.WorkbenchRegistry;
import com.deltanexus.system.config.ModConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 玩家数据实现（Capability）。
 *
 * <p>序列化（正确性优先）：{@link #serializeNBT()} 始终返回完整数据，
 * 杜绝任何路径（异常退出/增量跳过）下的存档丢失；反序列化失败会输出完整堆栈日志。</p>
 */
public class PlayerDataImpl implements IPlayerData, INBTSerializable<CompoundTag>, ICapabilityProvider {

    private final WarehouseHandler warehouse;
    private final SafeBoxHandler safeBox;
    private final BitSet unlocked = new BitSet(ModConfig.warehouseRows() * 9);
    private int warehouseLevel = 0;
    /** 安全箱等级（0 = 配置默认尺寸；>0 按安全箱升级树）。 */
    private int safeBoxLevel = 0;
    /** 工作台 id -> 任务队列（动态注册表；未知 id 惰性创建）。 */
    private final Map<String, Deque<Task>> tasks = new LinkedHashMap<>();
    private int nextTaskId = 1;

    public PlayerDataImpl() {
        // 容量 = 行数 x 9（每行 9 格）；配置未加载时（如客户端实体构造）回退默认 6 行 = 54
        int capacity = ModConfig.warehouseRows() * 9;
        warehouse = new WarehouseHandler(capacity);
        // 安全箱固定最大容量 9（3x3），解锁格数随等级/默认尺寸变化
        safeBox = new SafeBoxHandler(9);
        for (WorkbenchRegistry.Workbench wb : WorkbenchRegistry.get().all()) {
            tasks.put(wb.id, new ConcurrentLinkedDeque<>());
        }
        unlockUpTo(Math.min(ModConfig.safeBaseSlots(), capacity));
    }

    /** 仓库存储：写入受解锁槽位限制。 */
    private class WarehouseHandler extends ItemStackHandler {
        WarehouseHandler(int size) {
            super(size);
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return isSlotUnlocked(slot) && super.isItemValid(slot, stack);
        }

        /** 扩容（仅增大）：快照 -> setSize -> 回填物品，保证扩容不丢物品。 */
        void resize(int newSize) {
            if (newSize <= getSlots()) {
                return;
            }
            CompoundTag snapshot = serializeNBT();
            setSize(newSize);
            ListTag items = snapshot.getList("Items", Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) {
                CompoundTag itemTags = items.getCompound(i);
                int slot = itemTags.getInt("Slot");
                if (slot >= 0 && slot < getSlots()) {
                    setStackInSlot(slot, ItemStack.of(itemTags));
                }
            }
        }
    }

    /** 安全箱存储：写入受解锁格数与 NBT 限制校验（1.1.0；固定 9 格，未解锁/命中限制不可放入）。 */
    private class SafeBoxHandler extends ItemStackHandler {
        SafeBoxHandler(int size) {
            super(size);
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return isSafeSlotUnlocked(slot)
                    && !com.deltanexus.system.config.SafeBoxRestrictions.isRestricted(stack)
                    && super.isItemValid(slot, stack);
        }
    }

    @Override
    public ItemStackHandler getWarehouseHandler() {
        return warehouse;
    }

    @Override
    public int getCapacity() {
        return warehouse.getSlots();
    }

    @Override
    public BitSet getUnlockedSlots() {
        return unlocked;
    }

    @Override
    public boolean isSlotUnlocked(int index) {
        return index >= 0 && index < getCapacity() && unlocked.get(index);
    }

    @Override
    public int getWarehouseLevel() {
        return warehouseLevel;
    }

    @Override
    public void setWarehouseLevel(int level) {
        this.warehouseLevel = Math.max(0, level);
    }

    @Override
    public void unlockUpTo(int totalSlots) {
        int cap = Math.min(totalSlots, getCapacity());
        for (int i = 0; i < cap; i++) {
            unlocked.set(i);
        }
    }

    @Override
    public void setUnlockedSlots(int totalSlots) {
        int n = Math.max(0, Math.min(totalSlots, getCapacity()));
        unlocked.clear();
        unlocked.set(0, n);
    }

    @Override
    public void resizeWarehouse(int newCapacity) {
        if (newCapacity <= getCapacity()) {
            return;
        }
        warehouse.resize(newCapacity);
    }

    @Override
    public Deque<Task> getTasks(String workbenchId) {
        return tasks.computeIfAbsent(workbenchId, k -> new ConcurrentLinkedDeque<>());
    }

    @Override
    public Map<String, Deque<Task>> getAllTasks() {
        return tasks;
    }

    @Override
    public int nextTaskId() {
        return nextTaskId++;
    }

    @Override
    public ItemStack getWarehouseItem(int slot) {
        return slot >= 0 && slot < getCapacity() ? warehouse.getStackInSlot(slot) : ItemStack.EMPTY;
    }

    @Override
    public boolean setWarehouseItem(int slot, ItemStack stack) {
        if (!isSlotUnlocked(slot)) {
            return false;
        }
        warehouse.setStackInSlot(slot, stack);
        return true;
    }

    // ------------------------------------------------------------------
    // 安全箱（1.1.0）
    // ------------------------------------------------------------------

    @Override
    public ItemStackHandler getSafeBoxHandler() {
        return safeBox;
    }

    @Override
    public int getSafeBoxLevel() {
        return safeBoxLevel;
    }

    @Override
    public void setSafeBoxLevel(int level) {
        this.safeBoxLevel = Math.max(0, level);
    }

    /** 安全箱解锁格数：0 级 = 配置默认 w x h；等级 > 0 = 升级树累计解锁格数（且不低于默认尺寸，只增不减）。 */
    @Override
    public int getSafeBoxUnlockedSlots() {
        int configDefault = Math.max(1, Math.min(9,
                ModConfig.safeBoxWidth() * ModConfig.safeBoxHeight()));
        if (safeBoxLevel <= 0) {
            return configDefault;
        }
        int treeSlots = com.deltanexus.system.config.UpgradeConfig.get().safeUnlockSlotsForLevel(safeBoxLevel);
        if (treeSlots <= 0) {
            return configDefault;
        }
        return Math.max(1, Math.min(9, Math.max(configDefault, treeSlots)));
    }

    /** 安全箱显示宽度（1 ~ 3 列；2.0.1 起按升级树解锁列数，0 级 = 配置默认宽度）。 */
    @Override
    public int getSafeBoxWidth() {
        if (safeBoxLevel <= 0) {
            return ModConfig.safeBoxWidth();
        }
        int[] dims = com.deltanexus.system.config.UpgradeConfig.get().safeDimsForLevel(safeBoxLevel);
        if (dims == null) {
            return ModConfig.safeBoxWidth();
        }
        return Math.max(1, Math.min(3, dims[0]));
    }

    /** 安全箱显示高度（1 ~ 3 行；2.0.1 起按升级树解锁行数，0 级 = 配置默认高度）。 */
    @Override
    public int getSafeBoxHeight() {
        if (safeBoxLevel <= 0) {
            return ModConfig.safeBoxHeight();
        }
        int[] dims = com.deltanexus.system.config.UpgradeConfig.get().safeDimsForLevel(safeBoxLevel);
        if (dims == null) {
            return ModConfig.safeBoxHeight();
        }
        return Math.max(1, Math.min(3, dims[1]));
    }

    /**
     * 安全箱槽位是否解锁（2.0.3 行列形状）：按「行 x 列」形状解锁，
     * 即第 r 行 c 列（3x3 网格）当且仅当 r < 高度 && c < 宽度时可用
     * （2x2 = 2 行 2 列 = 110|110|000，不再是最前 N 格前缀）。
     */
    @Override
    public boolean isSafeSlotUnlocked(int index) {
        if (index < 0 || index >= 9) {
            return false;
        }
        int w = getSafeBoxWidth();
        int h = getSafeBoxHeight();
        return (index % 3) < w && (index / 3) < h;
    }

    // ------------------------------------------------------------------
    // 序列化（正确性优先：始终返回完整数据，杜绝任何路径下的存档丢失）
    // ------------------------------------------------------------------

    private static final String KEY_WAREHOUSE = "warehouse";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_UNLOCKED = "unlocked";
    private static final String KEY_TASKS = "tasks";
    private static final String KEY_NEXT_TASK_ID = "next_task_id";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_SAFE_BOX = "safe_box";
    private static final String KEY_SAFE_LEVEL = "safe_level";

    @Override
    public CompoundTag serializeNBT() {
        return buildTag();
    }

    /**
     * 强制返回完整序列化数据（与 serializeNBT 一致）。
     * 用于 PlayerEvent.Clone 等需要无条件搬运数据的场景。
     */
    public CompoundTag serializeFull() {
        return buildTag();
    }

    /** 任务状态变化（完成/移除）时标记。保留以兼容调用方（序列化已无增量优化）。 */
    public void markTasksDirty() {
    }

    private CompoundTag buildTag() {
        CompoundTag root = new CompoundTag();
        CompoundTag wh = new CompoundTag();
        wh.putInt(KEY_LEVEL, warehouseLevel);
        wh.putLongArray(KEY_UNLOCKED, unlocked.toLongArray());
        wh.put(KEY_ITEMS, warehouse.serializeNBT());
        root.put(KEY_WAREHOUSE, wh);

        // 安全箱（1.1.0）：等级 + 物品（固定 9 格）
        CompoundTag safe = new CompoundTag();
        safe.putInt(KEY_SAFE_LEVEL, safeBoxLevel);
        safe.put(KEY_ITEMS, safeBox.serializeNBT());
        root.put(KEY_SAFE_BOX, safe);

        CompoundTag tasksTag = new CompoundTag();
        for (Map.Entry<String, Deque<Task>> e : tasks.entrySet()) {
            ListTag list = new ListTag();
            for (Task t : e.getValue()) {
                CompoundTag taskTag = new CompoundTag();
                taskTag.putInt("id", t.taskId);
                taskTag.putString("recipe", t.recipeId);
                taskTag.putLong("start", t.startTime);
                taskTag.putLong("dur", t.cachedDurationMs);
                taskTag.putLong("acc", t.accumulatedMs);
                taskTag.putInt("status", t.status.id());
                list.add(taskTag);
            }
            tasksTag.put(e.getKey(), list);
        }
        root.put(KEY_TASKS, tasksTag);
        root.putInt(KEY_NEXT_TASK_ID, nextTaskId);
        return root;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            com.deltanexus.system.DeltaNexus.LOGGER.debug("[DN] 玩家数据加载：无存档数据");
            return;
        }
        try {
            CompoundTag wh = tag.getCompound(KEY_WAREHOUSE);
            if (wh.contains(KEY_LEVEL)) {
                warehouseLevel = Math.max(0, wh.getInt(KEY_LEVEL));
            }
            if (wh.contains(KEY_UNLOCKED)) {
                unlocked.clear();
                int bit = 0;
                for (long l : wh.getLongArray(KEY_UNLOCKED)) {
                    for (int i = 0; i < 64; i++) {
                        if (((l >> i) & 1L) == 1L) {
                            unlocked.set(bit + i);
                        }
                    }
                    bit += 64;
                }
            }
            if (wh.contains(KEY_ITEMS)) {
                CompoundTag itemsTag = wh.getCompound(KEY_ITEMS);
                if (itemsTag.getInt("Size") <= getCapacity()) {
                    warehouse.deserializeNBT(itemsTag);
                } else {
                    com.deltanexus.system.DeltaNexus.LOGGER.warn(
                            "[DN] 玩家仓库容量不匹配，存档 {} > 当前 {}，跳过物品加载，等级保留",
                            itemsTag.getInt("Size"), getCapacity());
                }
            }
            // 安全箱（1.1.0；旧存档无此段则保持默认等级 0 + 空物品）
            if (tag.contains(KEY_SAFE_BOX)) {
                CompoundTag safe = tag.getCompound(KEY_SAFE_BOX);
                if (safe.contains(KEY_SAFE_LEVEL)) {
                    safeBoxLevel = Math.max(0, safe.getInt(KEY_SAFE_LEVEL));
                }
                if (safe.contains(KEY_ITEMS)) {
                    CompoundTag itemsTag = safe.getCompound(KEY_ITEMS);
                    if (itemsTag.getInt("Size") <= 9) {
                        safeBox.deserializeNBT(itemsTag);
                    } else {
                        com.deltanexus.system.DeltaNexus.LOGGER.warn(
                                "[DN] 玩家安全箱容量不匹配，存档 {} > 9，跳过物品加载，等级保留",
                                itemsTag.getInt("Size"));
                    }
                }
            }
            CompoundTag tasksTag = tag.getCompound(KEY_TASKS);
            for (WorkbenchRegistry.Workbench wb : WorkbenchRegistry.get().all()) {
                Deque<Task> deque = tasks.computeIfAbsent(wb.id, k -> new ConcurrentLinkedDeque<>());
                deque.clear();
                // 新格式：任务按工作台 id 存储
                boolean hasNewKey = tasksTag.contains(wb.id, Tag.TAG_LIST);
                loadTasksInto(deque, tasksTag.getList(wb.id, Tag.TAG_COMPOUND));
                // 旧存档兼容：任务曾以配方目录名（armor_workbench 等）存储。
                // 严重修复：仅当目录名 ≠ id（默认工作台与 workbench add 均为 recipesDir = id，
                // 同 key 双载会导致任务翻倍、可重复领取奖励）且新 key 缺失时才回退加载
                if (!hasNewKey && !wb.recipesDir.equals(wb.id) && tasksTag.contains(wb.recipesDir, Tag.TAG_LIST)) {
                    loadTasksInto(deque, tasksTag.getList(wb.recipesDir, Tag.TAG_COMPOUND));
                }
            }
            nextTaskId = Math.max(1, tag.getInt(KEY_NEXT_TASK_ID));
            com.deltanexus.system.DeltaNexus.LOGGER.debug(
                    "[DN] 玩家数据加载成功：等级 {}, 解锁槽位 {}, 任务 {}",
                    warehouseLevel, unlocked.cardinality(), tasks.values().stream().mapToInt(Deque::size).sum());
        } catch (Exception e) {
            com.deltanexus.system.DeltaNexus.LOGGER.error("[DN] 玩家数据反序列化失败: {}", e.getMessage(), e);
        }
    }

    /** 加载任务列表（按 taskId 去重：taskId 全局唯一，自愈历史双载 bug 造成的重复任务）。 */
    private void loadTasksInto(Deque<Task> deque, ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag taskTag = list.getCompound(i);
            Task task = new Task(
                    taskTag.getInt("id"),
                    taskTag.getString("recipe"),
                    taskTag.getLong("start"),
                    taskTag.getLong("acc"),
                    taskTag.getLong("dur"),
                    TaskStatus.fromId(taskTag.getInt("status")));
            boolean duplicate = false;
            for (Task t : deque) {
                if (t.taskId == task.taskId) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                deque.addLast(task);
            }
        }
    }

    // ------------------------------------------------------------------
    // ICapabilityProvider（作为 provider 提供给实体）
    // ------------------------------------------------------------------

    private final LazyOptional<IPlayerData> lazy = LazyOptional.of(() -> this);

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap) {
        return CapabilityAttacher.PLAYER_DATA.orEmpty(cap, lazy);
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @org.jetbrains.annotations.Nullable net.minecraft.core.Direction side) {
        return CapabilityAttacher.PLAYER_DATA.orEmpty(cap, lazy);
    }
}
