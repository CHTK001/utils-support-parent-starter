package com.chua.common.support.objects.inject.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.inject.BeanDefinitionMethodInjector;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * JSR 标准方法注入器，通过反射处理 {@code @Resource} / {@code @Inject} 注解的 setter 方法。
 *
 * <p>所有注解均通过反射按类名检测，不依赖编译时注解 API。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("jsr")
public class JsrBeanDefinitionMethodInjector implements BeanDefinitionMethodInjector {

    /** Resource_javax */
    private static final String RESOURCE_JAVAX = "javax.annotation.Resource";
    /** Resource_jakarta */
    private static final String RESOURCE_JAKARTA = "jakarta.annotation.Resource";
    /** Inject_javax */
    private static final String INJECT_JAVAX = "javax.inject.Inject";
    /** Inject_jakarta */
    private static final String INJECT_JAKARTA = "jakarta.inject.Inject";
    /** Named_javax */
    private static final String NAMED_JAVAX = "javax.inject.Named";
    /** Named_jakarta */
    private static final String NAMED_JAKARTA = "jakarta.inject.Named";

    @Override
    /** 是否支持 */
    public boolean isSupport(Method method, BeanDefinition beanDefinition) {
        if (method == null) {
            return false;
        }
        for (Annotation ann : method.getAnnotations()) {
            String name = ann.annotationType().getName();
            if (RESOURCE_JAVAX.equals(name) || RESOURCE_JAKARTA.equals(name)
                    || INJECT_JAVAX.equals(name) || INJECT_JAKARTA.equals(name)) {
                return true;
            }
        }
        return false;
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
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return;
        }
        Object[] args = resolveArgs(method, beanProvider, typeProvider);
        try {
            ClassUtils.setAccessible(method);
            ReflectUtils.invoke(instance, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
        } catch (Exception e) {
            log.error("方法注入失败: {}.{}", instance.getClass().getName(), method.getName(), e);
        }
    }

    /**
     * 解析参数
     * @param method 方法
     * @param beanProvider Bean提供者
     * @param typeProvider 类型提供者
     */
    private Object[] resolveArgs(Method method,
                                 Function<String, Object> beanProvider,
                                 Function<Class<?>, Object> typeProvider) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        boolean isResource = false;
        for (Annotation ann : method.getAnnotations()) {
            String name = ann.annotationType().getName();
            if (RESOURCE_JAVAX.equals(name) || RESOURCE_JAKARTA.equals(name)) {
                isResource = true;
                break;
            }
        }
        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = null;
            if (isResource && beanProvider != null) {
                arg = beanProvider.apply(resolveName(method, paramTypes[i]));
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
        return args;
    }

    /**
     * 解析名称
     *
     * @param method 方法
     * @param paramType 参数类型
     * @return resolve名称的结果
     */
    private String resolveName(Method method, Class<?> paramType) {
        for (Annotation ann : method.getAnnotations()) {
            String name = ann.annotationType().getName();
            if (NAMED_JAVAX.equals(name) || NAMED_JAKARTA.equals(name)) {
                try {
Object val = ReflectUtils.invoke(ann, "value", Object.class, new Class<?>[0]);
                    if (val instanceof String s && !s.isEmpty()) {
                        return s;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return method.getName();
    }
}
