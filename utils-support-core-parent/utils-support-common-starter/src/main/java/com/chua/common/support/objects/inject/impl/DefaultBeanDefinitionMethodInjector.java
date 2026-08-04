package com.chua.common.support.objects.inject.impl;

import com.chua.common.support.objects.annotation.AutoInject;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.inject.BeanDefinitionMethodInjector;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.function.Function;
import org.jspecify.annotations.NullUnmarked;

/**
 * 默认方法注入器，处理 {@link AutoInject} 注解的 setter 方法。
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
@Slf4j
@Spi("default")
public class DefaultBeanDefinitionMethodInjector implements BeanDefinitionMethodInjector {

    @Override
    public boolean isSupport(Method method, BeanDefinition beanDefinition) {
        return method.isAnnotationPresent(AutoInject.class);
    }

    @Override
    public void inject(Method method, Object instance, BeanDefinition beanDefinition,
                       Function<String, Object> beanProvider,
                       Function<Class<?>, Object> typeProvider) {
        AutoInject autoInject = method.getAnnotation(AutoInject.class);
        if (autoInject == null) {
            return;
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return;
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = null;
            if (typeProvider != null) {
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
            method.invoke(instance, args);
        } catch (Exception e) {
            log.error("方法注入失败: {}.{}", instance.getClass().getName(), method.getName(), e);
        }
    }
}
