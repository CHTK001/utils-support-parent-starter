package com.chua.common.support.concurrent.lock.provider;

import com.chua.common.support.concurrent.lock.AbstractLockProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 基于固定容量环状缓冲区的锁提供者。
 *
 * <p>本质是把"互斥锁"放宽为"并发计数锁（Semaphore 语义）"，
 * 用 {@code size} 个许可（等价于环形缓冲区的 N 个槽位）实现固定上限的并发准入控制，
 * 同一时刻最多 {@code size} 个线程同时持锁，超出许可时按等待队列公平阻塞。
 * 适用于限流、连接池式准入、固定并发度控制等场景。</p>
 *
 * <p>与 {@link ObjectLockProvider} 的区别：object 锁同一时刻仅 1 个线程持锁（互斥），
 * 环状锁同一时刻最多 size 个线程持锁（并发计数）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 * @see com.chua.common.support.concurrent.lock.LockProvider
 * @see com.chua.common.support.concurrent.lock.LockFlow
 */
@Spi("circular")
public class CircularLockProvider extends AbstractLockProvider {

    /**
     * 环状缓冲区容量（并发槽位数 / 许可数）
     */
    private final int size;

    /**
     * 并发令牌桶：size 个许可，公平模式（FIFO 等待队列）
     */
    private final Semaphore semaphore;

    /**
     * 锁名称
     */
    private final String name;

    /**
     * 构造方法。
     *
     * @param name 锁名称
     * @param size 并发槽位数量（许可数），必须大于 0
     * @throws IllegalArgumentException 当 size 小于等于 0 时
     */
    public CircularLockProvider(String name, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size 必须大于 0，当前: " + size);
        }
        this.name = name;
        this.size = size;
        this.semaphore = new Semaphore(size, true);
    }

    /**
     * 默认构造，容量 1（等价于互斥锁）。
     *
     * @param name 锁名称
     */
    public CircularLockProvider(String name) {
        this(name, 1);
    }

    @Override
    protected boolean doTryLock(int timeout, TimeUnit timeUnit) {
        if (timeout <= 0) {
            return semaphore.tryAcquire();
        }
        try {
            return semaphore.tryAcquire(timeout, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    protected void doUnlock() {
        semaphore.release();
    }

    @Override
    protected String doGetName() {
        return name;
    }

    @Override
    protected String doGetType() {
        return "circular";
    }

    /**
     * 获取当前并发槽位数量（许可数）。
     *
     * @return 槽位数量
     */
    public int size() {
        return size;
    }

    /**
     * 获取当前可用许可数（空闲槽位）。
     *
     * @return 可用许可数
     */
    public int available() {
        return semaphore.availablePermits();
    }
}
