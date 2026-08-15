package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerFlow;
import com.chua.common.support.concurrent.circuitbreaker.annotation.CircuitBreaker;
import com.chua.common.support.lang.placeholder.StringValuePropertyResolver;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.spring.support.configuration.SpringBeanUtils;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.context.ApplicationContext;

/**
 * 熔断拦截器，处理 {@link CircuitBreaker} 注解的方法。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("com.chua.common.support.concurrent.circuitbreaker.annotation.CircuitBreaker")
public class CircuitBreakerIntercept implements MethodAnnotationIntercept<CircuitBreaker> {

    /**
     * SpEL 表达式解析器
     */
    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 占位符解析器
     */
    private final StringValuePropertyResolver propertyResolver = new StringValuePropertyResolver(null);

    @Override
    public Class<CircuitBreaker> annotationType() {
        return CircuitBreaker.class;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Object intercept(CircuitBreaker annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        String name = resolveName(annotation, proxyMethod);
        int failureThreshold = resolveInt(annotation.failureThreshold(), 5);
        int successThreshold = resolveInt(annotation.successThreshold(), 2);
        long waitDuration = resolveLong(annotation.waitDuration(), annotation.recoveryTime(), 60000);

        CircuitBreakerFlow flow = CircuitBreakerFlow.of(name)
                .failureThreshold(failureThreshold)
                .successThreshold(successThreshold)
                .waitDuration(waitDuration);

        if (StringUtils.hasText(annotation.fallback())) {
            flow.fallback(() -> resolveFallback(annotation, proxyMethod));
        }

        return flow.execute(() -> {
            try {
                return invocation.proceed();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
    }

    private int resolveInt(String value, int defaultValue) {
        String resolved = resolve(value);
        if (resolved == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(resolved);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long resolveLong(String value, String alias, long defaultValue) {
        String actual = StringUtils.hasText(alias) ? alias : value;
        String resolved = resolve(actual);
        if (resolved == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(resolved);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

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
     * <p>支持 {@code @beanName} 引用 Spring 容器中的 Bean。</p>
     */
    private StandardEvaluationContext createEvaluationContext() {
        StandardEvaluationContext context = new StandardEvaluationContext();
        ApplicationContext applicationContext = SpringBeanUtils.getApplicationContextOrNull();
        if (applicationContext != null) {
            context.setBeanResolver(new org.springframework.context.expression.BeanFactoryResolver(applicationContext));
        }
        return context;
    }

    private static String resolveName(CircuitBreaker annotation, ProxyMethod proxyMethod) {
        String name = annotation.name();
        if (StringUtils.hasText(name)) {
            return name;
        }
        return proxyMethod.getTarget().getClass().getName() + "." + proxyMethod.getMethod().getName();
    }

    private Object resolveFallback(CircuitBreaker annotation, ProxyMethod proxyMethod) {
        if (!StringUtils.hasText(annotation.fallback())) {
            return null;
        }
        Object target = proxyMethod.getTarget();
        if (target != null) {
            java.lang.reflect.Method fallbackMethod = ClassUtils.findMethod(target.getClass(), annotation.fallback(), proxyMethod.getParameterTypes());
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