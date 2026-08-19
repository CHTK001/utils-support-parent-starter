package com.chua.common.support.converter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 字段映射上下文 — 封装转换时的配置参数与上下文数据。
 *
 * <p>在 {@link FieldConverter#convert(Object, FieldMappingContext)} 中被传入，
 * 提供格式化模式、上下文变量、默认值等信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FieldMappingContext {

    /** 字段名称 */
    /** Field名称 */
    private final String fieldName;

    /** 映射后的列名 */
    /** Mapped名称 */
    private final String mappedName;

    /** 日期格式（可能为空） */
    /** 格式 */
    private final String format;

    /** 默认值表达式（可能为空） */
    /** 默认值 */
    private final String defaultValue;

    /** 上下文数据（用于 #{key} 表达式解析） */
    private final Map<String, Object> context;

    /** 字段的原始值（转换前的值） */
    /** Original值 */
    private Object originalValue;

    FieldMappingContext(String fieldName, String mappedName, String format,
                        String defaultValue, Map<String, Object> context, Object originalValue) {
        this.fieldName = fieldName;
        this.mappedName = mappedName;
        this.format = format;
        this.defaultValue = defaultValue;
        this.context = context != null ? context : Map.of();
        this.originalValue = originalValue;
    }

    /**
     * 创建 FieldMappingContext 构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getMappedName() {
        return mappedName;
    }

    public String getFormat() {
        return format;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public Object getOriginalValue() {
        return originalValue;
    }

    /**
     * 构建器。
     */
    public static class Builder {
        /** 字段名称 */
        /** Field名称 */
        private String fieldName;
        /** 映射目标名称 */
        /** Mapped名称 */
        private String mappedName;
        /** 日期格式 */
        /** 格式 */
        private String format;
        /** 默认值 */
        private String defaultValue;
        /** 上下文对象 */
        private Map<String, Object> context;
        /** 原始值 */
        /** Original值 */
        private Object originalValue;

        Builder() {
        }

        public Builder fieldName(String fieldName) {
            this.fieldName = fieldName;
            return this;
        }

        public Builder mappedName(String mappedName) {
            this.mappedName = mappedName;
            return this;
        }

        public Builder format(String format) {
            this.format = format;
            return this;
        }

        public Builder defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder context(Map<String, Object> context) {
            this.context = context;
            return this;
        }

        public Builder originalValue(Object originalValue) {
            this.originalValue = originalValue;
            return this;
        }

        public FieldMappingContext build() {
            return new FieldMappingContext(fieldName, mappedName, format, defaultValue, context, originalValue);
        }
    }
}