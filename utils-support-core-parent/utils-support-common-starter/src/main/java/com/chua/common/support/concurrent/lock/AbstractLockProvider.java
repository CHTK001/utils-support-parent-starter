package com.chua.common.support.concurrent.lock;

import java.util.concurrent.TimeUnit;

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
public abstract class AbstractLockProvider implements LockProvider {

    @Override
    /** Try锁 */
    public boolean tryLock(int timeout, TimeUnit timeUnit) {
        return doTryLock(timeout, timeUnit);
    }

    @Override
    /** Try锁 */
    public boolean tryLock(int timeout) {
        return doTryLock(timeout, TimeUnit.MILLISECONDS);
    }

    @Override
    /** 解锁 */
    public void unlock() {
        doUnlock();
    }

    @Override
    /** 获取Name */
    public String getName() {
        return doGetName();
    }

    @Override
    /** 获取Type */
    public String getType() {
        return doGetType();
    }

    @Override
    /** 关闭 */
    public void close() throws Exception {
        doUnlock();
    }

    /** DoTry锁 */
    protected abstract boolean doTryLock(int timeout, TimeUnit timeUnit);
    /** Do解锁 */
    protected abstract void doUnlock();
    /** Do获取Name */
    protected abstract String doGetName();
    /** Do获取Type */
    protected abstract String doGetType();
}
