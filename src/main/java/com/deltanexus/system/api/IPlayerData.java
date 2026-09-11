package com.deltanexus.system.api;

import com.deltanexus.system.common.Task;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.items.ItemStackHandler;

import java.util.BitSet;
import java.util.Deque;
import java.util.Map;

/**
 * 玩家数据能力接口（对外 API）。
 *
 * <p>仓库：BitSet 记录已解锁槽位索引（而非 List&lt;Boolean&gt;），内存占用最小化；
 * 物品存放在容量固定的 {@link ItemStackHandler} 中。</p>
 *
 * <p>任务：每个工作台（动态注册表 id）独立存储 {@code ConcurrentLinkedDeque<Task>}，
 * 时间戳驱动，无 Tick 依赖。</p>
 */
public interface IPlayerData extends ICapabilitySerializable<CompoundTag> {

    /** 仓库物品存储（容量 = 配置的仓库最大容量）。 */
    ItemStackHandler getWarehouseHandler();

    /** 仓库总容量（硬编码上限 108）。 */
    int getCapacity();

    /** 已解锁槽位索引集合。 */
    BitSet getUnlockedSlots();

    /** 指定槽位是否已解锁。 */
    boolean isSlotUnlocked(int index);

    /** 当前仓库等级（由升级树驱动）。 */
    int getWarehouseLevel();

    /** 设置仓库等级。 */
    void setWarehouseLevel(int level);

    /** 解锁到前 totalSlots 个槽位（只增不减）。 */
    void unlockUpTo(int totalSlots);

    /**
     * 精确设置解锁槽位数量（前 totalSlots 个解锁，其余锁定；越界按容量截断）。
     * 仅供管理指令使用：缩小会锁定多余槽位，其中物品将暂时无法取出。
     */
    void setUnlockedSlots(int totalSlots);

    /**
     * 扩容仓库存储容量（仅增大，保留已有物品）。
     * 页数配置扩容后调用，使在线玩家会话内立即获得新容量（无需重登）。
     */
    void resizeWarehouse(int newCapacity);

    /** 某个工作台（id）的制造任务队列（WAITING + COMPLETED）。 */
    Deque<Task> getTasks(String workbenchId);

    /** 全部工作台的制造任务。 */
    Map<String, Deque<Task>> getAllTasks();

    /** 下一个任务唯一 ID（自增）。 */
    int nextTaskId();

    /** 取回指定槽位的物品。 */
    ItemStack getWarehouseItem(int slot);

    /** 设置指定槽位的物品（越界/未解锁返回 false）。 */
    boolean setWarehouseItem(int slot, ItemStack stack);

    // ------------------------------------------------------------------
    // 安全箱（1.1.0Alpha）：独立小仓储，最大 3x3=9 格，尺寸随安全箱升级树增长
    // ------------------------------------------------------------------

    /** 安全箱物品存储（固定容量 9 = 最大 3x3）。 */
    ItemStackHandler getSafeBoxHandler();

    /** 当前安全箱等级（0 = 配置默认尺寸）。 */
    int getSafeBoxLevel();

    /** 设置安全箱等级。 */
    void setSafeBoxLevel(int level);

    /** 安全箱当前解锁格数（0 级 = 配置默认 w x h；等级 > 0 按安全箱升级树，且不低于默认尺寸）。 */
    int getSafeBoxUnlockedSlots();

    /** 安全箱显示宽度（1 ~ 3 格）。 */
    int getSafeBoxWidth();

    /** 安全箱显示高度（1 ~ 3 格）。 */
    int getSafeBoxHeight();

    /** 指定安全箱槽位是否已解锁。 */
    boolean isSafeSlotUnlocked(int index);

    /** 标记数据已变化（任务状态等直接字段修改后调用，确保存档）。 */
    default void markTasksDirty() {
    }
}
