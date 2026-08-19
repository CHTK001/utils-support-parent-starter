package com.chua.common.support.utils;

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
 *
 *
 *
 * <pre>
 * //
 * List<Order> orders = Arrays.asList(
 *     new Order("ORDER-20240101-00001", "                  ", 2, 110.0, "2025-10-01"),
 *     new Order("ORDER-20240101-00002", "                  ", 3, 120.0, "2025-10-01"),
 *     ...
 * );
 *
 * //       1         CollectionUtils
 * CollectionUtils.printTableFormat(
 *     "            :",
 *     orders,
 *     5,  //             5
 *     order -> String.format(
 *         "         : %s,       : %s,       : %d,       :   %.1f,       : %s",
 *         order.getOrderNo(), order.getProduct(), order.getQty(),
 *         order.getPrice(), order.getDate()
 *     )
 * );
 *
 * //       2         ListOption
 * ListOption<Order> listOption = new ListOption<>(orders);
 * listOption.printTableFormat(
 *     "            :",
 *     5,  //             5
 *     order -> String.format(
 *         "         : %s,       : %s,       : %d,       :   %.1f,       : %s",
 *         order.getOrderNo(), order.getProduct(), order.getQty(),
 *         order.getPrice(), order.getDate()
 *     )
 * );
 *
 * //       3               5
 * CollectionUtils.printTableFormat(
 *     "            :",
 *     orders,
 *     order -> String.format("         : %s,       : %s", order.getOrderNo(), order.getProduct())
 * );
 * </pre>
 *
 *
 * <pre>
 * [2025-11-01 16:16:38] [INFO ] [] [main] [com.chua.example:205] -             :
 * [2025-11-01 16:16:38] [INFO ] [] [main] [com.chua.example:209] -                     : ORDER-20240101-00001,       :                   ,       : 2,       :   110.0,       : 2025-10-01
 * [2025-11-01 16:16:38] [INFO ] [] [main] [com.chua.example:209] -                     : ORDER-20240101-00002,       :                   ,       : 3,       :   120.0,       : 2025-10-01
 * [2025-11-01 16:16:38] [INFO ] [] [main] [com.chua.example:209] -                     : ORDER-20240101-00003,       :             ,       : 4,       :   130.0,       : 2025-10-01
 * [2025-11-01 16:16:38] [INFO ] [] [main] [com.chua.example:209] -                     : ORDER-20240101-00004,       : 4K         ,       : 5,       :   140.0,       : 2025-10-01
 * [2025-11-01 16:16:38] [INFO ] [] [main] [com.chua.example:209] -                     : ORDER-20240101-00005,       :                   ,       : 1,       :   150.0,       : 2025-10-01
 * [2025-11-01 16:16:38] [INFO ] [] [main] [com.chua.example:214] -            ...        5
 * </pre>
 *
 * @author CH
 */
@Slf4j
public class CollectionUtils {


    /**
     * 遍历集合中的每个元素并执行回调。
     *
     * <p>安全遍历：如果集合或回调为 {@code null}，则直接返回而不执行任何操作。</p>
     *
     * @param collection 待遍历的集合，可能为 null
     * @param consumer   每个元素执行的回调，可能为 null
     * @param <T>        集合元素类型
     */
    public static <T> void forEach(@Nullable Collection<T> collection, @Nullable Consumer<T> consumer) {
        if(isEmpty(collection) || ObjectUtils.isNull(consumer)) {
            return;
        }
        collection.forEach(consumer);
    }

    /**
     *                         {@code null}                                                                  <br>
     *                {@link Collections#emptySet()}
     *
     * @param <T>
     * @param set                            null
     * @return                   null
     * @since 4.6.3
     */
    @Nonnull
    public static <T> Set<T> emptyIfNull(@Nullable Set<T> set) {
        return (null == set) ? Collections.emptySet() : set;
    }

    /**
     *                         {@code null}                                                                  <br>
     *                {@link Collections#emptyList()}
     *
     * @param <T>
     * @param list                            null
     * @return                   null
     * @since 4.6.3
     */
    @Nonnull
    public static <T> List<T> emptyIfNull(@Nullable List<T> list) {
        return (null == list) ? Collections.emptyList() : list;
    }

    /**
     *                                                                               -1
     *
     * @param <T>
     * @param collection
     * @param indexes
     * @return
     * @since 4.0.6
     */
@SuppressWarnings({"unchecked", "all"})
    public static <T> List<T> getAny(Collection<T> collection, int... indexes) {
        final int size = collection.size();
        final ArrayList<T> result = new ArrayList<>();
        if (collection instanceof List<T> list) {
            for (int index : indexes) {
                if (index < 0) {
                    index += size;
                }
                result.add(list.get(index));
            }
        } else {
            final Object[] array = collection.toArray();
            for (int index : indexes) {
                if (index < 0) {
                    index += size;
                }
                result.add((T) array[index]);
            }
        }
        return result;
    }

    /**
     *                                                          <br>
     *
     *
     * @param <T>
     * @param collection
     * @param matcher
     * @return
     * @since 5.2.5
     */

    /**
     *
     *
     * @param source
     * @param <T>
     * @return
     */
    public static <T> T getRandom(final Collection<T> source) {
        if (isEmpty(source)) {
            return null;
        }
        SecureRandom random = new SecureRandom();
        int i = random.nextInt(source.size());
        return CollectionUtils.find(source, i);
    }

    /**
     *          List         n   list,
     *
     * @param source
     * @param limit
     * @return
     */
    public static <T> List<List<T>> averageAssign(List<T> source, int limit) {
        if (null == source || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<T>> result = new ArrayList<>();
        int listCount = (source.size() - 1) / limit + 1;
        // (                  )
        int remainder = source.size() % listCount;
        //
        int number = source.size() / listCount;
        //
        int offset = 0;
        for (int i = 0; i < listCount; i++) {
            List<T> value;
            if (remainder > 0) {
                value = source.subList(i * number + offset, (i + 1) * number + offset + 1);
                remainder--;
                offset++;
            } else {
                value = source.subList(i * number + offset, (i + 1) * number + offset);
            }
            result.add(value);
        }
        return result;
    }

    /**
     * 判断 Iterable 是否为空（null 或无元素）。
     *
     * @param it  待检查的 Iterable，可能为 null
     * @param <E> 元素类型
     * @return 如果为 null 或无元素返回 true
     */
    public static <E> boolean isEmpty(Iterable<? extends E> it) {
        return null == it || !it.iterator().hasNext();
    }

    /**
     * 如果集合为空则返回空列表，否则返回原集合。
     *
     * @param collection 待检查的集合，可能为 null
     * @param <E>        元素类型
     * @return 非空时返回原集合，否则返回空列表
     */
    public static <E> List<E> ifEmpty(List<E> collection) {
        return isEmpty(collection) ? Collections.emptyList() : collection;
    }

    /**
     * 判断集合是否为空（null 或无元素）。
     *
     * @param collection 待检查的集合，可能为 null
     * @param <E>        元素类型
     * @return 如果为 null 或无元素返回 true
     */
    public static <E> boolean isEmpty(Collection<E> collection) {
        return null == collection || collection.isEmpty();
    }
    /**
     * 判断数组是否为空（null 或无元素）。
     *
     * @param arrays 待检查的数组，可能为 null
     * @param <E>    元素类型
     * @return 如果为 null 或无元素返回 true
     */
    public static <E> boolean isEmpty(E[] arrays) {
        return ArrayUtils.isEmpty(arrays);
    }
    /**
     * 判断 Map 是否为空（null 或无键值对）。
     *
     * @param kvMap 待检查的 Map，可能为 null
     * @param <K>   键类型
     * @param <V>   值类型
     * @return 如果为 null 或无键值对返回 true
     */
    public static <K, V> boolean isEmpty(Map<K, V> kvMap) {
        return MapUtils.isEmpty(kvMap);
    }

    /**
     * 判断集合是否非空（不为 null 且有元素）。
     *
     * @param collection 待检查的集合，可能为 null
     * @param <E>        元素类型
     * @return 如果非空返回 true
     */
    public static <E> boolean isNotEmpty(Collection<E> collection) {
        return !isEmpty(collection);
    }
    /**
     * 判断 Map 是否非空（不为 null 且有键值对）。
     *
     * @param map 待检查的 Map，可能为 null
     * @param <E> 键类型
     * @param <V> 值类型
     * @return 如果非空返回 true
     */
    public static <E, V> boolean isNotEmpty(Map<E, V> map) {
        return MapUtils.isNotEmpty(map);
    }

    /**
     * 获取集合的大小，null 安全。
     *
     * @param collection 待获取大小的集合，可能为 null
     * @return 集合的大小，null 返回 0
     */
    public static int size(Collection<?> collection) {
        return null == collection ? 0 : collection.size();
    }

    /**
     * 获取 Map 的大小，null 安全。
     *
     * @param collection 待获取大小的 Map，可能为 null
     * @return Map 的大小，null 返回 0
     */
    public static int size(Map<?, ?> collection) {
        return null == collection ? 0 : collection.size();
    }

    /**
     * 将对象安全地转为 List；若不是 List 则返回空列表。
     *
     * @param value 待转换的对象
     * @return 如果对象是 List 则强转返回，否则返回空列表
     */
    public static List<Object> ifList(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    /**
     * 判断对象是否为 List 类型。
     *
     * @param source 待判断的对象
     * @return 如果是 List 返回 true
     */
    public static boolean isList(Object source) {
        return source instanceof List;
    }

    /**
     * 获取集合中的第一个元素。
     *
     * <p>优先以 List 方式直接索引，否则使用迭代器获取第一个元素。</p>
     *
     * @param source 源集合，可能为 null
     * @param <T>    元素类型
     * @return 第一个元素，集合为空或 null 返回 null
     */
    public static <T> T findFirst(final Collection<T> source) {
        if (null == source || source.isEmpty()) {
            return null;
        }
        if (source instanceof List) {
            return ((List<T>) source).get(0);
        }
        Iterator<T> iterator = source.iterator();
        return iterator.next();
    }
    /**
     * 获取 Iterable 中的第一个元素。
     *
     * @param source 源 Iterable，可能为 null
     * @param <T>    元素类型
     * @return 第一个元素，null 返回 null
     */
    public static <T> T findFirst(final Iterable<T> source) {
        if(null == source) {
            return null;
        }
        Iterator<T> iterator = source.iterator();
        return iterator.next();
    }

    /**
     * 获取 Map 中的第一个值。
     *
     * @param source 源 Map，可能为 null
     * @param <T>    值类型
     * @return 第一个值，Map 为空或 null 返回 null
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
     * @param source       源集合，可能为 null
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
     * @param source 源集合，可能为 null
     * @param <T>    元素类型
     * @return 最后一个元素，集合为空或 null 返回 null
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
     * @param source       源集合，可能为 null
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
     * @return 指定索引的元素，不存在返回 null
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
     * @return 指定索引的元素，不存在返回 null
     * @see #get(Collection, int)
     */
    public static <T> T find(final Collection<T> source, final int index) {
        return find(source, index, null);
    }

    /**
     * 获取集合中指定索引的元素，不存在时返回默认值（支持负数索引）。
     *
     * <p>负数索引从末尾开始计数（如 -1 表示最后一个元素）。
     * 内部先将集合转为 ArrayList 再反转，然后按绝对值索引取值。</p>
     *
     * @param source       源集合，可能为 null
     * @param index        索引，负数从末尾计数
     * @param defaultValue 默认值
     * @param <T>          元素类型
     * @return 指定索引的元素，不存在返回 defaultValue
     */
    public static <T> T find(final Collection<T> source, final int index, final T defaultValue) {
        if (null == source) {
            return defaultValue;
        }

        if (index < 0) {
            ArrayList<T> ts = new ArrayList<>(source);
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

    /**
     * 判断集合是否有元素（非 null 且 size > 0）。
     *
     * @param collection 待检查的集合
     * @return 如果有元素返回 true
     */
    public static boolean hasElement(Collection<?> collection) {
        return size(collection) > 0;
    }


    // ------------------------------------------------------------------------------------------------- sort

    /**
     *          List
     *
     * @param <T>
     * @param pageNo                                       {@link PageUtils#getFirstPageNo()}         0
     * @param pageSize
     * @param list
     * @return
     * @since 4.1.20
     */
    public static <T> List<T> page(int pageNo, int pageSize, List<T> list) {
        if (isEmpty(list)) {
            return new ArrayList<>(0);
        }

        pageNo = pageNo - 1;

        int resultSize = list.size();
        //
        if (resultSize <= pageSize) {
            if (pageNo < (PageUtils.getFirstPageNo() + 1)) {
                return unmodifiable(list);
            } else {
                //
                return new ArrayList<>(0);
            }
        }
        //                                      long
        if (((long) (pageNo - PageUtils.getFirstPageNo()) * pageSize) > resultSize) {
            //
            return new ArrayList<>(0);
        }

        final int[] startEnd = PageUtils.transToStartEnd(pageNo, pageSize);
        if (startEnd[1] > resultSize) {
            startEnd[1] = resultSize;
            if (startEnd[0] > startEnd[1]) {
                return new ArrayList<>(0);
            }
        }

        return sub(list, startEnd[0], startEnd[1]);
    }

    /**
     *          List                        List
     *
     * @param list List
     * @param <T>
     * @return             List
     * @since 5.2.6
     */
    public static <T> List<T> unmodifiable(List<T> list) {
        if (null == list) {
            return null;
        }
        return Collections.unmodifiableList(list);
    }

    /**
     *          List
     *
     * @param <T>
     * @param list
     * @param pageSize
     * @param pageListConsumer
     * @since 5.7.10
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
     *
     *
     * @param <T>
     * @param collection
     * @param comparator
     * @return treeSet
     */
    public static <T> List<T> sort(Collection<T> collection, Comparator<? super T> comparator) {
        List<T> list = new ArrayList<>(collection);
        list.sort(comparator);
        return list;
    }


    /**
     *
     *
     * @param <T>
     * @param list
     * @param start
     * @param end
     * @return                                                                   List
     */
    public static <T> List<T> sub(List<T> list, int start, int end) {
        return sub(list, start, end, 1);
    }

    /**
     *                      <br>
     *             {@link List#subList(int, int)}
     *
     * @param <T>
     * @param list
     * @param start
     * @param end
     * @param step
     * @return                                                                   List
     * @since 4.0.6
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

        if (step < 1) {
            step = 1;
        }

        final List<T> result = new ArrayList<>();
        for (int i = start; i < end; i += step) {
            result.add(list.get(i));
        }
        return CollectionUtils.unmodifiable(result);
    }


    /**
     *                   number
     *
     * @param elementList
     * @param number
     * @param <T>
     * @return 1
     */
    public static <T> List<T> getRandomElement(List<T> elementList, int number) {
        if (CollectionUtils.isEmpty(elementList) || number < 1) {
            return Collections.emptyList();
        }
        //                                                       ,                               ,
        if (number >= elementList.size()) {
            List<T> result = new ArrayList<>(elementList);
            Collections.shuffle(result);
            return result;
        } else {
            List<T> result = new ArrayList<>(number);
            for (int i = 0; i < number; i++) {
                int index = ThreadLocalRandom.current().nextInt(0, elementList.size());
                T t = elementList.get(index);
                if (result.contains(t)) {
                    i--;
                } else {
                    result.add(t);
                }
            }
            Collections.shuffle(result);
            return result;
        }
    }

    /**
     * 将指定元素添加到 List 中（跳过 null 元素）。
     *
     * @param elements 目标列表
     * @param element  待添加的元素数组
     * @param <E>      元素类型
     * @return 添加后的列表，输入为 null 返回空列表
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
     * @return 添加后的列表，输入为 null 返回空列表
     */
    public static <E> List<E> addAll(List<E> elements, List<E> element) {
        if (null == elements || null == element) {
            return Collections.emptyList();
        }

        elements.addAll(element);
        return elements;
    }

    /**
     * 将数组转为 ArrayList。
     *
     * @param elements 源数组
     * @param <T>      元素类型
     * @return 包含数组元素的 List，数组为 null 返回空列表
     */
    public static <T> List<T> newArrayList(T... elements) {
        return null == elements ? Collections.emptyList() : Arrays.asList(elements);
    }

    /**
     * 将 Iterable 转为不可变 List。
     *
     * @param elements 源 Iterable
     * @param <E>      元素类型
     * @return 不可变的 List，Iterable 为 null 返回空列表
     */
    public static <E> List<E> newArrayList(Iterable<? extends E> elements) {
        if (null == elements) {
            return Collections.emptyList();
        }

        if (elements instanceof Collection) {
            return Collections.unmodifiableList(new LinkedList<>((Collection) elements));
        }

        return newArrayList(elements.iterator());
    }

    /**
     * 将 Iterator 转为不可变 List。
     *
     * <p>先提取第一个元素，再依次提取剩余元素，最终返回不可变列表。</p>
     *
     * @param elements 源 Iterator
     * @param <E>      元素类型
     * @return 不可变的 List
     */
    public static <E> List<E> newArrayList(Iterator<? extends E> elements) {
        if (!elements.hasNext()) {
            return Collections.emptyList();
        }
        E first = elements.next();
        if (!elements.hasNext()) {
            return Collections.unmodifiableList(addAll(new ArrayList<>(), first));
        }

        List<E> rs = new LinkedList<>();
        rs.add(first);

        while (elements.hasNext()) {
            rs.add(elements.next());
        }
        return Collections.unmodifiableList(rs);
    }

    /**
     * 将数组转为 LinkedList。
     *
     * @param list 源数组
     * @param <T>  元素类型
     * @return 包含数组元素的 LinkedList，数组为 null 返回空列表
     */
    public static <T> List<T> newLinkedList(T... list) {
        return null == list ? Collections.emptyList() : new LinkedList<>(Arrays.asList(list));
    }

    /**
     * 将数组转为 HashSet。
     *
     * @param list 源数组
     * @param <T>  元素类型
     * @return 包含数组元素的 HashSet，数组为 null 返回空 Set
     */
    public static <T> Set<T> newHashSet(T... list) {
        return null == list ? Collections.emptySet() : new HashSet<>(Arrays.asList(list));
    }

    /**
     * 计算多个列表的笛卡尔积。
     *
     * <p>将第一个列表与后续所有列表做笛卡尔积运算，返回所有组合的列表集合。</p>
     *
     * @param first 第一个列表
     * @param more  更多列表（可选）
     * @param <T>   元素类型
     * @return 所有组合的列表集合
     */
    public static <T> List<List<T>> descartes(List<T> first, List<T>... more) {
        List<List<T>> lists = new LinkedList<>();
        lists.add(first);
        lists.addAll(Arrays.asList(more));
        List<List<T>> result = new LinkedList<>();
        descartesRecursive(lists, 0, new LinkedList<>(), result);
        return result;
    }

    /** DescartesRecursive */
    private static <T> void descartesRecursive(List<List<T>> lists, int depth, LinkedList<T> current, List<List<T>> result) {
        if (depth == lists.size()) {
            result.add(new LinkedList<>(current));
            return;
        }
        for (T item : lists.get(depth)) {
            current.addLast(item);
            descartesRecursive(lists, depth + 1, current, result);
            current.removeLast();
        }
    }


    /**
     *                                                 1                           2
     *
     * <pre>
     *     subtractToList([1,2,3,4],[2,3,4,5]) -    [1]
     * </pre>
     *
     * @param coll1       1
     * @param coll2       2
     * @param <T>
     * @return
     * @since 5.3.5
     */
    public static <T> List<T> subtractToList(Collection<T> coll1, Collection<T> coll2) {

        if (isEmpty(coll1)) {
            return Collections.emptyList();
        }
        if (isEmpty(coll2)) {
            return list(true, coll1);
        }

        //
        final List<T> result = new LinkedList<>();
        Set<T> set = new HashSet<>(coll2);
        for (T t : coll1) {
            if (!set.contains(t)) {
                result.add(t);
            }
        }
        return result;
    }


    /**
     *             List
     *
     * @param <T>
     * @param isLinked               LinkedList
     * @param collection
     * @return List
     * @since 4.1.2
     */
    public static <T> List<T> list(boolean isLinked, Collection<T> collection) {
        if (null == collection) {
            return list(isLinked);
        }
        return isLinked ? new LinkedList<>(collection) : new ArrayList<>(collection);
    }

    /**
     *                List
     *
     * @param <T>
     * @param isLinked             LinkedList
     * @return List
     * @since 4.1.2
     */
    public static <T> List<T> list(boolean isLinked) {
        return isLinked ? new LinkedList<>() : new ArrayList<>();
    }

    /**
     *                         1
     *
     * @param source
     * @param target       1
     * @return                         1
     */
    public static boolean contains(String source, Collection<String> target) {
        if(StringUtils.isEmpty(source)) {
            return false;
        }

        for (String s : target) {
            if(s.equals(source)) {
                return true;
            }
        }

        return false;
    }
    /**
     *                         1
     *
     * @param source
     * @param target       1
     * @return                         1
     */
    public static boolean containsIgnoreCase(Collection<String> target, String source) {
        if(StringUtils.isEmpty(source)) {
            return false;
        }

        for (String s : target) {
            if(s.equalsIgnoreCase(source)) {
                return true;
            }
        }

        return false;
    }
    /**
     *
     *
     * @param source
     * @param target       1
     * @return                         1
     */
    public static boolean containsAllIgnoreCase(Collection<String> target, String... source) {
        if(ArrayUtils.isEmpty(source)) {
            return true;
        }

        for (String s : target) {
            for (String s1 : source) {
                if(!s.equalsIgnoreCase(s1)) {
                    return false;
                }
            }
        }

        return true;
    }
    /**
     *
     *
     * @param source
     * @param target       1
     * @return
     */
    public static boolean notContains(List<String> source, Collection<String> target) {
        for (String s : target) {
            if (!source.contains(s)) {
                return true;
            }
        }
        return false;
    }
    /**
     *                         1
     *
     * @param source
     * @param target       1
     * @return                         1
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
     *                         1
     *
     * @param source
     * @param target       1
     * @return                         1
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
     *                         1
     *
     * @param source
     * @param target       1
     * @return                         1
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
     * 检查 Iterator 中是否包含指定元素。
     *
     * @param iterator 待检查的 Iterator
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
     * 将 List 转为数组。
     *
     * @param args 源列表
     * @param <T>  元素类型
     * @return 转换后的数组
     */
    public static <T>T[] toArray(List<T> args) {
        return ArrayUtils.toArray(args);
    }
    /**
     * 将 List 和列名列表转为二维数组。
     *
     * @param args  源列表
     * @param names 列名列表
     * @return 二维数组
     */
    public static Object[][] toArray(List<Object> args, List<String> names) {
        return toArray(args.toArray(), names);
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
        List<Object[]> rs = new LinkedList<>();
        for (Object arg : args) {
            if(arg instanceof  Map) {
                rs.add(toArray((Map)arg, names));
                continue;
            }

            if(arg instanceof Collection) {
                rs.add(toArray((Collection)arg, names));
                continue;
            }

            rs.add(toArray(BeanUtils.objectToMap(arg), names));

        }


        return rs.toArray(new Object[0][]);
    }

    /**
     *
     *
     * @param arg
     * @param names
     * @return
     */
    private static Object[] toArray(Collection arg, List<String> names) {
        Object[] rs = new Object[names.size()];
        if(CollectionUtils.isEmpty(arg)) {
            return rs;
        }
        for (int i = 0; i < names.size(); i++) {
            rs[i] = null;
            if(arg.size() < i) {
                rs[i] = CollectionUtils.find(arg, i);
            }
        }

        return rs;
    }

    /**
     *
     *
     * @param arg
     * @param names
     * @return
     */
    private static Object[] toArray(Map arg, List<String> names) {
        Object[] rs = new Object[names.size()];
        if(MapUtils.isEmpty(arg)) {
            return rs;
        }

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i).replace("`", "");
            rs[i] = MapUtils.getConfig(arg, name);
        }

        return rs;
    }


    /**
     * 将 List 按 ID 函数转为 Map（key = function 返回值，value = 元素本身）。
     *
     * <p>如果列表中有重复 ID，后面的值会覆盖前面的。</p>
     *
     * @param list     源列表
     * @param function ID 提取函数，用于生成 Map 的 key
     * @param <R>      key 类型
     * @param <T>      元素类型
     * @return Map 结果
     */
    public static <R, T>Map<R, T> convertMap(List<T> list, Function<T, R> function) {
        if(isEmpty(list) || null == function) {
            return Collections.emptyMap();
        }
        Map<R, T> rs = new HashMap<>(list.size());
        for (T t : list) {
            rs.put(function.apply(t), t);
        }

        return rs;
    }
    /**
     * 将 List 按 ID 函数分组转为 Map（key = function 返回值，value = 元素列表）。
     *
     * <p>相同的 ID 会被归入同一个列表。</p>
     *
     * @param list     源列表
     * @param function ID 提取函数，用于分组的 key
     * @param <R>      key 类型
     * @param <T>      元素类型
     * @return 分组后的 Map 结果
     */
    public static <R, T>Map<R, List<T>> convertMaps(List<T> list, Function<T, R> function) {
        if(isEmpty(list) || null == function) {
            return Collections.emptyMap();
        }
        Map<R, List<T>> rs = new HashMap<>(list.size());
        for (T t : list) {
            rs.computeIfAbsent(function.apply(t), it -> new LinkedList<>()).add(t);
        }

        return rs;
    }

    /**
     * 将 List 包装为非 null 版本（null 转为空列表）。
     *
     * @param elements 源列表，可能为 null
     * @param <T>      元素类型
     * @return 非 null 的列表
     */
    public static <T>List<T> wrapper(List<T> elements) {
        return Optional.ofNullable(elements).orElse(Collections.emptyList());
    }
    /**
     * 将单个元素包装为单元素 List。
     *
     * @param element 待包装的元素，可能为 null
     * @param <T>     元素类型
     * @return 包含该元素的单例列表，null 返回空列表
     */
    public static <T>List<T> wrapper(T element) {
        if(null == element) {
            return Collections.emptyList();
        }
        return Collections.singletonList(element);
    }

    /**
     * 将 Set 包装为非 null 版本（null 转为空 Set）。
     *
     * @param elements 源 Set，可能为 null
     * @param <T>      元素类型
     * @return 非 null 的 Set
     */
    public static <T>Set<T> wrapper(Set<T> elements) {
        return Optional.ofNullable(elements).orElse(Collections.emptySet());
    }
    /**
     * 将 Map 包装为非 null 版本（null 转为空 Map）。
     *
     * @param elements 源 Map，可能为 null
     * @param <K>      key 类型
     * @param <V>      value 类型
     * @return 非 null 的 Map
     */
    public static <K, V>Map<K, V> wrapper(Map<K, V> elements) {
        return Optional.ofNullable(elements).orElse(Collections.emptyMap());
    }


    /**
     *                                                                                                             ":"   "="
     *                                                       IllegalArgumentException
     *
     * <p>
     * (               )
     * ["a","b"] ==> {a=b}
     * [" a "," b "] ==> {a=b}
     * ["a=b"] ==>{a=b}
     * ["a:b"] ==>{a=b}
     * ["a=b","c","d"] ==>{a=b,c=d}
     * ["a","a:b"] ==>{a="a:b"}
     * ["a","a,b"] ==>{a="a,b"}
     * </p>
     *
     * @param parameters
     * @return Map
     */

    public static Map<String, String> convertParameters(String[] parameters) {
        if (ArrayUtils.isEmpty(parameters)) {
            return new HashMap<>(DEFAULT_SIZE);
        }

        List<String> compatibleParameterArray = Arrays.stream(parameters)
                .map(String::trim)
                .reduce(
                        new ArrayList<>(parameters.length),
                        (list, parameter) -> {
                            if (list.size() % 2 == 1) {
                                // value doesn't split
                                list.add(parameter);
                                return list;
                            }

                            String[] sp1 = parameter.split(":");
                            if (sp1.length > 0 && sp1.length % 2 == 0) {
                                // key split
                                list.addAll(Arrays.stream(sp1).map(String::trim).toList());
                                return list;
                            }
                            sp1 = parameter.split("=");
                            if (sp1.length > 0 && sp1.length % 2 == 0) {
                                list.addAll(Arrays.stream(sp1).map(String::trim).toList());
                                return list;
                            }
                            list.add(parameter);
                            return list;
                        },
                        (a, b) -> a);

        return CollectionUtils.toStringMap(compatibleParameterArray.toArray(new String[0]));
    }

    /**
     *                                              Map
     *       pairs                                                                           [pkey1, pvalue1, pkey2, pvalue2, ...]
     *                                        IllegalArgumentException
     *
     * @param pairs
     * @return Map
     */
    public static Map<String, String> toStringMap(String... pairs) {
        Map<String, String> parameters = new HashMap<>(pairs.length);
        //       pairs                                          Map
        if (ArrayUtils.isEmpty(pairs)) {
            return parameters;
        }

        //       pairs
        if (pairs.length > 0) {
            if (pairs.length % 2 != 0) {
                throw new IllegalArgumentException("pairs must be even.");
            }
            //       pairs                                                         parameters Map
            for (int i = 0; i < pairs.length; i = i + 2) {
                parameters.put(pairs[i], pairs[i + 1]);
            }
        }
        return parameters;
    }

    /**
     *
     *
     * @param temp                                        null
     * @param <K>
     * @param <V>
     * @return
     */
    public static <K, V> Collection<? extends K> keySet(Map<K, V> temp) {
        //
        if(isEmpty(temp)) {
            return Collections.emptyList();
        }

        //
        List<K> keys = new ArrayList<>(temp.size());
        //
        for (Map.Entry<K, V> entry : temp.entrySet()) {
            keys.add(entry.getKey());
        }

        //
        return Collections.unmodifiableList(keys);
    }
    /**
     *
     *
     *                                              null               null
     *
     * @param s
     * @param function
     * @param <T>
     * @param <R>
     * @return                                        null                                                null
     */
    public static <T, R>R firstValidate(List<T> s, Function<T, R> function) {
        T t = firstValidate(s);
        return null == t ? null : function.apply(t);
    }

    /**
     *
     *
     * @param s
     * @return                                                                                  null
     * @param <T>
     */
    public static <T>T firstValidate(List<T> s) {
        //
        if(isEmpty(s)) {
            return null;
        }

        //
        for (T t : s) {
            if(ObjectUtils.isNotEmpty(t)) {
                return t;
            }
        }

        //                                                 null
        return null;
    }

    /**
     *       List
     *
     * @since 3.5.4
     */
    @SafeVarargs
    public static <T> List<T> toList(T... t) {
        if (t != null) {
            return Arrays.asList(t);
        }
        return Collections.emptyList();
    }

    /**
     *                Jdk1.8   ConcurrentHashMap         bug
     * https://bugs.openjdk.java.net/browse/JDK-8161372
     *
     *  A temporary workaround for Java 8 ConcurrentHashMap#computeIfAbsent specific performance issue: JDK-8161372.</br>
     *  @see <a href="https://bugs.openjdk.java.net/browse/JDK-8161372">https://bugs.openjdk.java.net/browse/JDK-8161372</a>
     *
     * @param concurrentHashMap ConcurrentHashMap                         ConcurrentHashMap
     * @param key               key
     * @param mappingFunction   function
     * @param <K>               k
     * @param <V>               v
     * @return V
     * @since 3.4.0
     */
    public static <K, V> V computeIfAbsent(Map<K, V> concurrentHashMap, K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        if (Projects.JDK_8) {
            V v = concurrentHashMap.get(key);
            if (null == v) {
                v = mappingFunction.apply(key);
                if (null == v) {
                    return null;
                }
                final V res = concurrentHashMap.putIfAbsent(key, v);
                if (null != res) {
                    return res;
                }
            }
            return v;
        } else {
            return concurrentHashMap.computeIfAbsent(key, mappingFunction);
        }

    }

    /**
     * 查找字符串列表中第一个包含指定子串的元素索引。
     *
     * @param list 字符串列表
     * @param ele  要查找的子串
     * @return 第一个包含子串的元素索引，未找到返回 -1
     */
    public static int getIndexByContains(List<String> list, String ele) {
        for (int i = 0; i < list.size(); i++) {
            String s = list.get(i);
            if (s.contains(ele)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 按步长间隔插入分隔符连接字符串列表。
     *
     * <p>每隔 step 个元素插入一次 seq 分隔符。</p>
     *
     * @param eles 字符串列表
     * @param step 步长间隔
     * @param seq  分隔符
     * @return 连接后的字符串
     */
    public static String join(List<String> eles, int step, String seq) {
        if(isEmpty(eles)) {
            return SYMBOL_EMPTY;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eles.size(); i++) {
            if (i > 0 && i % step == 0) {
                sb.append(seq);
            }
            sb.append(eles.get(i));
        }
        return sb.toString();
    }

    /**
     *                 N
     * @param originalList
     * @param step
     * @return                                                     N
     */
    public static List<List<Integer>> generateGroupList(List<Integer> originalList, int step) {
        //                                   groupSize
        if (originalList.size() % step != 0) {
            throw new IllegalArgumentException("                           step      ");
        }

        //                                                           N
        List<List<Integer>> newList = new ArrayList<>();

        //                             N
        for (int i = 0; i < originalList.size(); i += step) {
            List<Integer> group = originalList.subList(i, i + step);
            newList.add(group);
        }

        return newList;
    }

    /**
     * 获取列表中的唯一元素，如果列表为空则抛出异常。
     *
     * @param elements 列表
     * @param <E>      元素类型
     * @return 唯一的元素
     * @throws IllegalArgumentException 如果列表为空
     */
    public static <E>E singletonOrThrow(List<E> elements) {
        if(isEmpty(elements)) {
            throw new IllegalArgumentException("                  ");
        }
        return elements.getFirst();
    }

    /**
     * 限制 List 的大小，返回从头到指定长度的子列表。
     *
     * @param source 源列表
     * @param limit  最大长度
     * @param <T>    元素类型
     * @return 截取后的子列表
     */
    public static <T>List<T> limit(List<T> source, int limit) {
        return source.subList(0, Math.min(limit, source.size()));
    }


    /**
     * 从指定偏移量开始限制 List 的大小。
     *
     * @param source 源列表
     * @param offset 起始偏移量
     * @param limit  最大长度
     * @param <T>    元素类型
     * @return 截取后的子列表
     */
    public static <T>List<T> limit(List<T> source, int offset, int limit) {
        return source.subList(offset, Math.min(offset + limit, source.size()));
    }

    /**
     *
     *
     * @param list
     * @param function
     * @param <T>
     */
    public static <T>void print(List<T> list, Function<T, String> function) {
        if(isEmpty(list) || null == function) {
            log.info("list is empty");
            return;
        }

        StringBuffer sb = new StringBuffer();
        for (T t : list) {
            sb.append("         ").append(function.apply(t)).append("\n");
        }
        log.info("list: \n{}", sb);
    }

    /**
     *       List                                    "      X         "
     * [CH] 2025-01-01 v1.0.0
     *
     * @param title
     * @param list
     * @param maxLines                                               "      X   "
     * @param function
     * @param <T>
     */
    public static <T> void printTableFormat(String title, List<T> list, int maxLines, Function<T, String> function) {
        if (isEmpty(list) || null == function) {
            log.info("{}: [empty list]", title);
            return;
        }

        StringBuilder sb = new StringBuilder();

        //
        sb.append(title).append("\n");

        //
        int totalSize = list.size();
        int displaySize = Math.min(maxLines, totalSize);

        //          N
        for (int i = 0; i < displaySize; i++) {
            sb.append("           ").append(function.apply(list.get(i))).append("\n");
        }

        //                                  "      X   "
        if (totalSize > displaySize) {
            int remainingCount = totalSize - displaySize;
            sb.append("           ...        ").append(remainingCount).append("          \n");
        }

        log.info("{}", sb);
    }

    /**
     *       List                                    "      X         "
     * [CH] 2025-01-01 v1.0.0
     *
     *                   5
     *
     * @param title
     * @param list
     * @param function
     * @param <T>
     */
    public static <T> void printTableFormat(String title, List<T> list, Function<T, String> function) {
        printTableFormat(title, list, 5, function);
    }
}