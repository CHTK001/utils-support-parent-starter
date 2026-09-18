package com.chua.common.support.utils;

import com.chua.common.support.function.SafeConsumer;
import com.chua.common.support.function.SafeFunction;
import com.chua.common.support.function.SafeSupplier;
import com.chua.common.support.converter.Converter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_EMPTY;
import static com.chua.common.support.constant.ValueConstant.SYMBOL_EMPTY_OBJECT_ARRAY;
import static com.chua.common.support.constant.ValueConstant.SYMBOL_EMPTY_STRING;

/**
 * 对象工具类，提供对象操作的核心工具方法。
 *
 * <p>包含以下功能：
 * <ul>
 *   <li>空安全比较（{@link #nullSafeEquals} / {@link #nullSafeHashCode}）</li>
 *   <li>空值处理（{@link #defaultIfNull} / {@link #firstNonNull} / {@link #isNull}）</li>
 *   <li>三目运算简化（{@link #optional}）</li>
 *   <li>对象判空（{@link #isEmpty} / {@link #isNotEmpty} / {@link #isAnyEmpty}）</li>
 *   <li>类型转换（{@link #to} / {@link #toObjectArray} / {@link #utf8Bytes}）</li>
 *   <li>身份字符串（{@link #identityToString} / {@link #nullSafeToString}）</li>
 *   <li>BigDecimal 安全比较（{@link #equals} / {@link #equal}）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0
*/
public class ObjectUtils {

    /**
    * 对象工具。
    */
    private ObjectUtils() {
    }

    private static final long serialVersionUID = 1L; // 串行版本uid
    /** Initial_哈希 */
    private static final int INITIAL_HASH = 7;
    /** 倍数 */
    private static final int MULTIPLIER = 31;

    /** 空_字符串 */
    private static final String EMPTY_STRING = "";
    /** 空_字符串 */
    private static final String NULL_STRING = "null";
    /** Array_启动 */
    private static final String ARRAY_START = "{";
    /** Array_结束 */
    private static final String ARRAY_END = "}";
    /** 空_array */
    private static final String EMPTY_ARRAY = ARRAY_START + ARRAY_END;
    /** Array_element_separator */
    private static final String ARRAY_ELEMENT_SEPARATOR = ", ";
    /** At_标志 */
    private static final char AT_SIGN = '@';



    /**
    * 三目运算简化：根据布尔值返回对应的值。
    *
    * <p>等价于 {@code value ? trueValue : falseValue}，适用于需要内联选择值的场景。
    *
    * @param <O>        值类型占位
    * @param <T>        返回类型
    * @param value      布尔条件
    * @param trueValue  条件为 true 时返回的值
    * @param falseValue 条件为 false 时返回的值
    * @return 根据条件返回 true值 或 false值
    */
    @Nullable
    public static <O, T> T optional(boolean value, @Nullable T trueValue, @Nullable T falseValue) {
        return value ? trueValue : falseValue;
    }

    /**
    * 三目运算简化：根据对象是否为空返回对应的值。
    *
    * <p>等价于 {@code null == value ? falseOrNullValue : trueOrNoneValue}，用于空值检查后选择值。
    *
    * @param <O>              值类型占位
    * @param <T>              返回类型
    * @param value            待检查的对象
    * @param trueOrNoneValue  对象非空时返回的值
    * @param falseOrNullValue 对象为空时返回的值
    * @return 根据 值 是否为空返回对应的值
    */
    @Nullable
    public static <O, T> T optional(@Nullable O value, @Nullable T trueOrNoneValue, @Nullable T falseOrNullValue) {
        return null == value ? falseOrNullValue : trueOrNoneValue;
    }

    /**
    * 三目运算简化：根据对象是否为空执行函数或返回默认值。
    *
    * <p>对象非空时执行转换函数，对象为空时返回默认值。
    *
    * @param <O>             值类型
    * @param <T>             返回类型
    * @param value           待检查的对象
    * @param otFunction      对象非空时的转换函数
    * @param falseOrNullValue 对象为空时的默认值
    * @return 转换后的值或默认值
    */
    public static <O, T> T optional(O value, Function<O, T> otFunction, T falseOrNullValue) {
        return null == value ? falseOrNullValue : otFunction.apply(value);
    }


    /**
    * 空安全的对象比较。
    *
    * <p>处理两个对象是否相等，支持：
    * <ul>
    *   <li>同一引用（==）返回 true</li>
    *   <li>任意一方为空返回 false</li>
    *   <li>调用 {@link Object#equals} 比较</li>
    *   <li>数组类型使用 {@link Arrays#equals} 逐元素比较</li>
    * </ul>
    *
    * @param o1 对象 1，可为 空
    * @param o2 对象 2，可为 空
    * @return 两个对象相等返回 true，否则返回 false
    */
    public static boolean nullSafeEquals(@Nullable Object o1, @Nullable Object o2) {
        if (o1 == o2) {
            return true;
        }
        if (o1 == null || o2 == null) {
            return false;
        }
        if (o1.equals(o2)) {
            return true;
        }
        if (o1.getClass().isArray() && o2.getClass().isArray()) {
            return arrayEquals(o1, o2);
        }
        return false;
    }

    /**
    * 比较两个数组是否相等。
    *
    * <p>根据数组的实际类型（Object[]、boolean[]、byte[] 等）委托给对应的
    * {@link Arrays#equals} 方法进行逐元素比较。支持所有基本类型数组。
    *
    * @param o1 数组 1
    * @param o2 数组 2
    * @return 两个数组相等返回 true，否则返回 false
    */
    private static boolean arrayEquals(Object o1, Object o2) {
        if (o1 instanceof Object[] && o2 instanceof Object[]) {
            return Arrays.equals((Object[]) o1, (Object[]) o2);
        }
        if (o1 instanceof boolean[] && o2 instanceof boolean[]) {
            return Arrays.equals((boolean[]) o1, (boolean[]) o2);
        }
        if (o1 instanceof byte[] && o2 instanceof byte[]) {
            return Arrays.equals((byte[]) o1, (byte[]) o2);
        }
        if (o1 instanceof char[] && o2 instanceof char[]) {
            return Arrays.equals((char[]) o1, (char[]) o2);
        }
        if (o1 instanceof double[] && o2 instanceof double[]) {
            return Arrays.equals((double[]) o1, (double[]) o2);
        }
        if (o1 instanceof float[] && o2 instanceof float[]) {
            return Arrays.equals((float[]) o1, (float[]) o2);
        }
        if (o1 instanceof int[] && o2 instanceof int[]) {
            return Arrays.equals((int[]) o1, (int[]) o2);
        }
        if (o1 instanceof long[] && o2 instanceof long[]) {
            return Arrays.equals((long[]) o1, (long[]) o2);
        }
        if (o1 instanceof short[] && o2 instanceof short[]) {
            return Arrays.equals((short[]) o1, (short[]) o2);
        }
        return false;
    }


    /**
    * 返回给定对象的哈希码，通常即为该对象 {@code Object#hashCode()} 的返回值。
    * 若对象为数组，本方法会委托给本类中对应的 {@code nullSafeHashCode}
    * 数组重载方法；若对象为 {@code null}，本方法返回 0。
    *
    * @see Object#hashCode()
    * @see #nullSafeHashCode(Object[])
    * @see #nullSafeHashCode(boolean[])
    * @see #nullSafeHashCode(byte[])
    * @see #nullSafeHashCode(char[])
    * @see #nullSafeHashCode(double[])
    * @see #nullSafeHashCode(float[])
    * @see #nullSafeHashCode(int[])
    * @see #nullSafeHashCode(long[])
    * @see #nullSafeHashCode(short[])
    * @param obj obj
    * @return 空safe哈希编码的结果
    */
    public static int nullSafeHashCode(Object obj) {
        if (obj == null) {
            return 0;
        }
        if (obj.getClass().isArray()) {
            if (obj instanceof Object[]) {
                return nullSafeHashCode((Object[]) obj);
            }
            if (obj instanceof boolean[]) {
                return nullSafeHashCode((boolean[]) obj);
            }
            if (obj instanceof byte[]) {
                return nullSafeHashCode((byte[]) obj);
            }
            if (obj instanceof char[]) {
                return nullSafeHashCode((char[]) obj);
            }
            if (obj instanceof double[]) {
                return nullSafeHashCode((double[]) obj);
            }
            if (obj instanceof float[]) {
                return nullSafeHashCode((float[]) obj);
            }
            if (obj instanceof int[]) {
                return nullSafeHashCode((int[]) obj);
            }
            if (obj instanceof long[]) {
                return nullSafeHashCode((long[]) obj);
            }
            if (obj instanceof short[]) {
                return nullSafeHashCode((short[]) obj);
            }
        }
        return obj.hashCode();
    }


    /**
    * 返回 a 哈希 编码 基础 on the 内容 的 the specified array.
    * If {@code array} 是否 {@code null}, this 方法 返回 0.
    * @param array array
    * @return 空safe哈希编码的结果
    */
    public static int nullSafeHashCode(Object[] array) {
        if (array == null) {
            return 0;
        }
        int hash = INITIAL_HASH;
        for (Object element : array) {
            hash = MULTIPLIER * hash + nullSafeHashCode(element);
        }
        return hash;
    }

    /**
    * 返回 a 哈希 编码 基础 on the 内容 的 the specified array.
    * If {@code array} 是否 {@code null}, this 方法 返回 0.
    * @param array array
    * @return 空safe哈希编码的结果
    */
    public static int nullSafeHashCode(boolean[] array) {
        if (array == null) {
            return 0;
        }
        int hash = INITIAL_HASH;
        for (boolean element : array) {
            hash = MULTIPLIER * hash + Boolean.hashCode(element);
        }
        return hash;
    }

    /**
    * 返回 a 哈希 编码 基础 on the 内容 的 the specified array.
    * If {@code array} 是否 {@code null}, this 方法 返回 0.
    * @param array array
    * @return 空safe哈希编码的结果
    */
    public static int nullSafeHashCode(byte[] array) {
        if (array == null) {
            return 0;
        }
        int hash = INITIAL_HASH;
        for (byte element : array) {
            hash = MULTIPLIER * hash + element;
        }
        return hash;
    }

    /**
    * 返回 a 哈希 编码 基础 on the 内容 的 the specified array.
    * If {@code array} 是否 {@code null}, this 方法 返回 0.
    * @param array array
    * @return 空safe哈希编码的结果
    */
    public static int nullSafeHashCode(char[] array) {
        if (array == null) {
            return 0;
        }
        int hash = INITIAL_HASH;
        for (char element : array) {
            hash = MULTIPLIER * hash + element;
        }
        return hash;
    }

    /**
    * 返回 a 哈希 编码 基础 on the 内容 的 the specified array.
    * If {@code array} 是否 {@code null}, this 方法 返回 0.
    * @param array array
    * @return 空safe哈希编码的结果
    */
    public static int nullSafeHashCode(double[] array) {
        if (array == null) {
            return 0;
        }
        int hash = INITIAL_HASH;
        for (double element : array) {
            hash = MULTIPLIER * hash + Double.hashCode(element);
        }
        return hash;
    }

    /**
    * 返回 a 哈希 编码 基础 on the 内容 的 the specified array.
    * If {@code array} 是否 {@code null}, this 方法 返回 0.
    * @param array array
    * @return 空safe哈希编码的结果
    */
    public static int nullSafeHashCode(float[] array) {
        if (array == null) {
            return 0;
        }
        int hash = INITIAL_HASH;
        for (float element : array) {
            hash = MULTIPLIER * hash + Float.hashCode(element);
        }
        return hash;
    }

    /**
    * 返回 a 哈希 编码 基础 on the 内容 的 the specified array.
    * If {@code array} 是否 {@code null}, this 方法 返回 0.
    * @param array array
    * @return 空safe哈希编码的结果
    */
    public static int nullSafeHashCode(int[] array) {
        if (array == null) {
            return 0;
        }
        int hash = INITIAL_HASH;
        for (int element : array) {
            hash = MULTIPLIER * hash + element;
        }
        return hash;
    }

    /**
    * 返回 a 哈希 编码 基础 on the 内容 的 the specified array.
    * If {@code array} 是否 {@code null}, this 方法 返回 0.
    * @param array array
    * @return 空safe哈希编码的结果
    */
    public static int nullSafeHashCode(long[] array) {
        if (array == null) {
            return 0;
        }
        int hash = INITIAL_HASH;
        for (long element : array) {
            hash = MULTIPLIER * hash + Long.hashCode(element);
        }
        return hash;
    }

    /**
    * 返回 a 哈希 编码 基础 on the 内容 的 the specified array.
    * If {@code array} 是否 {@code null}, this 方法 返回 0.
    * @param array array
    * @return 空safe哈希编码的结果
    */
    public static int nullSafeHashCode(short[] array) {
        if (array == null) {
            return 0;
        }
        int hash = INITIAL_HASH;
        for (short element : array) {
            hash = MULTIPLIER * hash + element;
        }
        return hash;
    }

    /**
    * 计算多个对象的哈希码。
    *
    * <p>委托给 {@link Arrays#hashCode(Object[])} 计算。
    *
    * @param objects 对象数组
    * @return 哈希码
    */
    public static int hashCode(Object... objects) {
        return Arrays.hashCode(objects);
    }

    /**
    * 返回第一个非空的值。
    *
    * <p>支持 {@link Optional} 类型，如果 v1 是 Optional 且有值则解包后递归处理。
    *
    * @param v1 值 1
    * @param v2 值 2
    * @param <T> 值类型
    * @return 第一个非空的值，如果都为空则返回 空
    */
@SuppressWarnings("ALL")
    public static <T>T firstNonNull(T v1, T v2) {
        if(v1 instanceof Optional<?> optionalO) {
            if(optionalO.isPresent()) {
                Object object = optionalO.get();
                return (T) firstNonNull(object, v2);
            }
        }
        return null == v1 ? v2 : v1;
    }


    /**
    * 检查参数数组中是否有任意一个为空或空字符串。
    *
    * <p>对 String 类型额外检查是否为空字符串。
    *
    * @param args 待检查的参数数组
    * @return 任意一个为空返回 true，否则返回 false
    */
    public static boolean isAnyEmpty(Object... args) {
        for (Object arg : args) {
            if(null == arg) {
                return true;
            }

            if (arg instanceof String && StringUtils.isNullOrEmpty((String) arg)) {
                return true;
            }
        }

        return false;
    }
    /**
    * 如果值为空则返回默认值。
    *
    * <p>等价于 {@code value != null ? value : defaultValue}，利用 {@link Optional#orElse} 实现。
    *
    * @param value        待检查的值
    * @param defaultValue 值为空时的默认值
    * @param <T>          值类型
    * @return 非空时返回原值，否则返回默认值
    */
    public static <T>T defaultIfNull(T value, T defaultValue) {
        return Optional.ofNullable(value).orElse(defaultValue);
    }
    /**
    * 如果 类 为空或 Void Linux 类型则返回默认 类。
    *
    * <p>处理 {@link Void#TYPE}、{@link Void} 和 null 三种情况。
    *
    * @param value        待检查的 类
    * @param defaultValue 默认 类
    * @param <T>          类型
    * @return 有效的 类 或默认值
    */
    public static <T> Class<T> defaultIfNull(Class<?> value, Class<?> defaultValue) {
        if (null == value || void.class.isAssignableFrom(value) || Void.class.isAssignableFrom(value)) {
            return (Class<T>) defaultValue;
        }
        return (Class<T>) value;
    }


    /**
    * 如果值非空则执行转换函数并返回结果。
    *
    * <p>等价于 {@code defaultIfNull(value, successFunction, null)}。
    *
    * @param value           待检查的值
    * @param successFunction 值非空时的转换函数
    * @param <T>             输入类型
    * @param <E>             输出类型
    * @return 转换后的值，值为空时返回 空
    */
    public static <T, E>E defaultIfNull(T value, Function<T, E> successFunction) {
        return defaultIfNull(value, successFunction, null);
    }

    /**
    * 如果值非空则执行转换函数，否则执行空值回调。
    *
    * <p>利用 {@link Optional#map} 和 {@link Optional#orElse} 实现。
    *
    * @param value           待检查的值
    * @param successFunction 值非空时的转换函数
    * @param nullFunction    值为空时的回调 供应商
    * @param <T>             输入类型
    * @param <E>             输出类型
    * @return 转换后的值或回调结果
    */
    public static <T, E>E defaultIfNull(T value, Function<T, E> successFunction, Supplier<E> nullFunction) {
        return Optional.ofNullable(value).map(successFunction).orElse(null == nullFunction ? null : nullFunction.get());
    }
    /**
    * 如果对象为空则返回默认字符串。
    *
    * <p>非空时调用 {@link Object#toString()} 转换。
    *
    * @param value        待检查的对象
    * @param defaultValue 对象为空的默认字符串
    * @return 对象的字符串表示或默认值
    */
    public static String defaultIfStringNull(Object value, String defaultValue) {
        if(null == value) {
            return defaultValue;
        }
        return value.toString();
    }


    /**
    * 返回 a 字符串 representation 的 the specified 对象.
    * <p>Builds a String representation of the contents in case of an array.
    * 返回 a {@code "null"} 字符串 if {@code obj} 是否 {@code null}.
    *
    * @param obj the 对象 转为 构建 a 字符串 representation for
    * @return a 字符串 representation 的 {@code obj}
    */
    public static String nullSafeToString(Object obj) {
        if (obj == null) {
            return SYMBOL_EMPTY;
        }

        if (obj instanceof String) {
            return (String) obj;
        }
        if (obj instanceof Object[]) {
            return nullSafeToString(obj);
        }
        if (obj instanceof boolean[]) {
            return nullSafeToString(obj);
        }
        if (obj instanceof byte[]) {
            return nullSafeToString(obj);
        }
        if (obj instanceof char[]) {
            return nullSafeToString(obj);
        }
        if (obj instanceof double[]) {
            return nullSafeToString(obj);
        }
        if (obj instanceof float[]) {
            return nullSafeToString(obj);
        }
        if (obj instanceof int[]) {
            return nullSafeToString(obj);
        }
        if (obj instanceof long[]) {
            return nullSafeToString(obj);
        }
        if (obj instanceof short[]) {
            return nullSafeToString(obj);
        }
        String str = obj.toString();
        return (str != null ? str : SYMBOL_EMPTY_STRING);
    }

    /**
    * 转换 the given array (which may be a primitive array) 转为 an
    * 对象 array (if necessary 的 primitive orm 对象).
    * <p>A {@code null} source value will be converted to an
    * 空 对象 array.
    *
    * @param source the (potentially primitive) array
    * @return the corresponding 对象 array (从不 {@code null})
    * @throws IllegalArgumentException if the 参数 是否 not an array
    */
    public static Object[] toObjectArray(Object source) {
        if (source instanceof Object[]) {
            return (Object[]) source;
        }
        if (source == null) {
            return SYMBOL_EMPTY_OBJECT_ARRAY;
        }
        if (!source.getClass().isArray()) {
            throw new IllegalArgumentException("Source is not an array: " + source);
        }
        int length = Array.getLength(source);
        if (length == 0) {
            return SYMBOL_EMPTY_OBJECT_ARRAY;
        }
        Class<?> wrapperType = Array.get(source, 0).getClass();
        Object[] newArray = (Object[]) Array.newInstance(wrapperType, length);
        for (int i = 0; i < length; i++) {
            newArray[i] = Array.get(source, i);
        }
        return newArray;
    }

    /**
    * 比较两个对象是否相等。
    *
    * <p>委托给 {@link #equal(Object, Object)} 实现，支持 BigDecimal 特殊比较。
    *
    * @param obj1 对象 1
    * @param obj2 对象 2
    * @return 相等返回 true，否则返回 false
    * @see #equal(Object, Object)
    * @since 5.4.3
    */
    public static boolean equals(Object obj1, Object obj2) {
        return equal(obj1, obj2);
    }


    /**
    * 比较两个 Integer 是否相等，空值按 0 处理。
    *
    * <p>如果一方为 null 则视为 0 进行比较。
    *
    * @param obj1 Integer 1
    * @param obj2 Integer 2
    * @return 相等返回 true，否则返回 false
    * @see Objects#equals(Object, Object)
    */
    public static boolean equals(Integer obj1, Integer obj2) {
        if(null == obj1) {
            obj1 = 0;
        }

        if(null == obj2) {
            obj2 = 0;
        }

        return obj1.equals(obj2);
    }
    /**
    * 比较两个对象是否相等，支持 bigdecimal 特殊比较。
    *
    * <p>比较规则：
    * <ol>
    *   <li>两个对象同为 BigDecimal 时使用 {@link NumberUtils#equals(BigDecimal, BigDecimal)} 按值比较</li>
    *   <li>否则使用 {@link Objects#equals(Object, Object)} 比较</li>
    * </ol>
    *
    * @param obj1 对象 1
    * @param obj2 对象 2
    * @return 相等返回 true，否则返回 false
    * @see Objects#equals(Object, Object)
    */
    public static boolean equal(Object obj1, Object obj2) {
        if (obj1 instanceof BigDecimal && obj2 instanceof BigDecimal) {
            return NumberUtils.equals((BigDecimal) obj1, (BigDecimal) obj2);
        }
        return Objects.equals(obj1, obj2);
    }

    /**
    * 忽略大小写比较两个对象是否相等。
    *
    * <p>比较规则：
    * <ol>
    *   <li>同为 BigDecimal 时使用 {@link NumberUtils#equals(BigDecimal, BigDecimal)} 比较</li>
    *   <li>同为 String 时使用 {@link StringUtils#equalsIgnoreCase} 忽略大小写比较</li>
    *   <li>其他情况使用 {@link Objects#equals(Object, Object)} 比较</li>
    * </ol>
    *
    * @param obj1 对象 1
    * @param obj2 对象 2
    * @return 相等返回 true，否则返回 false
    * @see Object
    */
    public static boolean equalsIgnore(Object obj1, Object obj2) {
        if (obj1 instanceof BigDecimal && obj2 instanceof BigDecimal) {
            return NumberUtils.equals((BigDecimal) obj1, (BigDecimal) obj2);
        }
        if(obj1 instanceof String && obj2 instanceof String) {
            return StringUtils.equalsIgnoreCase((String) obj1, (String) obj2);
        }

        return Objects.equals(obj1, obj2);
    }
    /**
    * 根据值是否为空执行不同的回调。
    *
    * <p>值为空时执行空值回调，非空时执行转换函数。
    *
    * @param value       待检查的值
    * @param nullCallback 值为空时的回调
    * @param function    值非空时的转换函数
    * @return 回调或转换结果
    */
    public static Object withNull(Object value, SafeSupplier<Object> nullCallback, SafeFunction<Object, Object> function) {
        if (null == value) {
            return nullCallback.get();
        }
        return function.apply(value);
    }
    /**
    * 根据值是否为空执行转换函数。
    *
    * <p>值为空时直接返回 null，非空时执行转换函数。
    *
    * @param value    待检查的值
    * @param function 转换函数
    * @param <E>      输出类型
    * @param <T>      输入类型
    * @return 转换后的值或 空
    */
    public static <E, T>E withNull(T value,  SafeFunction<T, E> function) {
        if (null == value) {
            return null;
        }
        return function.apply(value);
    }

    /**
    * 如果值非空则执行消费操作。
    *
    * <p>值为空时直接返回，不做任何操作。
    *
    * @param value    待检查的值
    * @param consumer 值非空时的消费操作
    */
    public static void ifValidate(Object value, SafeConsumer<Object> consumer) {
        if (null == value) {
            return;
        }
        consumer.accept(value);
    }

    /**
    * 判断对象是否为空。
    *
    * <p>支持以下类型的判空：
    * <ul>
    *   <li>null → true</li>
    *   <li>Boolean → 返回自身的布尔值</li>
    *   <li>Iterable → 委托给 {@link CollectionUtils#isEmpty}</li>
    *   <li>String → 委托给 {@link StringUtils#isEmpty}</li>
    *   <li>其他 → false</li>
    * </ul>
    *
    * @param reference 待检查的对象
    * @return 为空返回 true，否则返回 false
    */
    public static boolean isEmpty(Object reference) {
        if(null == reference) {
            return true;
        }

        if(reference instanceof Boolean) {
            return (boolean) reference;
        }

        if (reference instanceof Iterable) {
            return CollectionUtils.isEmpty((Iterable)reference);
        }

        if(reference instanceof String) {
            return StringUtils.isEmpty(reference.toString());
        }

        return false;
    }


    /**
    * 判断对象是否是指定类型的实例。
    *
    * <p>值为空且目标为 void 类型时返回 null。
    * 否则检查类型兼容性，兼容时返回原值，不兼容时返回 空。
    *
    * @param value  待检查的对象
    * @param target 目标类型
    * @param <T>    目标类型
    * @return 类型兼容时返回原值，否则返回 空
    */
    public static <T> T withAssignableFrom(Object value, Class<T> target) {
        if (null == value && ClassUtils.isVoid(target)) {
            return null;
        }

        return target.isAssignableFrom(value.getClass()) ? (T) value : null;
    }

    /**
    * 如果值有效则执行转换函数。
    *
    * <p>值为 null 或空字符串时返回 null，否则执行转换函数。
    *
    * @param value    待检查的值
    * @param function 转换函数
    * @param <T>      输出类型
    * @param <E>      输入类型
    * @return 转换后的值或 空
    */
    public static <T, E> T ifValid(E value, Function<E, T> function) {
        boolean rs = null == value || (value instanceof String && "".equals(value));
        if (rs) {
            return null;
        }
        return function.apply(value);
    }

    /**
    * 判断对象是否为 空。
    *
    * @param value 待检查的对象
    * @return 为 空 返回 true，否则返回 false
    */
    public static boolean isNull(Object value) {
        return null == value;
    }


    /**
    * 追加对象的身份标识字符串到 Appendable。
    *
    * <p>生成格式为 {@code 类名@十六进制哈希码} 的身份标识字符串。
    *
    * <pre>
    * ObjectUtils.identityToString(appendable, "")            = appendable.append("java.lang.String@1e23"
    * ObjectUtils.identityToString(appendable, Boolean.TRUE)  = appendable.append("java.lang.Boolean@7fa"
    * </pre>
    *
    * @param appendable 追加的目标
    * @param object     对象
    * @throws IOException 写入异常
    * @since 3.2
    */
    public static void identityToString(final Appendable appendable, final Object object) throws IOException {
        appendable.append(object.getClass().getName())
                .append(AT_SIGN)
                .append(Integer.toHexString(System.identityHashCode(object)));
    }

    /**
    * 追加对象的身份标识字符串到 字符串缓冲。
    *
    * <p>生成格式为 {@code 类名@十六进制哈希码} 的身份标识字符串。
    *
    * <pre>
    * ObjectUtils.identityToString(buf, "")            = buf.append("java.lang.String@1e23"
    * ObjectUtils.identityToString(buf, Boolean.TRUE)  = buf.append("java.lang.Boolean@7fa"
    * </pre>
    *
    * @param buffer 追加的目标 字符串缓冲
    * @param object 对象
    * @since 2.4
    */
    public static void identityToString(final StringBuffer buffer, final Object object) {
        final String name = object.getClass().getName();
        final String hexString = Integer.toHexString(System.identityHashCode(object));
        buffer.ensureCapacity(buffer.length() + name.length() + 1 + hexString.length());
        buffer.append(name)
                .append(AT_SIGN)
                .append(hexString);
    }

    /**
    * 检查值是否为指定类型的实例，否则执行回调获取默认值。
    *
    * <p>值为空或类型不匹配时执行 supplier 回调。
    *
    * @param targetType 目标类型
    * @param value      待检查的值
    * @param supplier   不匹配时的回调
    * @param <T>        目标类型
    * @return 匹配时返回原值，否则返回回调结果
    */
    public static <T> T isPresent(Class<? extends T> targetType, Object value, Supplier<T> supplier) {
        if (null == value || !(targetType.isAssignableFrom(value.getClass()))) {
            return supplier.get();
        }

        return (T) value;
    }

    /**
    * 将源对象转换为目标类型的实例。
    *
    * <p>通过 {@link BeanUtils#copyProperties(Object, Class)} 实现属性复制。
    *
    * @param source     源对象
    * @param targetType 目标类型
    * @param <T>        目标类型
    * @return 目标类型的实例
    */
    public static <T> T to(Object source, Class<T> targetType) {
        return BeanUtils.copyProperties(source, targetType);
    }

    /**
    * 将可序列化对象转换为普通对象。
    *
    * <p>数组类型会通过 {@link StringUtils#utf8Str} 转换为字符串。
    *
    * @param serializable 可序列化对象
    * @return 转换后的对象
    */
    public static Object toObject(Serializable serializable) {
        if(null == serializable) {
            return null;
        }

        if(serializable.getClass().isPrimitive()) {
            return serializable;
        }

        if(serializable.getClass().isArray()) {
            return StringUtils.utf8Str(serializable);
        }

        return serializable;

    }

    /**
    * 判断对象是否非空。
    *
    * <p>委托给 {@link #isEmpty(Object)} 取反。
    *
    * @param obj 待检查的对象
    * @return 非空返回 true，否则返回 false
    * @since 4.5.7
    */
    public static boolean isNotEmpty(Object obj) {
        return !isEmpty(obj);
    }
    /**
    * 将对象转换为 UTF-8 字节数组。
    *
    * <p>通过 {@link Converter#convertIfNecessary} 转换。
    *
    * @param obj 待转换的对象
    * @return UTF-8 字节数组
    */
    public static byte[] utf8Bytes(Object obj) {
        return Converter.convertIfNecessary(obj, byte[].class);
    }
    /**
    * 判断所有对象是否都非空。
    *
    * <p>委托给 {@link ArrayUtils#isAllNotEmpty} 实现。
    *
    * @param objs 待检查的对象数组
    * @return 全部非空返回 true，否则返回 false
    */
    public static boolean isAllNotEmpty(Object... objs) {
        return ArrayUtils.isAllNotEmpty(objs);
    }

    /**
    * 将对象转为字符串。
    *
    * @param value 对象
    * @return 字符串表示，为 空 时返回 空
    */
    public static String toString(Object value) {
        return value == null ? null : value.toString();
    }


    /**
    * 将值包装为 {@link Comparable}。
    *
    * <p>如果值已实现 Comparable 则直接返回，否则使用 toString 比较。
    * 值为 空 时返回始终相等（compare转为 返回 0）的 Comparable。
    *
    * @param value 待包装的值
    * @param <E>   值类型
    * @return Comparable 实例
    */
    public static <E>Comparable<E> newComparable(E value) {
        if(value == null) {
            return o -> 0;
        }

        if(value instanceof Comparable comparable) {
            return comparable;
        }

        return (Comparable<E>)(Comparable<String>) o -> o.compareTo( value.toString());
    }
}
