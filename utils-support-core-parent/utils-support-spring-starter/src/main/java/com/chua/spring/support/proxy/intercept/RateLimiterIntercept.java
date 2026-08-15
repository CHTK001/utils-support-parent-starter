package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.rate.RateLimiterFlow;
import com.chua.common.support.concurrent.rate.annotation.RateLimiter;
import com.chua.common.support.lang.placeholder.StringValuePropertyResolver;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.spring.support.configuration.SpringBeanUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 限流拦截器，处理 {@link RateLimiter} 注解的方法。
 *
 * <p>基于 {@link RateLimiterFlow} 门面，通过链式 API 获取许可，
 * 降级回调统一交给 {@link RateLimiterFlow#fallback} 处理。</p>
 *
 * <p><b>属性解析链：</b></p>
 * <ol>
 *   <li>优先解析 {@code #{...}} SpEL 表达式，支持 {@code @beanName} 引用 Spring Bean</li>
 *   <li>其次解析 {@code ${...}} 占位符，从配置源读取</li>
 *   <li>以上均不匹配时当作字面量直接使用</li>
 * </ol>
 *
 * @author CH
 */
@Spi("com.chua.common.support.concurrent.rate.annotation.RateLimiter")
public class RateLimiterIntercept implements MethodAnnotationIntercept<RateLimiter> {

    /**
     * SpEL 表达式解析器，用于解析注解属性中的 {@code #{...}} 表达式
     */
    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 占位符解析器，用于解析注解属性中的 {@code ${...}} 占位符
     */
    private final StringValuePropertyResolver propertyResolver = new StringValuePropertyResolver(null);

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
        double permitsPerSecond = resolveDouble(annotation.permitsPerSecond(), 1);
        long warmupPeriod = resolveLong(annotation.warmupPeriod(), 0);
        long waitTime = resolveLong(annotation.waitTime(), 0);

        RateLimiterFlow flow = RateLimiterFlow.of(annotation.name(), permitsPerSecond);
        if (warmupPeriod > 0) {
            flow.warmup(warmupPeriod);
        }
        if (waitTime > 0) {
            flow.timeout(waitTime, TimeUnit.MILLISECONDS);
        }
        if (StringUtils.hasText(annotation.fallback())) {
            flow.fallback(() -> resolveFallback(annotation, proxyMethod));
        }
        if (flow.tryAcquire()) {
            return invocation.proceed();
        }
        return onRejected(annotation, proxyMethod);
    }

    /**
     * 解析双精度属性值。
     *
     * @param value        注解属性值（可能是 SpEL、占位符或字面量）
     * @param defaultValue 解析失败时的默认值
     * @return 解析后的双精度值
     */
    private double resolveDouble(String value, double defaultValue) {
        String resolved = resolve(value);
        if (resolved == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(resolved);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 解析长整型属性值。
     *
     * @param value        注解属性值（可能是 SpEL、占位符或字面量）
     * @param defaultValue 解析失败时的默认值
     * @return 解析后的长整型值
     */
    private long resolveLong(String value, long defaultValue) {
        String resolved = resolve(value);
        if (resolved == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(resolved);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 解析属性文本，按优先级依次尝试 SpEL、占位符、字面量。
     *
     * <ol>
     *   <li>文本含 {@code #{...}} 时，提取中间表达式交给 SpEL 引擎求值</li>
     *   <li>文本含 {@code ${...}} 时，交给 {@link StringValuePropertyResolver} 解析占位符</li>
     *   <li>其余情况直接返回原文（字面量）</li>
     * </ol>
     *
     * @param text 注解属性原始文本
     * @return 解析后的值，无法解析时返回 null
     */
    private String resolve(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (text.contains("#{") && text.contains("}")) {
            int start = text.indexOf("#{");
            int end = text.indexOf("}", start);
            if (start >= 0 && end > start) {
                String expr = text.substring(start + 2, end);
                try {
                    Object value = parser.parseExpression(expr).getValue(createEvaluationContext());
                    if (value != null) {
                        return value.toString();
                    }
                } catch (Exception ignored) {
                    // SpEL 求值失败，回退到占位符解析
                }
            }
        }
        if (text.contains("${")) {
            return propertyResolver.resolvePlaceholders(text);
        }
        return text;
    }

    /**
     * 创建 Spring Bean 感知的 SpEL 求值上下文。
     *
     * <p>Spring 环境中注册 {@link BeanFactoryResolver}，使表达式可通过
     * {@code @beanName} 引用容器 Bean；非 Spring 环境仅支持纯表达式求值。</p>
     *
     * @return 绑定 Bean 解析器的求值上下文
     */
    private StandardEvaluationContext createEvaluationContext() {
        StandardEvaluationContext context = new StandardEvaluationContext();
        ApplicationContext applicationContext = SpringBeanUtils.getApplicationContextOrNull();
        if (applicationContext != null) {
            context.setBeanResolver(new BeanFactoryResolver(applicationContext));
        }
        return context;
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