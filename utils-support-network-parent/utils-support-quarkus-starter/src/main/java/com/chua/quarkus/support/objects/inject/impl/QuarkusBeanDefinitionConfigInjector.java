package com.chua.quarkus.support.objects.inject.impl;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.reflection.ReflectUtils;
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
 * Quarkus (micro配置文件 配置) 配置注入器，通过反射处理 {@code @ConfigProperty} 注解的字段和方法参数。
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
     * 配置 财产
     */
    private static final String CONFIG_PROPERTY = "org.eclipse.microprofile.config.inject.ConfigProperty";
    /**
     * unconfigured 值
     */
    private static final String UNCONFIGURED_VALUE = "org.eclipse.microprofile.config.inject.ConfigProperty.UNCONFIGURED_VALUE";

    @Override
    /** 是否支持 */
    public boolean isSupport(Field field, BeanDefinition beanDefinition) {
        if (field == null) {
            return false;
        }
        return hasConfigProperty(field.getAnnotations());
    }

    @Override
    /** Inject */
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
    /** 是否支持 */
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
    /** Inject */
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

    /**
     * 是否拥有配置财产
     *
     * @param annotations 注解
     * @return 是否包含配置财产的结果
     */
    private boolean hasConfigProperty(Annotation[] annotations) {
        for (Annotation ann : annotations) {
            if (CONFIG_PROPERTY.equals(ann.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查找配置财产
     *
     * @param annotations 注解
     * @return find配置财产的结果
     */
    private Annotation findConfigProperty(Annotation[] annotations) {
        for (Annotation ann : annotations) {
            if (CONFIG_PROPERTY.equals(ann.annotationType().getName())) {
                return ann;
            }
        }
        return null;
    }

    /**
     * 解析值
     *
     * @param annotation 注解
     * @param targetType 目标类型
     * @param environment 环境
     * @return resolve值的结果
     */
    private Object resolveValue(Annotation annotation, Class<?> targetType, Environment environment) {
        try {
            String name = (String) ReflectUtils.invoke(annotation, "name", String.class);
            String defaultValue = (String) ReflectUtils.invoke(annotation, "defaultValue", String.class);

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
