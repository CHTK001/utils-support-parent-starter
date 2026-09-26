package com.chua.common.support.lang.reflect;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

/**
 * 字段定义模型，表示一个字段的完整信息（类型、注解、修饰符）。
 *
 * <p>通过 {@link AnnotationUtils#getFieldDefinition(Field)} 创建，
 * 封装反射元数据 + 注解定义（递归解析元注解链）。</p>
 *
 * @param field 原始 Field 对象
 * @param declaringClass 声明类
 * @param fieldName 字段名
 * @param fieldType 字段类型
 * @param annotations 注解定义列表（递归解析元注解）
 * @param modifiers 修饰符
 * @author CH
 * @since 4.0.0.42
 */
public record FieldDefinition(
    Field field,
    Class<?> declaringClass,
    String fieldName,
    Class<?> fieldType,
    List<AnnotationDefinition> annotations,
    int modifiers
) {

    /**
     * 规范构造器：字段元数据必填，注解定义列表做防御性拷贝。
     *
     * <p>value class 前置条件——空值敌对，且集合组件必须深不可变。
     * {@code field} / {@code declaringClass} / {@code fieldName} / {@code fieldType}
     * 均被 {@link #toString()} 与 {@link #hasAnnotation} 无条件解引用；
     * 注解定义列表由 {@code AnnotationUtils#getFieldDefinition} 保证非空且元素非空，
     * 因此使用 {@link List#copyOf}。</p>
     *
     * @param field          原始 Field 对象，不允许为 null
     * @param declaringClass 声明类，不允许为 null
     * @param fieldName      字段名，不允许为 null
     * @param fieldType      字段类型，不允许为 null
     * @param annotations    注解定义列表，不允许为 null
     * @param modifiers      修饰符
     */
    public FieldDefinition {
        Objects.requireNonNull(field, "field 不能为 null");
        Objects.requireNonNull(declaringClass, "declaringClass 不能为 null");
        Objects.requireNonNull(fieldName, "fieldName 不能为 null");
        Objects.requireNonNull(fieldType, "fieldType 不能为 null");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations 不能为 null"));
    }

    /**
     * 判断是否为公开字段。
     *
     * @return {@code true} 如果字段为 public
     */
    public boolean isPublic() {
        return java.lang.reflect.Modifier.isPublic(modifiers);
    }

    /**
     * 判断是否为静态字段。
     *
     * @return {@code true} 如果字段为 static
     */
    public boolean isStatic() {
        return java.lang.reflect.Modifier.isStatic(modifiers);
    }

    /**
     * 判断是否为 final 字段。
     *
     * @return {@code true} 如果字段为 final
     */
    public boolean isFinal() {
        return java.lang.reflect.Modifier.isFinal(modifiers);
    }

    /**
     * 判断是否为 volatile 字段。
     *
     * @return {@code true} 如果字段为 volatile
     */
    public boolean isVolatile() {
        return java.lang.reflect.Modifier.isVolatile(modifiers);
    }

    /**
     * 判断是否为 transient 字段。
     *
     * @return {@code true} 如果字段为 transient
     */
    public boolean isTransient() {
        return java.lang.reflect.Modifier.isTransient(modifiers);
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
        if (isFinal()) {
            sb.append("final ");
        }
        if (isVolatile()) {
            sb.append("volatile ");
        }
        if (isTransient()) {
            sb.append("transient ");
        }
        sb.append(fieldType.getSimpleName()).append(" ").append(fieldName);
        return sb.toString();
    }
}
