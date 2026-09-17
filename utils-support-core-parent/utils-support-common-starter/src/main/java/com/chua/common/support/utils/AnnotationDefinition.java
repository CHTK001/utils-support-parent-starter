package com.chua.common.support.utils;

import java.lang.annotation.Annotation;

/**
 * 注解定义，封装解析结果。
 *
 * <p>由 {@link AnnotationUtils#resolveAnnotationDefinition} 返回，用于区分
 * 注解来源（直接声明 / 父类继承 / 子类覆盖）。</p>
 *
 * @param <A> 注解类型
 * @author CH
 * @since 4.0.0.43
*/
public final class AnnotationDefinition<A extends Annotation> {

    /**
    * 注解来源标记。
    * @author CH
    * @since 4.0.0
    */
    public enum Source {
        /** 直接在当前元素上声明 */
        DIRECT,
        /**
        * 从父类/父接口继承（{@link java.lang.annotation.Inherited}）
        */
        INHERITED,
        /** 从父类方法重写继承 */
        OVERRIDDEN_METHOD,
        /**
        * 通过别名解析找到（如 {@code @GetMapping} → {@code @RequestMapping}）
        *
        * @param annotation 注解
        * @param annotationClass 注解类
        * @return 的overridden方法的结果
        */
        ALIAS_RESOLVED
    }

    private final A annotation;
    private final Source source;
    private final Class<A> annotationClass;
    private final boolean subclassOverridesParent;

    private AnnotationDefinition(A annotation, Source source, Class<A> annotationClass,
                                 boolean subclassOverridesParent) {
        /**
        * 的direct。
        * @param annotation 注解
        * @param annotationClass 注解类
        * @return 的direct的结果
        */
        this.annotation = annotation;
        this.source = source;
        this.annotationClass = annotationClass;
        this.subclassOverridesParent = subclassOverridesParent;
    /**
    * 的inherited。
    * @param annotation 注解
    * @param annotationClass 注解类
    * @return 的inherited的结果
    */
    }

    public static <A extends Annotation> AnnotationDefinition<A> ofDirect(A annotation, Class<A> annotationClass) {
        return new AnnotationDefinition<>(annotation, Source.DIRECT, annotationClass, false);
    /**
    * 的别名resolved。
    * @param annotation 注解
    * @param annotationClass 注解类
    * @return 的别名resolved的结果
    */
    }

    public static <A extends Annotation> AnnotationDefinition<A> ofInherited(A annotation, Class<A> annotationClass) {
        return new AnnotationDefinition<>(annotation, Source.INHERITED, annotationClass, false);
    }

    public static <A extends Annotation> AnnotationDefinition<A> ofAliasResolved(A annotation, Class<A> annotationClass) {
        return new AnnotationDefinition<>(annotation, Source.ALIAS_RESOLVED, annotationClass, false);
    }

    public static <A extends Annotation> AnnotationDefinition<A> ofOverriddenMethod(A annotation, Class<A> annotationClass) {
        return new AnnotationDefinition<>(annotation, Source.OVERRIDDEN_METHOD, annotationClass, false);
    }

    /**
    * 创建子类覆盖父类的注解定义（子类优先标志置位）。
    * @param annotationClass 注解类
    * @param annotation 注解
    * @return subclassOverrides的结果
    */
    public static <A extends Annotation> AnnotationDefinition<A> subclassOverrides(Class<A> annotationClass, A annotation) {
        return new AnnotationDefinition<>(annotation, Source.DIRECT, annotationClass, true);
    }

    public A getAnnotation() {
        return annotation;
    }

    public Source getSource() {
        return source;
    }

    public Class<A> getAnnotationClass() {
        return annotationClass;
    }

    /**
    * 是否被子类覆盖（子类优先级高于父类）。
    * @return 是否subclassoverrides的结果
    */
    public boolean isSubclassOverrides() {
        return subclassOverridesParent;
    }

    @Override
    public String toString() {
        return "AnnotationDefinition{" +
                "annotation=" + annotation +
                ", source=" + source +
                ", annotationClass=" + annotationClass.getSimpleName() +
                ", subclassOverrides=" + subclassOverridesParent +
                '}';
    }
}
