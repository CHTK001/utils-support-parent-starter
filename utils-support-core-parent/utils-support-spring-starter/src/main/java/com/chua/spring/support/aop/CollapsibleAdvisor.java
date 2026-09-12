package com.chua.spring.support.aop;

import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.spring.support.annotation.Collapsible;
import com.chua.spring.support.proxy.intercept.CollapsibleIntercept;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/**
 * {@link Collapsible} 注解的 Spring AOP Advisor。
 *
 * <p>基于 {@link StaticMethodMatcherPointcutAdvisor} 实现，内部复用
 * {@link CollapsibleIntercept} 的折叠逻辑。注解解析兼容代理场景：JDK 动态代理时
 * 方法为接口方法（注解声明在实现类方法上）、CGLIB 时方法可能为代理方法，
 * 均通过目标用户类（{@link ClassUtils#getUserClass(Class)}）还原后查找注解。</p>
 *
 * @author CH
 * @since 2026/09/03
 */
public class CollapsibleAdvisor extends StaticMethodMatcherPointcutAdvisor {

    /**
      * 创建 collapsibleadvisor 实例。
     *
     * @param intercept 折叠拦截器实例
     */
    public CollapsibleAdvisor(CollapsibleIntercept intercept) {
        super(new CollapsibleAdvice(intercept));
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return findCollapsible(method, targetClass) != null;
    }

    /**
     * 解析方法上的折叠注解，兼容接口/代理方法场景。
     *
     * <p>优先直接读取方法注解；缺失时以目标用户类为基准解析最具体方法
     * （接口方法 → 实现类方法、代理方法 → 原方法）后再次查找。</p>
     *
     * @param method      候选方法（可能是接口方法或代理方法）
     * @param targetClass 目标类型（可为代理类，内部还原为用户类），可为 空
     * @return 折叠注解，未标注返回 空
     */
    private static Collapsible findCollapsible(Method method, Class<?> targetClass) {
        Collapsible annotation = method.getAnnotation(Collapsible.class);
        if (annotation != null) {
            return annotation;
        }
        if (targetClass != null) {
            Method mostSpecific = ClassUtils.getMostSpecificMethod(method, ClassUtils.getUserClass(targetClass));
            if (mostSpecific != null && mostSpecific != method) {
                annotation = mostSpecific.getAnnotation(Collapsible.class);
            }
        }
        return annotation;
    }

    /**
     * 折叠通知，将 AOP 调用适配为折叠拦截器的统一入参。
     * @author CH
     * @since 4.0.0
     */
    private static class CollapsibleAdvice implements MethodInterceptor {

        /**
         * 折叠拦截器
         */
        private final CollapsibleIntercept intercept;

        /**
          * collapsibleadvice。
         * @param intercept intercept
         */
        private CollapsibleAdvice(CollapsibleIntercept intercept) {
            this.intercept = intercept;
        }

        @Override
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            Object target = invocation.getThis();
            Collapsible annotation = findCollapsible(method, resolveTargetClass(target));
            if (annotation == null) {
                return invocation.proceed();
            }
            return intercept.intercept(annotation,
                    ProxyMethod.builder()
                            .target(target)
                            .method(method)
                            .args(invocation.getArguments())
                            .build(),
                    (MethodInvocation) invocation::proceed);
        }

        /**
         * 解析代理对象背后的目标用户类。
         *
         * <p>JDK 动态代理与 CGLIB 代理均实现 {@link org.springframework.aop.framework.Advised}，
         * 经 {@code getTargetSource().getTargetClass()} 拿到真实目标类，用于查找实现类方法上的注解。</p>
         *
         * @param target 代理对象（可为 空）
         * @return 目标用户类，无法解析时返回 空
         */
        private static Class<?> resolveTargetClass(Object target) {
            if (target instanceof org.springframework.aop.framework.Advised) {
                return ((org.springframework.aop.framework.Advised) target).getTargetSource().getTargetClass();
            }
            return target != null ? ClassUtils.getUserClass(target.getClass()) : null;
        }
    }
}
