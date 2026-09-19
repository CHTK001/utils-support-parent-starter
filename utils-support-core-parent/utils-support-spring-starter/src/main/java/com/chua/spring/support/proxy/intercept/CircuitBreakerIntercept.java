package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerFlow;
import com.chua.common.support.concurrent.circuitbreaker.annotation.CircuitBreaker;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.AbstractMethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.Method;

/**
 * 熔断拦截器，处理带有 {@link CircuitBreaker} 注解的方法。
 *
 * <p>通过 {@link MethodAnnotationIntercept} SPI 机制被 Invoker 的 Proxy 自动发现。
 * 方法上标注 {@code @CircuitBreaker} 时，读取注解属性，构建 {@link CircuitBreakerFlow}
 * 门面，并在熔断保护下执行目标方法。熔断打开时调用回退方法降级。</p>
 *
 * <p>属性解析链与通用规则见 {@link AbstractMethodAnnotationIntercept}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("com.chua.common.support.concurrent.circuitbreaker.annotation.CircuitBreaker")
public class CircuitBreakerIntercept extends AbstractMethodAnnotationIntercept implements MethodAnnotationIntercept<CircuitBreaker> {

    @Override
    /** 注解类型 */
    public Class<CircuitBreaker> annotationType() {
        return CircuitBreaker.class;
    }

    @Override
    /** 订单 */
    public int order() {
        return 100;
    }

    @Override
    /** Intercept */
    public Object intercept(CircuitBreaker annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
 // 解析熔断器名称：优先使用注解 名称（支持 spel），未填则使用 类名.方法名
        String name = resolveName(annotation.name(), proxyMethod);

 // 解析各阈值属性（支持 spel 和占位符），解析失败时使用默认值
        int failureThreshold = resolveInt(annotation.failureThreshold(), 5, proxyMethod);
        int successThreshold = resolveInt(annotation.successThreshold(), 2, proxyMethod);
        long waitDuration = resolveLong(annotation.waitDuration(), annotation.recoveryTime(), 60000, proxyMethod);

        // 构建熔断门面，配置失败阈值、成功阈值和恢复时间
        CircuitBreakerFlow flow = CircuitBreakerFlow.of(name)
                .failureThreshold(failureThreshold)
                .successThreshold(successThreshold)
                .waitDuration(waitDuration);

        // 注解指定了回退方法时，熔断打开后调用回退方法降级
        if (StringUtils.hasText(annotation.fallback())) {
            flow.fallback(() -> resolveFallback(annotation, proxyMethod));
        }

        // 在熔断保护下执行目标方法，失败自动累加，成功自动重置
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
     * <p>回退方法必须与目标方法位于同一类中，且参数签名完全一致。
     * 通过反射定位回退方法并执行，返回降级结果。</p>
     *
     * @param annotation  熔断注解
     * @param proxyMethod 被拦截的方法信息
     * @return 回退方法的返回值，找不到回退方法时返回 空
     */
    private Object resolveFallback(CircuitBreaker annotation, ProxyMethod proxyMethod) {
        return FallbackResolver.resolve(annotation.fallback(), proxyMethod);
    }
}
