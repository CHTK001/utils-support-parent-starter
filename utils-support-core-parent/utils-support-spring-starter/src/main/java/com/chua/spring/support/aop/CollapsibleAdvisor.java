package com.chua.spring.support.aop;

import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.spring.support.annotation.Collapsible;
import com.chua.spring.support.proxy.intercept.CollapsibleIntercept;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;

import java.lang.reflect.Method;

/**
 * {@link Collapsible} 注解的 Spring AOP Advisor。
 *
 * <p>基于 {@link StaticMethodMatcherPointcutAdvisor} 实现，内部复用
 * {@link CollapsibleIntercept} 的折叠逻辑，将并发相同调用合并为一次执行。</p>
 *
 * @author CH
 * @since 2026/09/03
 */
public class CollapsibleAdvisor extends StaticMethodMatcherPointcutAdvisor {

    /**
     * 创建 CollapsibleAdvisor 实例。
     *
     * @param intercept 折叠拦截器实例
     */
    public CollapsibleAdvisor(CollapsibleIntercept intercept) {
        super(new CollapsibleAdvice(intercept));
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return method.isAnnotationPresent(Collapsible.class);
    }

    /**
     * 折叠通知，将 AOP 调用适配为折叠拦截器的统一入参。
     */
    private static class CollapsibleAdvice implements MethodInterceptor {

        /**
         * 折叠拦截器
         */
        private final CollapsibleIntercept intercept;

        private CollapsibleAdvice(CollapsibleIntercept intercept) {
            this.intercept = intercept;
        }

        @Override
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            Collapsible annotation = method.getAnnotation(Collapsible.class);
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
