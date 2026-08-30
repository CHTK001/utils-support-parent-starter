package com.chua.common.support.utils;

import com.chua.common.support.collection.ConcurrentReferenceHashMap;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 注解工具类，提供注解存在性判断（含继承链）、
 * Spring MVC 注解别名解析等能力。
 *
 * <p>示例：{@code AnnotationUtils.isAnnotationPresent(clazz, "org.springframework.web.bind.annotation.GetMapping")}
 * 会同时匹配 {@code @RequestMapping}（继承+元注解场景）。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public class AnnotationUtils {

    /**
     * Spring MVC 窄注解 → 宽注解别名映射（通过全限定类名缓存，懒加载）。
     * Key: 窄注解全限定名，Value: 宽注解全限定名。
     */
    private static final Map<String, String> ALIAS_MAP = new ConcurrentReferenceHashMap<>(16);

    static {
        // 初始化 Spring MVC 注解别名（类不存在时后续反射解析会跳过）
        ALIAS_MAP.put("org.springframework.web.bind.annotation.GetMapping",
                "org.springframework.web.bind.annotation.RequestMapping");
        ALIAS_MAP.put("org.springframework.web.bind.annotation.PostMapping",
                "org.springframework.web.bind.annotation.RequestMapping");
        ALIAS_MAP.put("org.springframework.web.bind.annotation.PutMapping",
                "org.springframework.web.bind.annotation.RequestMapping");
        ALIAS_MAP.put("org.springframework.web.bind.annotation.DeleteMapping",
                "org.springframework.web.bind.annotation.RequestMapping");
        ALIAS_MAP.put("org.springframework.web.bind.annotation.PatchMapping",
                "org.springframework.web.bind.annotation.RequestMapping");
        ALIAS_MAP.put("org.springframework.web.bind.annotation.RequestMapping",
                "org.springframework.web.bind.annotation.RequestMapping");
    }

    // ---- 公共 API ----

    /**
     * 判断目标元素上是否存在指定注解（包含继承链和接口）。
     *
     * <p>遍历当前类、父类、实现接口，并检查注解是否被 {@link java.lang.annotation.Inherited} 标注。</p>
     *
     * @param element         注解所在的目标元素
     * @param annotationClass 待检查的注解类型
     * @return 如果存在则返回 {@code true}
     * @since 4.0.0.43
     */
    public static boolean isAnnotationPresent(AnnotatedElement element, Class<? extends Annotation> annotationClass) {
        if (element == null || annotationClass == null) {
            return false;
        }
        if (element.isAnnotationPresent(annotationClass)) {
            return true;
        }
        if (element instanceof Class) {
            return hasInheritedAnnotation((Class<?>) element, annotationClass);
        }
        return false;
    }

    /**
     * 判断类是否存在指定注解（向上遍历继承链）。
     *
     * @param clazz           目标类
     * @param annotationClass 待检查的注解类型
     * @return 如果存在则返回 {@code true}
     * @since 4.0.0.43
     */
    public static boolean isAnnotationPresent(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        if (clazz == null || annotationClass == null) {
            return false;
        }
        return isAnnotationPresent((AnnotatedElement) clazz, annotationClass);
    }

    /**
     * 判断方法是否存在指定注解（向上遍历继承链）。
     *
     * @param method          目标方法
     * @param annotationClass 待检查的注解类型
     * @return 如果存在则返回 {@code true}
     * @since 4.0.0.43
     */
    public static boolean isAnnotationPresent(Method method, Class<? extends Annotation> annotationClass) {
        if (method == null || annotationClass == null) {
            return false;
        }
        if (method.isAnnotationPresent(annotationClass)) {
            return true;
        }
        return hasOverriddenAnnotation(method, annotationClass);
    }

    /**
     * 获取目标元素上的注解（继承链查找），未找到时返回 {@code null}。
     *
     * @param element         注解所在的目标元素
     * @param annotationClass 注解类型
     * @param <A>             注解泛型
     * @return 找到的注解实例，未找到返回 {@code null}
     * @since 4.0.0.43
     */
    public static <A extends Annotation> A getAnnotation(AnnotatedElement element, Class<A> annotationClass) {
        if (element == null || annotationClass == null) {
            return null;
        }
        A annotation = element.getAnnotation(annotationClass);
        if (annotation != null) {
            return annotation;
        }
        if (element instanceof Class) {
            return findInheritedAnnotation((Class<?>) element, annotationClass);
        }
        return null;
    }

    /**
     * 获取类上的注解（继承链查找），未找到时返回 {@code null}。
     *
     * @param clazz           目标类
     * @param annotationClass 注解类型
     * @param <A>             注解泛型
     * @return 找到的注解实例，未找到返回 {@code null}
     * @since 4.0.0.43
     */
    public static <A extends Annotation> A getAnnotation(Class<?> clazz, Class<A> annotationClass) {
        return getAnnotation((AnnotatedElement) clazz, annotationClass);
    }

    /**
     * 获取方法上的注解（继承链查找），未找到时返回 {@code null}。
     *
     * @param method          目标方法
     * @param annotationClass 注解类型
     * @param <A>             注解泛型
     * @return 找到的注解实例，未找到返回 {@code null}
     * @since 4.0.0.43
     */
    public static <A extends Annotation> A getAnnotation(Method method, Class<A> annotationClass) {
        if (method == null || annotationClass == null) {
            return null;
        }
        A annotation = method.getAnnotation(annotationClass);
        if (annotation != null) {
            return annotation;
        }
        return findOverriddenAnnotation(method, annotationClass);
    }

    /**
     * 判断类是否包含任意 Spring MVC 映射注解
     * （{@code @RequestMapping}、{@code @GetMapping}、{@code @PostMapping} 等）。
     *
     * @param clazz 目标类
     * @return 如果存在任意映射注解则返回 {@code true}
     * @since 4.0.0.43
     */
    public static boolean isMappingAnnotation(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        for (String alias : ALIAS_MAP.keySet()) {
            try {
                Class<?> annClass = Class.forName(alias);
                if (isAnnotationPresent(clazz, (Class<? extends Annotation>) annClass)) {
                    return true;
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return false;
    }

    /**
     * 判断方法是否包含任意 Spring MVC 映射注解
     * （{@code @RequestMapping}、{@code @GetMapping}、{@code @PostMapping} 等）。
     *
     * @param method 目标方法
     * @return 如果存在任意映射注解则返回 {@code true}
     * @since 4.0.0.43
     */
    public static boolean isMappingAnnotation(Method method) {
        if (method == null) {
            return false;
        }
        for (String alias : ALIAS_MAP.keySet()) {
            try {
                Class<?> annClass = Class.forName(alias);
                if (isAnnotationPresent(method, (Class<? extends Annotation>) annClass)) {
                    return true;
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return false;
    }

    /**
     * 将 Spring 窄注解全限定名解析为对应的宽注解全限定名。
     *
     * <pre>
     * resolveRequestMappingAlias("org.springframework.web.bind.annotation.GetMapping")
     *     → "org.springframework.web.bind.annotation.RequestMapping"
     * resolveRequestMappingAlias("java.lang.Override")
     *     → "java.lang.Override"（无别名则返回原值）
     * </pre>
     *
     * @param annotationClassName 窄注解全限定名
     * @return 对应的宽注解全限定名，无别名时返回原值
     * @since 4.0.0.43
     */
    public static String resolveRequestMappingAlias(String annotationClassName) {
        if (annotationClassName == null || annotationClassName.isEmpty()) {
            return annotationClassName;
        }
        return ALIAS_MAP.getOrDefault(annotationClassName, annotationClassName);
    }

    /**
     * 将窄注解 Class 解析为对应的宽注解 Class（Spring MVC 映射注解场景）。
     *
     * <p>若该注解类存在别名映射，则返回宽注解类；否则返回原类本身。</p>
     *
     * @param annotationClass 窄注解类型
     * @return 对应的宽注解类型
     * @since 4.0.0.43
     */
    public static Class<? extends Annotation> resolveRequestMappingAlias(Class<? extends Annotation> annotationClass) {
        if (annotationClass == null) {
            return annotationClass;
        }
        String name = annotationClass.getName();
        String resolved = ALIAS_MAP.getOrDefault(name, name);
        if (resolved.equals(name)) {
            return annotationClass;
        }
        try {
            return (Class<? extends Annotation>) Class.forName(resolved);
        } catch (ClassNotFoundException e) {
            return annotationClass;
        }
    }

    // ---- private helpers ----

    private static boolean hasInheritedAnnotation(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        // 检查父类
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            if (isAnnotationPresent(superClass, annotationClass)) {
                return true;
            }
        }
        // 检查接口
        for (Class<?> iface : clazz.getInterfaces()) {
            if (isAnnotationPresent(iface, annotationClass)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A findInheritedAnnotation(Class<?> clazz, Class<A> annotationClass) {
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            A annotation = getAnnotation(superClass, annotationClass);
            if (annotation != null) {
                return annotation;
            }
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            A annotation = getAnnotation(iface, annotationClass);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    private static boolean hasOverriddenAnnotation(Method method, Class<? extends Annotation> annotationClass) {
        String name = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        Class<?> declaringClass = method.getDeclaringClass();

        for (Class<?> superClazz : getSuperClasses(declaringClass)) {
            try {
                for (Method superMethod : superClazz.getDeclaredMethods()) {
                    if (superMethod.getName().equals(name) &&
                            java.util.Arrays.equals(superMethod.getParameterTypes(), paramTypes)) {
                        if (superMethod.isAnnotationPresent(annotationClass)) {
                            return true;
                        }
                    }
                }
            } catch (NoClassDefFoundError ignored) {
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A findOverriddenAnnotation(Method method, Class<A> annotationClass) {
        String name = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        Class<?> declaringClass = method.getDeclaringClass();

        for (Class<?> superClazz : getSuperClasses(declaringClass)) {
            try {
                for (Method superMethod : superClazz.getDeclaredMethods()) {
                    if (superMethod.getName().equals(name) &&
                            java.util.Arrays.equals(superMethod.getParameterTypes(), paramTypes)) {
                        A annotation = (A) superMethod.getAnnotation(annotationClass);
                        if (annotation != null) {
                            return annotation;
                        }
                    }
                }
            } catch (NoClassDefFoundError ignored) {
            }
        }
        return null;
    }

    private static java.util.List<Class<?>> getSuperClasses(Class<?> clazz) {
        java.util.List<Class<?>> list = new java.util.ArrayList<>();
        Class<?> current = clazz.getSuperclass();
        while (current != null && current != Object.class) {
            list.add(current);
            current = current.getSuperclass();
        }
        return list;
    }

    // ---- 原有方法保留 ----

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
                Object value = ReflectUtils.invoke(annotation, method.getName(), Object.class);
                attributes.put(method.getName(), value);
            } catch (Exception e) {
                // ignore
            }
        }
        return attributes;
    }
}
