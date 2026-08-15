package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.bulkhead.BulkheadFlow;
import com.chua.common.support.concurrent.bulkhead.annotation.Bulkhead;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.AbstractMethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.StringUtils;

import java.lang.reflect.Method;

/**
 * 并发隔离拦截器，处理 {@link Bulkhead} 注解的方法。
 *
 * <p>通过 {@link MethodAnnotationIntercept} SPI 机制被 Invoker 的 Proxy 自动发现。
 * 读取注解属性构建 {@link BulkheadFlow}，在并发隔离保护下执行目标方法，
 * 并发数达上限时自动降级。</p>
 *
 * <p>属性解析链与通用规则见 {@link AbstractMethodAnnotationIntercept}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("com.chua.common.support.concurrent.bulkhead.annotation.Bulkhead")
public class BulkheadIntercept extends AbstractMethodAnnotationIntercept implements MethodAnnotationIntercept<Bulkhead> {

    @Override
    public Class<Bulkhead> annotationType() {
        return Bulkhead.class;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Object intercept(Bulkhead annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        // 解析隔离名称（支持 SpEL），未填使用 类名.方法名
        String name = resolveName(annotation.name(), proxyMethod);

        // 解析最大并发数（支持 SpEL 和占位符），解析失败时使用默认值
        int maxConcurrent = resolveInt(annotation.maxConcurrent(), 10, proxyMethod);
        boolean fair = annotation.fair();

        // 构建并发隔离门面
        BulkheadFlow flow = BulkheadFlow.of(name)
                .maxConcurrent(maxConcurrent)
                .fair(fair);
        if (StringUtils.hasText(annotation.fallback())) {
            flow.fallback(() -> resolveFallback(annotation, proxyMethod));
        }

        // 在并发隔离保护下执行目标方法
        return flow.execute(() -> {
            try {
                return invocation.proceed();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 调用注解指定的回退方法。
     *
     * @param annotation  隔离注解
     * @param proxyMethod 被拦截的方法信息
     * @return 回退方法的返回值，找不到时返回 null
     */
    private Object resolveFallback(Bulkhead annotation, ProxyMethod proxyMethod) {
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