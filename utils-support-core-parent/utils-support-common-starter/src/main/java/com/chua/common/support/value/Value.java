
package com.chua.common.support.value;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.utils.ObjectUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * 值包装接口，提供统一的值访问和类型转换能力。
 * <p>
 * 该接口定义了从值容器中安全获取各种类型数据的契约，支持以下特性：
 * <ul>
 *   <li><b>类型转换</b>：通过 {@link #getValue(Class)} 获取指定类型的值，底层使用 {@link com.chua.common.support.converter.Converter} 进行自动类型转换</li>
 *   <li><b>默认值</b>：所有 {@code asXXX(defaultValue)} 方法在值为 null 时返回指定的默认值</li>
 *   <li><b>空值安全</b>：{@link #of(Object)} 工厂方法在值 null 时返回 {@link NullValue} 单例，避免空指针</li>
 *   <li><b>异常承载</b>：{@link #getThrowable()} 支持携带转换过程中产生的异常信息</li>
 * </ul>
 * </p>
 *
 * @param <T> 值类型
 * @author CH
 * @since 2020/12/19
 */
public interface Value<T> extends Serializable {

    /**
     * 创建 Value 实例。
     * <p>值为 null 时返回 {@link NullValue} 单例，否则返回 {@link DefaultValue} 实例。</p>
     *
     * @param value 值
     * @param <T> 值类型
     * @return Value 实例
     */
@SuppressWarnings("ALL")
    static <T> Value<T> of(T value) {
        return null == value ? (Value<T>) NullValue.INSTANCE : new DefaultValue<>(value);
    }

    /**
     * 获取原始值。
     *
     * @return 原始值，可能为 null
     */
    T getValue();

    /**
     * 获取值，如果为 null 则返回默认值。
     *
     * @param defaultValue 默认值
     * @return 值或默认值
     */
    default T getDefaultValue(Object defaultValue) {
        return Optional.ofNullable(getValue()).orElse((T) defaultValue);
    }

    /**
     * 获取指定类型的值。
     * <p>通过 {@link com.chua.common.support.converter.Converter} 将原始值转换为目标类型。</p>
     *
     * @param target 目标类型
     * @param <E> 目标类型
     * @return 转换后的值
     */
    default <E> E getValue(Class<E> target) {
        if (target == Object.class) {
            return (E) getValue();
        }
        return Converter.convertIfNecessary(getValue(), target);
    }

    /**
     * 获取转换过程中产生的异常。
     *
     * @return 异常，可能为 null
     */
    Throwable getThrowable();

    /**
     * 判断当前值是否为 null。
     *
     * @return true 表示为 null
     */
    boolean isNull();

    /**
     * 判断当前值是否等于指定值。
     *
     * @param value 指定值
     * @return true 表示相等
     */
    boolean is(T value);

    /**
     * 获取字符串值（通过类型转换）。
     *
     * @return 字符串值
     */
    default String getStringValue() {
        return getValue(String.class);
    }

    /**
     * 获取字符串值，如果为 null 则返回默认值。
     *
     * @param defaultValue 默认值
     * @return 字符串值或默认值
     */
    default String asString(String defaultValue) {
        return ObjectUtils.defaultIfNull(asString(), defaultValue);
    }

    /**
     * 获取字符串值（同 {@link #getStringValue()}）。
     *
     * @return 字符串值
     */
    default String asString() {
        return getStringValue();
    }

    /**
     * 获取 Integer 值（通过类型转换）。
     *
     * @return 整数值
     */
    default Integer asInteger() {
        return getValue(Integer.class);
    }

    /**
     * 获取 Integer 值，如果为 null 则返回默认值。
     *
     * @param defaultValue 默认值
     * @return 整数值或默认值
     */
    default Integer asInteger(Integer defaultValue) {
        return ObjectUtils.defaultIfNull(asInteger(), defaultValue);
    }

    /**
     * 获取 Boolean 值（通过类型转换）。
     *
     * @return 布尔值
     */
    default Boolean asBoolean() {
        return getValue(Boolean.class);
    }

    /**
     * 获取 Boolean 值，如果为 null 则返回默认值。
     *
     * @param defaultValue 默认值
     * @return 布尔值或默认值
     */
    default Boolean asBoolean(Boolean defaultValue) {
        return ObjectUtils.defaultIfNull(asBoolean(), defaultValue);
    }

    /**
     * 获取 Long 值（通过类型转换）。
     *
     * @return 长整数值
     */
    default Long asLong() {
        return getValue(Long.class);
    }

    /**
     * 获取 Long 值，如果为 null 则返回默认值。
     *
     * @param defaultValue 默认值
     * @return 长整数值或默认值
     */
    default Long asLong(Long defaultValue) {
        return ObjectUtils.defaultIfNull(asLong(), defaultValue);
    }

    /**
     * 获取 Float 值（通过类型转换）。
     *
     * @return 浮点值
     */
    default Float asFloat() {
        return getValue(Float.class);
    }

    /**
     * 获取 Float 值，如果为 null 则返回默认值。
     *
     * @param defaultValue 默认值
     * @return 浮点值或默认值
     */
    default Float asFloat(Float defaultValue) {
        return ObjectUtils.defaultIfNull(asFloat(), defaultValue);
    }

    /**
     * 获取 Double 值（通过类型转换）。
     *
     * @return 双精度值
     */
    default Double asDouble() {
        return getValue(Double.class);
    }

    /**
     * 获取 Double 值，如果为 null 则返回默认值。
     *
     * @param defaultValue 默认值
     * @return 双精度值或默认值
     */
    default Double asDouble(Double defaultValue) {
        return ObjectUtils.defaultIfNull(asDouble(), defaultValue);
    }

    /**
     * 获取 Byte 值（通过类型转换）。
     *
     * @return 字节值
     */
    default Byte asByte() {
        return getValue(Byte.class);
    }

    /**
     * 获取 Byte 值，如果为 null 则返回默认值。
     *
     * @param defaultValue 默认值
     * @return 字节值或默认值
     */
    default Byte asByte(Byte defaultValue) {
        return ObjectUtils.defaultIfNull(asByte(), defaultValue);
    }

    /**
     * 获取 BigDecimal 值（通过类型转换）。
     *
     * @return BigDecimal 值
     */
    default BigDecimal asBigDecimal() {
        return getValue(BigDecimal.class);
    }

    /**
     * 获取 BigDecimal 值，如果为 null 则返回默认值。
     *
     * @param defaultValue 默认值
     * @return BigDecimal 值或默认值
     */
    default BigDecimal asBigDecimal(BigDecimal defaultValue) {
        return ObjectUtils.defaultIfNull(asBigDecimal(), defaultValue);
    }

    /**
     * 获取 BigDecimal 值，如果为 null 则返回 {@link BigDecimal#ZERO}。
     *
     * @return BigDecimal 值或 0
     */
    default BigDecimal asBigDecimalOrZero() {
        return asBigDecimal(BigDecimal.ZERO);
    }
}
