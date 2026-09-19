package com.chua.spring.support.objects.inject.impl;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.environment.Environment;
import com.chua.common.support.objects.inject.BeanDefinitionConfigInjector;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.spring.support.objects.environment.SpringEnvironmentAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * Spring 配置注入器，处理 {@link Value} 注解。
 *
 * <p>通过 {@link SpringEnvironmentAdapter} 将框架 {@link Environment} 包装为 Spring
 * {@link org.springframework.core.env.Environment}，使 {@code ${...}} 占位符和
 * {@code #{...}} SpEL 均委托 Spring 原生机制解析，配置源来自框架已聚合的配置。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("spring")
public class SpringBeanDefinitionConfigInjector implements BeanDefinitionConfigInjector {

    /**
     * 解析器
    */
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    /**
     * springenv
    */
    private volatile org.springframework.core.env.Environment springEnv;

    @Override
    /**
     * 是否支持
     * @param field 字段
     * @param beanDefinition Beandefinition
     */
    public boolean isSupport(Field field, BeanDefinition beanDefinition) {
        return field != null && field.isAnnotationPresent(Value.class);
    }

    @Override
    /**
     * Inject
     * @param field 字段
     * @param bean Bean
     * @param beanDefinition Beandefinition
     * @param environment 环境
     */
    public Object inject(Field field, Object bean, BeanDefinition beanDefinition, Environment environment) {
        if (field == null || bean == null) {
            return null;
        }
        Value value = field.getAnnotation(Value.class);
        if (value == null) {
            return null;
        }
        return resolveValue(value.value(), field.getType(), environment);
    }

    @Override
    /**
     * 是否支持
     * @param method 方法
     * @param beanDefinition Beandefinition
     */
    public boolean isSupport(Method method, BeanDefinition beanDefinition) {
        if (method == null) {
            return false;
        }
        for (Parameter param : method.getParameters()) {
            if (param.isAnnotationPresent(Value.class)) {
                return true;
            }
        }
        return false;
    }

    @Override
    /**
     * Inject
     * @param method 方法
     * @param bean Bean
     * @param beanDefinition Beandefinition
     * @param environment 环境
     */
    public Object[] inject(Method method, Object bean, BeanDefinition beanDefinition, Environment environment) {
        if (method == null || bean == null) {
            return null;
        }
        Parameter[] params = method.getParameters();
        if (params.length == 0) {
            return null;
        }
        Object[] result = new Object[params.length];
        boolean hit = false;
        for (int i = 0; i < params.length; i++) {
            Value value = params[i].getAnnotation(Value.class);
            if (value == null) {
                continue;
            }
            hit = true;
            result[i] = resolveValue(value.value(), params[i].getType(), environment);
        }
        return hit ? result : null;
    }

    /**
     * 获取springenv
     * @param frameworkEnv 框架env
     * @return 获取springenv的结果
     */
    private org.springframework.core.env.Environment getSpringEnv(Environment frameworkEnv) {
        org.springframework.core.env.Environment env = this.springEnv;
        if (env instanceof SpringEnvironmentAdapter adapter && adapter.getDelegate() == frameworkEnv) {
            return env;
        }
        env = new SpringEnvironmentAdapter(frameworkEnv);
        this.springEnv = env;
        return env;
    }

    @SuppressWarnings("unchecked")
    /**
     * 解析值
     * @param expression expression
     * @param targetType 目标类型
     * @param frameworkEnv 框架env
     * @return resolve值的结果
     */
    private Object resolveValue(String expression, Class<?> targetType, Environment frameworkEnv) {
        if (expression == null) {
            return null;
        }
        try {
            org.springframework.core.env.Environment springEnv = getSpringEnv(frameworkEnv);

 // spel：直接走 Spring expressionparser
            if (expression.startsWith("#{") && expression.endsWith("}")) {
                String spel = expression.substring(2, expression.length() - 1);
                Expression expr = PARSER.parseExpression(spel);
                StandardEvaluationContext ctx = new StandardEvaluationContext();
                ctx.setRootObject(System.getProperties());
                ctx.setVariable("env", springEnv);
                Object val = expr.getValue(ctx);
                if (val == null) {
                    return null;
                }
                if (targetType.isInstance(val)) {
                    return val;
                }
                return Converter.convertIfNecessary(val, targetType);
            }

 // ${} 占位符或直接值：走 Spring 环境.resolveplaceholders
            String resolved = springEnv.resolvePlaceholders(expression);
            if (StringUtils.isEmpty(resolved)) {
                return null;
            }
            // 无法解析的占位符，不注入
            if (resolved.contains("${")) {
                return null;
            }
            if (resolved.equals(expression)) {
                return expression;
            }
            if (targetType == String.class) {
                return resolved;
            }
            return Converter.convertIfNecessary(resolved, targetType);
        } catch (Exception e) {
            log.warn("[spring-impl] 解析 @Value 失败: {}", expression, e);
            return null;
        }
    }
}
