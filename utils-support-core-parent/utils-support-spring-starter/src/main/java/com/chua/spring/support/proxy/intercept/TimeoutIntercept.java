package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.timeout.TimeoutFlow;
import com.chua.common.support.concurrent.timeout.annotation.Timeout;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.AbstractMethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.Method;
import java.util.concurrent.TimeoutException;

/**
 * 超时拦截器，处理 {@link Timeout} 注解的方法。
 *
 * <p>通过 {@link MethodAnnotationIntercept} SPI 机制被 Invoker 的 Proxy 自动发现。
 * 读取注解属性构建 {@link TimeoutFlow}，在超时保护下执行目标方法，超时自动中断并降级。</p>
 *
 * <p>属性解析链与通用规则见 {@link AbstractMethodAnnotationIntercept}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("com.chua.common.support.concurrent.timeout.annotation.Timeout")
public class TimeoutIntercept extends AbstractMethodAnnotationIntercept implements MethodAnnotationIntercept<Timeout> {

    @Override
    /** AnnotationType */
    public Class<Timeout> annotationType() {
        return Timeout.class;
    }

    @Override
    /** Order */
    public int order() {
        return 100;
    }

    @Override
    /** Intercept */
    public Object intercept(Timeout annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        // 解析超时名称（支持 SpEL），未填使用 类名.方法名
        String name = resolveName(annotation.name(), proxyMethod);

        // 解析超时时间（支持 SpEL 和占位符），解析失败时使用默认值
        long timeoutMillis = resolveLong(annotation.timeout(), 3000, proxyMethod);

        // 构建超时门面
        TimeoutFlow flow = TimeoutFlow.of(name).timeout(timeoutMillis);
        if (StringUtils.hasText(annotation.fallback())) {
            flow.fallback(() -> resolveFallback(annotation, proxyMethod));
        }

        // 在超时保护下执行目标方法
        try {
            return flow.execute(() -> {
                try {
                    return invocation.proceed();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (TimeoutException te) {
            // 超时发生，配置了回退方法则返回降级结果
            if (StringUtils.hasText(annotation.fallback())) {
                return resolveFallback(annotation, proxyMethod);
            }
            throw te;
        }
    }

    /**
     * 调用注解指定的回退方法。
     *
     * @param annotation  超时注解
     * @param proxyMethod 被拦截的方法信息
     * @return 回退方法的返回值，找不到时返回 null
     */
    private Object resolveFallback(Timeout annotation, ProxyMethod proxyMethod) {
        return FallbackResolver.resolve(annotation.fallback(), proxyMethod);
    }
}