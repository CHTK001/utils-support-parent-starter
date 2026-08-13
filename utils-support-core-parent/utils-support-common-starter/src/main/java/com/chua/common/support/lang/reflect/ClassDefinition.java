package com.chua.common.support.lang.reflect;

import java.util.List;

/**
 * 类定义模型，表示一个类的完整信息（继承关系、注解、方法、字段）。
 *
 * <p>通过 {@link AnnotationUtils#getClassDefinition(Class)} 创建，
 * 封装反射元数据 + 递归解析的注解定义链。</p>
 *
 * @param clazz 原始 Class 对象
 * @param superclass 父类
 * @param interfaces 实现的接口列表
 * @param annotations 类注解定义列表（递归解析元注解）
 * @param methods 方法定义列表（含构造器）
 * @param fields 字段定义列表
 * @param modifiers 修饰符
 * @author CH
 * @since 4.0.0.42
 */
public record ClassDefinition(
    Class<?> clazz,
    Class<?> superclass,
    List<Class<?>> interfaces,
    List<AnnotationDefinition> annotations,
    List<MethodDefinition> methods,
    List<FieldDefinition> fields,
    int modifiers
) {

    /**
     * 判断是否为公开类。
     *
     * @return {@code true} 如果类为 public
     */
    public boolean isPublic() {
        return java.lang.reflect.Modifier.isPublic(modifiers);
    }

    /**
     * 判断是否为抽象类。
     *
     * @return {@code true} 如果类为 abstract
     */
    public boolean isAbstract() {
        return java.lang.reflect.Modifier.isAbstract(modifiers);
    }

    /**
     * 判断是否为 final 类。
     *
     * @return {@code true} 如果类为 final
     */
    public boolean isFinal() {
        return java.lang.reflect.Modifier.isFinal(modifiers);
    }

    /**
     * 判断是否为接口。
     *
     * @return {@code true} 如果为接口
     */
    public boolean isInterface() {
        return clazz.isInterface();
    }

    /**
     * 判断是否为枚举。
     *
     * @return {@code true} 如果为枚举
     */
    public boolean isEnum() {
        return clazz.isEnum();
    }

    /**
     * 判断是否为注解类型。
     *
     * @return {@code true} 如果为注解
     */
    public boolean isAnnotation() {
        return clazz.isAnnotation();
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
     * 查找指定方法名的方法定义。
     *
     * @param methodName 方法名
     * @return 找到的方法定义，未找到返回 {@code null}
     */
    public MethodDefinition findMethod(String methodName) {
        for (MethodDefinition md : methods) {
            if (md.methodName().equals(methodName)) {
                return md;
            }
        }
        return null;
    }

    /**
     * 查找指定字段名的字段定义。
     *
     * @param fieldName 字段名
     * @return 找到的字段定义，未找到返回 {@code null}
     */
    public FieldDefinition findField(String fieldName) {
        for (FieldDefinition fd : fields) {
            if (fd.fieldName().equals(fieldName)) {
                return fd;
            }
        }
        return null;
    }

    /**
     * 获取指定参数个数、指定方法名的方法定义（用于重载消歧）。
     *
     * @param methodName 方法名
     * @param paramCount 参数个数
     * @return 匹配的方法定义，未找到返回 {@code null}
     */
    public MethodDefinition findMethod(String methodName, int paramCount) {
        for (MethodDefinition md : methods) {
            if (md.methodName().equals(methodName) && md.parameters().size() == paramCount) {
                return md;
            }
        }
        return null;
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
        if (isInterface()) {
            sb.append("interface ");
        } else if (isAnnotation()) {
            sb.append("@interface ");
        } else if (isEnum()) {
            sb.append("enum ");
        } else if (isAbstract()) {
            sb.append("abstract ");
        } else if (isFinal()) {
            sb.append("final ");
        }
        sb.append(clazz.getSimpleName());
        if (superclass != null && !superclass.equals(Object.class)) {
            sb.append(" extends ").append(superclass.getSimpleName());
        }
        if (!interfaces.isEmpty()) {
            sb.append(" implements ");
            for (int i = 0; i < interfaces.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(interfaces.get(i).getSimpleName());
            }
        }
        sb.append(" {").append(methods.size()).append(" methods, ").append(fields.size()).append(" fields}");
        return sb.toString();
    }
}
