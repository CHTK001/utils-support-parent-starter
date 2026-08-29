
package com.chua.common.support.value;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.utils.ObjectUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 值包装接口，提供统一的值访问和类型转换能力。
 * <p>
 * 该接口定义了从值容器中安全获取各种类型数据的契约，支持以下特性：
 * <ul>
 *   <li><b>类型转换</b>：通过 {@link #getValue(Class)} 获取指定类型的值，底层使用 {@link com.chua.common.support.converter.Converter} 进行自动类型转换</li>
 *   <li><b>默认值</b>：所有 {@code asXXX(defaultValue)} 方法在值为 null 时返回指定的默认值</li>
 *   <li><b>空值安全</b>：{@link #of(Object)} 工厂方法在值 null 时返回 {@link NullValue} 单例，避免空指针</li>
 *   <li><b>异常承载</b>：{@link #getThrowable()} 支持携带转换过程中产生的异常信息</li>
 *   <li><b>函数式增强</b>：{@link #orElse}, {@link #orElseGet}, {@link #orElseThrow}, {@link #map}, {@link #flatMap}, {@link #ifPresent} 提供安全的链式操作，避免空指针</li>
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

    /**
     * 如果当前值不为 null，则返回该值；否则返回 {@code other}。
     *
     * @param other 备用值
     * @return 值或备用值
     */
    default T orElse(T other) {
        return getValue() != null ? getValue() : other;
    }

    /**
     * 如果当前值不为 null，则返回该值；否则返回 {@code other} 提供的值。
     *
     * @param other 备用值提供者
     * @return 值或备用值
     */
    @SuppressWarnings("NullAway")
    default T orElseGet(Supplier<? extends T> other) {
        return getValue() != null ? getValue() : other.get();
    }

    /**
     * 如果当前值不为 null，则返回该值；否则抛出指定异常。
     *
     * @param exceptionSupplier 异常提供者
     * @param <X>             异常类型
     * @return 值
     * @throws X 如果值为 null
     */
    default <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        if (getValue() != null) {
            return getValue();
        }
        throw exceptionSupplier.get();
    }

    /**
     * 如果当前值不为 null，则将其转换为新的 {@link Value}。
     *
     * @param mapper 转换函数
     * @param <R>    转换后的值类型
     * @return 转换后的 Value
     */
    @SuppressWarnings({"unchecked", "NullAway"})
    default <R> Value<R> map(Function<? super T, ? extends R> mapper) {
        T v = getValue();
        return v != null ? Value.of((R) mapper.apply(v)) : (Value<R>) NullValue.INSTANCE;
    }

    /**
     * 如果当前值不为 null，则将其转换为新的 {@link Value}。
     *
     * @param mapper 转换函数，返回一个新的 Value
     * @param <R>    转换后的值类型
     * @return 转换后的 Value
     */
    @SuppressWarnings({"all", "unchecked", "NullAway"})
    default <R> Value<R> flatMap(Function<? super T, ? extends Value<? extends R>> mapper) {
        T v = getValue();
        if (v == null) {
            return (Value<R>) NullValue.INSTANCE;
        }
        Value<? extends R> result = mapper.apply(v);
        if (result == null) {
            return (Value<R>) NullValue.INSTANCE;
        }
        return (Value<R>) result;
    }

    /**
     * 如果当前值不为 null，则执行指定的消费行为。
     *
     * @param consumer 消费行为
     */
    default void ifPresent(Consumer<? super T> consumer) {
        if (getValue() != null) {
            consumer.accept(getValue());
        }
    }
}
