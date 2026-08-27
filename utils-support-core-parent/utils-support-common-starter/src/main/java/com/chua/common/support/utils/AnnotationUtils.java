package com.chua.common.support.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 注解工具类
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AnnotationUtils {

    /**
     * 获取注解属性
     *
     * @param clazz           类
     * @param annotationClass 注解类
     * @return 注解属性映射
     */
    public static Map<String, Object> getAnnotationAttributes(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        Map<String, Object> attributes = new HashMap<>();
        Annotation annotation = clazz.getAnnotation(annotationClass);
        if (annotation == null) {
            return attributes;
        }
        for (Method method : annotationClass.getDeclaredMethods()) {
            try {
                Object value = method.invoke(annotation);
                attributes.put(method.getName(), value);
            } catch (Exception e) {
                // ignore
            }
        }
        return attributes;
    }
}