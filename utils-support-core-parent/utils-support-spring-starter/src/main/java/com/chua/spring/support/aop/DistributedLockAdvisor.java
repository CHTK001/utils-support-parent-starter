package com.chua.spring.support.aop;

import com.chua.common.support.concurrent.lock.annotation.DistributedLock;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.spring.support.proxy.intercept.DistributedLockIntercept;
import lombok.AllArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;

import java.lang.reflect.Method;

/**
* {@link DistributedLock} 注解的 Spring AOP Advisor。
* <p>
* 基于 {@link StaticMethodMatcherPointcutAdvisor} 实现，
* 内部复用 {@link DistributedLockIntercept} 的拦截逻辑。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class DistributedLockAdvisor extends StaticMethodMatcherPointcutAdvisor {

    /**
    * 构造函数。
    *
    * @param intercept 分布式锁拦截器实例
     */
    public DistributedLockAdvisor(DistributedLockIntercept intercept) {
        super(new DistributedLockAdvice(intercept));
    }

    /**
    * 判断方法是否匹配目标类上的 {@link DistributedLock} 注解。
    *
    * @param method      待检查的方法对象
    * @param targetClass 目标类的类型信息
    * @return 如果方法包含 {@link DistributedLock} 注解则返回 true，否则返回 false
     */
    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return method.isAnnotationPresent(DistributedLock.class);
    }

    /**
    * {@link DistributedLock} 注解的 Spring AOP Advice 实现类。
    * @author CH
    * @since 4.0.0
     */
    private static class DistributedLockAdvice implements MethodInterceptor {

        /**
        * 分布式锁拦截器实例，用于执行具体的锁定逻辑。
         */
        private final DistributedLockIntercept intercept;

        /**
        * 构造方法。
        *
        * @param intercept 分布式锁拦截器实例
         */
        DistributedLockAdvice(DistributedLockIntercept intercept) {
            this.intercept = intercept;
        }

        /**
        * 方法拦截入口。
        * <p>
        * 获取当前方法的 {@link DistributedLock} 注解，
        * 若存在则调用拦截器处理，否则直接执行原方法。
        * </p>
        *
        * @param invocation AOP 方法调用上下文
        * @return 方法执行结果
        * @throws Throwable 执行过程中可能抛出的异常
         */
        @Override
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            DistributedLock annotation = method.getAnnotation(DistributedLock.class);

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