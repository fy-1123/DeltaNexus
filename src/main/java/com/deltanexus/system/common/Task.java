package com.deltanexus.system.common;

/**
 * 制造任务（时间戳驱动，零Tick依赖）。
 *
 * <p>离线模式（默认）：startTime 固定，离线期间照常计时，剩余 = endTime - now。</p>
 *
 * <p>在线模式：仅玩家在线时计时。startTime = 当前在线段开始时刻，
 * accumulatedMs = 之前在线段的累计时长；剩余 = duration - accumulated - (now - startTime)。
 * 玩家重新登录时由服务端将 startTime 重置为 now（离线时间不计入）。</p>
 */
public class Task {
    public final int taskId;
    public final String recipeId;
    /** 当前在线段开始时间（离线模式 = 任务创建时间）。 */
    public long startTime;
    /** 已累计在线时长（毫秒，仅在线模式使用）。 */
    public long accumulatedMs;
    public final long cachedDurationMs;
    public TaskStatus status;

    public Task(int taskId, String recipeId, long startTime, long cachedDurationMs, TaskStatus status) {
        this(taskId, recipeId, startTime, 0L, cachedDurationMs, status);
    }

    public Task(int taskId, String recipeId, long startTime, long accumulatedMs,
                long cachedDurationMs, TaskStatus status) {
        this.taskId = taskId;
        this.recipeId = recipeId;
        this.startTime = startTime;
        this.accumulatedMs = accumulatedMs;
        this.cachedDurationMs = cachedDurationMs;
        this.status = status;
    }

    /** 计划完成时间点（毫秒时间戳，离线模式）。 */
    public long endTimeMs() {
        return startTime + cachedDurationMs;
    }

    /** 离线模式剩余毫秒；<=0 表示已完成。 */
    public long remainingMs(long now) {
        return endTimeMs() - now;
    }

    /** 在线模式剩余毫秒（仅玩家在线时段计时）；<=0 表示已完成。 */
    public long remainingMsOnline(long now) {
        long elapsed = accumulatedMs + Math.max(0, now - startTime);
        return cachedDurationMs - elapsed;
    }

    /** 在线模式累计已进行毫秒。 */
    public long elapsedMsOnline(long now) {
        return accumulatedMs + Math.max(0, now - startTime);
    }

    /** 剩余秒数（向上取整，避免提前显示 0）。 */
    public int remainingSeconds(long now) {
        long ms = remainingMs(now);
        if (ms <= 0) {
            return 0;
        }
        return (int) ((ms + 999) / 1000);
    }
}
