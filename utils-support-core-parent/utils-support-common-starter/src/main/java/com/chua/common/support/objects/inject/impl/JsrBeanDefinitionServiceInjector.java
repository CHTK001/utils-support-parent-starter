package com.chua.common.support.objects.inject.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.inject.BeanDefinitionServiceInjector;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * JSR 标准注解注入器，通过反射处理 @Resource 和 @Inject 注解。
 *
 * <p>支持的 JSR 标准：
 * <ul>
 *   <li>JSR-250：@Resource（javax.annotation.Resource / jakarta.annotation.Resource）</li>
 *   <li>JSR-330：@Inject（javax.inject.Inject / jakarta.inject.Inject）</li>
 *   <li>JSR-330：@Named（javax.inject.Named / jakarta.inject.Named）与 @Inject 配合使用</li>
 * </ul></p>
 *
 * <p>所有注解均通过反射按类名检测，不依赖编译时注解 API，避免类路径冲突。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jsr")
@SpiDescribe("JSR 标准注解注入器（@Resource、@Inject）")
public class JsrBeanDefinitionServiceInjector implements BeanDefinitionServiceInjector {

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
    /** 是否Support */
    public boolean isSupport(Field field, BeanDefinition beanDefinition) {
        if (field == null) {
            return false;
        }
        for (Annotation ann : field.getAnnotations()) {
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
     * @param field field
     * @param bean bean
     * @param beanDefinition beanDefinition
     * @param beanProvider beanProvider
     * @param typeProvider typeProvider
     */
    public Object inject(Field field, Object bean, BeanDefinition beanDefinition,
                         Function<String, Object> beanProvider,
                         Function<Class<?>, Object> typeProvider) {
        if (field == null || bean == null) {
            return null;
        }
        for (Annotation ann : field.getAnnotations()) {
            String name = ann.annotationType().getName();
            if (RESOURCE_JAVAX.equals(name) || RESOURCE_JAKARTA.equals(name)) {
                return injectResource(ann, field, beanProvider, typeProvider);
            }
        }
        for (Annotation ann : field.getAnnotations()) {
            String name = ann.annotationType().getName();
            if (INJECT_JAVAX.equals(name) || INJECT_JAKARTA.equals(name)) {
                return injectWithNamed(ann, field, beanProvider, typeProvider);
            }
        }
        return null;
    }

    /**
     * InjectResource
     * @param resource resource
     * @param field field
     * @param beanProvider beanProvider
     * @param typeProvider typeProvider
     */
    private Object injectResource(Annotation resource, Field field,
                                  Function<String, Object> beanProvider,
                                  Function<Class<?>, Object> typeProvider) {
        try {
            String resourceName = getAnnotationAttribute(resource, "name", "");
            Class<?> resourceType = getAnnotationAttribute(resource, "type", null);
            if (resourceType == null || resourceType == Object.class) {
                resourceType = field.getType();
            }
            if (!resourceName.isEmpty()) {
                if (beanProvider != null) {
                    Object result = beanProvider.apply(resourceName);
                    if (result != null) {
                        return result;
                    }
                }
            }
            if (typeProvider != null) {
                return typeProvider.apply(resourceType);
            }
        } catch (Exception e) {
            log.warn("@Resource 注入失败: {}", field.getName(), e);
        }
        return null;
    }

    /**
     * Inject设置Named
     * @param inject inject
     * @param field field
     * @param beanProvider beanProvider
     * @param typeProvider typeProvider
     */
    private Object injectWithNamed(Annotation inject, Field field,
                                   Function<String, Object> beanProvider,
                                   Function<Class<?>, Object> typeProvider) {
        try {
            String namedValue = findNamedValue(field);
            if (!namedValue.isEmpty() && beanProvider != null) {
                Object result = beanProvider.apply(namedValue);
                if (result != null) {
                    return result;
                }
            }
            if (typeProvider != null) {
                return typeProvider.apply(field.getType());
            }
        } catch (Exception e) {
            log.warn("@Inject 注入失败: {}", field.getName(), e);
        }
        return null;
    }

    /** 查找NamedValue */
    private String findNamedValue(Field field) {
        for (Annotation ann : field.getAnnotations()) {
            String name = ann.annotationType().getName();
            if (NAMED_JAVAX.equals(name) || NAMED_JAKARTA.equals(name)) {
                return getAnnotationAttribute(ann, "value", "");
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    /** 获取AnnotationAttribute */
    private <T> T getAnnotationAttribute(Annotation annotation, String attributeName, T defaultValue) {
        try {
            Method method = annotation.annotationType().getMethod(attributeName);
            return (T) method.invoke(annotation);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
