package com.chua.spring.support.aop;

import com.chua.common.support.concurrent.bulkhead.annotation.Bulkhead;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.spring.support.proxy.intercept.BulkheadIntercept;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;

import java.lang.reflect.Method;

/**
* {@link Bulkhead} 注解的 Spring AOP Advisor。
*
* @author CH
* @since 4.0.0.42
 */
public class BulkheadAdvisor extends StaticMethodMatcherPointcutAdvisor {

    /**
    * 隔离advisor。
    * @param intercept intercept
    * @author CH
    * @since 4.0.0
     */
    public BulkheadAdvisor(BulkheadIntercept intercept) {
        super(new BulkheadAdvice(intercept));
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return method.isAnnotationPresent(Bulkhead.class);
    }

    private static class BulkheadAdvice implements MethodInterceptor {
        private final BulkheadIntercept intercept; // intercept

        BulkheadAdvice(BulkheadIntercept intercept) {
            this.intercept = intercept;
        }

        @Override
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            Bulkhead annotation = method.getAnnotation(Bulkhead.class);
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