// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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

    /**
     * 名称
    */
    private final String name;
    /**
     * 锁
    */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 创建 chronicle锁提供者 实例
    */
    public ChronicleLockProvider() {
        this("default");
    }

    /**
     * 创建 chronicle锁提供者 实例
     * @param name 名称
     */
    public ChronicleLockProvider(String name) {
        this.name = name;
    }

    /**
     * 执行尝试锁
    */
    @Override
    protected boolean doTryLock(int timeout, TimeUnit timeUnit) {
        try {
            return lock.tryLock(timeout, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 执行解锁
    */
    @Override
    protected void doUnlock() {
        lock.unlock();
    }

    /**
     * 执行获取名称
    */
    @Override
    protected String doGetName() {
        return name;
    }

    /**
     * 执行获取类型
    */
    @Override
    protected String doGetType() {
        return "chronicle";
    }
}
