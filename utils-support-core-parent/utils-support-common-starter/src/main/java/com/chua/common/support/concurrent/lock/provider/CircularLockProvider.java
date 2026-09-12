package com.chua.common.support.concurrent.lock.provider;

import com.chua.common.support.concurrent.lock.AbstractLockProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于固定容量环状缓冲区的锁提供者。
 *
 * <p>用定长数组 + 头尾双指针维护 N 个并发槽位（环形缓冲区），
 * 同一时刻最多 {@code size} 个线程同时持锁，超出槽位时阻塞等待空槽。
 * 本质是把"互斥锁"放宽为"并发计数锁（Semaphore 语义）"，
 * 用环状缓冲实现固定上限的并发准入控制，适用于限流、连接池式准入等场景。</p>
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
     * 环状缓冲区容量（并发槽位数）
     */
    private final int size;

    /**
     * 每个槽位的占用标志
     */
    private final AtomicBoolean[] holders;

    /**
     * 写指针：下一个尝试分配的槽位
     */
    private final AtomicReference<Integer> writeIndex = new AtomicReference<>(0);

    /**
     * 读指针：环形遍历位置
     */
    private final AtomicReference<Integer> readIndex = new AtomicReference<>(0);

    /**
     * 锁名称
     */
    private final String name;

    /**
     * 构造方法。
     *
     * @param name 锁名称
     * @param size 并发槽位数量，必须大于 0
     * @throws IllegalArgumentException 当 size 小于等于 0 时
     */
    public CircularLockProvider(String name, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size 必须大于 0，当前: " + size);
        }
        this.name = name;
        this.size = size;
        this.holders = new AtomicBoolean[size];
        for (int i = 0; i < size; i++) {
            holders[i] = new AtomicBoolean(false);
        }
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
        long deadline = System.currentTimeMillis() + toMillis(timeout, timeUnit);
        while (System.currentTimeMillis() < deadline) {
            if (acquire()) {
                return true;
            }
            sleepQuietly(1L);
        }
        return acquire();
    }

    @Override
    protected void doUnlock() {
        int idx = readIndex.getAndUpdate(i -> (i + 1) % size);
        holders[idx].set(false);
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
     * 获取当前并发槽位数量。
     *
     * @return 槽位数量
     */
    public int size() {
        return size;
    }

    /**
     * 环形分配一个空闲槽位（CAS 自旋，最多环绕一圈）。
     *
     * @return 成功占位返回 true，无空闲槽位返回 false
     */
    private boolean acquire() {
        int start = writeIndex.get();
        for (int step = 0; step < size; step++) {
            int idx = (start + step) % size;
            if (holders[idx].compareAndSet(false, true)) {
                writeIndex.compareAndSet(start, (idx + 1) % size);
                return true;
            }
        }
        return false;
    }

    /**
     * 将超时时间换算为毫秒。
     *
     * @param timeout 超时数值
     * @param timeUnit 时间单位
     * @return 毫秒数
     */
    private long toMillis(int timeout, TimeUnit timeUnit) {
        if (timeout <= 0) {
            return 0L;
        }
        return timeUnit.toMillis(timeout);
    }

    /**
     * 短暂休眠，被中断时恢复中断标志。
     *
     * @param millis 毫秒
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
