package com.chua.common.support.utils;

import com.chua.common.support.constant.CommonConstant;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.matcher.PathMatcher;

import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.StringJoiner;

import static com.chua.common.support.constant.CommonConstant.*;
import static com.chua.common.support.constant.ValueConstant.*;


/**
* 数组工具类，提供数组操作的核心工具方法。
*
* <p>包含以下功能：
* <ul>
*   <li>数组创建 — 空数组常量、类型化数组、包装数组</li>
*   <li>数组拷贝 — 克隆、截取、合并、追加、插入</li>
*   <li>数组查找 — 遍历查找、二分查找、正向/反向查找、元素索引</li>
*   <li>数组转换 — 字符串与数组互转、集合与数组互转、基本类型与包装类型互转</li>
*   <li>数组判断 — 判空、是否包含、元素类型校验、值比较</li>
*   <li>数组操作 — 反转、去重、排序、移除、填充</li>
*   <li>子数组 — 获取子数组、分页、坐标转换</li>
*   <li>编辑距离 — 莱文斯坦距离计算</li>
* </ul>
*
* @author CH
* @since 4.0.0
 */
public class ArrayUtils {

    /**
    * array工具。
     */
    private ArrayUtils() {
    }

    /**
    *
    *
    * @param array array
    * @param name  名称
    * @return the 结果
     */
    public static boolean isMatch(@Nullable String[] array, @Nullable String name) {
        if (isEmpty(array)) {
            return false;
        }

        for (String s : array) {
            if (s.contains(SYMBOL_ASTERISK)) {
                if (PathMatcher.INSTANCE.match(s, name)) {
                    return true;
                }
                continue;
            }

            if (s.equals(name)) {
                return true;
            }
        }

        return false;
    }

    /**
    *
    *
    * @param obj obj
    * @return {@code null}       false
     */
    public static boolean isArray(@Nullable Object obj) {
        return null != obj && obj.getClass().isArray();
    }

    /**
    * {@code null}
    *
    * @param <T>
    * @param array array
    * @return {@code null}
    * @since 3.0.7
     */
@SuppressWarnings({"unchecked", "all"})
    public static <T> boolean hasNull(T... array) {
        if (!isEmpty(array)) {
            for (T element : array) {
                if (Objects.isNull(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
    *
    *
    * @param source       源
    * @param defaultArray 默认array
    * @param <T>
    * @return the 结果
     */
    public static <T> T[] defaultIfEmpty(T[] source, T[] defaultArray) {
        if (null == source || source.length == 0) {
            return defaultArray;
        }

        return source;
    }

    /**
    * {@link System#arraycopy(Object, int, Object, int, int)}<br>
    * 0
    *
    * @param src    src
    * @param length 长度
    * @return the 结果
    * @since 3.0.6
     */
    public static <T> T copyRange(T src, int length) {
        int max = Array.getLength(src);
        T temp = null;
        if (max <= length) {
            temp = (T) Array.newInstance(Array.get(src, 0).getClass(), max);
            System.arraycopy(src, 0, temp, 0, max);
            return temp;
        }
        temp = (T) Array.newInstance(Array.get(src, 0).getClass(), length);
        System.arraycopy(src, 0, temp, 0, length);
        return temp;
    }

    /**
    * {@link System#arraycopy(Object, int, Object, int, int)}<br>
    * 0
    *
    * @param src    src
    * @param dest   dest
    * @param length 长度
    * @return the 结果
    * @since 3.0.6
     */
    public static Object copy(Object src, Object dest, int length) {
        System.arraycopy(src, 0, dest, 0, length);
        return dest;
    }

    /**
    *
    *
    * @param stringArray  字符串array
    * @param index        索引
    * @param defaultValue 默认值
    * @return the 结果
    * @see NullPointerException
     */
    public static <T> T getIndex(T[] stringArray, int index, T defaultValue) {
        return stringArray == null || stringArray.length == 0 || stringArray.length <= index ? defaultValue : stringArray[index];
    }

    /**
    *
    *
    * @param stringArray 字符串array
    * @param index       索引
    * @return the 结果
    * @see NullPointerException
     */
    public static <T> T getIndex(T[] stringArray, int index) {
        return getIndex(stringArray, index, null);
    }

    /**
    * byte[]           float[]
    *
    * @param value 值
    * @return float[]                空
     */
    public static float[] transToFloatArray(byte[] value) {
        float[] floats = new float[value.length];
        int count = 0;
        for (byte o : value) {
            floats[count++] = Converter.convertIfNecessary(o, float.class);
        }
        return floats;
    }

    /**
    * float[] 转 double[]。
    *
    * <p>null 输入返回 null，空数组返回空数组。</p>
    *
    * @param value float 数组
    * @return 对应的 double 数组，空 输入返回 空
    * @author CH
    * @since 4.0.0.42
     */
    public static double[] toDouble(float[] value) {
        if (value == null) {
            return null;
        }
        double[] result = new double[value.length];
        for (int i = 0; i < value.length; i++) {
            result[i] = value[i];
        }
        return result;
    }

    /**
    * double[] 转 float[]。
    *
    * <p>null 输入返回 null，空数组返回空数组。</p>
    *
    * @param value double 数组
    * @return 对应的 float 数组，空 输入返回 空
    * @author CH
    * @since 4.0.0.42
     */
    public static float[] toFloat(double[] value) {
        if (value == null) {
            return null;
        }
        float[] result = new float[value.length];
        for (int i = 0; i < value.length; i++) {
            result[i] = (float) value[i];
        }
        return result;
    }

    /**
    * byte[]
    *
    * @param value 值
    * @param type  类型
    * @return null
     */
    public static <T> T[] transToArray(byte[] value, Class<T> type) {
        T[] newInstance = (T[]) Array.newInstance(type, value.length);
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (byte o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, type);
        }
        return newInstance;
    }

    /**
    * long[]
    *
    * @param value 值
    * @param type  类型
    * @return null
     */
    public static <T> T[] transToArray(long[] value, Class<T> type) {
        T[] newInstance = (T[]) Array.newInstance(type, value.length);
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (long o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, type);
        }
        return newInstance;
    }

    /**
    * float[]
    *
    * @param value 值
    * @param type  类型
    * @return null
     */
    public static <T> T[] transToArray(float[] value, Class<T> type) {
        T[] newInstance = (T[]) Array.newInstance(type, value.length);
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (float o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, type);
        }
        return newInstance;
    }

    /**
    * double[]
    *
    * @param value 值
    * @param type  类型
    * @return null
     */
    public static <T> T[] transToArray(double[] value, Class<T> type) {
        T[] newInstance = (T[]) Array.newInstance(type, value.length);
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (double o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, type);
        }
        return newInstance;
    }

    /**
    * short[]
    *
    * @param value 值
    * @param type  类型
    * @return null
     */
    public static <T> T[] transToArray(short[] value, Class<T> type) {
        T[] newInstance = (T[]) Array.newInstance(type, value.length);
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (short o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, type);
        }
        return newInstance;
    }

    /**
    * int[]
    *
    * @param value 值
    * @param type  类型
    * @return null
     */
    public static <T> T[] transToArray(int[] value, Class<T> type) {
        T[] newInstance = (T[]) Array.newInstance(type, value.length);
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (int o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, type);
        }
        return newInstance;
    }

    /**
    * 布尔值[]
    *
    * @param value 值
    * @param type  类型
    * @return null
     */
    public static <T> T[] transToArray(boolean[] value, Class<T> type) {
        T[] newInstance = (T[]) Array.newInstance(type, value.length);
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (boolean o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, type);
        }
        return newInstance;
    }

    /**
    * 对象[]
    *
    * @param value 值
    * @param type  类型
    * @return null
     */
    public static <T> T[] transToArray(Object[] value, Class<T> type) {
        T[] newInstance = (T[]) Array.newInstance(type, value.length);
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (Object o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, type);
        }
        return newInstance;
    }

    /**
    * 列表
    *
    * @param value 值
    * @param type  类型
    * @return null
     */
    public static <T> T[] transToArray(List value, Class<T> type) {
        T[] newInstance = (T[]) Array.newInstance(type, null == value ? 0 : value.size());
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (Object o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, type);
        }
        return newInstance;
    }

    /**
    *
    *
    * @param array array
    * @param value 值
    * @return the 结果
    * @since 3.0.7
     */
    public static boolean contains(char[] array, char value) {
        return indexOf(array, value) > INDEX_NOT_FOUND;
    }

    /**
    *
    *
    * @param array array
    * @param str   str
    * @return true               false
     */
    public static <T> boolean contains(T[] array, T str) {
        if (array == null) {
            return false;
        }

        if (isEmpty(array) && (str == null || (str instanceof String && StringUtils.isEmpty(str.toString())))) {
            return true;
        }

        for (T item : array) {
            if (null == item && null == str) {
                return true;
            }

            if (null == str) {
                return false;
            }

            if (null != item && item.equals(str)) {
                return true;
            }
        }
        return false;
    }

    /**
    *
    *
    * @param array array
    * @param str   str
    * @return true               false
     */
    public static <T> boolean containsIgnoreCase(T[] array, T str) {
        if (array == null) {
            return false;
        }

        for (T item : array) {
            if (null == item && null == str) {
                return true;
            }

            if (null == str) {
                return false;
            }

            if (null != item && item.toString().toLowerCase().equalsIgnoreCase(str.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
    * {@link CommonConstant#INDEX_NOT_FOUND}
    *
    * @param array array
    * @param value 值
    * @return {@link 通用常量#索引_NOT_FOUND}
    * @since 3.0.7
     */
    public static int indexOf(char[] array, char value) {
        if (null != array) {
            for (int i = 0; i < array.length; i++) {
                if (value == array[i]) {
                    return i;
                }
            }
        }
        return INDEX_NOT_FOUND;
    }

    /**
    *
    *
    * @param array1 1
    * @param array2 2
    * @return the 结果
    * @since 5.4.2
     */
    public static boolean isEquals(Object array1, Object array2) {
        if (array1 == array2) {
            return true;
        }
        if (hasNull(array1, array2)) {
            return false;
        }

        if (array1 instanceof long[]) {
            return Arrays.equals((long[]) array1, (long[]) array2);
        } else if (array1 instanceof int[]) {
            return Arrays.equals((int[]) array1, (int[]) array2);
        } else if (array1 instanceof short[]) {
            return Arrays.equals((short[]) array1, (short[]) array2);
        } else if (array1 instanceof char[]) {
            return Arrays.equals((char[]) array1, (char[]) array2);
        } else if (array1 instanceof byte[]) {
            return Arrays.equals((byte[]) array1, (byte[]) array2);
        } else if (array1 instanceof double[]) {
            return Arrays.equals((double[]) array1, (double[]) array2);
        } else if (array1 instanceof float[]) {
            return Arrays.equals((float[]) array1, (float[]) array2);
        } else if (array1 instanceof boolean[]) {
            return Arrays.equals((boolean[]) array1, (boolean[]) array2);
        } else {
 // Not an array 的 primitives
            return Arrays.deepEquals((Object[]) array1, (Object[]) array2);
        }
    }

    /**
    *
    *
    * @param source 源
    * @param target Target
    * @return true
     */
    public static boolean isEquals(Class<?>[] source, Class<?>[] target) {
        if (source == target && source == null) {
            return true;
        }

        if (source.length != target.length) {
            return false;
        }

        boolean isEquals = true;
        for (int i = 0; i < source.length; i++) {
            Class<?> sourceClass = source[i];
            Class<?> targetClass = target[i];

            if (null == sourceClass || void.class == sourceClass || Void.class == sourceClass) {
                continue;
            }

            if (!targetClass.isAssignableFrom(sourceClass)) {
                isEquals = false;
                break;
            }
        }
        return isEquals;
    }

    /**
    *
    *
    * @param params 参数
    * @param args   参数
    * @return the 结果
     */
    public static boolean isEquals(Class<?>[] params, Object[] args) {
        if (params.length != args.length) {
            return false;
        }

        int index = 0;
        for (Object arg : args) {
            Class<?> item = params[index++];
            if (arg != null) {
                if (!ClassUtils.fromPrimitive(item).isAssignableFrom(arg.getClass())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
    *
    *
    * @param params 参数
    * @param args   参数
    * @return the 结果
     */
    public static boolean isEquals(Class<?>[] params, String[] args) {
        if (params.length != args.length) {
            return false;
        }

        int index = 0;
        for (Object arg : args) {
            Class<?> item = params[index];
            boolean rs = null != arg && item.getTypeName().equals(args[index]);
            index++;
            if (!rs) {
                return false;
            }
        }
        return true;
    }

    /**
    *
    *
    * @param array array
    * @return true
     */
    public static <T> boolean isEmpty(@Nullable T[] array) {
        return null == array || array.length == 0;
    }

    /**
    * {@code null}                     {@link ObjectUtils#isEmpty(Object)}
    *
    * @param args 参数
    * @return the 结果
    * @since 4.5.18
     */
    public static boolean hasEmpty(Object... args) {
        if (isNotEmpty(args)) {
            for (Object element : args) {
                if (ObjectUtils.isEmpty(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
    * {@code null}                     {@link ObjectUtils#isEmpty(Object)}
    *
    * @param args ,
    * @return the 结果
    * @since 4.5.18
     */
    public static boolean isAllNotEmpty(Object... args) {
        return !hasEmpty(args);
    }

    /**
    *
    *
    * @param array array
    * @return true
     */
    public static boolean isEmpty(@Nullable final int[] array) {
        return null == array || array.length == 0;
    }

    /**
    *
    *
    * @param array array
    * @return true
     */
    public static boolean isEmpty(@Nullable final float[] array) {
        return null == array || array.length == 0;
    }

    /**
    *
    *
    * @param array array
    * @return true
     */
    public static boolean isEmpty(@Nullable final byte[] array) {
        return null == array || array.length == 0;
    }

    // ----------------------------------------------------------------------

    /**
    * <p>                                           {@code null}
    *
    * @param <T>
    * @param array array
    * @return {@code true}                                {@code null}
    * @since 2.5
     */
    public static <T> boolean isNotEmpty(@Nullable final T[] array) {
        return !isEmpty(array);
    }

    /**
    * <p>                                           {@code null}
    *
    * @param <T>
    * @param array array
    * @return {@code true}                                {@code null}
    * @since 2.5
     */
    public static <T> boolean isNotEmpty(@Nullable final byte[] array) {
        return !isEmpty(array);
    }

    /**
    * x
    *
    * @param index      索引
    * @param arrayWidth x
    * @return x
     */
    public static int convert1DtoX(final int index, final int arrayWidth) {
        return index % arrayWidth;
    }

    /**
    * y
    *
    * @param index      索引
    * @param arrayWidth x
    * @return y
     */
    public static int convert1DtoY(final int index, final int arrayWidth) {
        return index / arrayWidth;
    }

    /**
    *
    *
    * @param x          x
    * @param y          y
    * @param arrayWidth x
    * @return the 结果
     */
    public static int convert2dTo1d(final int x, final int y, final int arrayWidth) {
        return y * arrayWidth + x;
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return the 结果
     */
    public static <T> T[] mergeOfDistince(T[] target, T... source) {
        if (target.length == 0 && source.length == 0) {
            return null;
        }

        List<T> objects = new ArrayList<>(target.length + source.length);
        for (T t : target) {
            if (objects.contains(t)) {
                continue;
            }
            objects.add(t);
        }

        return objects.toArray(target);
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return the 结果
     */
    public static <T> T[] merge(T[] target, T... source) {
        if (target.length == 0 && source.length == 0) {
            return null;
        }

        List<T> objects = new ArrayList<>(target.length + source.length);
        objects.addAll(Arrays.asList(target));
        objects.addAll(Arrays.asList(source));

        return objects.toArray(target);
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return 添加element的结果
     */
    public static <E> void addElement(E[] target, Collection<E> source) {
        addElement(target, source.toArray(target));
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return the 结果
     */
    public static <E> E[] addFirstElement(E[] target, E source) {
        if (target.length == 0) {
            return target;
        }

        int oldLength = target.length;
        Class<? extends E[]> aClass = (Class<? extends E[]>) target.getClass();
        E[] copy = (E[]) Array.newInstance(aClass.getComponentType(), oldLength + 1);
        System.arraycopy(target, 0, copy, 1, oldLength);
        target[0] = source;

        return target;
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return the 结果
     */
    public static <E> E[] addLastElement(E[] target, E source) {
        if (target.length == 0) {
            return target;
        }

        int oldLength = target.length;
        target = Arrays.copyOf(target, oldLength + 1);
        target[oldLength] = source;

        return target;
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @param from   从
    * @param to     转为
    * @return the 结果
     */
    public static <E> E[] setRange(E[] target, E source, int from, int to) {
        if (target.length == 0) {
            return target;
        }
        for (int i = from; i <= to; i++) {
            target[i] = source;
        }
        return target;
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return the 结果
     */
    public static <E> E[] setFirst(E[] target, E source) {
        if (target.length == 0) {
            return target;
        }
        target[0] = source;
        return target;
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return the 结果
     */
    public static <E> E[] setLast(E[] target, E source) {
        if (target.length == 0) {
            return target;
        }
        target[target.length - 1] = source;
        return target;
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @param index  索引
    * @return the 结果
     */
    public static <E> E[] setElement(E[] target, E source, int index) {
        if (target.length == 0) {
            return target;
        }
        target[index] = source;
        return target;
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return the 结果
     */
    public static <E> E[] addElement(E[] target, E... source) {
        if (target.length == 0 && source.length == 0) {
            return target;
        }

        int oldLength = target.length;
        target = Arrays.copyOf(target, oldLength + source.length);
        System.arraycopy(source, 0, target, oldLength, source.length);

        return target;
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return 插入element的结果
     */
    public static <E> void insertElement(E[] target, Collection<E> source) {
        insertElement(target, source.toArray((E[]) Array.newInstance(CollectionUtils.findLast(source).getClass(), 0)));
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @param offset 偏移量
    * @return 插入element的结果
     */
    public static <E> void insertElement(E[] target, int offset, Collection<E> source) {
        insertElement(target, offset, source.toArray((E[]) Array.newInstance(CollectionUtils.findLast(source).getClass(), 0)));
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return 插入element的结果
     */
    public static <E> void insertElement(E[] target, E[] source) {
        insertElement(target, 0, source);
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @return 插入element的结果
     */
    public static <E> void insertElement(E[] target, E source) {
        insertElement(target, 0, Collections.singletonList(source));
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @param offset 偏移量
    * @return 插入element的结果
     */
    public static <E> void insertElement(E[] target, int offset, E source) {
        insertElement(target, offset, Collections.singletonList(source));
    }

    /**
    *
    *
    * @param target Target
    * @param source 源
    * @param offset 偏移量
    * @return 插入element的结果
     */
    public static <E> void insertElement(E[] target, int offset, E[] source) {
        if (target.length == 0 && source.length == 0) {
            return;
        }

        System.arraycopy(source, 0, target, offset, source.length);
    }

    /**
    *
    *
    * @param iterable 可迭代
    * @param array    array
    * @return the 结果
     */
    public static <T> T[] toArray(Iterable<? extends T> iterable, T[] array) {
        List<? extends T> ts = CollectionUtils.newArrayList(iterable);
        return ts.toArray(array);
    }

    /**
    *
    *
    * @param iterable 可迭代
    * @param function function
    * @return the 结果
    * @param generator 生成器
     */
    public static <T, A> A[] toArray(T[] iterable, Function<T, A> function, IntFunction<A[]> generator) {
        if (null == iterable) {
            return null;
        }
        List<? extends T> ts = CollectionUtils.newArrayList(iterable);
        return ts.stream().map(function).toArray(generator);
    }

    /**
    *
    *
    * @param iterable 可迭代
    * @param function function
    * @return the 结果
     */
    public static <T> String[] toArrayString(T[] iterable, Function<T, String> function) {
        if (null == iterable) {
            return SYMBOL_EMPTY_STRING_ARRAY;
        }

        return toArray(iterable, function, String[]::new);
    }

    /**
    *
    *
    * @param iterable 可迭代
    * @param function function
    * @return the 结果
    * @param generator 生成器
     */
    public static <T, A> A[] toArray(Iterable<? extends T> iterable, Function<T, A> function, IntFunction<A[]> generator) {
        if (null == iterable) {
            return null;
        }
        List<? extends T> ts = CollectionUtils.newArrayList(iterable);
        return ts.stream().map(function).toArray(generator);
    }

    /**
    *
    *
    * @param iterable 可迭代
    * @param function function
    * @return the 结果
     */
    public static <T> String[] toArrayString(Iterable<? extends T> iterable, Function<T, String> function) {
        if (null == iterable) {
            return SYMBOL_EMPTY_STRING_ARRAY;
        }
        List<? extends T> ts = CollectionUtils.newArrayList(iterable);
        return ts.stream().map(function).toArray(String[]::new);
    }

    /**
    *
    *
    * @param iterable 可迭代
    * @return the 结果
     */
    public static <T> T[] toArray(Iterable<? extends T> iterable) {
        List<? extends T> ts = CollectionUtils.newArrayList(iterable);
        if (null == ts || ts.isEmpty()) {
            return null;
        }
        return ts.toArray((T[]) Array.newInstance(ts.get(0).getClass(), 0));
    }

    /**
    *
    *
    * @param split     分割
    * @param separator separator
    * @return the 结果
     */
    public static String[] trimOrSeparator(String[] split, String separator) {
        String[] rs = new String[split.length];
        for (int i = 0; i < split.length; i++) {
            String s = split[i];
            rs[i] = StringUtils.startWithMove(StringUtils.endWithMove(s.trim(), separator), separator);
        }

        return rs;
    }

    /**
    *
    *
    * @param value 值
    * @return null
     */
    public static byte[] transToByteArray(String[] value) {
        if (value == null) {
            return new byte[0];
        }

        byte[] bytes = new byte[value.length];
        for (int i = 0; i < value.length; i++) {
            String s = value[i];
            try {
                bytes[i] = Converter.convertIfNecessary(s, byte.class);
            } catch (Exception e) {
                break;
            }
        }
        return new byte[0];
    }

    /**
    *
    *
    * @param value 值
    * @return null
     */
    public static byte[] transToByteArray(Object value) {
        if (value == null) {
            return new byte[0];
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            byte[] array = new byte[length];
            for (int i = 0; i < array.length; i++) {
                array[i] = Converter.convertIfNecessary(Array.get(value, i), Byte.class).byteValue();
            }
            return array;
        }

        if (value instanceof Collection) {
            return transToByteArray(new LinkedList((Collection) value));
        }
        return new byte[0];
    }

    /**
    *
    *
    * @param value 值
    * @return null
     */
    public static byte[] transToByteArray(List value) {
        byte[] newInstance = new byte[value.size()];
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (Object o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, Byte.class).byteValue();
        }
        return newInstance;
    }

    /**
    *
    *
    * @param value 值
    * @return null
     */
    public static <T> byte[] transToByteArray(T[] value) {
        byte[] newInstance = new byte[value.length];
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (Object o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, Byte.class).byteValue();
        }
        return newInstance;
    }

    /**
    * trans转为bytearray
    *
    * @param value 值
    * @return trans转为bytearray的结果
     */
    public static byte[] transToByteArray(float[] value) {
        if (value == null) {
            return null;
        }
        byte[] result = new byte[value.length * 4];
        for (int i = 0; i < value.length; i++) {
            int bits = Float.floatToIntBits(value[i]);
            result[i * 4] = (byte) (bits >> 24);
            result[i * 4 + 1] = (byte) (bits >> 16);
            result[i * 4 + 2] = (byte) (bits >> 8);
            result[i * 4 + 3] = (byte) bits;
        }
        return result;
    }

    /**
    *
    *
    * @param value 值
    * @return null
     */
    public static <T> int[] transToIntArray(byte[] value) {
        int[] newInstance = new int[value.length];
        if (newInstance.length == 0) {
            return newInstance;
        }
        int count = 0;
        for (Object o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, int.class).byteValue();
        }
        return newInstance;
    }

    /**
    * <br>
    * 空
    *
    * @param <T>
    * @param arrays arrays
    * @return the 结果
     */
    @SafeVarargs
    public static <T> T[] addAll(T[]... arrays) {
        if (arrays.length == 1) {
            return arrays[0];
        }

        int length = 0;
        for (T[] array : arrays) {
            if (null != array) {
                length += array.length;
            }
        }
        T[] result = newArray(arrays.getClass().getComponentType().getComponentType(), length);

        length = 0;
        for (T[] array : arrays) {
            if (null != array) {
                System.arraycopy(array, 0, result, length, array.length);
                length += array.length;
            }
        }
        return result;
    }

    /**
    *
    *
    * @param <T>
    * @param componentType 组件类型
    * @param newSize       新大小
    * @return the 结果
     */
    public static <T> T[] newArray(Class<?> componentType, int newSize) {
        return (T[]) Array.newInstance(componentType, newSize);
    }

    /**
    *
    *
    * @param newSize 新大小
    * @return the 结果
    * @since 3.3.0
     */
    public static Object[] newArray(int newSize) {
        return new Object[newSize];
    }

    /**
    *
    *
    * @param array array
    * @return the 结果
    * @since 3.2.2
     */
    public static Class<?> getComponentType(Object array) {
        return null == array ? null : array.getClass().getComponentType();
    }

    /**
    *
    *
    * @param arr arr
    * @return the 结果
     */
    public static boolean allEmpty(Object[] arr) {
        for (Object o : arr) {
            if (null != o) {
                return false;
            }
        }

        return true;
    }

    /**
    * 字符串
    *
    * @param obj obj
    * @return the 结果
     */
    public static String toString(Object obj) {
        if (null == obj) {
            return null;
        }

        if (obj instanceof long[]) {
            return Arrays.toString((long[]) obj);
        } else if (obj instanceof int[]) {
            return Arrays.toString((int[]) obj);
        } else if (obj instanceof short[]) {
            return Arrays.toString((short[]) obj);
        } else if (obj instanceof char[]) {
            return Arrays.toString((char[]) obj);
        } else if (obj instanceof byte[]) {
            return Arrays.toString((byte[]) obj);
        } else if (obj instanceof boolean[]) {
            return Arrays.toString((boolean[]) obj);
        } else if (obj instanceof float[]) {
            return Arrays.toString((float[]) obj);
        } else if (obj instanceof double[]) {
            return Arrays.toString((double[]) obj);
        } else if (ArrayUtils.isArray(obj)) {
            //             
            try {
                return Arrays.deepToString((Object[]) obj);
            } catch (Exception ignore) {
                //ignore
            }
        }

        return obj.toString();
    }

    /**
    *
    *
    * @param arr arr
    * @return the 结果
     */
    public static List<String> strArrayToList(String[] arr) {
        List<String> result = new ArrayList<String>();
        if (arr == null) {
            return result;
        }
        Collections.addAll(result, arr);
        return result;
    }

    /**
    *
    *
    * @param columns    columns
    * @param collection 集合
    * @return the 结果
     */
    public static Object[][] toArrays(List<String> columns, List<?> collection) {
        Object[][] rs = new Object[collection.size()][columns.size()];
        for (int i = 0; i < collection.size(); i++) {
            Object o = collection.get(i);
            Object[] objects = toArray(columns, o);
            System.arraycopy(objects, 0, rs[i], 0, columns.size());
        }

        return rs;
    }

    /**
    *
    *
    * @param columns columns
    * @param o       o
    * @return the 结果
     */
    private static Object[] toArray(List<String> columns, Object o) {
        Map<String, Object> beanMap = BeanUtils.objectToMap(o);
        Object[] rs = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            rs[i] = beanMap.get(column);
        }

        return rs;
    }

    /**
    *
    *
    * @param value 值
    * @return the 结果
     */
    public static Collection<?> toList(Object value) {
        List<Object> rs = new LinkedList<>();
        int length = Array.getLength(value);
        for (int i = 0; i < length; i++) {
            rs.add(Array.get(value, i));
        }

        return rs;
    }

    /**
    *
    *
    * @param arr    arr
    * @param offset 偏移量
    * @return the 结果
     */
    public static <T> T[] subArray(T[] arr, int offset) {
        if (arr.length < offset) {
            return null;
        }

        T[] v = (T[]) Array.newInstance(arr[0].getClass(), arr.length - offset);
        System.arraycopy(arr, offset, v, 0, v.length);
        return v;
    }

    /**
    * <p>
    *
    * <p>       {@code null}
    *
    * @param array {@code null}
     */
    public static void reverse(final byte[] array) {
        if (array == null) {
            return;
        }
        reverse(array, 0, array.length);
    }

    /**
    * <p>
    *
    *
    * <p>
    * {@code null}
    *
    * @param array               {@code null}
    * @param startIndexInclusive 0               0
    * @param endIndexExclusive   结束索引-1
    * @since 3.2
     */
    public static void reverse(final byte[] array, final int startIndexInclusive, final int endIndexExclusive) {
        if (array == null) {
            return;
        }
        int i = Math.max(startIndexInclusive, 0);
        int j = Math.min(array.length, endIndexExclusive) - 1;
        byte tmp;
        while (j > i) {
            tmp = array[j];
            array[j] = array[i];
            array[i] = tmp;
            j--;
            i++;
        }
    }

    /**
    *
    *
    * @param arr     arr
    * @param offset  偏移量
    * @param offset2 偏移量2
    * @return the 结果
     */
    public static <T> T[] subArray(T[] arr, int offset, int offset2) {
        if (arr.length < offset) {
            return null;
        }

        if (offset2 <= offset) {
            return null;
        }

        T[] v = (T[]) Array.newInstance(arr[0].getClass(), offset2 - offset);
        System.arraycopy(arr, offset, v, 0, offset2 - offset);
        return v;
    }

    /**
    *
    *
    * @param arr         arr
    * @param targetValue Target值
    * @return the 结果
     */
    public static boolean arraysContains(String[] arr, String targetValue) {
        for (String s : arr) {
            if (s.equals(targetValue)) {
                return true;
            }
        }
        return false;
    }

    /**
    *
    *
    * @param arr     arr
    * @param offset  偏移量
    * @param offset2 偏移量2
    * @return the 结果
     */
    public static byte[] subArray(byte[] arr, int offset, int offset2) {
        if (arr.length < offset) {
            return null;
        }

        if (offset2 <= offset) {
            return null;
        }

        byte[] rs = new byte[offset2 - offset + 1];
        System.arraycopy(arr, offset, rs, 0, offset2 - offset + 1);
        return rs;
    }

    /**
    *
    *
    * @param value 值
    * @return the 结果
     */
    public static int length(String[] value) {
        return null != value ? value.length : 0;
    }

    /**
    *
    *
    * @param array array
    * @return the 结果
     */
    public static byte[] reverseArray(byte[] array) {
        byte[] copy = new byte[array.length];
        copy(array, copy, copy.length);

        for (int i = 0; i < copy.length / 2; i++) {
            byte temp = copy[i];
            copy[i] = copy[copy.length - 1 - i];
            copy[copy.length - 1 - i] = temp;
        }

        return copy;
    }

    /**
    *
    *
    * @param bytes       bytes
    * @param fixedLength fixed长度
    * @return the 结果
     */
    public static byte[] fixed(byte[] bytes, int fixedLength) {
        if (bytes.length > fixedLength) {
            byte[] rs = new byte[fixedLength];
            System.arraycopy(bytes, 0, rs, 0, fixedLength);
            return rs;
        }
        byte[] rs = new byte[fixedLength];
        System.arraycopy(bytes, 0, rs, 0, bytes.length);

        return rs;
    }

    /**
    *
    *
    * @param array array
    * @return the 结果
     */
    public static String first(String[] array) {
        if (null == array || array.length == 0) {
            return null;
        }

        return array[0];
    }

    /**
    *
    *
    * @param array array
    * @return the 结果
     */
    public static String last(String[] array) {
        if (null == array || array.length == 0) {
            return null;
        }

        return array[array.length - 1];
    }

    /**
    *
    *
    * @param ele ele
    * @param <E>
    * @return the 结果
     */
    public static <E> E[] of(E... ele) {
        //                                                                                        
        E[] rs = (E[]) Array.newInstance(ele.getClass().getComponentType(), ele.length);

        //                                              
        System.arraycopy(ele, 0, rs, 0, ele.length);

        return rs;
    }

    /**
    *
    *
    * @param source     源
    * @param ele        ele
    * @param ignoreCase ignore大小写
    * @return true               false
     */
    public static boolean containsAny(Set<String> source, String[] ele, boolean ignoreCase) {
        //                                                                               false                                       
        if (isEmpty(ele) || CollectionUtils.isEmpty(source)) {
            return false;
        }

        //                                                                         
        for (String s : ele) {
            //                                        true                                                            
            if ((!ignoreCase && source.contains(s)) || (ignoreCase && CollectionUtils.containsIgnoreCase(source, s))) {
                return true;
            }
        }
        //                                                             false                                                   
        return false;
    }

    /**
    *
    *
    * @param responseBody 响应主体
    * @param start        启动
    * @param end          结束
    * @return the 结果
     */
    public static byte[] range(byte[] responseBody, Integer start, Integer end) {
        return ArrayUtils.subArray(responseBody, (start == null || start < 0 ? 0 : start), (null == end || end < 0 ? responseBody.length : end));
    }

    /**
    *
    *
    * @param source 源
    * @param target 1
    * @return 1
     */
    public static boolean containsAllIgnoreCase(String[] target, String... source) {
        if (ArrayUtils.isEmpty(source)) {
            return true;
        }

        for (String s : target) {
            for (String s1 : source) {
                if (!s.equalsIgnoreCase(s1)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
    *
    *
    * @param key   键
    * @param limit 限制
    * @return the 结果
     */
    public static byte[] rightPadAndLimit(byte[] key, int limit) {
        byte[] rs = new byte[limit];
        if (key.length >= limit) {
            System.arraycopy(key, 0, rs, 0, limit);
            return rs;
        }

        System.arraycopy(key, 0, rs, 0, key.length);
        return rs;
    }

    /**
    * N
    *
    * @param originalArray 原始array
    * @param step          N
    * @return N
     */
    public static <T> T[][] generatePairArray(T[] originalArray, int step) {
 // 群体大小
        if (originalArray.length % step != 0) {
            throw new IllegalArgumentException("                           groupSize      ");
        }

 // 群体大小
        T[][] newArray = (T[][]) Array.newInstance(originalArray[0].getClass(), originalArray.length / step);

        //                             N                                                 
        for (int i = 0; i < originalArray.length; i += step) {
            T[] group = (T[]) Array.newInstance(originalArray[0].getClass(), step);
            System.arraycopy(originalArray, i, group, 0, step);
            newArray[i / step] = group;
        }

        return newArray;
    }

    /**
    *
    *
    * @param strings       字符串
    * @param strings1      字符串1
    * @param strBiFunction strbifunction
    * @param <S>
    * @param <T>
    * @param <R>
    * @return the 结果
     */
    public static <S, T, R> R[] convert(S[] strings, T[] strings1, BiFunction<S, T, R> strBiFunction) {
        List<Object> result = new ArrayList<Object>(strings.length * strings1.length);
        for (S string : strings) {
            for (T t : strings1) {
                result.add(strBiFunction.apply(string, t));
            }
        }
        if (result.isEmpty()) {
            return (R[]) ArrayUtils.newArray(0);
        }
        return (R[]) result.toArray(ArrayUtils.newArray(result.get(0).getClass(), 0));
    }

    /**
    *
    *
    * @param array array
    * @return the 结果
     */
    public static Float[] toObject(float[] array) {
        if (array == null) {
            return null;
        } else if (array.length == 0) {
            return SYMBOL_EMPTY_OBJECT_FLOAT_ARRAY;
        }
        final Float[] result = new Float[array.length];
        for (int i = 0; i < array.length; i++) {
            result[i] = array[i];
        }
        return result;
    }

    /**
    * <p>
    *
    * <p>       {@code null}                                {@code null}
    *
    * @param array array
    * @return null         空
     */
    public static Byte[] toObject(final byte[] array) {
        if (array == null) {
            return null;
        } else if (array.length == 0) {
            return SYMBOL_EMPTY_OBJECT_BYTE_ARRAY;
        }
        final Byte[] result = new Byte[array.length];
        for (int i = 0; i < array.length; i++) {
            result[i] = array[i];
        }
        return result;
    }

    /**
    * <p>
    *
    * <p>       {@code null}                                {@code null}
    *
    * @param value 值
    * @return null         空
     */
    public static String[] toUpperCase(String[] value) {
        if (value == null) {
            return null;
        }
        for (int i = 0; i < value.length; i++) {
            value[i] = value[i].toUpperCase();
        }
        return value;
    }

    /**
    * <p>
    *
    * <p>       {@code null}                                {@code null}
    *
    * @param value 值
    * @return null         空
     */
    public static String[] toLowerCase(String[] value) {
        if (value == null) {
            return null;
        }
        for (int i = 0; i < value.length; i++) {
            value[i] = value[i].toLowerCase();
        }
        return value;
    }


    /**
    * 排序字段
    *
    * @param values 值
    * @param sortedFields 排序字段
    * @param direction             /      
    * @return the 结果
     */
    /**
    * 排序条件.排序direction
    * @author CH
    * @since 4.0.0
     */
    public enum SortDirection {
        /** 升序 */
        ASC,
        /** 降序 */
        DESC
    }

    /**
    * 排序
    * @param values 值
    * @param sortedFields 排序字段
    * @param direction direction
     */
    public static String[] sort(String[] values,
                                String[] sortedFields,
                                SortDirection direction) {
        if (values == null) {
            return new String[0];
        }
        if (sortedFields == null || sortedFields.length == 0) {
            return values.clone();
        }

        // 1.                    Map<         ,       >                        
        Map<String, Integer> order = new HashMap<>(sortedFields.length);
        int idx = 0;
        for (String k : sortedFields) {
            //                                  
            order.putIfAbsent(k, idx++);
        }

        // 2.                                                             
        List<String> inDict = new ArrayList<>(values.length);
        List<String> outDict = new ArrayList<>(values.length);
        for (String v : values) {
            (order.containsKey(v) ? inDict : outDict).add(v);
        }

        // 3.                                                 /   
        Comparator<String> cmp = Comparator.comparingInt(order::get);
        if (direction == SortDirection.DESC) {
            cmp = cmp.reversed();
        }
        inDict.sort(cmp);

        // 4.                    +          
        inDict.addAll(outDict);
        return inDict.toArray(new String[0]);
    }

    /**
    * 排序字段                             偏移量element
    *
    * @param values        值
    * @param sortedFields  排序字段
    * @param offsetElement 偏移量element
    * @return offsetElement
     */
    public static String[] filterAfter(String[] values,
                                       String[] sortedFields,
                                       String offsetElement) {
        if (values == null || values.length == 0) {
            return new String[0];
        }
        if (sortedFields == null || sortedFields.length == 0) {
            //                               
            sortedFields = new String[0];
        }

        // 1.                   
        Map<String, Integer> order = new HashMap<>(sortedFields.length);
        int idx = 0;
        for (String k : sortedFields) {
            order.putIfAbsent(k, idx++);
        }

        // 2.                                                       
        List<String> inDict = new ArrayList<>(values.length);
        List<String> outDict = new ArrayList<>(values.length);
        for (String v : values) {
            (order.containsKey(v) ? inDict : outDict).add(v);
        }

        // 3.                            
        inDict.sort(Comparator.comparingInt(order::get));
        // 4.                    +          
        inDict.addAll(outDict);

 // 5.     偏移量element
        int pos = inDict.indexOf(offsetElement);
        if (pos == -1 || pos == inDict.size() - 1) {
            return new String[0];
        }

        // 6.                
        return inDict.subList(pos + 1, inDict.size())
                .toArray(new String[0]);
    }

    /**
    *
    *
    * @param firstValue 第一个值
    * @param extValues  ext值
    * @return the 结果
     */
    public static String join(String firstValue, String... extValues) {
        if (extValues == null || extValues.length == 0) {
            return firstValue;
        }

        StringBuilder sb = new StringBuilder(firstValue);
        for (String extValue : extValues) {
            sb.append(SYMBOL_COMMA).append(extValue);
        }
        return sb.substring(1);
    }

    /**
    * 用指定分隔符连接对象数组。
    *
    * <p>数组中的 null 元素会被替换为空字符串；byte[] 元素会被转换为默认字符集的字符串。</p>
    *
    * @param array     对象数组，为 空 时返回 空
    * @param separator 分隔符
    * @return 连接后的字符串
     */
    public static String join(Object[] array, String separator) {
        if (array == null) {
            return null;
        }
        StringJoiner stringJoiner = new StringJoiner(separator);
        for (Object o : array) {
            if (null == o) {
                stringJoiner.add(EMPTY_STRING);
                continue;
            }
            if (o instanceof byte[] bytes) {
                stringJoiner.add(new String(bytes));
                continue;
            }
            stringJoiner.add(o.toString());
        }
        return stringJoiner.toString();
    }
}
