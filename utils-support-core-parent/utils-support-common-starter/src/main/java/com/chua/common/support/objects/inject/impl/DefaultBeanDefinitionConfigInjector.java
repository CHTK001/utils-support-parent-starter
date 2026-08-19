package com.chua.common.support.objects.inject.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.objects.annotation.ConfigValue;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.environment.Environment;
import com.chua.common.support.objects.inject.BeanDefinitionConfigInjector;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 默认配置注入器，基于 @ConfigValue 注解进行配置值注入。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("default")
@SpiDescribe("默认配置注入器（@ConfigValue）")
public class DefaultBeanDefinitionConfigInjector implements BeanDefinitionConfigInjector {

    @Override
    /** 是否Support */
    public boolean isSupport(Field field, BeanDefinition beanDefinition) {
        if (field == null) { return false; }
        return field.isAnnotationPresent(ConfigValue.class);
    }

    @Override
    /** Inject */
    public Object inject(Field field, Object bean, BeanDefinition beanDefinition, Environment environment) {
        if (field == null || bean == null || environment == null) { return null; }
        ConfigValue configValue = field.getAnnotation(ConfigValue.class);
        if (configValue == null) { return null; }
        return rawValue(configValue.value(), configValue.defaultValue());
    }

    @Override
    /** 是否Support */
    public boolean isSupport(Method method, BeanDefinition beanDefinition) {
        if (method == null) {
            return false;
        }
        for (Parameter param : method.getParameters()) {
            if (param.isAnnotationPresent(ConfigValue.class)) {
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
            ConfigValue cv = params[i].getAnnotation(ConfigValue.class);
            if (cv == null) {
                continue;
            }
            hit = true;
            result[i] = rawValue(cv.value(), cv.defaultValue());
        }
        return hit ? result : null;
    }

    /**
     * 返回原始表达式字符串，表达式解析在 {@code AbstractBeanDefinition} 统一处理。
     */
    private static Object rawValue(String value, String defaultValue) {
        if (value != null && !value.isEmpty()) {
            return value;
        }
        if (defaultValue != null && !defaultValue.isEmpty()) {
            return Converter.convertIfNecessary(defaultValue, String.class);
        }
        return null;
    }
}
