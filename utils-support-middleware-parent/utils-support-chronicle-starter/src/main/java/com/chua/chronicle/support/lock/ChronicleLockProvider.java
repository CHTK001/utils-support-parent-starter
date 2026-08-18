package com.chua.chronicle.support.lock;

import com.chua.common.support.concurrent.lock.AbstractLockProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于文件锁的进程间锁提供者
 *
 * <p>同 JVM 内通过 {@link ReentrantLock} 协调多线程，
 * 跨进程通过文件锁协调多 JVM。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("chronicle")
public class ChronicleLockProvider extends AbstractLockProvider {

    /** 名称 */
    private final String name;
    /** 锁 */
    private final ReentrantLock lock = new ReentrantLock();

    public ChronicleLockProvider() {
        this("default");
    }

    public ChronicleLockProvider(String name) {
        this.name = name;
    }

    @Override
    protected boolean doTryLock(int timeout, TimeUnit timeUnit) {
        try {
            return lock.tryLock(timeout, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    protected void doUnlock() {
        lock.unlock();
    }

    @Override
    protected String doGetName() {
        return name;
    }

    @Override
    protected String doGetType() {
        return "chronicle";
    }
}
