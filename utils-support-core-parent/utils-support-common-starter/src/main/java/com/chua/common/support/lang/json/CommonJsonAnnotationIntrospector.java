package com.chua.common.support.lang.json;

import com.chua.common.support.lang.json.annotation.JsonFormat;
import com.chua.common.support.lang.json.annotation.JsonIgnore;
import com.chua.common.support.lang.json.annotation.JsonName;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

/**
 * 统一门户注解到 Jackson 的适配器（AnnotationIntrospector）。
 *
 * <p>将 common-starter 的统一 JSON 注解（{@link JsonName}、{@link JsonIgnore}、{@link JsonFormat}）
 * 映射为 Jackson 内部的属性语义，使业务实体只需标注门户注解即可被 Jackson 实现识别，
 * 无需引入 Jackson 专属注解（{@code @JsonProperty} 等）。</p>
 *
 * <p>适配规则：</p>
 * <ul>
 *     <li>{@link JsonName} → 序列化 / 反序列化的字段名</li>
 *     <li>{@link JsonIgnore} → 忽略标记（双向）</li>
 *     <li>{@link JsonFormat} → 日期时间格式化 pattern</li>
 * </ul>
 *
 * <p>本类继承 {@link JacksonAnnotationIntrospector}，在优先读取门户注解的同时保留
 * Jackson 原生注解（{@code @JsonProperty} 等）的兼容支持，两套注解可共存。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CommonJsonAnnotationIntrospector extends JacksonAnnotationIntrospector {

    /**
     * 序列化字段名：优先门户注解 {@link JsonName}，否则回退 Jackson 原生逻辑。
     *
     * @param a 待序列化的属性
     * @return 字段名
     */
    @Override
    public PropertyName findNameForSerialization(Annotated a) {
        JsonName jsonName = a.getAnnotation(JsonName.class);
        if (null != jsonName && !jsonName.value().isEmpty()) {
            return PropertyName.construct(jsonName.value());
        }
        return super.findNameForSerialization(a);
    }

    /**
     * 反序列化字段名：优先门户注解 {@link JsonName}，否则回退 Jackson 原生逻辑。
     *
     * @param a 待反序列化的属性
     * @return 字段名
     */
    @Override
    public PropertyName findNameForDeserialization(Annotated a) {
        JsonName jsonName = a.getAnnotation(JsonName.class);
        if (null != jsonName && !jsonName.value().isEmpty()) {
            return PropertyName.construct(jsonName.value());
        }
        return super.findNameForDeserialization(a);
    }

    /**
     * 忽略标记：存在门户注解 {@link JsonIgnore} 时返回 true，否则回退 Jackson 原生逻辑。
     *
     * @param m 属性成员
     * @return 是否忽略
     */
    @Override
    public boolean hasIgnoreMarker(AnnotatedMember m) {
        if (null != m.getAnnotation(JsonIgnore.class)) {
            return true;
        }
        return super.hasIgnoreMarker(m);
    }

    /**
     * 日期格式：存在门户注解 {@link JsonFormat} 时映射为 Jackson 格式，否则回退原生逻辑。
     *
     * @param memberOrClass 属性或类
     * @return Jackson 格式定义
     */
    @Override
    public com.fasterxml.jackson.annotation.JsonFormat.Value findFormat(Annotated memberOrClass) {
        JsonFormat jsonFormat = memberOrClass.getAnnotation(JsonFormat.class);
        if (null != jsonFormat && !jsonFormat.value().isEmpty()) {
            return com.fasterxml.jackson.annotation.JsonFormat.Value.forPattern(jsonFormat.value());
        }
        return super.findFormat(memberOrClass);
    }
}
