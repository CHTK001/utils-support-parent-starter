package com.chua.common.support.objects.inject.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.objects.annotation.AutoInject;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.inject.BeanDefinitionServiceInjector;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.function.Function;

/**
   * 默认服务注入器，基于 @autoinject 注解进行依赖注入。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("default")
@SpiDescribe("默认服务注入器（@AutoInject）")
public class DefaultBeanDefinitionServiceInjector implements BeanDefinitionServiceInjector {

    @Override
    /** 是否支持 */
    public boolean isSupport(Field field, BeanDefinition beanDefinition) {
        if (field == null) { return false; }
        return field.isAnnotationPresent(AutoInject.class);
    }

    @Override
    /**
     * Inject
     * @param field 字段
     * @param bean Bean
     * @param beanDefinition Beandefinition
     * @param beanProvider Bean提供者
     * @param typeProvider 类型提供者
     */
    public Object inject(Field field, Object bean, BeanDefinition beanDefinition,
                         Function<String, Object> beanProvider,
                         Function<Class<?>, Object> typeProvider) {
        if (field == null || bean == null || beanProvider == null) { return null; }
        AutoInject autoInject = field.getAnnotation(AutoInject.class);
        if (autoInject == null) { return null; }
        try {
            Class<?> fieldType = field.getType();
            String beanName = autoInject.value();
            boolean required = autoInject.required();
            Object value;
            if (beanName != null && !beanName.isEmpty()) {
                value = beanProvider.apply(beanName);
            } else {
                value = typeProvider != null ? typeProvider.apply(fieldType) : null;
            }
            if (value != null) { return value; }
            if (required) {
                throw new IllegalStateException(
                        String.format("无法注入 Bean 字段 %s.%s (类型: %s)", bean.getClass().getName(), field.getName(), fieldType.getName()));
            }
            return null;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("注入字段失败: {}", field.getName(), e);
            if (autoInject.required()) { throw new IllegalStateException("Bean 注入失败: " + field.getName(), e); }
            return null;
        }
    }
}