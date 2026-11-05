// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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

    /**
     * 注解类型
    */
    @Override
    public Class<Transactional> annotationType() {
        return Transactional.class;
    }

    /**
     * 订单
    */
    @Override
    public int order() {
        return 200;
    }

    /**
     * Intercept
    */
    @Override
    public Object intercept(Transactional annotation, ProxyMethod proxyMethod,
                            MethodInvocation invocation) throws Throwable {
        boolean hasTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        if (hasTransaction) {
            return invocation.proceed();
        }
        return invocation.proceed();
    }}