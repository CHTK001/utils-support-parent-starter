package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.rate.RateLimiterFlow;
import com.chua.common.support.concurrent.rate.annotation.RateLimiter;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.AbstractMethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 限流拦截器，处理 {@link RateLimiter} 注解的方法。
 *
 * <p>通过 {@link MethodAnnotationIntercept} SPI 机制被 Invoker 的 Proxy 自动发现。
 * 基于 {@link RateLimiterFlow} 门面，通过链式 API 获取许可，降级回调交给
 * {@link RateLimiterFlow#fallback} 处理。</p>
 *
 * <p>属性解析链与通用规则见 {@link AbstractMethodAnnotationIntercept}。</p>
 *
 * @author CH
 */
@Spi("com.chua.common.support.concurrent.rate.annotation.RateLimiter")
public class RateLimiterIntercept extends AbstractMethodAnnotationIntercept implements MethodAnnotationIntercept<RateLimiter> {

    @Override
    /** AnnotationType */
    public Class<RateLimiter> annotationType() {
        return RateLimiter.class;
    }

    @Override
    /** Order */
    public int order() {
        return 100;
    }

    @Override
    /** Intercept */
    public Object intercept(RateLimiter annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        // 解析限流器名称（支持 SpEL），未填使用 类名.方法名
        String name = resolveName(annotation.name(), proxyMethod);

        // 解析数值属性（支持 SpEL 和占位符），解析失败时使用默认值
        double permitsPerSecond = resolveDouble(annotation.permitsPerSecond(), 1, proxyMethod);
        long warmupPeriod = resolveLong(annotation.warmupPeriod(), 0, proxyMethod);
        long waitTime = resolveLong(annotation.waitTime(), 0, proxyMethod);

        // 构建限流门面
        RateLimiterFlow flow = RateLimiterFlow.of(name, permitsPerSecond);
        if (warmupPeriod > 0) {
            flow.warmup(warmupPeriod);
        }
        if (waitTime > 0) {
            flow.timeout(waitTime, TimeUnit.MILLISECONDS);
        }
        if (StringUtils.hasText(annotation.fallback())) {
            flow.fallback(() -> resolveFallback(annotation, proxyMethod));
        }

        // 尝试获取许可，成功放行，失败降级
        if (flow.tryAcquire()) {
            return invocation.proceed();
        }
        return onRejected(annotation, proxyMethod);
    }

    /**
     * 限流拒绝处理：优先返回回退结果，无回退时抛出限流异常。
     *
     * @param annotation  限流注解
     * @param proxyMethod 被拦截的方法信息
     * @return 回退方法的返回值
     * @throws IllegalStateException 无回退方法时抛出
     */
    private Object onRejected(RateLimiter annotation, ProxyMethod proxyMethod) throws Throwable {
        Object fallbackResult = resolveFallback(annotation, proxyMethod);
        if (fallbackResult != null) {
            return fallbackResult;
        }
        throw new IllegalStateException("限流拒绝：" + annotation.name());
    }

    /**
     * 调用注解指定的回退方法。
     *
     * @param annotation  限流注解
     * @param proxyMethod 被拦截的方法信息
     * @return 回退方法的返回值，找不到时返回 null
     */
    private Object resolveFallback(RateLimiter annotation, ProxyMethod proxyMethod) {
        return FallbackResolver.resolve(annotation.fallback(), proxyMethod);
    }
}