package com.chua.common.support.lang.reflect;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * 方法定义模型，表示一个方法及其完整信息（注解、参数、返回类型、修饰符）。
 *
 * <p>通过 {@link AnnotationUtils#getMethodDefinition(Method)} 创建，
 * 封装反射元数据 + 注解定义（递归解析元注解链）。</p>
 *
 * @param method 原始 Method 对象
 * @param declaringClass 声明类
 * @param methodName 方法名
 * @param returnType 返回类型
 * @param parameters 参数定义列表
 * @param annotations 注解定义列表（递归解析元注解）
 * @param modifiers 修饰符
 * @author CH
 * @since 4.0.0.42
 */
public record MethodDefinition(
    Method method,
    Class<?> declaringClass,
    String methodName,
    Class<?> returnType,
        List<ParameterDefinition> parameters,
        List<AnnotationDefinition> annotations,
        int modifiers
) {

    /**
     * 规范构造器：方法元数据必填，参数与注解定义列表做防御性拷贝。
     *
     * <p>value class 前置条件——空值敌对，且集合组件必须深不可变。
     * {@code method} / {@code declaringClass} / {@code methodName} / {@code returnType}
     * 均被业务方法无条件解引用；两个列表由 {@code AnnotationUtils#getMethodDefinition}
     * 保证非空且元素非空，因此使用 {@link List#copyOf}。</p>
     *
     * @param method         原始 Method 对象，不允许为 null
     * @param declaringClass 声明类，不允许为 null
     * @param methodName     方法名，不允许为 null
     * @param returnType     返回类型，不允许为 null
     * @param parameters     参数定义列表，不允许为 null
     * @param annotations    注解定义列表，不允许为 null
     * @param modifiers      修饰符
     */
    public MethodDefinition {
        Objects.requireNonNull(method, "method 不能为 null");
        Objects.requireNonNull(declaringClass, "declaringClass 不能为 null");
        Objects.requireNonNull(methodName, "methodName 不能为 null");
        Objects.requireNonNull(returnType, "returnType 不能为 null");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters 不能为 null"));
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations 不能为 null"));
    }

    /**
     * 参数定义模型。
     *
     * @param name 参数名
     * @param type 参数类型
     * @param annotations 参数注解定义列表
     * @author CH
     * @since 4.0.0.42
     */
    public record ParameterDefinition(
        String name,
        Class<?> type,
        List<AnnotationDefinition> annotations
    ) {

        /**
         * 规范构造器：参数名与参数类型必填，参数注解定义列表做防御性拷贝。
         *
         * <p>value class 前置条件——空值敌对，且集合组件必须深不可变。
         * 列表由 {@code AnnotationUtils#getMethodDefinition} 保证非空且元素非空，
         * 因此使用 {@link List#copyOf}。</p>
         *
         * @param name        参数名，不允许为 null
         * @param type        参数类型，不允许为 null
         * @param annotations 参数注解定义列表，不允许为 null
         */
        public ParameterDefinition {
            Objects.requireNonNull(name, "name 不能为 null");
            Objects.requireNonNull(type, "type 不能为 null");
            annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations 不能为 null"));
        }
    }

    /**
     * 获取第一个参数类型。
     *
     * @return 第一个参数类型，无参返回 {@code null}
     */
    public Class<?> getFirstParamType() {
        return parameters.isEmpty() ? null : parameters.getFirst().type();
    }

    /**
     * 判断是否为公开方法。
     *
     * @return {@code true} 如果方法为 public
     */
    public boolean isPublic() {
        return java.lang.reflect.Modifier.isPublic(modifiers);
    }

    /**
     * 判断是否为静态方法。
     *
     * @return {@code true} 如果方法为 static
     */
    public boolean isStatic() {
        return java.lang.reflect.Modifier.isStatic(modifiers);
    }

    /**
     * 判断是否为抽象方法。
     *
     * @return {@code true} 如果方法为 abstract
     */
    public boolean isAbstract() {
        return java.lang.reflect.Modifier.isAbstract(modifiers);
    }

    /**
     * 判断是否包含指定注解类型（递归元注解链）。
     *
     * @param annotationClass 注解类
     * @return 是否包含
     */
    public boolean hasAnnotation(Class<? extends java.lang.annotation.Annotation> annotationClass) {
        for (AnnotationDefinition def : annotations) {
            if (def.annotationClass().isAssignableFrom(annotationClass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将定义转为字符串形式。
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (isPublic()) {
            sb.append("public ");
        }
        if (isStatic()) {
            sb.append("static ");
        }
        if (isAbstract()) {
            sb.append("abstract ");
        }
        sb.append(returnType.getSimpleName()).append(" ").append(methodName).append("(");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parameters.get(i).type().getSimpleName()).append(" ").append(parameters.get(i).name());
        }
        sb.append(")");
        return sb.toString();
    }
}
