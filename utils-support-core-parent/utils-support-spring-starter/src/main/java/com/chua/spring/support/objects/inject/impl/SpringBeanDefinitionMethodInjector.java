package com.chua.spring.support.objects.inject.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.inject.BeanDefinitionMethodInjector;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Spring 方法注入器，处理 {@link Autowired} / {@link Qualifier} 注解的 setter 方法。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("spring")
public class SpringBeanDefinitionMethodInjector implements BeanDefinitionMethodInjector {

    @Override
    /** 是否支持 */
    public boolean isSupport(Method method, BeanDefinition beanDefinition) {
        return method != null && method.isAnnotationPresent(Autowired.class);
    }

    @Override
    /**
     * Inject
     * @param method 方法
     * @param instance instance
     * @param beanDefinition Beandefinition
     * @param beanProvider Bean提供者
     * @param typeProvider 类型提供者
     */
    public void inject(Method method, Object instance, BeanDefinition beanDefinition,
                       Function<String, Object> beanProvider,
                       Function<Class<?>, Object> typeProvider) {
        if (method == null || instance == null) {
            return;
        }
        Autowired autowired = method.getAnnotation(Autowired.class);
        if (autowired == null) {
            return;
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return;
        }

        Qualifier qualifier = method.getAnnotation(Qualifier.class);
        String qualifierName = qualifier != null ? qualifier.value() : null;
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = null;
            if (qualifierName != null && beanProvider != null) {
                arg = beanProvider.apply(qualifierName);
            }
            if (arg == null && typeProvider != null) {
                try {
                    arg = typeProvider.apply(paramTypes[i]);
                } catch (Exception ignored) {
                }
            }
            if (arg == null && beanProvider != null) {
                try {
                    arg = beanProvider.apply(method.getName());
                } catch (Exception ignored) {
                }
            }
            args[i] = arg;
        }
        try {
            ClassUtils.setAccessible(method);
            ReflectUtils.invoke(instance, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
        } catch (Exception e) {
            log.error("[spring-impl] 方法注入失败: {}.{}", instance.getClass().getName(), method.getName(), e);
        }
    }
}
