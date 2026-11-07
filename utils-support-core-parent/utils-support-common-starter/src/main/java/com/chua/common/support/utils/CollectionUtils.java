package com.chua.common.support.utils;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.constant.Projects;
import com.chua.common.support.utils.BeanUtils;
import com.google.common.base.Joiner;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_COMMA;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_EMPTY;
import static com.chua.common.support.constant.NumberConstant.DEFAULT_SIZE;


/**
 * 集合工具类，提供 列表、设置、映射 等集合的常用操作方法。
 *
 * <p>包含空值检查、遍历、聚合、分组、排序、打印格式化等功能，
 * 部分方法参考 Google Guava 实现。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public class CollectionUtils {

    /**
     * 集合工具。
     */
    private CollectionUtils() {
    }






     * @param limit  每组最大元素数，必须大于 0
     * @param <T>    元素类型
     * @return 分组后的列表集合
     * @throws IllegalArgumentException {@code limit} 小于等于 0 时抛出
     */
    public static <T> List<List<T>> averageAssign(List<T> source, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("每组最大元素数 limit 必须大于 0，实际: " + limit);
        }
        if (null == source || source.isEmpty()) {
            return Collections.emptyList();
        }
        int size = source.size();
        // 最少需要的组数：向上取整
        int listCount = (size - 1) / limit + 1;
        // 前 remainder 组多分配 1 个元素
        int remainder = size % listCount;
        int number = size / listCount;

        List<List<T>> result = Wraps.list(listCount);
        int offset = 0;
        for (int i = 0; i < listCount; i++) {
            int from = i * number + offset;
            int to;
            if (remainder > 0) {
                to = (i + 1) * number + offset + 1;
                remainder--;
            } else {
                to = (i + 1) * number + offset;
            }
            result.add(source.subList(from, to));
        }
        return result;
    }

    /**
     * 判断 可迭代 是否为空（空 或无元素）。
     *
     * @param it  待检查的 可迭代，可能为 空
     * @param <E> 元素类型
     * @return 如果为 空 或无元素返回 true
     */
    public static <E> boolean isEmpty(Iterable<? extends E> it) {
        return null == it || !it.iterator().hasNext();
    }


    /**
     * 判断数组是否为空（空 或无元素）。
     *
     * @param arrays 待检查的数组，可能为 空
     * @param <E>    元素类型
     * @return 如果为 空 或无元素返回 true
     */
    public static <E> boolean isEmpty(E[] arrays) {
        return ArrayUtils.isEmpty(arrays);
    }
    /**
     * 判断 映射 是否为空（空 或无键值对）。
     *
     * @param kvMap 待检查的 映射，可能为 空
     * @param <K>   键类型
     * @param <V>   值类型
     * @return 如果为 空 或无键值对返回 true
     */
    public static <K, V> boolean isEmpty(Map<K, V> kvMap) {
        return MapUtils.isEmpty(kvMap);
    }

    /**
     * 判断集合是否非空（不为 空 且有元素）。
     *
     * @param collection 待检查的集合，可能为 空
     * @param <E>        元素类型
     * @return 如果非空返回 true
     */
    public static <E> boolean isNotEmpty(Collection<E> collection) {
        return !isEmpty(collection);
    }
    /**
     * 判断 映射 是否非空（不为 空 且有键值对）。
     *
     * @param map 待检查的 映射，可能为 空
     * @param <E> 键类型
     * @param <V> 值类型
     * @return 如果非空返回 true
     */
    public static <E, V> boolean isNotEmpty(Map<E, V> map) {
        return MapUtils.isNotEmpty(map);
    }

    /**
     * 获取集合的大小，空 安全。
     *
     * @param collection 待获取大小的集合，可能为 空
     * @return 集合的大小，null 返回 0
     */
    public static int size(Collection<?> collection) {
        return null == collection ? 0 : collection.size();
    }

    /**
     * 获取 映射 的大小，空 安全。
     *
     * @param collection 待获取大小的 映射，可能为 空
     * @return Map 的大小，空 返回 0
     */
    public static int size(Map<?, ?> collection) {
        return null == collection ? 0 : collection.size();
    }


    /**
     * 判断对象是否为 列表 类型。
     *
     * @param source 待判断的对象
     * @return 如果是 列表 返回 true
     */
    public static boolean isList(Object source) {
        return source instanceof List;
    }

    /**
     * 获取集合中的第一个元素。
     *
     * <p>优先以 List 方式直接索引，否则使用迭代器获取第一个元素。</p>
     *
     * @param source 源集合，可能为 空
     * @param <T>    元素类型
     * @return 第一个元素，集合为空或 空 返回 空
     */
    public static <T> T findFirst(final Collection<T> source) {
        if (null == source || source.isEmpty()) {
            return null;
        }
        if (source instanceof List) {
            return ((List<T>) source).getFirst();
        }
        Iterator<T> iterator = source.iterator();
        return iterator.next();
    }
    /**
     * 获取 可迭代 中的第一个元素。
     *
     * @param source 源 可迭代，可能为 空
     * @param <T>    元素类型
     * @return 第一个元素，null 返回 空
     */
    public static <T> T findFirst(final Iterable<T> source) {
        if (null == source) {
            return null;
        }
        Iterator<T> iterator = source.iterator();
        return iterator.next();
    }

    /**
     * 获取 映射 中的第一个值。
     *
     * @param source 源 映射，可能为 空
     * @param <T>    值类型
     * @return 第一个值，Map 为空或 空 返回 空
     */
    public static <T> T findFirst(final Map<?, T> source) {
        if (null == source || source.isEmpty()) {
            return null;
        }
        Iterator<T> iterator = source.values().iterator();
        return iterator.next();
    }

    /**
     * 获取集合中的第一个元素，如果不存在则返回默认值。
     *
     * @param source       源集合，可能为 空
     * @param defaultValue 默认值
     * @param <T>          元素类型
     * @return 第一个元素，不存在则返回默认值
     */
    public static <T> T findFirst(final Collection<T> source, T defaultValue) {
        return Optional.ofNullable(findFirst(source)).orElse(defaultValue);
    }

    /**
     * 获取集合中的最后一个元素。
     *
     * <p>优先以 List 方式直接索引，否则使用 Stream 跳过到末尾。</p>
     *
     * @param source 源集合，可能为 空
     * @param <T>    元素类型
     * @return 最后一个元素，集合为空或 空 返回 空
     */
    public static <T> T findLast(final Collection<T> source) {
        if (null == source || source.isEmpty()) {
            return null;
        }
        if (source instanceof List) {
            return ((List<T>) source).get(source.size() - 1);
        }
        return source.stream().skip(source.size() - 1).findFirst().get();
    }

    /**
     * 获取集合中的最后一个元素，如果不存在则返回默认值。
     *
     * @param source       源集合，可能为 空
     * @param defaultValue 默认值
     * @param <T>          元素类型
     * @return 最后一个元素，不存在则返回默认值
     */
    public static <T> T findLast(final Collection<T> source, T defaultValue) {
        return Optional.ofNullable(findLast(source)).orElse(defaultValue);
    }

    /**
     * 获取集合中指定索引的元素（支持负数索引，从末尾计数）。
     *
     * @param source 源集合
     * @param index  索引，负数从末尾开始计数
     * @param <T>    元素类型
     * @return 指定索引的元素，不存在返回 空
     */
    public static <T> T get(final Collection<T> source, final int index) {
        return find(source, index, null);
    }

    /**
     * 获取集合中指定索引的元素（同 {@link #get(Collection, int)}）。
     *
     * @param source 源集合
     * @param index  索引，负数从末尾开始计数
     * @param <T>    元素类型
     * @return 指定索引的元素，不存在返回 空
     * @see #get(Collection, int)
     */
    public static <T> T find(final Collection<T> source, final int index) {
        return find(source, index, null);
    }

    /**
     * 获取集合中指定索引的元素，不存在时返回默认值（支持负数索引）。
     *
     * <p>负数索引从末尾开始计数（如 -1 表示最后一个元素）。
     * 内部先将集合转为 array列表 再反转，然后按绝对值索引取值。</p>
     *
     * @param source       源集合，可能为 空
     * @param index        索引，负数从末尾计数
     * @param defaultValue 默认值
     * @param <T>          元素类型
     * @return 指定索引的元素，不存在返回 默认值
     */
    public static <T> T find(final Collection<T> source, final int index, final T defaultValue) {
        if (null == source) {
            return defaultValue;
        }

        if (index < 0) {
            List<T> ts = Wraps.list(source);
            Collections.reverse(ts);
            return find(ts, Math.abs(index), defaultValue);
        }

        int length = source.size();
        if (index >= length) {
            return defaultValue;
        }
        if (source instanceof List) {
            return Optional.ofNullable(((List<T>) source).get(index)).orElse(defaultValue);
        }
        return source.stream().skip(index).findFirst().orElse(defaultValue);
    }



 // ------------------------------------------------------------------------------------------------- 排序


    /**
     * 将 列表 转为不可修改视图，空 时返回 空。
     *
     * @param list 源列表
     * @param <T>  元素类型
     * @return 不可修改的列表，null 时返回 空
     */
    public static <T> List<T> unmodifiable(List<T> list) {
        if (null == list) {
            return null;
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 对 列表 分页，逐页调用消费者回调。
     *
     * @param list               源列表
     * @param pageSize           每页大小
     * @param pageListConsumer   每页数据的消费回调
     * @param <T>                元素类型
     */
    public static <T> void page(List<T> list, int pageSize, Consumer<List<T>> pageListConsumer) {
        if (isEmpty(list) || pageSize <= 0) {
            return;
        }

        final int total = list.size();
        final int totalPage = PageUtils.totalPage(total, pageSize);
        for (int pageNo = PageUtils.getFirstPageNo(); pageNo < totalPage + PageUtils.getFirstPageNo(); pageNo++) {
            //
            final int[] startEnd = PageUtils.transToStartEnd(pageNo, pageSize);
            if (startEnd[1] > total) {
                startEnd[1] = total;
            }

            //
            pageListConsumer.accept(sub(list, startEnd[0], startEnd[1]));
        }
    }




    /**
     * 截取 列表 子列表，支持指定步长（启动/结束 均支持负数索引）。
     *
     * @param list  源列表
     * @param start 起始索引（含），负数从末尾计数
     * @param end   结束索引（不含），负数从末尾计数
     * @param step  步长，必须大于等于 1
     * @param <T>   元素类型
     * @return 截取后的不可变子列表
     */
    public static <T> List<T> sub(List<T> list, int start, int end, int step) {
        if (list == null) {
            return null;
        }

        if (list.isEmpty()) {
            return Collections.emptyList();
        }

        final int size = list.size();
        if (start < 0) {
            start += size;
        }
        if (end < 0) {
            end += size;
        }
        if (start == size) {
            return Collections.emptyList();
        }
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        if (end > size) {
            if (start >= size) {
                return Collections.emptyList();
            }
            end = size;
        }

        final List<T> result = Wraps.list();
        for (int i = start; i < end; i += step) {
            result.add(list.get(i));
        }
        return CollectionUtils.unmodifiable(result);
    }



     * @param <E>      元素类型
     * @return 添加后的列表，输入为 空 返回空列表
     */
    public static <E> List<E> addAll(List<E> elements, E... element) {
        if (null == elements || element.length == 0) {
            return Collections.emptyList();
        }

        for (E e : element) {
            if (null == e) {
                continue;
            }
            elements.add(e);
        }

        return elements;
    }

    /**
     * 将集合中的所有元素添加到目标列表。
     *
     * @param elements 目标列表
     * @param element  源集合
     * @param <E>      元素类型
     * @return 添加后的列表，输入为 空 返回空列表
     */
    public static <E> List<E> addAll(List<E> elements, List<E> element) {
        if (null == elements || null == element) {
            return Collections.emptyList();
        }

        elements.addAll(element);
        return elements;
    }

    /**
     * 将数组转为 array列表。
     *
     * @param elements 源数组
     * @param <T>      元素类型
     * @return 包含数组元素的 列表，数组为 空 返回空列表
     */
    public static <T> List<T> newArrayList(T... elements) {
        return null == elements ? Wraps.list() : Wraps.list(Arrays.asList(elements));
    }

    /**
     * 将 可迭代 转为不可变 列表。
     *
     * @param elements 源 可迭代
     * @param <E>      元素类型
     * @return 不可变的 列表，可迭代 为 空 返回空列表
     */
    public static <E> List<E> newArrayList(Iterable<? extends E> elements) {
        if (null == elements) {
            return Collections.emptyList();
        }

        if (elements instanceof Collection) {
            return Collections.unmodifiableList(Wraps.list((Collection) elements));
        }

        return newArrayList(elements.iterator());
    }

    /**
     * 将 迭代器 转为不可变 列表。
     *
     * <p>先提取第一个元素，再依次提取剩余元素，最终返回不可变列表。</p>
     *
     * @param elements 源 迭代器
     * @param <E>      元素类型
     * @return 不可变的 列表
     */
    public static <E> List<E> newArrayList(Iterator<? extends E> elements) {
        if (!elements.hasNext()) {
            return Collections.emptyList();
        }
        E first = elements.next();
        if (!elements.hasNext()) {
            return Collections.unmodifiableList(addAll(Wraps.list(), first));
        }

        List<E> rs = Wraps.list();
        rs.add(first);

        return Collections.unmodifiableList(rs);
    }









    /**
     * 创建指定类型的空 列表（链接列表 或 array列表）。
     *
     * <p>{@code isLinked} 参数自 4.0.0.42 起保留仅为兼容旧签名：
     * 分装列表统一由 {@code Wraps} 工厂按 SPI 后端创建，两种取值返回同构列表。</p>
     *
     * @param isLinked 是否使用 链接列表（兼容参数，现无差异）
     * @param <T>      元素类型
     * @return 空 列表
     */
    public static <T> List<T> list(boolean isLinked) {
        return Wraps.list();
    }

    /**
     * 判断字符串是否存在于目标集合中（精确匹配）。
     *
     * @param source 待查找的字符串
     * @param target 目标集合
     * @return 存在返回 true
     */
    public static boolean contains(String source, Collection<String> target) {
        if (StringUtils.isEmpty(source)) {
            return false;
        }

        for (String s : target) {
            if (s.equals(source)) {
                return true;
            }
        }

        return false;
    }
    /**
     * 判断字符串是否存在于目标集合中（忽略大小写）。
     *
     * @param target 目标集合
     * @param source 待查找的字符串
     * @return 存在返回 true
     */
    public static boolean containsIgnoreCase(Collection<String> target, String source) {
        if (StringUtils.isEmpty(source)) {
            return false;
        }

        for (String s : target) {
            if (s.equalsIgnoreCase(source)) {
            }
        }

        return false;
    }
    /**
     * 判断 列表 中是否包含目标集合中的任意元素。
     *
     * @param source 源列表
     * @param target 目标集合
     * @return 存在交集返回 true
     */
    public static boolean contains(List<String> source, Collection<String> target) {
        for (String s : target) {
            if (source.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 设置 中是否包含目标集合中的任意元素。
     *
     * @param source 源 设置
     * @param target 目标集合
     * @return 存在交集返回 true
     */
    public static boolean contains(Set<String> source, Collection<String> target) {
        for (String s : target) {
            if (source.contains(s)) {
                return true;
            }
        }
        return false;
    }
    /**
     * 判断 设置 中是否包含逗号分隔字符串中的任意元素。
     *
     * @param source  源 设置
     * @param target  逗号分隔的待查找字符串
     * @return 存在交集返回 true
     */
    public static boolean contains(Set<String> source, String target) {
        for (String s : target.split(SYMBOL_COMMA)) {
            if (source.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查 迭代器 中是否包含指定元素。
     *
     * @param iterator 待检查的 迭代器
     * @param element  要查找的元素
     * @return 如果找到返回 true，否则返回 false
     */
    public static boolean contains(Iterator<?> iterator, Object element) {
        if (iterator != null) {
            while (iterator.hasNext()) {
                Object candidate = iterator.next();
                if (ObjectUtils.nullSafeEquals(candidate, element)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查 Enumeration 中是否包含指定元素。
     *
     * @param enumeration 待检查的 Enumeration
     * @param element     要查找的元素
     * @return 如果找到返回 true，否则返回 false
     */
    public static boolean contains(Enumeration<?> enumeration, Object element) {
        if (enumeration != null) {
            while (enumeration.hasMoreElements()) {
                Object candidate = enumeration.nextElement();
                if (ObjectUtils.nullSafeEquals(candidate, element)) {
                    return true;
                }
            }
        }
        return false;
    }
    /**
     * 将 列表 转为数组。
     *
     * @param args 源列表
     * @param <T>  元素类型
     * @return 转换后的数组
     */
    public static <T>T[] toArray(List<T> args) {
        return ArrayUtils.toArray(args);
    }
    /**
     * 将 列表 和列名列表转为二维数组。
     *
     * @param args  源列表
     * @param names 列名列表
     * @return 二维数组
     */
    public static Object[][] toArray(List<Object> args, List<String> names) {
        return toArray(args.toArray(new Object[0]), names);
    }
    /**
     * 将对象数组的每个元素转为与列名对应的行记录（二维数组）。
     *
     * <p>每个元素可能是 Map、Collection 或普通 Java Bean，会按列名提取对应值。</p>
     *
     * @param args  对象数组
     * @param names 列名列表
     * @return 二维数组
     */
    public static Object[][] toArray(Object[] args, List<String> names) {
        List<Object[]> rs = Wraps.list();
        for (Object arg : args) {
            if (arg instanceof  Map) {
                rs.add(toArray((Map)arg, names));
                continue;
            }

            if (arg instanceof Collection) {
                rs.add(toArray((Collection)arg, names));
            }

            rs.add(toArray(BeanUtils.objectToMap(arg), names));

        }


        return rs.toArray(new Object[0][]);
    }

     *
     *
     * @param arg
     * @param names
     * @return
     */
    private static Object[] toArray(Collection arg, List<String> names) {
        Object[] rs = new Object[names.size()];
        if (CollectionUtils.isEmpty(arg)) {
            return rs;
        }
        for (int i = 0; i < names.size(); i++) {
            if (arg.size() < i) {
                rs[i] = CollectionUtils.find(arg, i);
            }
        }

        return rs;
    }

    /**
     * 转换为Array。
     *
     *
     * @param arg
     * @param names
     * @return
     */
    private static Object[] toArray(Map arg, List<String> names) {
        Object[] rs = new Object[names.size()];
        if (MapUtils.isEmpty(arg)) {
            return rs;
        }

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i).replace("`", "");
            rs[i] = MapUtils.getConfig(arg, name);
        }

        return rs;
    }




    /**
     * 将 设置 包装为非 空 版本（空 转为空 设置）。
     *
     * @param elements 源 设置，可能为 空
     * @param <T>      元素类型
     * @return 非 空 的 设置
     */
    public static <T>Set<T> wrapper(Set<T> elements) {
        return Optional.ofNullable(elements).orElse(Collections.emptySet());
    }
    /**
     * 将 映射 包装为非 空 版本（空 转为空 映射）。
     *
     * @param elements 源 映射，可能为 空
     * @param <K>      键 类型
     * @param <V>      值 类型
     * @return 非 空 的 映射
     */
    public static <K, V>Map<K, V> wrapper(Map<K, V> elements) {
        return Optional.ofNullable(elements).orElse(Collections.emptyMap());
    }



     * <p>数组长度必须为偶数，否则抛出 {@link IllegalArgumentException}。</p>
     *
     * @param pairs 键值对字符串数组
     * @return 转换后的 映射
     */
    public static Map<String, String> toStringMap(String... pairs) {
        Map<String, String> parameters = Wraps.map(pairs.length);
 // pairs                                          映射
        if (ArrayUtils.isEmpty(pairs)) {
            return parameters;
        }

        //       pairs
        if (pairs.length > 0) {
            if (pairs.length % 2 != 0) {
                throw new IllegalArgumentException("pairs must be even.");
            }
 // pairs                                                         参数 映射
            for (int i = 0; i < pairs.length; i = i + 2) {
                parameters.put(pairs[i], pairs[i + 1]);
            }
        }
        return parameters;
    }

    /**
     * 获取 映射 的所有 键 并返回不可变列表，空 时返回空列表。
     *
     * @param temp 源 映射
     * @param <K>  键 类型
     * @param <V>  值 类型
     * @return 不可变的 键 列表
     */
    public static <K, V> Collection<? extends K> keySet(Map<K, V> temp) {
        //
        if (isEmpty(temp)) {
            return Collections.emptyList();
        }

        //
        List<K> keys = Wraps.list(temp.size());
        //
        for (Map.Entry<K, V> entry : temp.entrySet()) {
            keys.add(entry.getKey());
        }

        //
        return Collections.unmodifiableList(keys);
    }

    /**
     * 获取列表中第一个非 空 且非空的元素。
     *
     * @param s 源列表
     * @param <T> 元素类型
     * @return 第一个有效元素，不存在则返回 空
     */
    public static <T> T firstValidate(List<T> s) {
        //
        if (isEmpty(s)) {
            return null;
        }

        //
        for (T t : s) {
            if (ObjectUtils.isNotEmpty(t)) {
            }
        }

 // 空
        return null;
    }









     * @throws IllegalArgumentException 源列表为 null 时抛出
     */
    public static <T>List<T> limit(List<T> source, int offset, int limit) {
        if (source == null) {
            throw new IllegalArgumentException("源列表不能为空");
        }
        int size = source.size();
        if (offset < 0) {
            offset = 0;
        }
        if (offset >= size || limit <= 0) {
            return Collections.emptyList();
        }
        // 用减法而非 offset + limit，避免 limit 极大时整数溢出
        int end = (int) Math.min((long) offset + limit, size);
        return source.subList(offset, end);
    }



    /**
     * 以表格格式打印 列表，默认最多显示 5 行。
     *
     * @param title    标题
     * @param list     源列表
     * @param function 元素到字符串的转换函数
     * @param <T>      元素类型
     */
    public static <T> void printTableFormat(String title, List<T> list, Function<T, String> function) {
        printTableFormat(title, list, 5, function);
    }
}
