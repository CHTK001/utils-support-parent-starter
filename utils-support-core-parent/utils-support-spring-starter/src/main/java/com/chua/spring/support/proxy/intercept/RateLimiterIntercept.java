package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.rate.RateLimiterFlow;
import com.chua.common.support.concurrent.rate.annotation.RateLimiter;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.spring.support.configuration.SpringBeanUtils;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 限流拦截器，处理 {@link RateLimiter} 注解的方法。
 *
 * <p>基于 {@link RateLimiterFlow} 门面，通过链式 API 获取许可，
 * 降级回调统一交给 {@link RateLimiterFlow#fallback} 处理。</p>
 *
 * @author CH
 */
@Spi("com.chua.common.support.concurrent.rate.annotation.RateLimiter")
public class RateLimiterIntercept implements MethodAnnotationIntercept<RateLimiter> {

    @Override
    public Class<RateLimiter> annotationType() {
        return RateLimiter.class;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Object intercept(RateLimiter annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        RateLimiterFlow flow = RateLimiterFlow.of(annotation.name(), annotation.permitsPerSecond());
        if (annotation.warmupPeriod() > 0) {
            flow.warmup(annotation.warmupPeriod());
        }
        if (annotation.waitTime() > 0) {
            flow.timeout(annotation.waitTime(), TimeUnit.MILLISECONDS);
        }
        if (StringUtils.hasText(annotation.fallback())) {
            flow.fallback(() -> resolveFallback(annotation, proxyMethod));
        }
        if (flow.tryAcquire()) {
            return invocation.proceed();
        }
        return onRejected(annotation, proxyMethod);
    }

    private Object onRejected(RateLimiter annotation, ProxyMethod proxyMethod) throws Throwable {
        Object fallbackResult = resolveFallback(annotation, proxyMethod);
        if (fallbackResult != null) {
            return fallbackResult;
        }
        throw new IllegalStateException("限流拒绝：" + annotation.name());
    }

    private Object resolveFallback(RateLimiter annotation, ProxyMethod proxyMethod) {
        if (!StringUtils.hasText(annotation.fallback())) {
            return null;
        }
        ApplicationContext ctx = SpringBeanUtils.getApplicationContextOrNull();
        if (ctx != null) {
            try {
                Object bean = ctx.getBean(annotation.fallback());
                Method method = bean.getClass().getMethod(annotation.fallback(), proxyMethod.getParameterTypes());
                return method.invoke(bean, proxyMethod.getArgs());
            } catch (Exception ignored) {
            }
        }
        Object target = proxyMethod.getTarget();
        if (target != null) {
            Method fallbackMethod = ClassUtils.findMethod(target.getClass(), annotation.fallback(), proxyMethod.getParameterTypes());
            if (fallbackMethod != null) {
                try {
                    return ClassUtils.invokeMethod(fallbackMethod, target, proxyMethod.getArgs());
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}