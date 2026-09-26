package com.chua.common.support.lang.reflect;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 注解定义模型，表示一个注解及其完整元注解链（递归）。
 *
 * <p>包含注解类、属性键值对、元注解链、保留策略、目标元素类型和是否可继承等完整信息。</p>
 *
 * <p>元注解链支持无限递归解析：例如 {@code @RestController} 包含 {@code @Controller}，
 * 而 {@code @Controller} 又包含 {@code @Component}，三者都会被递归展开。</p>
 *
 * @param annotationClass 注解类
 * @param attributes 注解属性映射（元素名 → 值）
 * @param metaAnnotations 元注解定义列表（递归展开）
 * @param retention 保留策略
 * @param targets 目标元素类型数组
 * @param inherited 是否可继承
 * @param documented 是否文档化
 * @author CH
 * @since 4.0.0.42
 */
public record AnnotationDefinition(
    Class<? extends Annotation> annotationClass,
    Map<String, Object> attributes,
    List<AnnotationDefinition> metaAnnotations,
    RetentionPolicy retention,
    ElementType[] targets,
    boolean inherited,
    boolean documented
) {

    /**
     * 规范构造器：注解类必填，属性映射、元注解链与目标数组做防御性拷贝。
     *
     * <p>value class 前置条件——空值敌对，且集合与数组组件必须深不可变。
     * {@code AnnotationUtils#extractAttributeMethods} 以 {@code null} 作为属性值的初始值，
     * 因此属性映射含 null 值，只能用可容纳 null 的不可变包装，不能用 {@code Map.copyOf}。
     * 元注解链元素为非空记录，可用 {@link List#copyOf}。</p>
     *
     * <p>目标元素类型数组保留 null 语义：{@code AnnotationUtils#getTargets} 当前总是返回
     * 非空数组，但本记录是公开 API，无从证明外部调用方不传 null。</p>
     *
     * @param annotationClass 注解类，不允许为 null
     * @param attributes      注解属性映射（元素名 → 值），不允许为 null
     * @param metaAnnotations 元注解定义列表（递归展开），不允许为 null
     * @param retention       保留策略
     * @param targets         目标元素类型数组，可为 null
     * @param inherited       是否可继承
     * @param documented      是否文档化
     */
    public AnnotationDefinition {
        Objects.requireNonNull(annotationClass, "annotationClass 不能为 null");
        attributes = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes 不能为 null")));
        metaAnnotations = List.copyOf(
                Objects.requireNonNull(metaAnnotations, "metaAnnotations 不能为 null"));
        targets = targets == null ? null : targets.clone();
    }

    /**
     * 访问器覆写：返回内部目标元素类型数组的副本。
     *
     * <p>value class 前置条件——外部不得持有内部数组引用。</p>
     *
     * @return 目标元素类型数组副本；targets 为 null 时返回 null
     */
    @Override
    public ElementType[] targets() {
        return targets == null ? null : targets.clone();
    }

    /**
     * 获取元注解链的完整路径（含自身），用于调试和日志。
     *
     * <p>例如: {@code @MyAnno -> @Target -> @Retention}</p>
     *
     * @return 注解全限定名列表
     */
    public List<String> fullChain() {
        List<String> chain = new ArrayList<>();
        chain.add(annotationClass.getName());
        for (AnnotationDefinition meta : metaAnnotations) {
            chain.addAll(meta.fullChain());
        }
        return chain;
    }

    /**
     * 获取指定嵌套层级的属性值。
     *
     * @param name 属性名
     * @return 属性值，找不到返回 {@code null}
     */
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    /**
     * 判断是否包含指定属性名。
     *
     * @param name 属性名
     * @return 是否包含
     */
    public boolean hasAttribute(String name) {
        return attributes.containsKey(name);
    }

    /**
     * 判断元注解链中是否包含指定注解类型。
     *
     * @param annotationClass 待查找注解类
     * @return 是否包含
     */
    public boolean hasMetaAnnotation(Class<? extends Annotation> annotationClass) {
        for (AnnotationDefinition meta : metaAnnotations) {
            if (meta.annotationClass().isAssignableFrom(annotationClass)) {
                return true;
            }
            if (meta.hasMetaAnnotation(annotationClass)) {
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
        sb.append("@").append(annotationClass.getSimpleName());
        if (!attributes.isEmpty()) {
            sb.append("(");
            int i = 0;
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                i++;
            }
            sb.append(")");
        }
        return sb.toString();
    }
}
