// [P3C A20 豁免] A20/A30/A31: 仅含常量定义行，豁免。
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
    /**
     * Initial_哈希
     */
    private static final int INITIAL_HASH = 7;
    /**
     * 倍数
     */
    private static final int MULTIPLIER = 31;

    /**
     * 空_字符串
     */
    private static final String EMPTY_STRING = "";
    /**
     * 空_字符串
     */
    private static final String NULL_STRING = "null";
    /**
     * Array_启动
     */
    private static final String ARRAY_START = "{";
    /**
     * Array_结束
     */
    private static final String ARRAY_END = "}";
    /**
     * 空_array
     */
    private static final String EMPTY_ARRAY = ARRAY_START + ARRAY_END;
    /**
     * Array_element_separator
     */
    private static final String ARRAY_ELEMENT_SEPARATOR = ", ";
    /**
     * At_标志
     */
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
     * @return 空安全哈希编码的结果
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
     * Returns a hash code based on the contents of the specified array.
     * If {@code array} is {@code null}, this method returns 0.
     * @param array array
     * @return a null safe hash code based on the content of the array
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
     * Returns a hash code based on the contents of the specified array.
     * If {@code array} is {@code null}, this method returns 0.
     * @param array array
     * @return a null safe hash code based on the content of the array
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
     * Returns a hash code based on the contents of the specified array.
     * If {@code array} is {@code null}, this method returns 0.
     * @param array array
     * @return a null safe hash code based on the content of the array
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
     * Returns a hash code based on the contents of the specified array.
     * If {@code array} is {@code null}, this method returns 0.
     * @param array array
     * @return a null safe hash code based on the content of the array
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
     * Returns a hash code based on the contents of the specified array.
     * If {@code array} is {@code null}, this method returns 0.
     * @param array array
     * @return a null safe hash code based on the content of the array
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
     * Returns a hash code based on the contents of the specified array.
     * If {@code array} is {@code null}, this method returns 0.
     * @param array array
     * @return a null safe hash code based on the content of the array
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
     * Returns a hash code based on the contents of the specified array.
     * If {@code array} is {@code null}, this method returns 0.
     * @param array array
     * @return a null safe hash code based on the content of the array
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
     * Returns a hash code based on the contents of the specified array.
     * If {@code array} is {@code null}, this method returns 0.
     * @param array array
     * @return a null safe hash code based on the content of the array
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
     * Returns a hash code based on the contents of the specified array.
     * If {@code array} is {@code null}, this method returns 0.
     * @param array array
     * @return a null safe hash code based on the content of the array
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


     */
@SuppressWarnings("ALL")
    public static <T>T firstNonNull(T v1, T v2) {
        if (v1 instanceof Optional<?> optionalO) {
            if (optionalO.isPresent()) {
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
            if (null == arg) {
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
        if (null == value) {
            return defaultValue;
        }
        return value.toString();
    }


    /**
     * Returns a String representation of the specified object.
     * <p>Builds a String representation of the contents in case of an array.
     * Returns a {@code "null"} String if {@code obj} is {@code null}.
     *
     * @param obj the object to build a String representation for
     * @return a String representation of {@code obj}
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


     * @param obj2 Integer 2
     * @return 相等返回 true，否则返回 false
     * @see Objects#equals(Object, Object)
     */
    public static boolean equals(Integer obj1, Integer obj2) {
        if (null == obj1) {
            obj1 = 0;
        }

        if (null == obj2) {
            obj2 = 0;
        }

        return obj1.equals(obj2);
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
        if (obj1 instanceof String && obj2 instanceof String) {
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
    public static Object withNull(Object value, SafeSupplier<Object> nullCallback,
                                  SafeFunction<Object, Object> function) {
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
        }
        return function.apply(value);
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
        if (null == reference) {
            return true;
        }

        if (reference instanceof Boolean) {
            return (boolean) reference;
        }

        if (reference instanceof Iterable) {
            return CollectionUtils.isEmpty((Iterable)reference);
        }

        if (reference instanceof String) {
            return StringUtils.isEmpty(reference.toString());
        }

        return false;
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



}
