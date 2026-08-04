package com.chua.common.support.concurrent.lock.provider;

import com.chua.common.support.concurrent.lock.AbstractLockProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullUnmarked;

/**
 * @author CH
 */
@NullUnmarked
@Spi("semaphore")
public class SemaphoreLockProvider extends AbstractLockProvider {

    /**
     * 名称
     */
    private final String name;
    private final Semaphore semaphore;

    public SemaphoreLockProvider() {
        this(false);
    }

    public SemaphoreLockProvider(boolean fair) {
        this("default", 1, fair);
    }

    public SemaphoreLockProvider(String name) {
        this(name, 1);
    }

    public SemaphoreLockProvider(String name, boolean fair) {
        this(name, 1, fair);
    }

    public SemaphoreLockProvider(String name, int permits) {
        this(name, permits, false);
    }

    public SemaphoreLockProvider(String name, int permits, boolean fair) {
        this.name = name;
        this.semaphore = new Semaphore(permits, fair);
    }

    @Override
    protected boolean doTryLock(int timeout, TimeUnit timeUnit) {
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
        return "semaphore";
    }
}
