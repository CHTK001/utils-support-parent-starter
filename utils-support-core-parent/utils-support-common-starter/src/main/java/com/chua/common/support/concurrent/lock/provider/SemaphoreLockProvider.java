package com.chua.common.support.concurrent.lock.provider;

import com.chua.common.support.concurrent.lock.AbstractLockProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Spi("semaphore")
public class SemaphoreLockProvider extends AbstractLockProvider {

    /**
     * 名称
     */
    private final String name;
    /**
     * 信号量
    */
    private final Semaphore semaphore;

    /**
     * 创建 SemaphoreLockProvider 实例
    */
    public SemaphoreLockProvider() {
        this(false);
    }

    /**
     * 创建 SemaphoreLockProvider 实例
     * @param fair fair
     */
    public SemaphoreLockProvider(boolean fair) {
        this("default", 1, fair);
    }

    /**
     * 创建 SemaphoreLockProvider 实例
     * @param name name
     */
    public SemaphoreLockProvider(String name) {
        this(name, 1);
    }

    /**
     * 创建 SemaphoreLockProvider 实例
     * @param name name
     * @param fair boolean
     */
    public SemaphoreLockProvider(String name, boolean fair) {
        this(name, 1, fair);
    }

    /**
     * 创建 SemaphoreLockProvider 实例
     * @param name name
     * @param permits int
     */
    public SemaphoreLockProvider(String name, int permits) {
        this(name, permits, false);
    }

    /**
     * 创建 SemaphoreLockProvider 实例
     * @param name name
     * @param permits int
     * @param fair boolean
     */
    public SemaphoreLockProvider(String name, int permits, boolean fair) {
        this.name = name;
        this.semaphore = new Semaphore(permits, fair);
    }

    @Override
    /**
     * DoTry锁
    */
    protected boolean doTryLock(int timeout, TimeUnit timeUnit) {
        try {
            return semaphore.tryAcquire(timeout, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    /**
     * Do解锁
    */
    protected void doUnlock() {
        semaphore.release();
    }

    @Override
    /**
     * Do获取Name
    */
    protected String doGetName() {
        return name;
    }

    @Override
    /**
     * Do获取Type
    */
    protected String doGetType() {
        return "semaphore";
    }
}
