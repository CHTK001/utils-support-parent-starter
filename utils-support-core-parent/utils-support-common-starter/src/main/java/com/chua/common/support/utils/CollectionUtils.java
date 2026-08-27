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
 * 集合工具类，提供 List、Set、Map 等集合的常用操作方法。
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
     * 将 null 的 Set 替换为空 Set，避免空指针。
     *
     * @param set 待检查的 Set，可能为 null
     * @param <T> 元素类型
     * @return 非空 Set，null 时返回空 Set
     */
    @Nonnull
    public static <T> Set<T> emptyIfNull(@Nullable Set<T> set) {
        return (null == set) ? Collections.emptySet() : set;
    }

    /**
     * 将 null 的 List 替换为空 List，避免空指针。
     *
     * @param list 待检查的 List，可能为 null
     * @param <T>  元素类型
     * @return 非空 List，null 时返回空 List
     */
    @Nonnull
    public static <T> List<T> emptyIfNull(@Nullable List<T> list) {
        return (null == list) ? Collections.emptyList() : list;
    }

    /**
     * 从集合中按多个索引批量获取元素，支持负数索引（从末尾计数）。
     *
     * @param collection 源集合
     * @param indexes    要获取的元素索引数组
     * @param <T>        元素类型
     * @return 对应索引的元素列表
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
     * 从集合中随机选取一个元素（使用 {@link SecureRandom}）。
     *
     * @param source 源集合，可能为 null
     * @param <T>    元素类型
     * @return 随机选中的元素，集合为空或 null 返回 null
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
     * 将 List 平均分配为指定数量的子列表（前几个列表多一个元素）。
     *
     * @param source 源列表
     * @param limit  每组最大元素数
     * @param <T>    元素类型
     * @return 分组后的列表集合
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
     * 对 List 进行分页，返回指定页码的数据子集。
     *
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页大小
     * @param list     源列表
     * @param <T>      元素类型
     * @return 指定页的元素列表，越界返回空列表
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
     * 将 List 转为不可修改视图，null 时返回 null。
     *
     * @param list 源列表
     * @param <T>  元素类型
     * @return 不可修改的列表，null 时返回 null
     */
    public static <T> List<T> unmodifiable(List<T> list) {
        if (null == list) {
            return null;
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 对 List 分页，逐页调用消费者回调。
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
     * 对集合按给定 Comparator 排序后返回新 List。
     *
     * @param collection 源集合
     * @param comparator 比较器
     * @param <T>        元素类型
     * @return 排序后的 List
     */
    public static <T> List<T> sort(Collection<T> collection, Comparator<? super T> comparator) {
        List<T> list = new ArrayList<>(collection);
        list.sort(comparator);
        return list;
    }


    /**
     * 截取 List 的子列表（start 包含，end 不包含，支持负数索引）。
     *
     * @param list  源列表
     * @param start 起始索引（负数从末尾计数）
     * @param end   结束索引（负数从末尾计数）
     * @param <T>   元素类型
     * @return 截取后的不可变子列表
     */
    public static <T> List<T> sub(List<T> list, int start, int end) {
        return sub(list, start, end, 1);
    }

    /**
     * 截取 List 子列表，支持指定步长（start/end 均支持负数索引）。
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
     * 从列表中随机选取指定数量的不重复元素。
     *
     * @param elementList 源列表
     * @param number      要选取的元素个数，必须大于 0
     * @param <T>         元素类型
     * @return 随机选取的元素列表，source 为空或 number 无效时返回空列表
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
        return null == elements ? new ArrayList<>() : new ArrayList<>(Arrays.asList(elements));
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
     * 计算 coll1 与 coll2 的差集，返回仅在 coll1 中的元素列表。
     *
     * <p>示例：subtractToList([1,2,3,4], [2,3,4,5]) → [1]</p>
     *
     * @param coll1 被减集合
     * @param coll2 减集合
     * @param <T>   元素类型
     * @return 差集列表
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
     * 将 Collection 转为指定类型的 List（LinkedList 或 ArrayList）。
     *
     * @param isLinked   是否使用 LinkedList
     * @param collection 源集合，为 null 时返回空 List
     * @param <T>        元素类型
     * @return 转换后的 List
     */
    public static <T> List<T> list(boolean isLinked, Collection<T> collection) {
        if (null == collection) {
            return list(isLinked);
        }
        return isLinked ? new LinkedList<>(collection) : new ArrayList<>(collection);
    }

    /**
     * 创建指定类型的空 List（LinkedList 或 ArrayList）。
     *
     * @param isLinked 是否使用 LinkedList
     * @param <T>      元素类型
     * @return 空 List
     */
    public static <T> List<T> list(boolean isLinked) {
        return isLinked ? new LinkedList<>() : new ArrayList<>();
    }

    /**
     * 判断字符串是否存在于目标集合中（精确匹配）。
     *
     * @param source 待查找的字符串
     * @param target 目标集合
     * @return 存在返回 true
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
     * 判断字符串是否存在于目标集合中（忽略大小写）。
     *
     * @param target 目标集合
     * @param source 待查找的字符串
     * @return 存在返回 true
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
     * 判断目标集合是否包含所有源字符串（忽略大小写）。
     *
     * @param target 目标集合
     * @param source 待查找的字符串数组
     * @return 全部包含返回 true
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
     * 判断目标集合中是否有元素不在源列表中（即检查是否存在差异）。
     *
     * @param source 源列表
     * @param target 目标集合
     * @return 存在不在 source 中的 target 元素时返回 true
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
     * 判断 List 中是否包含目标集合中的任意元素。
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
     * 判断 Set 中是否包含目标集合中的任意元素。
     *
     * @param source 源 Set
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
     * 判断 Set 中是否包含逗号分隔字符串中的任意元素。
     *
     * @param source  源 Set
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
     * 将参数数组解析为 Map，支持 ":" 或 "=" 分隔的键值对格式。
     *
     * <p>自动处理空格、多值分割等情况。</p>
     *
     * @param parameters 参数字符串数组
     * @return 解析后的 Map
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
     * 将交替排列的键值对字符串数组转为 Map（[key1, val1, key2, val2, ...]）。
     *
     * <p>数组长度必须为偶数，否则抛出 {@link IllegalArgumentException}。</p>
     *
     * @param pairs 键值对字符串数组
     * @return 转换后的 Map
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
     * 获取 Map 的所有 key 并返回不可变列表，null 时返回空列表。
     *
     * @param temp 源 Map
     * @param <K>  key 类型
     * @param <V>  value 类型
     * @return 不可变的 key 列表
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
     * 对列表中每个元素应用函数，返回第一个非 null 结果。
     *
     * @param s        源列表
     * @param function 转换函数
     * @param <T>      元素类型
     * @param <R>      返回值类型
     * @return 第一个非 null 转换结果，不存在则返回 null
     */
    public static <T, R> R firstValidate(List<T> s, Function<T, R> function) {
        T t = firstValidate(s);
        return null == t ? null : function.apply(t);
    }

    /**
     * 获取列表中第一个非 null 且非空的元素。
     *
     * @param s 源列表
     * @param <T> 元素类型
     * @return 第一个有效元素，不存在则返回 null
     */
    public static <T> T firstValidate(List<T> s) {
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
     * 将可变参数数组转为 List。
     *
     * @param t 元素数组
     * @param <T> 元素类型
     * @return 包含元素的 List，null 时返回空列表
     */
    @SafeVarargs
    public static <T> List<T> toList(T... t) {
        if (t != null) {
            return Arrays.asList(t);
        }
        return Collections.emptyList();
    }

    /**
     *  JDK 8 ConcurrentHashMap#computeIfAbsent 的性能修复版本。
     *
     * <p>规避 JDK-8161372 bug：在多线程并发场景下避免死循环。</p>
     *
     * @param concurrentHashMap 并发 Map
     * @param key               键
     * @param mappingFunction   映射函数
     * @param <K>               key 类型
     * @param <V>               value 类型
     * @return 映射结果
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
     * 将列表按指定步长分组为子列表。
     *
     * @param originalList 源列表
     * @param step         每组元素个数，必须能整除列表长度
     * @return 分组后的列表集合
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
     * 将 List 中每个元素通过函数转换为字符串后打印到日志。
     *
     * @param list     源列表，为空时直接返回
     * @param function 元素到字符串的转换函数
     * @param <T>      元素类型
     */
    public static <T> void print(List<T> list, Function<T, String> function) {
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
     * 以表格格式打印 List，最多显示 maxLines 行，超出显示省略信息。
     *
     * @param title    标题
     * @param list     源列表
     * @param maxLines 最大显示行数
     * @param function 元素到字符串的转换函数
     * @param <T>      元素类型
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
     * 以表格格式打印 List，默认最多显示 5 行。
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