package com.chua.common.support.concurrent.lock.provider;


import com.chua.common.support.concurrent.lock.AbstractLockProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

/**
 * 基于 Java 内置 ReentrantLock 实现的对象锁提供者。
 * 该类实现了 LockProvider 接口，用于提供线程安全的对象级互斥锁功能。
 * 支持公平锁和非公平锁模式，并可通过名称进行标识。
 *
 * @author CH
 * @since 2023-01-01
 */
@Spi("object")
public class ObjectLockProvider extends AbstractLockProvider {

    /**
     * 底层的可重入锁实例，负责实际的加锁和解锁操作。
     */
    private final java.util.concurrent.locks.ReentrantLock reentrantLock;

    /**
     * 该锁的唯一标识名称，用于区分不同的锁实例。
     */
    private final String name;

    /**
     * 默认构造函数，创建一个名为 "default" 的非公平锁。
     */
    public ObjectLockProvider() {
        this("default", false);
    }

    /**
     * 根据指定名称创建锁的构造函数，默认为非公平锁。
     *
     * @param name 锁的名称标识
     */
    public ObjectLockProvider(String name) {
        this(name, false);
    }

    /**
     * 完整构造函数，允许指定锁的名称和公平性策略。
     *
     * @param name 锁的名称标识
     * @param fair true 表示使用公平锁（按请求顺序获取），false 表示使用非公平锁（可能跳过等待队列直接获取）
     */
    public ObjectLockProvider(String name, boolean fair) {
        this.name = name;
        this.reentrantLock = new java.util.concurrent.locks.ReentrantLock(fair);
    }

    /**
     * 尝试获取锁，如果在规定时间内无法获取则返回 false。
     * 如果在等待过程中被中断，将恢复中断状态并返回 false。
     *
     * @param timeout 等待时间
     * @param timeUnit 时间单位
     * @return 成功获取锁返回 true，超时或中断返回 false
     */
    @Override
    protected boolean doTryLock(int timeout, TimeUnit timeUnit) {
        try {
            return reentrantLock.tryLock(timeout, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    /** Do解锁 */
    protected void doUnlock() {
        reentrantLock.unlock();
    }

    @Override
    /** Do获取Name */
    protected String doGetName() {
        return name;
    }

    @Override
    /** Do获取Type */
    protected String doGetType() {
        return "object";
    }

    /**
     * 创建一个新的 Condition 对象，用于实现更复杂的线程同步逻辑。
     * Condition 允许在锁的基础上进行条件变量的等待和通知操作。
     *
     * @return 新的 Condition 实例
     */
    public Condition newCondition() {
        return reentrantLock.newCondition();
    }
}
