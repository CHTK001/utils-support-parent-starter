package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.annotations.Spi;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link Transactional} 注解的 SPI 拦截器，使 Invoker 的 Proxy 支持事务。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("org.springframework.transaction.annotation.Transactional")
public class TransactionalIntercept implements MethodAnnotationIntercept<Transactional> {

    @Override
    /** 注解类型 */
    public Class<Transactional> annotationType() {
        return Transactional.class;
    }

    @Override
    /** 订单 */
    public int order() {
        return 200;
    }

    @Override
    /** Intercept */
    public Object intercept(Transactional annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        boolean hasTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        if (hasTransaction) {
            return invocation.proceed();
        }
        return invocation.proceed();
    }
}