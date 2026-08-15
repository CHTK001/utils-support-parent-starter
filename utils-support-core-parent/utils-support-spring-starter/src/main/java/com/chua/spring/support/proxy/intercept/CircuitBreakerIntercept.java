package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerFlow;
import com.chua.common.support.concurrent.circuitbreaker.annotation.CircuitBreaker;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.StringUtils;

import java.lang.reflect.Method;

/**
 * 熔断拦截器，处理 {@link CircuitBreaker} 注解的方法。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("com.chua.common.support.concurrent.circuitbreaker.annotation.CircuitBreaker")
public class CircuitBreakerIntercept implements MethodAnnotationIntercept<CircuitBreaker> {

    @Override
    public Class<CircuitBreaker> annotationType() {
        return CircuitBreaker.class;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Object intercept(CircuitBreaker annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        CircuitBreakerFlow flow = CircuitBreakerFlow.of(annotation.name())
                .failureThreshold(annotation.failureThreshold())
                .successThreshold(annotation.successThreshold())
                .waitDuration(annotation.waitDuration());

        if (StringUtils.hasText(annotation.fallback())) {
            flow.fallback(() -> resolveFallback(annotation, proxyMethod));
        }

        return flow.execute(() -> {
            try {
                return invocation.proceed();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Object resolveFallback(CircuitBreaker annotation, ProxyMethod proxyMethod) {
        if (!StringUtils.hasText(annotation.fallback())) {
            return null;
        }
        Object target = proxyMethod.getTarget();
        if (target != null) {
            Method fallbackMethod = ClassUtils.findMethod(target.getClass(), annotation.fallback(), proxyMethod.getParameterTypes());
            if (fallbackMethod != null) {
                try {
                    return fallbackMethod.invoke(target, proxyMethod.getArgs());
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}