package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.lock.LockFlow;
import com.chua.common.support.concurrent.lock.annotation.DistributedLock;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 分布式锁拦截器，处理 {@link DistributedLock} 注解的方法。
 *
 * <p>基于 {@link LockFlow} 门面，通过链式 API 获取锁。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@Spi("com.chua.common.support.concurrent.lock.annotation.DistributedLock")
public class DistributedLockIntercept implements MethodAnnotationIntercept<DistributedLock> {

    @Override
    /**
     * 注解类型
    */
    public Class<DistributedLock> annotationType() {
        return DistributedLock.class;
    }

    @Override
    /**
     * 订单
    */
    public int order() {
        return 100;
    }

    @Override
    /**
     * Intercept
    */
    public Object intercept(DistributedLock annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        return LockFlow.of(annotation.name())
                .lockType(annotation.lockType())
                .fair(annotation.fair())
                .waitTime(annotation.waitTime())
                .leaseTime(annotation.leaseTime())
                .execute(() -> {
                    try {
                        return invocation.proceed();
                    } catch (Throwable ex) {
                        throw new Exception(ex);
                    }
                });
    }
}