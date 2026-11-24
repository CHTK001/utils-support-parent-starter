package com.chua.common.support.ai.agent;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于内存的 {@link AgentSuspendStore} 默认实现：带 TTL 与条目上限。
 *
 * <p><b>TTL</b>：读取时惰性判断过期；另有守护线程周期性清扫，避免无人读取的
 * 过期条目长期占位。默认 TTL 30 分钟。</p>
 *
 * <p><b>逐出</b>：条目数达到上限时，优先清除已过期条目；仍超限则按写入顺序
 * 逐出最旧条目，防止用户中途放弃导致的确定性内存泄漏。</p>
 *
 * <p><b>线程安全</b>：基于并发容器与原子计数，可并发读写。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class InMemoryAgentSuspendStore implements AgentSuspendStore {

    /**
     * 默认条目存活时长：30 分钟
     */
    public static final long DEFAULT_TTL_MILLIS = 30L * 60L * 1000L;

    /**
     * 默认条目数上限
     */
    public static final int DEFAULT_MAX_ENTRIES = 256;

    /**
     * 默认清扫周期：60 秒
     */
    private static final long DEFAULT_SWEEP_INTERVAL_MILLIS = 60L * 1000L;

    /**
     * 进程级共享实例（globalDefault 单例）
     */
    static final class GlobalHolder {
        static final InMemoryAgentSuspendStore INSTANCE =
                new InMemoryAgentSuspendStore(DEFAULT_TTL_MILLIS, DEFAULT_MAX_ENTRIES, true);
    }

    /**
     * key → 条目
     */
    private final Map<String, AgentSuspendState> store = new ConcurrentHashMap<>();

    /**
     * 写入顺序队列，逐出时从头取最旧
     */
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();

    /**
     * 有效条目计数
     */
    private final AtomicInteger count = new AtomicInteger(0);

    /**
     * 默认 TTL（条目未显式指定时使用）
     */
    private final long defaultTtlMillis;

    /**
     * 条目数上限
     */
    private final int maxEntries;

    /**
     * 清扫线程（懒启动）
     */
    private final AtomicLong lastSweepAt = new AtomicLong(0);
    private volatile Thread sweeper;

    /**
     * 使用默认 TTL / 上限，不启动守护清扫线程（读取时惰性过期）。
     */
    public InMemoryAgentSuspendStore() {
        this(DEFAULT_TTL_MILLIS, DEFAULT_MAX_ENTRIES, false);
    }

    /**
     * 完整构造。
     *
     * @param defaultTtlMillis 默认 TTL（毫秒），非正数视为不过期
     * @param maxEntries       条目数上限，非正数视为不限
     * @param startSweeper     是否启动守护清扫线程
     */
    public InMemoryAgentSuspendStore(long defaultTtlMillis, int maxEntries, boolean startSweeper) {
        this.defaultTtlMillis = defaultTtlMillis > 0 ? defaultTtlMillis : 0;
        this.maxEntries = maxEntries > 0 ? maxEntries : 0;
        if (startSweeper) {
            startSweeperIfNeeded();
        }
    }

    @Override
    public void put(AgentSuspendState state) {
        if (state == null || state.key() == null) {
            return;
        }
        AgentSuspendState effective = state;
        if (effective.ttlMillis() <= 0 && defaultTtlMillis > 0) {
            effective = new AgentSuspendState(effective.key(), effective.agentId(), effective.payload(),
                    effective.attributes(), effective.createdAt(), defaultTtlMillis);
        }
        AgentSuspendState previous = store.put(effective.key(), effective);
        if (previous == null) {
            count.incrementAndGet();
            insertionOrder.add(effective.key());
        }
        evictIfNeeded();
    }

    @Override
    public AgentSuspendState get(String key) {
        if (key == null) {
            return null;
        }
        AgentSuspendState state = store.get(key);
        if (state == null) {
            return null;
        }
        if (state.isExpired()) {
            remove(key);
            return null;
        }
        return state;
    }

    @Override
    public AgentSuspendState remove(String key) {
        if (key == null) {
            return null;
        }
        AgentSuspendState state = store.remove(key);
        if (state != null) {
            count.decrementAndGet();
            insertionOrder.remove(key);
        }
        return state != null && !state.isExpired() ? state : null;
    }

    @Override
    public int size() {
        return Math.max(count.get(), 0);
    }

    @Override
    public void close() {
        Thread thread = sweeper;
        if (thread != null) {
            thread.interrupt();
            sweeper = null;
        }
        store.clear();
        insertionOrder.clear();
        count.set(0);
    }

    /**
     * 条目超限时逐出：先清过期，再按写入顺序清最旧。
     */
    private void evictIfNeeded() {
        if (maxEntries <= 0 || count.get() <= maxEntries) {
            return;
        }
        // 先清已过期条目
        long now = System.currentTimeMillis();
        for (Map.Entry<String, AgentSuspendState> entry : store.entrySet()) {
            if (count.get() <= maxEntries) {
                break;
            }
            if (entry.getValue().isExpired(now)) {
                remove(entry.getKey());
            }
        }
        // 仍超限则按写入顺序逐出最旧条目
        while (count.get() > maxEntries) {
            String oldest = insertionOrder.poll();
            if (oldest == null) {
                break;
            }
            if (store.remove(oldest) != null) {
                count.decrementAndGet();
            }
        }
    }

    /**
     * 惰性启动守护清扫线程：周期移除过期条目。
     */
    private void startSweeperIfNeeded() {
        if (sweeper != null) {
            return;
        }
        synchronized (this) {
            if (sweeper != null) {
                return;
            }
            Thread thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(DEFAULT_SWEEP_INTERVAL_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    sweepExpired();
                }
            }, "agent-suspend-store-sweeper");
            thread.setDaemon(true);
            sweeper = thread;
            thread.start();
        }
    }

    /**
     * 清扫全部已过期条目。
     */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        lastSweepAt.set(now);
        Iterator<Map.Entry<String, AgentSuspendState>> iterator = store.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AgentSuspendState> entry = iterator.next();
            if (entry.getValue().isExpired(now)) {
                iterator.remove();
                count.decrementAndGet();
                insertionOrder.remove(entry.getKey());
            }
        }
    }
}
