package com.chua.spring.support.objects.inject.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.inject.BeanDefinitionServiceInjector;
import com.chua.common.support.spi.annotations.Spi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.reflect.Field;
import java.util.function.Function;

/**
* Spring 字段注入器，处理 {@link Autowired} / {@link Qualifier} 注解。
*
* @author CH
* @since 2024/12/20
 */
@Spi("spring")
public class SpringBeanDefinitionServiceInjector implements BeanDefinitionServiceInjector {

    @Override
    /** 是否支持 */
    public boolean isSupport(Field field, BeanDefinition beanDefinition) {
        return field != null && field.isAnnotationPresent(Autowired.class);
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
        if (field == null || bean == null) {
            return null;
        }
        Autowired autowired = field.getAnnotation(Autowired.class);
        if (autowired == null) {
            return null;
        }

        Qualifier qualifier = field.getAnnotation(Qualifier.class);
        String qualifierName = qualifier != null ? qualifier.value() : null;

        if (qualifierName != null && !qualifierName.isEmpty() && beanProvider != null) {
            try {
                Object result = beanProvider.apply(qualifierName);
                if (result != null) {
                    return result;
                }
            } catch (Exception ignored) {
            }
        }

        if (typeProvider != null) {
            try {
                Object result = typeProvider.apply(field.getType());
                if (result != null) {
                    return result;
                }
            } catch (Exception ignored) {
            }
        }

        if (beanProvider != null) {
            try {
                Object result = beanProvider.apply(field.getName());
                if (result != null) {
                    return result;
                }
            } catch (Exception ignored) {
            }
        }

        if (autowired.required()) {
            throw new IllegalStateException(
                    "@Autowired(required=true) 字段注入失败: " + bean.getClass().getName() + "." + field.getName());
        }
        return null;
    }
}
