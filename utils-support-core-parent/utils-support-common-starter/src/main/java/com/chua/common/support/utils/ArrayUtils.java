// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.utils;

import com.chua.common.support.collection.wrap.Wraps;
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
     * 安全获取数组指定下标的元素，越界或下标为负时返回默认值。
     *
     * <p>下标为负时同样返回默认值（不做负数倒序索引），避免调用方误以为
     * {@code -1} 表示末元素。</p>
     *
     * @param stringArray  源数组，允许为 null
     * @param index        索引，小于 0 或越界时返回默认值
     * @param defaultValue 默认值
     * @param <T>          元素类型
     * @return 指定下标的元素；越界或数组为空时返回 {@code defaultValue}
     */
    public static <T> T getIndex(T[] stringArray, int index, T defaultValue) {
        if (stringArray == null || index < 0 || index >= stringArray.length) {
            return defaultValue;
        }
        return stringArray[index];
    }

    /**
     * 获取 Index。
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
     * 判断是否包含指定元素。
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
     * 判断是否包含指定元素。
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
     * 忽略大小写判断是否包含。
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
     * 是否为 Equals。
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
     * 是否为 Equals。
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
     * 是否为 Equals。
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
     * 是否为 Equals。
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
     * 是否为 Empty。
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
     * @param args ,
     * @return the 结果
     * @since 4.5.18
     */
    public static boolean isAllNotEmpty(Object... args) {
        return !hasEmpty(args);
    }
     * 是否为 Empty。
     *
     *
     * @param array array
     * @return true
     */
    public static boolean isEmpty(@Nullable final int[] array) {
        return null == array || array.length == 0;
    }

    /**
     * 是否为 Empty。
     *
     *
     * @param array array
     * @return true
     */
    public static boolean isEmpty(@Nullable final float[] array) {
        return null == array || array.length == 0;
    }

    /**
     * 是否为 Empty。
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
     * 是否为 NotEmpty。
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
     * 是否为 NotEmpty。
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
     * 合并两个数组并按首次出现顺序去重，返回新数组。
     *
     * <p>去重规则基于 {@link Object#equals(Object)}，先遍历 {@code target} 再遍历
     * {@code source}，因此 {@code target} 中的元素总排在前面。两个数组均为空时返回
     * 空数组（不返回 null）。</p>
     *
     * @param target 基础数组，不允许为 null
     * @param source 待合并的数组，不允许为 null
     * @param <T>     元素类型
     * @return 合并去重后的新数组
     * @throws IllegalArgumentException 任一参数为 null 时抛出
     */
    public static <T> T[] mergeOfDistince(T[] target, T... source) {
        if (target == null || source == null) {
            throw new IllegalArgumentException("待合并的数组不能为空");
        }
        if (target.length == 0 && source.length == 0) {
            @SuppressWarnings("unchecked")
            T[] empty = (T[]) Array.newInstance(target.getClass().getComponentType(), 0);
            return empty;
        }

        List<T> merged = Wraps.list(target.length + source.length);
        // 先加入 target，保证其元素排在前面
        for (T t : target) {
            if (!merged.contains(t)) {
                merged.add(t);
            }
        }
        // 再补上 source 中尚未出现的元素
        for (T t : source) {
            if (!merged.contains(t)) {
                merged.add(t);
            }
        }

        @SuppressWarnings("unchecked")
        T[] result = (T[]) Array.newInstance(target.getClass().getComponentType(), merged.size());
        return merged.toArray(result);
    }

    /**
     * 合并数组。
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

        List<T> objects = Wraps.list(target.length + source.length);
        objects.addAll(Arrays.asList(target));
        objects.addAll(Arrays.asList(source));

        return objects.toArray(target);
    }








    /**
     * 添加 Element。
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

    /**
     * 插入元素。
     *
     *
     * @param target Target
     * @param source 源
     * @param offset 偏移量
     */
    public static <E> void insertElement(E[] target, int offset, Collection<E> source) {
        insertElement(target, offset,
                      source.toArray((E[]) Array.newInstance(CollectionUtils.findLast(source).getClass(), 0)));
    }

    /**
     * 插入元素。
     *
     *
     * @param target Target
     * @param source 源
     */
    public static <E> void insertElement(E[] target, E[] source) {
        insertElement(target, 0, source);
    }

    /**
     * 插入元素。
     *
     *
     * @param target Target
     * @param source 源
     */
    public static <E> void insertElement(E[] target, E source) {
        insertElement(target, 0, Collections.singletonList(source));
    }

    /**
     * 插入元素。
     *
     *
     * @param target Target
     * @param source 源
     * @param offset 偏移量
     */
    public static <E> void insertElement(E[] target, int offset, E source) {
        insertElement(target, offset, Collections.singletonList(source));
    }

    /**
     * 插入元素。
     *
     *
     * @param target Target
     * @param source 源
     * @param offset 偏移量
     */
    public static <E> void insertElement(E[] target, int offset, E[] source) {
        if (target.length == 0 && source.length == 0) {
            return;
        }

        System.arraycopy(source, 0, target, offset, source.length);
    }

    /**
     * 转换为Array。
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
     * 转换为Array。
     *
     *
     * @param iterable 可迭代
     * @param function function
     * @return the 结果
     * @param generator 生成器
     */
    public static <T, A> A[] toArray(T[] iterable, Function<T, A> function, IntFunction<A[]> generator) {
        if (null == iterable) {
        }
        List<? extends T> ts = CollectionUtils.newArrayList(iterable);
        return ts.stream().map(function).toArray(generator);
    }


     * @return the 结果
     * @param generator 生成器
     */
    public static <T, A> A[] toArray(Iterable<? extends T> iterable, Function<T, A> function,
            IntFunction<A[]> generator) {
        if (null == iterable) {
            return null;
        }
        List<? extends T> ts = CollectionUtils.newArrayList(iterable);
        return ts.stream().map(function).toArray(generator);
    }
     * 转换为ArrayString。
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
     * 转换为Array。
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
        return ts.toArray((T[]) Array.newInstance(ts.getFirst().getClass(), 0));
    }


    /**
     * 转换为字节数组。
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
     * 转换为字节数组。
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
            }
            return array;
        }

        if (value instanceof Collection) {
            return transToByteArray(Wraps.list((Collection) value));
        }
        return new byte[0];
    }


    /**
     * 转换为字节数组。
     *
     *
     * @param value 值
     * @return null
     */
    public static <T> byte[] transToByteArray(T[] value) {
        byte[] newInstance = new byte[value.length];
        if (newInstance.length == 0) {
            return newInstance;
        for (Object o : value) {
            newInstance[count++] = Converter.convertIfNecessary(o, Byte.class).byteValue();
        }
        return newInstance;
    }

    /**
     * trans转为字节数组
     *
     * @param value 值
     * @return trans转为字节数组的结果
     */
    public static byte[] transToByteArray(float[] value) {
        if (value == null) {
            return null;
        }
        byte[] result = new byte[value.length * 4];
        for (int i = 0; i < value.length; i++) {
            result[i * 4] = (byte) (bits >> 24);
            result[i * 4 + 1] = (byte) (bits >> 16);
            result[i * 4 + 2] = (byte) (bits >> 8);
            result[i * 4 + 3] = (byte) bits;
        }
        return result;
    }



    public static <T> T[] newArray(Class<?> componentType, int newSize) {
        return (T[]) Array.newInstance(componentType, newSize);
    }

    /**
     * 创建数组。
     *
     *
     * @param newSize 新大小
     * @return the 结果
     * @since 3.3.0
     */
    public static Object[] newArray(int newSize) {
        return new Object[newSize];


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
     * 转换为Array。
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
        return rs;
    }

    /**
     * 转换为List。
     *
     *
     * @param value 值
     * @return the 结果
     */
    public static Collection<?> toList(Object value) {
        List<Object> rs = Wraps.list();
        int length = Array.getLength(value);
        for (int i = 0; i < length; i++) {
            rs.add(Array.get(value, i));
        }

        return rs;
    }

    /**
     * 截取子数组。
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
     * 截取子数组。
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
     * 截取子数组。
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
        if (offset2 <= offset) {
            return null;
        }

        byte[] rs = new byte[offset2 - offset + 1];
        System.arraycopy(arr, offset, rs, 0, offset2 - offset + 1);
        return rs;
    }







    /**
     * 判断是否包含任一元素。
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
        //
        return false;
    }







    /**
     * 转换为Object。
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
        for (int i = 0; i < array.length; i++) {
            result[i] = array[i];
        }
        return result;
    }

    /**
     * 转换为UpperCase。
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
     * 排序字段
     *
     * @param values 值
     * @param sortedFields 排序字段
     * @param direction             /
     * @return the 结果
     *
     * 排序条件.排序direction
     * @author CH
     * @since 4.0.0
     */
    public enum SortDirection {
        /**
         * 升序
         */
        ASC,
        /**
         * 降序
         */
        DESC
    }

                                SortDirection direction) {
        if (values == null) {
            return new String[0];
        }
        if (sortedFields == null || sortedFields.length == 0) {
            return values.clone();
        }

        // 1.                    Map<         ,       >
        Map<String, Integer> order = Wraps.map(sortedFields.length);
        int idx = 0;
        for (String k : sortedFields) {
            //
            order.putIfAbsent(k, idx++);
        }

        // 2.
        List<String> inDict = Wraps.list(values.length);
        List<String> outDict = Wraps.list(values.length);
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

                                       String offsetElement) {
        if (values == null || values.length == 0) {
            return new String[0];
        }
        if (sortedFields == null || sortedFields.length == 0) {
            //
            sortedFields = new String[0];
        }

        // 1.
        Map<String, Integer> order = Wraps.map(sortedFields.length);
        int idx = 0;
        for (String k : sortedFields) {
            order.putIfAbsent(k, idx++);
        }

        // 2.
        List<String> inDict = Wraps.list(values.length);
        List<String> outDict = Wraps.list(values.length);
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
        return stringJoiner.toString();
    }
}