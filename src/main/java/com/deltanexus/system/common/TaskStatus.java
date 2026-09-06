package com.deltanexus.system.common;

/**
 * 制造任务状态。
 */
public enum TaskStatus {
    WAITING(0),
    COMPLETED(1);

    private final int id;

    TaskStatus(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static TaskStatus fromId(int id) {
        return id == COMPLETED.id ? COMPLETED : WAITING;
    }
}
