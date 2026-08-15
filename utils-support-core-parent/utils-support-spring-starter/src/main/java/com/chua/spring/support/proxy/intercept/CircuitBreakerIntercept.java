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
import org.springframework.context.ApplicationContext;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * 熔断拦截器，处理带有 {@link CircuitBreaker} 注解的方法。
 *
 * <p>本拦截器通过 {@link MethodAnnotationIntercept} SPI 机制被 Invoker 的 Proxy 自动发现。
 * 方法上标注 {@code @CircuitBreaker} 时，拦截器读取注解属性，构建 {@link CircuitBreakerFlow}
 * 门面，并在熔断保护下执行目标方法。熔断打开时调用回退方法降级。</p>
 *
 * <p><b>属性解析链：</b></p>
 * <ol>
 *   <li>优先解析 {@code #{...}} SpEL 表达式，支持 {@code @beanName} 引用 Spring Bean</li>
 *   <li>其次解析 {@code ${...}} 占位符，从配置源读取</li>
 *   <li>以上均不匹配时当作字面量直接使用</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("com.chua.common.support.concurrent.circuitbreaker.annotation.CircuitBreaker")
public class CircuitBreakerIntercept implements MethodAnnotationIntercept<CircuitBreaker> {

    /**
     * SpEL 表达式解析器，用于解析注解属性中的 {@code #{...}} 表达式
     */
    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 占位符解析器，用于解析注解属性中的 {@code ${...}} 占位符
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
        // 解析熔断器名称：优先使用注解 name，未填则使用 类名.方法名
        String name = resolveName(annotation, proxyMethod);

        // 解析各阈值属性（支持 SpEL 和占位符），解析失败时使用默认值
        int failureThreshold = resolveInt(annotation.failureThreshold(), 5);
        int successThreshold = resolveInt(annotation.successThreshold(), 2);
        long waitDuration = resolveLong(annotation.waitDuration(), annotation.recoveryTime(), 60000);

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
     * 解析整型属性值。
     *
     * @param value        注解中的属性值（可能是 SpEL 表达式、占位符或字面量）
     * @param defaultValue 解析失败时使用的默认值
     * @return 解析后的整型值
     */
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

    /**
     * 解析长整型属性值，支持语义别名。
     *
     * <p>当主属性 {@code value} 为空而别名 {@code alias} 非空时，使用别名值。
     * 典型场景：{@code waitDuration} 与 {@code recoveryTime} 互为别名。</p>
     *
     * @param value        主属性值
     * @param alias        别名属性值（如 recoveryTime）
     * @param defaultValue 解析失败时使用的默认值
     * @return 解析后的长整型值
     */
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
     * <p>在 Spring 环境中注册 {@link BeanFactoryResolver}，使 SpEL 表达式可以
     * 通过 {@code @beanName} 引用容器中的 Bean。非 Spring 环境只能求值纯表达式。</p>
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

    /**
     * 解析熔断器名称。
     *
     * <p>注解未填写 name 时，默认使用 {@code 目标类全限定名.方法名} 作为唯一标识，
     * 确保同一方法在多次调用间共享同一个熔断器实例。</p>
     *
     * @param annotation  熔断注解
     * @param proxyMethod 被拦截的方法信息
     * @return 熔断器名称
     */
    private static String resolveName(CircuitBreaker annotation, ProxyMethod proxyMethod) {
        String name = annotation.name();
        if (StringUtils.hasText(name)) {
            return name;
        }
        return proxyMethod.getTarget().getClass().getName() + "." + proxyMethod.getMethod().getName();
    }

    /**
     * 调用注解指定的回退方法。
     *
     * <p>回退方法必须与目标方法位于同一类中，且参数签名完全一致。
     * 通过反射定位回退方法并执行，返回降级结果。</p>
     *
     * @param annotation  熔断注解
     * @param proxyMethod 被拦截的方法信息
     * @return 回退方法的返回值，找不到回退方法时返回 null
     */
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