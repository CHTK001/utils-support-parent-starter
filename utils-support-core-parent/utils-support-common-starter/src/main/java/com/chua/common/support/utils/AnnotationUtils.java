package com.chua.common.support.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 娉ㄨВ宸ュ叿绫? *
 * @author CH
 */
public class AnnotationUtils {

    /**
     * 鑾峰彇娉ㄨВ灞炴€?     *
     * @param clazz 绫?     * @param annotationClass 娉ㄨВ绫?     * @return 娉ㄨВ灞炴€ф槧灏?     */
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
