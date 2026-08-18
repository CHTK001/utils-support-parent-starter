package com.chua.spring.support.aop;

import com.chua.common.support.concurrent.rate.annotation.RateLimiter;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.spring.support.proxy.intercept.RateLimiterIntercept;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;

import java.lang.reflect.Method;

/**
 * {@link RateLimiter} 注解的 Spring AOP Advisor。
 *
 * <p>基于 {@link StaticMethodMatcherPointcutAdvisor} 实现，
 * 内部复用 {@link RateLimiterIntercept} 的拦截逻辑。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@RequiredArgsConstructor
public class RateLimiterAdvisor extends StaticMethodMatcherPointcutAdvisor {

    public RateLimiterAdvisor(RateLimiterIntercept intercept) {
        super(new RateLimiterAdvice(intercept));
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return method.isAnnotationPresent(RateLimiter.class);
    }

    @RequiredArgsConstructor
    private static class RateLimiterAdvice implements MethodInterceptor {

        /** Intercept */
        private final RateLimiterIntercept intercept;

        @Override
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            RateLimiter annotation = method.getAnnotation(RateLimiter.class);
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