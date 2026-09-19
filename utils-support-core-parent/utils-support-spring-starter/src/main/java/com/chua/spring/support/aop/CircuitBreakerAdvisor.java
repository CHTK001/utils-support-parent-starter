package com.chua.spring.support.aop;

import com.chua.common.support.concurrent.circuitbreaker.annotation.CircuitBreaker;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.spring.support.proxy.intercept.CircuitBreakerIntercept;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;

import java.lang.reflect.Method;

/**
 * {@link CircuitBreaker} 注解的 Spring AOP Advisor。
 *
 * @author CH
 * @since 4.0.0.42
 */
@RequiredArgsConstructor
public class CircuitBreakerAdvisor extends StaticMethodMatcherPointcutAdvisor {

    /**
     * 创建 熔断中断advisor 实例
     * @param intercept intercept
     */
    public CircuitBreakerAdvisor(CircuitBreakerIntercept intercept) {
        super(new CircuitBreakerAdvice(intercept));
    }

    @Override
    /**
     * Matches
     *
     * @param method 方法
     * @param targetClass 目标类
     * @return 匹配的结果
     * @author CH
     * @since 4.0.0
     */
    public boolean matches(Method method, Class<?> targetClass) {
        return method.isAnnotationPresent(CircuitBreaker.class);
    }

    @RequiredArgsConstructor
    private static class CircuitBreakerAdvice implements MethodInterceptor {

        /** Intercept */
        private final CircuitBreakerIntercept intercept;

        @Override
        /** 调用 */
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            CircuitBreaker annotation = method.getAnnotation(CircuitBreaker.class);
            if (annotation == null) {
                return invocation.proceed();
            }
            return intercept.intercept(annotation,
                    ProxyMethod.builder()
                            .target(invocation.getThis())
                            .method(method)
                            .args(invocation.getArguments())
                            .build(),
                    (MethodInvocation) invocation::proceed);
        }
    }
}
