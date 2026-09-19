package com.chua.common.support.value;

import lombok.AllArgsConstructor;

/**
 * 默认值实现，包装一个具体值并支持携带可选的默认值和异常信息。
 * <p>
 * 当原始值为 空 时，{@link #getValue()} 会回退返回 {@code defaultValue}。
 * 该类为包私有，通过 {@link Value#of(Object)} 工厂方法创建实例。
 * </p>
 *
 * @param <T> 值类型
 * @author CH
 * @since 4.0.0.42
 */
@AllArgsConstructor
class DefaultValue<T> implements Value<T> {

    /** 原始值 */
    private final T value;
    /** 默认值（当 值 为 空 时返回） */
    private final T defaultValue;
    /** 转换过程中产生的异常 */
    private final Throwable throwable;

    /**
     * 构造函数，仅设置原始值。
     *
     * @param value 原始值
     */
    DefaultValue(T value) {
        this(value, null, null);
    }

    /**
     * 构造函数，设置原始值和异常信息。
     *
     * @param value      原始值
     * @param throwable  转换异常
     */
    DefaultValue(T value, Throwable throwable) {
        this(value, null, throwable);
    }

    /**
     * 构造函数，设置原始值和默认值。
     *
     * @param value        原始值
     * @param defaultValue 默认值（值 为 空 时使用）
     */
    DefaultValue(T value, T defaultValue) {
        this.value = value;
        this.defaultValue = defaultValue;
        this.throwable = null;
    }

    /**
     * 获取原始值，如果为 空 则返回预设的默认值。
     *
     * @return 值或默认值
     */
    @Override
    public T getValue() {
        return value != null ? value : defaultValue;
    }

    /**
     * 获取转换过程中产生的异常。
     *
     * @return 异常，可能为 空
     */
    @Override
    public Throwable getThrowable() {
        return throwable;
    }

    /**
     * 判断原始值是否为 空。
     *
     * @return true 表示原始值为 空
     */
    @Override
    public boolean isNull() {
        return value == null;
    }

    /**
     * 判断原始值是否等于指定值。
     *
     * @param value 指定值
     * @return true 表示相等
     */
    @Override
    public boolean is(T value) {
        return this.value == value || (this.value != null && this.value.equals(value));
    }
}
