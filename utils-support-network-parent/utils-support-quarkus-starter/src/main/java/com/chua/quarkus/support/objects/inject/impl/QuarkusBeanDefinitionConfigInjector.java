package com.chua.quarkus.support.objects.inject.impl;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.environment.Environment;
import com.chua.common.support.objects.inject.BeanDefinitionConfigInjector;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * Quarkus (MicroProfile Config) 配置注入器，通过反射处理 {@code @ConfigProperty} 注解的字段和方法参数。
 *
 * <p>不直接依赖 microprofile-config-api 编译 API，所有注解均通过反射按类名检测。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("quarkus")
public class QuarkusBeanDefinitionConfigInjector implements BeanDefinitionConfigInjector {

    /**
     * config property
     */
    private static final String CONFIG_PROPERTY = "org.eclipse.microprofile.config.inject.ConfigProperty";
    /**
     * unconfigured 值
     */
    private static final String UNCONFIGURED_VALUE = "org.eclipse.microprofile.config.inject.ConfigProperty.UNCONFIGURED_VALUE";

    @Override
    public boolean isSupport(Field field, BeanDefinition beanDefinition) {
        if (field == null) {
            return false;
        }
        return hasConfigProperty(field.getAnnotations());
    }

    @Override
    public Object inject(Field field, Object bean, BeanDefinition beanDefinition, Environment environment) {
        if (field == null || bean == null || environment == null) {
            return null;
        }
        Annotation cp = findConfigProperty(field.getAnnotations());
        if (cp == null) {
            return null;
        }
        return resolveValue(cp, field.getType(), environment);
    }

    @Override
    public boolean isSupport(Method method, BeanDefinition beanDefinition) {
        if (method == null) {
            return false;
        }
        for (Parameter param : method.getParameters()) {
            if (hasConfigProperty(param.getAnnotations())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object[] inject(Method method, Object bean, BeanDefinition beanDefinition, Environment environment) {
        if (method == null || bean == null || environment == null) {
            return null;
        }
        Parameter[] params = method.getParameters();
        if (params.length == 0) {
            return null;
        }
        Object[] result = new Object[params.length];
        boolean hit = false;
        for (int i = 0; i < params.length; i++) {
            Annotation cp = findConfigProperty(params[i].getAnnotations());
            if (cp == null) {
                continue;
            }
            hit = true;
            result[i] = resolveValue(cp, params[i].getType(), environment);
        }
        return hit ? result : null;
    }

    private boolean hasConfigProperty(Annotation[] annotations) {
        for (Annotation ann : annotations) {
            if (CONFIG_PROPERTY.equals(ann.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private Annotation findConfigProperty(Annotation[] annotations) {
        for (Annotation ann : annotations) {
            if (CONFIG_PROPERTY.equals(ann.annotationType().getName())) {
                return ann;
            }
        }
        return null;
    }

    private Object resolveValue(Annotation annotation, Class<?> targetType, Environment environment) {
        try {
            String name = (String) annotation.annotationType().getMethod("name").invoke(annotation);
            String defaultValue = (String) annotation.annotationType().getMethod("defaultValue").invoke(annotation);

            Object value = null;
            if (name != null && !name.isEmpty()) {
                value = environment.getProperty(name, targetType);
            }
            if (value == null && defaultValue != null && !defaultValue.isEmpty()
                    && !UNCONFIGURED_VALUE.equals(defaultValue)) {
                value = Converter.convertIfNecessary(defaultValue, targetType);
            }
            return value;
        } catch (Exception e) {
            log.warn("解析 @ConfigProperty 失败: {}", annotation, e);
            return null;
        }
    }
}
