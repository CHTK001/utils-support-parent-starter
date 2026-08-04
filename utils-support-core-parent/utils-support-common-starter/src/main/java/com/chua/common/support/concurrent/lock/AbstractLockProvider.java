package com.chua.common.support.concurrent.lock;

import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullUnmarked;

/**
 * 锁提供者抽象基类
 *
 * <p>提供通用锁方法的默认实现，子类只需实现核心锁逻辑：
 * {@link #doTryLock(int, TimeUnit)}、{@link #doUnlock()}、
 * {@link #doGetName()}、{@link #doGetType()}。
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public abstract class AbstractLockProvider implements LockProvider {

    @Override
    public boolean tryLock(int timeout, TimeUnit timeUnit) {
        return doTryLock(timeout, timeUnit);
    }

    @Override
    public boolean tryLock(int timeout) {
        return doTryLock(timeout, TimeUnit.MILLISECONDS);
    }

    @Override
    public void unlock() {
        doUnlock();
    }

    @Override
    public String getName() {
        return doGetName();
    }

    @Override
    public String getType() {
        return doGetType();
    }

    @Override
    public void close() throws Exception {
        doUnlock();
    }

    protected abstract boolean doTryLock(int timeout, TimeUnit timeUnit);
    protected abstract void doUnlock();
    protected abstract String doGetName();
    protected abstract String doGetType();
}
