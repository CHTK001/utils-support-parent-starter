package com.chua.common.support.export.meta;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 列元数据 —— 由多个 {@code MetaResolver} 按优先级叠加构建的不可变视图。
 *
 * <p><b>叠加语义</b>：解析器依次写入（{@code put}），后写入者直接覆盖先写入者；
 * 未写入的属性保留低优先级来源的结果。这实现了"三方注解 → 自定义注解 → 反射兜底"
 * 的分层覆盖，且不需要解析器感知彼此。</p>
 *
 * <p><b>覆盖追踪</b>：{@link #defined()} 返回被显式声明过的属性集合，供实现方判断
 * "该列是否被自定义注解接管"。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ColumnMeta {

    /**
     * 列元数据可被覆盖的属性。
     */
    public enum Attribute {
        /**
         * 列标题。
         */
        NAME,
        /**
         * 列顺序。
         */
        ORDER,
        /**
         * 列宽。
         */
        WIDTH,
        /**
         * 是否忽略。
         */
        IGNORE,
        /**
         * 列类型。
         */
        TYPE,
        /**
         * 日期格式。
         */
        DATE_FORMAT,
        /**
         * 枚举映射。
         */
        ENUM_NAMES,
        /**
         * 前缀。
         */
        PREFIX,
        /**
         * 后缀。
         */
        SUFFIX,
        /**
         * 默认值。
         */
        DEFAULT_VALUE,
        /**
         * 对齐。
         */
        ALIGN,
        /**
         * 换行。
         */
        WRAP,
        /**
         * 转换器标识。
         */
        CONVERTER,
        /**
         * 取值表达式。
         */
        EXPRESSION,
        /**
         * 模板标签名。
         */
        TAG,
        /**
         * 精度。
         */
        SCALE,
        /**
         * 反射访问器（Field）。
         */
        FIELD,
        /**
         * 反射访问器（Method）。
         */
        GETTER
    }

    /**
     * 列对应的属性名（由 {@code DocumentMeta} 注入）。
     */
    private String property = "";

    /**
     * 列标题。
     */
    private String name = "";

    /**
     * 属性顺序。
     */
    private int order;

    /**
     * 列宽，0 表示自动。
     */
    private int width;

    /**
     * 是否忽略。
     */
    private boolean ignore;

    /**
     * 列类型。
     */
    private ColumnType type = ColumnType.AUTO;

    /**
     * 日期格式。
     */
    private String dateFormat = "";

    /**
     * 枚举映射表：值 → 文本。
     */
    private final Map<String, String> enumNames = new LinkedHashMap<>();

    /**
     * 前缀。
     */
    private String prefix = "";

    /**
     * 后缀。
     */
    private String suffix = "";

    /**
     * 空值默认值。
     */
    private String defaultValue = "";

    /**
     * 水平对齐。
     */
    private HorizontalAlign align = HorizontalAlign.AUTO;

    /**
     * 是否换行。
     */
    private boolean wrap;

    /**
     * 转换器标识。
     */
    private String converter = "";

    /**
     * 取值表达式。
     */
    private String expression = "";

    /**
     * 模板标签名。
     */
    private String tag = "";

    /**
     * 精度，-1 表示不限定。
     */
    private int scale = -1;

    /**
     * 反射字段访问器。
     */
    private Field field;

    /**
     * 反射方法访问器。
     */
    private Method getter;

    /**
     * 被显式声明过的属性。
     */
    private final java.util.EnumSet<Attribute> defined = java.util.EnumSet.noneOf(Attribute.class);

    /**
     * 声明列名。
     *
     * @param value 列名，空值忽略
     * @return 当前对象
     */
    public ColumnMeta name(String value) {
        if (value != null && !value.isEmpty()) {
            this.name = value;
            this.defined.add(Attribute.NAME);
        }
        return this;
    }

    /**
     * 声明列顺序。
     *
     * @param value 列顺序
     * @return 当前对象
     */
    public ColumnMeta order(int value) {
        this.order = value;
        this.defined.add(Attribute.ORDER);
        return this;
    }

    /**
     * 声明列宽。
     *
     * @param value 列宽，0 表示自动
     * @return 当前对象
     */
    public ColumnMeta width(int value) {
        this.width = Math.max(0, value);
        this.defined.add(Attribute.WIDTH);
        return this;
    }

    /**
     * 声明是否忽略。
     *
     * @param value 忽略标记
     * @return 当前对象
     */
    public ColumnMeta ignore(boolean value) {
        this.ignore = value;
        this.defined.add(Attribute.IGNORE);
        return this;
    }

    /**
     * 声明列类型。
     *
     * @param value 列类型，{@link ColumnType#AUTO} 视为未声明
     * @return 当前对象
     */
    public ColumnMeta type(ColumnType value) {
        if (value != null && value != ColumnType.AUTO) {
            this.type = value;
            this.defined.add(Attribute.TYPE);
        }
        return this;
    }

    /**
     * 声明日期格式。
     *
     * @param value 日期格式，空值忽略
     * @return 当前对象
     */
    public ColumnMeta dateFormat(String value) {
        if (value != null && !value.isEmpty()) {
            this.dateFormat = value;
            this.defined.add(Attribute.DATE_FORMAT);
        }
        return this;
    }

    /**
     * 声明枚举映射表。
     *
     * @param mapping 值 → 文本
     * @return 当前对象
     */
    public ColumnMeta enumNames(Map<String, String> mapping) {
        if (mapping != null && !mapping.isEmpty()) {
            this.enumNames.clear();
            this.enumNames.putAll(mapping);
            this.defined.add(Attribute.ENUM_NAMES);
        }
        return this;
    }

    /**
     * 声明枚举映射表（紧凑字符串）。
     *
     * @param specs {@code "0=禁用,1=启用"}
     * @return 当前对象
     */
    public ColumnMeta enumNames(String... specs) {
        if (specs == null || specs.length == 0) {
            return this;
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String spec : specs) {
            if (spec == null || spec.isBlank()) {
                continue;
            }
            int idx = spec.indexOf('=');
            if (idx < 0) {
                mapping.put(spec.trim(), spec.trim());
            } else {
                mapping.put(spec.substring(0, idx).trim(), spec.substring(idx + 1));
            }
        }
        return enumNames(mapping);
    }

    /**
     * 声明前缀。
     *
     * @param value 前缀
     * @return 当前对象
     */
    public ColumnMeta prefix(String value) {
        this.prefix = value == null ? "" : value;
        this.defined.add(Attribute.PREFIX);
        return this;
    }

    /**
     * 声明后缀。
     *
     * @param value 后缀
     * @return 当前对象
     */
    public ColumnMeta suffix(String value) {
        this.suffix = value == null ? "" : value;
        this.defined.add(Attribute.SUFFIX);
        return this;
    }

    /**
     * 声明默认值。
     *
     * @param value 默认值
     * @return 当前对象
     */
    public ColumnMeta defaultValue(String value) {
        this.defaultValue = value == null ? "" : value;
        this.defined.add(Attribute.DEFAULT_VALUE);
        return this;
    }

    /**
     * 声明对齐方式。
     *
     * @param value 对齐方式，{@link HorizontalAlign#AUTO} 视为未声明
     * @return 当前对象
     */
    public ColumnMeta align(HorizontalAlign value) {
        if (value != null && value != HorizontalAlign.AUTO) {
            this.align = value;
            this.defined.add(Attribute.ALIGN);
        }
        return this;
    }

    /**
     * 声明是否换行。
     *
     * @param value 换行标记
     * @return 当前对象
     */
    public ColumnMeta wrap(boolean value) {
        this.wrap = value;
        this.defined.add(Attribute.WRAP);
        return this;
    }

    /**
     * 声明转换器标识。
     *
     * @param value 转换器标识
     * @return 当前对象
     */
    public ColumnMeta converter(String value) {
        if (value != null && !value.isEmpty()) {
            this.converter = value;
            this.defined.add(Attribute.CONVERTER);
        }
        return this;
    }

    /**
     * 声明取值表达式。
     *
     * @param value 取值表达式，空值忽略
     * @return 当前对象
     */
    public ColumnMeta expression(String value) {
        if (value != null && !value.isEmpty()) {
            this.expression = value;
            this.defined.add(Attribute.EXPRESSION);
        }
        return this;
    }

    /**
     * 声明模板标签名。
     *
     * @param value 标签名，空值忽略
     * @return 当前对象
     */
    public ColumnMeta tag(String value) {
        if (value != null && !value.isEmpty()) {
            this.tag = value;
            this.defined.add(Attribute.TAG);
        }
        return this;
    }

    /**
     * 声明精度。
     *
     * @param value 精度，-1 表示不限定
     * @return 当前对象
     */
    public ColumnMeta scale(int value) {
        if (value >= 0) {
            this.scale = value;
            this.defined.add(Attribute.SCALE);
        }
        return this;
    }

    /**
     * 声明字段访问器。
     *
     * @param value 字段访问器
     * @return 当前对象
     */
    public ColumnMeta field(Field value) {
        if (value != null) {
            this.field = value;
            this.defined.add(Attribute.FIELD);
        }
        return this;
    }

    /**
     * 声明方法访问器。
     *
     * @param value 方法访问器
     * @return 当前对象
     */
    public ColumnMeta getter(Method value) {
        if (value != null) {
            this.getter = value;
            this.defined.add(Attribute.GETTER);
        }
        return this;
    }

    /**
     * 判断某属性是否被显式声明。
     *
     * @param attribute 属性
     * @return 是否已声明
     */
    public boolean isDefined(Attribute attribute) {
        return defined.contains(attribute);
    }

    /**
     * 获取已声明的属性集合。
     *
     * @return 属性集合
     */
    public java.util.Set<Attribute> defined() {
        return Collections.unmodifiableSet(defined);
    }

    /**
     * 获取列标题。
     *
     * @return 列标题
     */
    public String getName() {
        return name;
    }

    /**
     * 获取属性名。
     *
     * @return 属性名
     */
    public String getProperty() {
        return property;
    }

    /**
     * 设置属性名（仅由 {@code DocumentMeta} 调用）。
     *
     * @param value 属性名
     */
    public void setProperty(String value) {
        this.property = value == null ? "" : value;
    }

    /**
     * 获取列顺序。
     *
     * @return 列顺序
     */
    public int getOrder() {
        return order;
    }

    /**
     * 获取列宽。
     *
     * @return 列宽
     */
    public int getWidth() {
        return width;
    }

    /**
     * 是否忽略该列。
     *
     * @return 忽略标记
     */
    public boolean isIgnore() {
        return ignore;
    }

    /**
     * 获取列类型。
     *
     * @return 列类型
     */
    public ColumnType getType() {
        return type;
    }

    /**
     * 获取日期格式。
     *
     * @return 日期格式
     */
    public String getDateFormat() {
        return dateFormat;
    }

    /**
     * 获取枚举映射表。
     *
     * @return 枚举映射表
     */
    public Map<String, String> getEnumNames() {
        return Collections.unmodifiableMap(enumNames);
    }

    /**
     * 获取前缀。
     *
     * @return 前缀
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * 获取后缀。
     *
     * @return 后缀
     */
    public String getSuffix() {
        return suffix;
    }

    /**
     * 获取默认值。
     *
     * @return 默认值
     */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * 获取对齐方式；{@link HorizontalAlign#AUTO} 时按列类型推断。
     *
     * @return 对齐方式
     */
    public HorizontalAlign getAlign() {
        if (align != HorizontalAlign.AUTO) {
            return align;
        }
        if (type.isNumeric() || type == ColumnType.BOOLEAN) {
            return HorizontalAlign.RIGHT;
        }
        if (type == ColumnType.ENUM) {
            return HorizontalAlign.CENTER;
        }
        return HorizontalAlign.LEFT;
    }

    /**
     * 是否换行。
     *
     * @return 换行标记
     */
    public boolean isWrap() {
        return wrap;
    }

    /**
     * 获取转换器标识。
     *
     * @return 转换器标识
     */
    public String getConverter() {
        return converter;
    }

    /**
     * 获取取值表达式。
     *
     * @return 取值表达式
     */
    public String getExpression() {
        return expression;
    }

    /**
     * 获取模板标签名。
     *
     * <p>优先级：显式 {@code tag} → 反射访问器对应的属性名 → 列标题。</p>
     *
     * @return 标签名
     */
    public String getTag() {
        if (!tag.isEmpty()) {
            return tag;
        }
        if (getter != null) {
            String method = getter.getName();
            if (method.startsWith("get") && method.length() > 3) {
                return decapitalize(method.substring(3));
            }
            if (method.startsWith("is") && method.length() > 2) {
                return decapitalize(method.substring(2));
            }
            return method;
        }
        if (field != null) {
            return field.getName();
        }
        return name;
    }

    /**
     * 获取精度。
     *
     * @return 精度
     */
    public int getScale() {
        return scale;
    }

    /**
     * 获取字段访问器。
     *
     * @return 字段访问器
     */
    public Field getField() {
        return field;
    }

    /**
     * 获取方法访问器。
     *
     * @return 方法访问器
     */
    public Method getGetter() {
        return getter;
    }

    @Override
    /**
     * 转换为字符串
     * @return 字符串 对象
     */
    public String toString() {
        return "ColumnMeta{field=" + field + ", name='" + name + "', order=" + order
                + ", type=" + type + ", align=" + align + "}";
    }

    /**
     * 属性名首字母小写（与 {@code java.beans.Introspector#decapitalize} 规则一致，
     * 但避免依赖 {@code java.desktop} 模块）。
     *
     * @param name 属性名
     * @return 小写化结果
     */
    private static String decapitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }
}