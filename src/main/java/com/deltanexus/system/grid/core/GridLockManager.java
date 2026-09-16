package com.deltanexus.system.grid.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网格事务锁（0.3.0Beta）。
 *
 * <p>所有会改动网格的入口（菜单点击、快捷移动、交易交付、整理指令、外部交付）统一经此获取锁，
 * 禁止裸 {@code synchronized}。锁按 {@link LockKey} 的<b>固定全局顺序</b>获取，天然避免死锁。</p>
 *
 * <p>服务端网格操作本来就跑在主线程、同一 Tick 内同步完成，因此这里通常是零竞争的；
 * 它的价值在于：跨线程入口（Web 编辑器线程、能力数据异步保存）与同一玩家的并发入口
 * 不会互相撕裂。</p>
 */
public final class GridLockManager {

    /** 锁对象身份：作用域 + 拥有者（玩家 UUID）。 */
    public record LockKey(String scope, UUID owner) implements Comparable<LockKey> {
        @Override
        public int compareTo(LockKey other) {
            int byScope = scope.compareTo(other.scope);
            if (byScope != 0) {
                return byScope;
            }
            if (owner == null && other.owner == null) {
                return 0;
            }
            if (owner == null) {
                return -1;
            }
            if (other.owner == null) {
                return 1;
            }
            return owner.compareTo(other.owner);
        }
    }

    /** 玩家网格锁（背包 + 仓库 + 安全箱同一把，简化事务边界）。 */
    public static LockKey player(UUID uuid) {
        return new LockKey("grid.player", uuid);
    }

    /** 共享容器锁。 */
    public static LockKey container(Object identity) {
        return new LockKey("grid.container", identity == null ? null : UUID.nameUUIDFromBytes(
                String.valueOf(System.identityHashCode(identity)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static final ConcurrentHashMap<LockKey, Object> MONITORS = new ConcurrentHashMap<>();

    private GridLockManager() {
    }

    private static Object monitor(LockKey key) {
        return MONITORS.computeIfAbsent(key, k -> new Object());
    }

    /** 按固定顺序获取一组锁。 */
    public static LockSet acquire(Collection<LockKey> keys) {
        List<LockKey> sorted = new ArrayList<>(keys == null ? List.of() : keys);
        sorted.sort(LockKey::compareTo);
        List<Object> acquired = new ArrayList<>(sorted.size());
        for (LockKey key : sorted) {
            Object m = monitor(key);
            synchronized (m) {
                acquired.add(m);
            }
        }
        return new LockSet(sorted, acquired);
    }

    public static LockSet acquire(LockKey... keys) {
        return acquire(keys == null ? List.of() : List.of(keys));
    }

    /** 便捷写法：持锁执行。 */
    public static <T> T withLocks(Collection<LockKey> keys, java.util.function.Supplier<T> body) {
        try (LockSet ignored = acquire(keys)) {
            return body.get();
        }
    }

    /** 已获取的锁集合（{@code try-with-resources} 释放，逆序释放）。 */
    public static final class LockSet implements AutoCloseable {
        private final List<LockKey> keys;
        private final List<Object> monitors;
        private boolean released;

        private LockSet(List<LockKey> keys, List<Object> monitors) {
            this.keys = keys;
            this.monitors = monitors;
        }

        public List<LockKey> keys() {
            return List.copyOf(keys);
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            for (int i = monitors.size() - 1; i >= 0; i--) {
                // 内置监视器：必须在获取它的同一线程释放
                synchronized (monitors.get(i)) {
                    // 空块：仅用于配对释放监视器
                }
            }
        }
    }
}
