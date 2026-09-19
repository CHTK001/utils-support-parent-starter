package com.chua.common.support.lang.reflect;

import com.chua.common.support.reflection.ReflectUtils;
import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注解反射工具类，提供注解解析、递归元注解链展开、以及注解/方法/字段/类定义模型创建功能。
 *
 * <p>核心能力：</p>
 * <ul>
 *     <li>解析注解属性：从 {@link Class} 反射提取注解元素键值对</li>
 *     <li>递归元注解链：自动展开注解上标注的元注解（如 {@code @RestController} → {@code @Controller} → {@code @Component}）</li>
 *     <li>定义模型：通过 {@link AnnotationDefinition}、{@link MethodDefinition}、{@link FieldDefinition}、
 *     {@link ClassDefinition} 统一封装反射元数据</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>
 *   // 1. 解析注解属性（基础功能）
 *   Map&lt;String, Object&gt; attrs = AnnotationUtils.getAnnotationAttributes(MyClass.class, Service.class);
 *
 *   // 2. 递归解析注解定义（含元注解链）
 *   AnnotationDefinition def = AnnotationUtils.getAnnotationDefinition(Controller.class);
 *   def.metaAnnotations();  // 包含 @Component, @Retention 等元注解定义
 *
 *   // 3. 解析方法定义（含参数定义 + 递归注解链）
 *   MethodDefinition methodDef = AnnotationUtils.getMethodDefinition(someMethod);
 *   methodDef.hasAnnotation(Override.class);
 *
 *   // 4. 解析字段定义
 *   FieldDefinition fieldDef = AnnotationUtils.getFieldDefinition(someField);
 *
 *   // 5. 获取完整类定义
 *   ClassDefinition classDef = AnnotationUtils.getClassDefinition(MyClass.class);
 *   classDef.methods().stream().filter(m -&gt; m.hasAnnotation(Service.class))...
 * </pre>
 *
 * <h2>递归元注解链示例</h2>
 * <pre>
 *   注解: @RestController
 *   元注解链: @Controller → @Component → @Retention → @Target → @Documented
 *             → @Retention → @Target → @Documented
 *             → @Retention → @Target → @Documented
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class AnnotationUtils {

    /** 创建 AnnotationUtils 实例 */
    private AnnotationUtils() {
    }

    /**
     * 获取类上的注解属性映射。
     *
     * <p>遍历注解类中所有声明的方法，通过反射提取对应属性值，组装为 {@link Map}。</p>
     *
     * @param clazz           目标类
     * @param annotationClass 注解类
     * @return 注解属性映射，键为元素名，值为元素值；未找到注解返回空 Map
     */
    public static Map<String, Object> getAnnotationAttributes(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        Annotation annotation = clazz.getAnnotation(annotationClass);
        if (annotation == null) {
            return attributes;
        }
        for (Method method : annotationClass.getDeclaredMethods()) {
            try {
                Object value = ReflectUtils.invoke(annotation, method.getName(), Object.class);
                attributes.put(method.getName(), value);
            } catch (Exception ignored) {
            }
        }
        return attributes;
    }

    /**
     * 递归解析注解定义，包含完整的元注解链。
     *
     * <p>从指定注解类出发，递归展开所有元注解（注解上的注解），每个注解生成一个 {@link AnnotationDefinition}。
     * 元注解链支持无限深度递归。</p>
     *
     * @param annotationClass 注解类
     * @return 注解定义
     */
    public static AnnotationDefinition getAnnotationDefinition(Class<? extends Annotation> annotationClass) {
        Map<String, Object> attributes = extractAttributeMethods(annotationClass);
        RetentionPolicy retention = getRetentionPolicy(annotationClass);
        ElementType[] targets = getTargets(annotationClass);
        boolean inherited = isInherited(annotationClass);
        boolean documented = isDocumented(annotationClass);

        List<AnnotationDefinition> metaDefinitions = new ArrayList<>();
        for (Annotation meta : annotationClass.getAnnotations()) {
            metaDefinitions.add(getAnnotationDefinition(meta.annotationType()));
        }

        return new AnnotationDefinition(
                annotationClass,
                attributes,
                metaDefinitions,
                retention,
                targets,
                inherited,
                documented
        );
    }

    /**
     * 递归解析方法定义，包含方法上的所有注解（含元注解链）以及参数上的注解。
     *
     * @param method 目标方法
     * @return 方法定义
     */
    public static MethodDefinition getMethodDefinition(Method method) {
        List<AnnotationDefinition> annotationDefs = getAnnotationsFor(method);
        List<MethodDefinition.ParameterDefinition> paramDefs = new ArrayList<>();
        java.lang.reflect.Parameter[] params = method.getParameters();
        for (java.lang.reflect.Parameter param : params) {
            List<AnnotationDefinition> paramAnnos = getAnnotationsFor(param);
            paramDefs.add(new MethodDefinition.ParameterDefinition(param.getName(), param.getType(), paramAnnos));
        }
        return new MethodDefinition(
                method,
                method.getDeclaringClass(),
                method.getName(),
                method.getReturnType(),
                paramDefs,
                annotationDefs,
                method.getModifiers()
        );
    }

    /**
     * 递归解析字段定义，包含字段上的所有注解（含元注解链）。
     *
     * @param field 目标字段
     * @return 字段定义
     */
    public static FieldDefinition getFieldDefinition(Field field) {
        List<AnnotationDefinition> annotationDefs = getAnnotationsFor(field);
        return new FieldDefinition(
                field,
                field.getDeclaringClass(),
                field.getName(),
                field.getType(),
                annotationDefs,
                field.getModifiers()
        );
    }

    /**
     * 递归解析类定义，包含类上的所有注解、所有方法定义和字段定义。
     *
     * <p>方法定义包含构造器，按声明顺序排列。</p>
     *
     * @param clazz 目标类
     * @return 类定义
     */
    public static ClassDefinition getClassDefinition(Class<?> clazz) {
        List<AnnotationDefinition> annotationDefs = getAnnotationsFor(clazz);

        List<MethodDefinition> methodDefs = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            methodDefs.add(getMethodDefinition(method));
        }

        List<FieldDefinition> fieldDefs = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            fieldDefs.add(getFieldDefinition(field));
        }

        List<Class<?>> interfaces = new ArrayList<>();
        for (Class<?> iface : clazz.getInterfaces()) {
            interfaces.add(iface);
        }

        return new ClassDefinition(
                clazz,
                clazz.getSuperclass(),
                interfaces,
                annotationDefs,
                methodDefs,
                fieldDefs,
                clazz.getModifiers()
        );
    }

    /**
     * 获取指定元素上所有注解的完整定义（递归展开元注解链）。
     *
     * @param element 注解元素
     * @return 注解定义列表
     */
    private static List<AnnotationDefinition> getAnnotationsFor(java.lang.reflect.AnnotatedElement element) {
        List<AnnotationDefinition> defs = new ArrayList<>();
        for (Annotation ann : element.getDeclaredAnnotations()) {
            defs.add(getAnnotationDefinition(ann.annotationType()));
        }
        return defs;
    }

    /**
     * 提取注解类中所有声明的方法对应的属性名映射（初始值为 null，仅提取元素名）。
     *
     * @param annotationClass 注解类
     * @return 属性名映射（初始为空）
     */
    private static Map<String, Object> extractAttributeMethods(Class<? extends Annotation> annotationClass) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Method method : annotationClass.getDeclaredMethods()) {
            attributes.put(method.getName(), null);
        }
        return attributes;
    }

    /**
     * 从注解类获取保留策略。
     *
     * @param annotationClass 注解类
     * @return 保留策略，未标注返回 {@code RetentionPolicy.CLASS}
     */
    private static RetentionPolicy getRetentionPolicy(Class<? extends Annotation> annotationClass) {
        Retention retention = annotationClass.getAnnotation(Retention.class);
        return (retention != null) ? retention.value() : RetentionPolicy.CLASS;
    }

    /**
     * 从注解类获取目标元素类型数组。
     *
     * @param annotationClass 注解类
     * @return 目标元素类型数组，未标注返回空数组
     */
    private static ElementType[] getTargets(Class<? extends Annotation> annotationClass) {
        Target target = annotationClass.getAnnotation(Target.class);
        return (target != null) ? target.value() : new ElementType[0];
    }

    /**
     * 判断注解是否标注了 {@link Inherited}。
     *
     * @param annotationClass 注解类
     * @return {@code true} 如果标注了 {@code @Inherited}
     */
    private static boolean isInherited(Class<? extends Annotation> annotationClass) {
        return annotationClass.isAnnotationPresent(Inherited.class);
    }

    /**
     * 判断注解是否标注了 {@link Documented}。
     *
     * @param annotationClass 注解类
     * @return {@code true} 如果标注了 {@code @Documented}
     */
    private static boolean isDocumented(Class<? extends Annotation> annotationClass) {
        return annotationClass.isAnnotationPresent(Documented.class);
    }
}
