package com.chua.spring.support.aop;

import com.chua.common.support.concurrent.timeout.annotation.Timeout;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.spring.support.proxy.intercept.TimeoutIntercept;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;

import java.lang.reflect.Method;

/**
* {@link Timeout} 注解的 Spring AOP Advisor。
*
* @author CH
* @since 4.0.0.42
 */
public class TimeoutAdvisor extends StaticMethodMatcherPointcutAdvisor {

    /**
    * 超时advisor。
    * @param intercept intercept
    * @author CH
    * @since 4.0.0
    */
    public TimeoutAdvisor(TimeoutIntercept intercept) {
        super(new TimeoutAdvice(intercept));
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return method.isAnnotationPresent(Timeout.class);
    }

    private static class TimeoutAdvice implements MethodInterceptor {
        private final TimeoutIntercept intercept; // intercept

        TimeoutAdvice(TimeoutIntercept intercept) {
            this.intercept = intercept;
        }

        @Override
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            Timeout annotation = method.getAnnotation(Timeout.class);
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
