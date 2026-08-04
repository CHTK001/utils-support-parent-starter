package com.chua.common.support.utils;

import com.chua.common.support.collection.MultiLinkedValueMap;
import com.chua.common.support.collection.MultiValueMap;
import com.chua.common.support.converter.Converter;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;

import java.io.File;
import java.lang.reflect.Array;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_COMMA;
import static com.chua.common.support.constant.NumberConstant.NUMBER_3;
import static com.chua.common.support.constant.ValueConstant.EMPTY_PROPERTIES;
import static com.chua.common.support.constant.ValueConstant.SYMBOL_EMPTY_STRING_ARRAY;
import org.jspecify.annotations.NullUnmarked;


/**
 * Map 工具类，提供 Map 操作的核心工具方法。
 *
 * <p>包含以下功能：
 * <ul>
 *   <li>Map 创建 — 空 Map、单值 Map、默认容量计算</li>
 *   <li>值获取 — 类型安全取值（字符串、数字、布尔、日期、枚举）、带默认值取值</li>
 *   <li>属性转换 — 对象与 Map 互转、Properties 转换、JSON 对象转换</li>
 *   <li>Map 操作 — 合并、排序、过滤、前缀处理、键值对遍历</li>
 *   <li>路径查询 — 点号分隔的嵌套键取值、深度查找</li>
 *   <li>集合转换 — LinkedMultiValueMap 支持一对多映射</li>
 * </ul>
 *
 * @author CH
 */
@SuppressWarnings({"NullAway", "unchecked", "ALL"})
@NullUnmarked
public class MapUtils {

    /**
     * 默认初始容量，HashMap 默认的桶数量。
     */
    public static final int DEFAULT_INITIAL_CAPACITY = 16;
    /**
     * 默认负载因子，当 Map 中元素数量达到 size * LOAD_FACTOR 时触发扩容。
     */
    public static final float DEFAULT_LOAD_FACTOR = 0.75f;
    /**
     * 2 的最大幂次阈值，用于 {@link #capacity(int)} 中判断是否需要使用精确容量计算。
     * 值为 {@code 1 << 30}（约 10.7 亿），超过此值时直接返回 Integer.MAX_VALUE。
     */
    private static final int MAX_POWER_OF_TWO = 1 << (Integer.SIZE - 2);

    /**
     * 将嵌套 Map 展开为扁平化的 Map 结构。
     *
     * <p>递归遍历 Map 及其嵌套的 Map 或 List，将嵌套键拼接为点号分隔的扁平键，
     * List 元素以 {@code [index]} 形式标记位置。
     *
     * @param map 待展开的嵌套 Map，允许为 null
     * @return 扁平化后的 Map，若 map 为 null 则返回空 Map
     */
    public static Map<String, Object> flattenMap(Map<String, Object> map) {
        if (null == map) {
            return Collections.emptyMap();
        }
        Map<String, Object> flattenedMap = new HashMap<>(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                Map<String, Object> subMap = flattenMap((Map<String, Object>) value);
                for (Map.Entry<String, Object> subEntry : subMap.entrySet()) {
                    flattenedMap.put(key + "." + subEntry.getKey(), subEntry.getValue());
                }
            } else if (value instanceof List) {
                List<Object> list = (List<Object>) value;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Map) {
                        Map<String, Object> subMap = flattenMap((Map<String, Object>) item);
                        for (Map.Entry<String, Object> subEntry : subMap.entrySet()) {
                            flattenedMap.put(key + "[" + i + "]." + subEntry.getKey(), subEntry.getValue());
                        }
                    } else {
                        flattenedMap.put(key + "[" + i + "]", item);
                    }
                }
            } else {
                flattenedMap.put(key, value);
            }
        }
        return flattenedMap;
    }

    /**
     * 根据预期大小创建一个新的 HashMap。
     *
     * <p>内部自动计算合适的初始容量，避免 HashMap 在填充过程中频繁扩容。
     *
     * @param expectedSize 预期的元素数量
     * @param <K>          键类型
     * @param <V>          值类型
     * @return 初始容量适配 expectedSize 的 HashMap
     * @since 3.4.0
     */
    public static <K, V> HashMap<K, V> newHashMapWithExpectedSize(int expectedSize) {
        return new HashMap<>(capacity(expectedSize));
    }

    /**
     * 计算适合指定预期大小的 HashMap 初始容量。
     *
     * <p>根据负载因子 0.75 反推容量，确保在不超过 expectedSize 个元素时无需扩容。
     * expectedSize 为负数时抛出 IllegalArgumentException。
     *
     * @param expectedSize 预期的元素数量
     * @return 适配 expectedSize 的初始容量，最大为 Integer.MAX_VALUE
     * @throws IllegalArgumentException 当 expectedSize 为负数时
     * @since 3.4.0
     */
    private static int capacity(int expectedSize) {
        if (expectedSize < NUMBER_3) {
            if (expectedSize < 0) {
                throw new IllegalArgumentException("expectedSize cannot be negative but was: " + expectedSize);
            }
            return expectedSize + 1;
        }
        if (expectedSize < MAX_POWER_OF_TWO) {


            return (int) ((float) expectedSize / 0.75F + 1.0F);
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 将后续 Map 数据全部合并到第一个 Map 中。
     *
     * @param source 待合并的 Map 数组，至少一个元素
     * @param <K>    键类型
     * @param <V>    值类型
     * @return 合并后的 Map，即第一个元素
     */
    @SafeVarargs
    public static <K, V> Map<K, V> putAll(final Map<K, V>... source) {
        if (null == source || source.length == 0) {
            return Collections.emptyMap();
        }
        Map<K, V> result = source[0];
        for (int i = 1; i < source.length; i++) {
            Map<K, V> map = source[i];
            if (isEmpty(map)) {
                continue;
            }
            result.putAll(map);
        }
        return result;
    }

    /**
     * 合并多个 Map 为一个新的 Map。
     *
     * @param source 待合并的 Map 数组
     * @param <K>    键类型
     * @param <V>    值类型
     * @return 合并后的新 Map
     */
    @SafeVarargs
    public static <K, V> Map<K, V> merge(final Map<K, V>... source) {
        if (null == source) {
            return Collections.emptyMap();
        }
        Map<K, V> result = new HashMap<>(source.length);
        for (Map<K, V> map : source) {
            if (isEmpty(map)) {
                continue;
            }
            result.putAll(map);
        }
        return result;
    }

    /**
     * 从 Map 中获取字符串值，若值为 null 或空字符串则尝试备用 key 取值。
     *
     * <p>先对 key 取值，若结果为 null 或空字符串则尝试 key2 取值，
     * 两步都为空时返回 defaultValue。
     *
     * @param map          Map
     * @param key          主 key
     * @param key2         备用 key
     * @param defaultValue 默认值
     * @param <K>          键类型
     * @param <V>          值类型
     * @return 取到的字符串值，若都为空则返回 defaultValue
     */
    public static <K, V> String getStringForEmpty(final Map<K, V> map, final K key, final K key2, final String defaultValue) {
        if (null == map) {
            return defaultValue;
        }

        Object v = getObject(map, key);
        return null == v || "".equals(v) ? (v = getObject(map, key2)) == null ? defaultValue : v.toString() : v.toString();
    }


    /**
     * 若 key 在 Map 中不存在则放入默认值，并返回最终的值。
     *
     * <p>基于"若不存在则计算并放入"的模式：先尝试 {@link Map#get(Object)}，
     * 若返回 null 则调用 {@link Map#put(Object, Object)} 放入默认值，
     * 再通过 {@link Map#put} 的返回值确认是否真正放入成功。
     * 此方法与 {@link Map#computeIfAbsent(Object, java.util.function.Function)} 不同之处在于
     * 它接受一个固定值（而非函数）。
     *
     * @param map   Map，若为 null 则直接返回 null
     * @param key   键
     * @param value 当 key 不存在时要放入的默认值，若为 null 则直接返回 null
     * @param <K>   键类型
     * @param <V>   值类型
     * @return Map 中最终存在的值（原有值或刚放入的默认值）
     */
    public static <K, V> V getComputeIfAbsent(Map<K, V> map, K key, V value) {
        if (map == null || null == value) {
            return null;
        }
        V v = map.get(key);
        if (null == v) {
            v = map.put(key, value);
            if (null == v) {
                v = value;
            }
        }
        return v;
    }

/**
     * 判断 Properties 是否为空。
     *
     * <p>Properties 为 null 或无任何键值对时返回 true。
     *
     * @param properties Properties 对象，允许为 null
     * @return 若 properties 为 null 或为空则返回 true
     */
    public static boolean isEmpty(Properties properties) {
        return (properties == null || properties.isEmpty());
    }

    /**
     * 判断 Map 是否为空。
     *
     * <p>Map 为 null 或无任何键值对时返回 true。
     *
     * @param map Map 对象，允许为 null
     * @return 若 map 为 null 或为空则返回 true
     */
    public static boolean isEmpty(Map map) {
        return (map == null || map.isEmpty());
    }

    /**
     * 判断 Dictionary 是否为空。
     *
     * <p>Dictionary 为 null 或无任何键值对时返回 true。
     *
     * @param dictionary Dictionary 对象，允许为 null
     * @return 若 dictionary 为 null 或为空则返回 true
     */
    public static boolean isEmpty(Dictionary<?, ?> dictionary) {
        return (dictionary == null || dictionary.isEmpty());
    }

    /**
     * 判断 Dictionary 是否非空。
     *
     * <p>Dictionary 不为 null 且至少包含一个键值对时返回 true。
     *
     * @param dictionary Dictionary 对象，允许为 null
     * @return 若 dictionary 非 null 且非空则返回 true
     */
    public static boolean isNotEmpty(Dictionary<?, ?> dictionary) {
        return !isEmpty(dictionary);
    }

    /**
     * 判断 Map 是否非空。
     *
     * <p>Map 不为 null 且至少包含一个键值对时返回 true。
     *
     * @param map Map 对象，允许为 null
     * @return 若 map 非 null 且非空则返回 true
     */
    public static boolean isNotEmpty(Map map) {
        return !isEmpty(map);
    }

    /**
     * 从 Map 中获取 Number 值，取不到时返回默认值。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 当取不到 Number 时的默认值
     * @return 取到的 Number，若为 null 则返回 defaultValue
     */
    public static <K, V> Number getNumber(final Map<K, V> map, K key, Number defaultValue) {
        Number answer = getNumber(map, key);
        return null == answer ? defaultValue : answer;
    }

    /**
     * 从 Map 中获取 Number 值。
     *
     * <p>取出的值若为 Number 类型则直接返回；若为字符串则尝试通过
     * {@link NumberFormat} 解析为 Number。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 Number，若不存在或无法转换则返回 null
     */
    public static <K, V> Number getNumber(final Map<K, V> map, final K key) {
        Object answer = getObject(map, key);
        if (answer != null) {
            if (answer instanceof Number) {
                return (Number) answer;
            } else if (answer instanceof String) {
                try {
                    String text = (String) answer;
                    return NumberFormat.getInstance().parse(text);
                } catch (ParseException e) {

                }
            }
        }
        return null;
    }

    /**
     * 从 Map 中获取原始对象值。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return Map 中 key 对应的值，若 Map 为 null 或 key 为 null 则返回 null
     */
    public static <K, V> V getObject(final Map<K, V> map, final K key) {
        return null != key && map != null ? map.get(key) : null;
    }

    /**
     * 从 Map 中依次尝试多个 key，返回第一个非 null 的值。
     *
     * @param <K>  键类型
     * @param <V>  值类型
     * @param map  Map
     * @param keys 多个候选键
     * @return 第一个非 null 的值，若所有 key 对应的值均为 null 则返回 null
     */
    public static <K, V> Object getObject(final Map<K, V> map, final K... keys) {
        for (K key : keys) {
            V v = map.get(key);
            if (null != v) {
                return v;
            }
        }
        return null;
    }

    /**
     * 从 Map 中获取原始对象值，取不到时返回默认值。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return Map 中 key 对应的值，若为 null 则返回 defaultValue
     */
    public static <K, V> Object getObject(final Map<K, V> map, final K key, final Object defaultValue) {
        Object object = getObject(map, key);
        if (object != null) {
            return object;
        }
        return defaultValue;
    }

    /**
     * 从 Map 中获取指定类型的值，类型不匹配时返回默认值。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param <R>          期望的返回值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @param type         期望的类型
     * @return 类型匹配的对象，不匹配或为 null 时返回 defaultValue
     */
    public static <K, V, R> R getType(final Map<K, V> map, final K key, final R defaultValue, final Class<R> type) {
        Object object = getObject(map, key);
        return null != object && type.isAssignableFrom(object.getClass()) ? (R) object : defaultValue;
    }

    /**
     * 从 Map 中获取指定类型的值，类型不匹配时返回 null。
     *
     * @param <K>  键类型
     * @param <V>  值类型
     * @param <R>  期望的返回值类型
     * @param map  Map
     * @param key  键
     * @param type 期望的类型
     * @return 类型匹配的对象，不匹配或为 null 时返回 null
     */
    public static <K, V, R> R getType(final Map<K, V> map, final K key, final Class<R> type) {
        Object object = getObject(map, key);
        return null != object && type.isAssignableFrom(object.getClass()) ? (R) object : null;
    }

    /**
     * 从对象中获取字符串值，对象可以是 Map 或可转为 Map 的 POJO。
     *
     * <p>若对象为 Map 则直接取值，否则先通过 JavaBean 内省机制将对象转为 Map 再取值，
     * 值通过 {@code toString} 转为字符串。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map 或可转为 Map 的 POJO 对象，允许为 null
     * @param key 键
     * @return Map 中 key 对应的字符串值，若 Map 为 null 或值不存在则返回 null
     */
    public static <K, V> String getString(final Object map, final K key) {
        if (map instanceof Map) {
            return getString((Map<?, ?>) map, key);
        }
        return getString(beanToMap(map), key);
    }

    /**
     * 从 Map 中获取字符串值，值通过 {@code toString} 转为字符串。
     *
     * <p>Map 为 null 或 key 对应值为 null 时返回 null。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map，允许为 null
     * @param key 键
     * @return Map 中 key 对应值的字符串表示，若 Map 为 null 或值为 null 则返回 null
     */
    public static <K, V> String getString(final Map<K, V> map, final K key) {
        Object object = getObject(map, key);
        if (null == object) {
            return null;
        }
        return object.toString();
    }

    /**
     * 从 Map 中依次尝试多个 key（List 形式），返回第一个非 null 值的字符串表示。
     *
     * <p>遍历 key 列表，对每个 key 通过 {@link #getObject(Map, Object)} 取值，
     * 第一个非 null 值通过 {@code toString} 转为字符串并返回。
     * 全部 key 取值均为 null 时返回 defaultValue。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map，允许为 null
     * @param key          候选键列表
     * @param defaultValue 当所有 key 均取不到值时的默认值
     * @return 第一个非 null 值的字符串表示，全为空时返回 defaultValue
     */
    public static <K, V> String getString(final Map<K, V> map, final List<K> key, final String defaultValue) {
        for (K k : key) {
            Object object = getObject(map, k);
            if (null != object) {
                return object.toString();
            }
        }
        return defaultValue;
    }

    /**
     * 从 Map 中依次尝试多个 key，返回第一个非 null 值的字符串表示。
     *
     * <p>内部通过 {@code toString} 转换取值结果。
     *
     * @param <K>  键类型
     * @param <V>  值类型
     * @param map  Map
     * @param keys 多个候选键
     * @return 第一个非 null 值的字符串表示，全为空时返回 null
     */
    public static <K, V> String getString(final Map<K, V> map, final K... keys) {
        for (K k : keys) {
            Object object = getObject(map, k);
            if (null != object) {
                return object.toString();
            }
        }
        return null;
    }

    /**
     * 从 Map 中获取字符串值，若 key 取不到值则尝试 key2，都为空时返回默认值。
     *
     * <p>内部通过 {@code toString} 转换取值结果。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          主键
     * @param key2         备用键
     * @param defaultValue 默认值
     * @return 取到的字符串值，都为空时返回 defaultValue
     */
    public static <K, V> String getString(final Map<K, V> map, final K key, final K key2, final String defaultValue) {
        if (null == map) {
            return defaultValue;
        }

        Object v = getObject(map, key);
        return null == v ? (v = getObject(map, key2)) == null ? defaultValue : v.toString() : v.toString();
    }

    /**
     * 从 Map 中获取字符串值，取不到时返回默认值。
     *
     * <p>内部通过 {@code toString} 转换取值结果。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的字符串值，若为 null 则返回 defaultValue
     */
    public static <K, V> String getString(final Map<K, V> map, final K key, final String defaultValue) {
        String answer = getString(map, key);
        if (answer == null) {
            answer = defaultValue;
        }
        return answer;
    }

    /**
     * 从 Map 中获取字符串数组，按分隔符拆分字符串值。
     *
     * <p>值若为字符串数组则直接返回；值为字符串或对象时调用 toString 后按分隔符拆分。
     *
     * @param <K>       键类型
     * @param <V>       值类型
     * @param map       Map
     * @param key       键
     * @param delimiter 分隔符
     * @return 拆分后的字符串数组，若值为 null 则返回空字符串数组
     */
    public static <K, V> String[] getStringArray(final Map<K, V> map, final K key, final String delimiter) {
        Object object = getObject(map, key);
        if (null == object) {
            return SYMBOL_EMPTY_STRING_ARRAY;
        }
        if (object instanceof String[]) {
            return Arrays.stream((String[]) object).filter(Objects::nonNull).toArray(String[]::new);
        }

        String string = object.toString();
        if(string.startsWith("[") && string.endsWith("]")) {
            string = string.substring(1, string.length() - 1);
        }
        return Splitter.on(",").omitEmptyStrings().trimResults().splitToList(string).toArray(new String[0]);
    }

    /**
     * 从 Map 中获取字符串数组，取不到时返回默认值。
     *
     * <p>值若为字符串数组则直接返回；值为字符串时按逗号拆分。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的字符串数组，若值为 null 则返回 defaultValue
     */
    public static <K, V> String[] getStringArray(final Map<K, V> map, final K key, final String[] defaultValue) {
        Object object = getObject(map, key);
        if (null == object) {
            return defaultValue;
        }
        if (object instanceof String[]) {
            return (String[]) object;
        }

        String string = object.toString();
        return string.split(",");
    }

/**
     * 从 Map 中获取字符串数组，默认使用逗号（{@code ,}）作为分隔符拆分字符串值。
     *
     * <p>内部调用 {@link #getStringArray(Map, Object, String)} 并以逗号为分隔符。
     * 值若为字符串数组则直接返回；值为字符串或对象时调用 toString 后按逗号拆分。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 拆分后的字符串数组，若值为 null 则返回空字符串数组
     */
    public static <K, V> String[] getStringArray(final Map<K, V> map, final K key) {
        return getStringArray(map, key, SYMBOL_COMMA);
    }

/**
     * 从 Map 中获取 Date 值。
     *
     * <p>值若为 Date 则直接返回；若为 Long 则按时间戳构造；若为字符串则按默认格式解析。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 Date，若值不存在或无法转换则返回 null
     */
    public static <K, V> Date getDate(final Map<K, V> map, final K key) {
        Object answer = getObject(map, key);
        if (null == answer) {
            return null;
        }
        if (answer instanceof Date) {
            return (Date) answer;
        }
        if (answer instanceof Long) {
            return new Date((Long) answer);
        }

        if (answer instanceof String) {
            DateFormat dateFormat = new SimpleDateFormat();
            try {
                return dateFormat.parse((String) answer);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从 Map 中获取 Double 值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Double。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 Double，若 Map 为 null 或值不存在则返回 null
     */
    public static <K, V> Double getDouble(final Map<K, V> map, final K key) {
        Number answer = getNumber(map, key);
        if (answer == null) {
            return null;
        } else if (answer instanceof Double) {
            return (Double) answer;
        }
        return answer.doubleValue();
    }

    /**
     * 从 Map 中获取 Double 值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Double。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 Double，若为 null 则返回 defaultValue
     */
    public static <K, V> Double getDouble(final Map<K, V> map, final K key, final Double defaultValue) {
        Double aDouble = getDouble(map, key);
        return null == aDouble ? defaultValue : aDouble;
    }

    /**
     * 从 Map 中获取 Double 值，若 key 取不到则尝试 key2，都为空时返回默认值。
     *
     * <p>内部通过 {@link #getDouble(Map, Object)} 获取 Double。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          主键
     * @param key2         备用键
     * @param defaultValue 默认值
     * @return 取到的 Double，都为空时返回 defaultValue
     */
    public static <K, V> Double getDouble(final Map<K, V> map, final K key, final K key2, final Double defaultValue) {
        if (null == map) {
            return defaultValue;
        }
        Double aDouble = getDouble(map, key);
        return null == aDouble ? (aDouble = getDouble(map, key2)) == null ? defaultValue : aDouble : aDouble;
    }

    /**
     * 从 Map 中获取 double 基本类型值，取不到时返回 0。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 double。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 double 值，若为 null 则返回 0
     */
    public static <K, V> double getDoubleValue(final Map<K, V> map, final K key) {
        Double aDouble = getDouble(map, key);
        return null == aDouble ? 0D : aDouble;
    }

    /**
     * 从 Map 中获取 double 基本类型值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 double。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 double 值，若为 null 则返回 defaultValue
     */
    public static <K, V> double getDoubleValue(final Map<K, V> map, final K key, final double defaultValue) {
        Double aDouble = getDouble(map, key);
        return null == aDouble ? defaultValue : aDouble;
    }


/**
     * 从 Map 中获取 int 基本类型值，取不到时返回 0。
     *
     * <p>内部通过 {@link #getInteger(Map, Object)} 获取 Integer，再自动拆箱为 int。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 int 值，若为 null 则返回 0
     */
    public static <K, V> int getIntValue(final Map<K, V> map, final K key) {
        Integer integer = getInteger(map, key);
        return null == integer ? 0 : integer;
    }

    /**
     * 从 Map 中获取 int 基本类型值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getInteger(Map, Object)} 获取 Integer，再自动拆箱为 int。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 当取不到值时的默认值
     * @return 取到的 int 值，若为 null 则返回 defaultValue
     */
    public static <K, V> int getIntValue(final Map<K, V> map, final K key, final int defaultValue) {
        Integer integer = getInteger(map, key);
        return null == integer ? defaultValue : integer;
    }

    /**
     * 从 Map 中获取 Integer 值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Integer。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 Integer，若为 null 则返回 null
     */
    public static <K, V> Integer getInteger(final Map<K, V> map, final K key) {
        Number answer = getNumber(map, key);
        if (answer == null) {
            return null;
        } else if (answer instanceof Integer) {
            return (Integer) answer;
        }
        return answer.intValue();
    }

    /**
     * 从 Map 中获取 Integer 值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Integer。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 Integer，若为 null 则返回 defaultValue
     */
    public static <K, V> Integer getInteger(final Map<K, V> map, final K key, final Integer defaultValue) {
        Integer integer = getInteger(map, key);
        return null == integer ? defaultValue : integer;
    }


/**
     * 从 Map 中依次尝试多个 key 获取 Integer 值，全为空时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Integer。
     *
     * @param <V>          值类型
     * @param map          Map
     * @param defaultValue 默认值
     * @param keys         多个候选键
     * @return 第一个非 null 的 Integer 值，全为空时返回 defaultValue
     */
    public static <V> Integer multiInteger(final Map<String, V> map, final Integer defaultValue, String... keys) {
        for (String key : keys) {
            Integer integer = getInteger(map, key);
            if (null != integer) {
                return integer;
            }
        }
        return defaultValue;
    }

    /**
     * 从 Map 中获取 List 值，值必须为 List 类型否则返回 null。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param <E> List 元素类型
     * @param map Map
     * @param key 键
     * @return 取到的 List，若 Map 为 null、值为 null 或类型不匹配则返回 null
     */
    public static <K, V, E> List<E> getList(final Map<K, V> map, final K key) {
        if (map == null) {
            return null;
        }
        Object object = map.get(key);
        if (object == null) {
            return null;
        }
        if (object instanceof List) {
            return (List<E>) object;
        }
        return null;
    }

    /**
     * 从 Map 中获取 List 值，取不到时返回默认值。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param <E>          List 元素类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 List，若为 null 则返回 defaultValue
     */
    public static <K, V, E> List<E> getList(final Map<K, V> map, final K key, final List<E> defaultValue) {
        List<E> list = getList(map, key);
        return list != null ? list : defaultValue;
    }

    /**
     * 从 Map 中获取内嵌的子 Map。
     *
     * @param <K>  外层 Map 的键类型
     * @param <V>  外层 Map 的值类型
     * @param <MK> 内嵌 Map 的键类型
     * @param <MV> 内嵌 Map 的值类型
     * @param map  Map
     * @param key  键
     * @return 取到的内嵌 Map，若值不存在或类型不匹配则返回 null
     */
    public static <K, V, MK, MV> Map<MK, MV> getMap(final Map<K, V> map, final K key) {
        if (map == null) {
            return null;
        }
        Object object = map.get(key);
        if (object == null) {
            return null;
        }
        if (object instanceof Map) {
            return (Map<MK, MV>) object;
        }
        return null;
    }

    /**
     * 从 Map 中获取 long 基本类型值，取不到时返回 0。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 long。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 long 值，若为 null 则返回 0
     */
    public static <K, V> long getLongValue(final Map<K, V> map, final K key) {
        Long value = getLong(map, key);
        return null == value ? 0 : value;
    }

    /**
     * 从 Map 中获取 long 基本类型值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 long。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 long 值，若为 null 则返回 defaultValue
     */
    public static <K, V> long getLongValue(final Map<K, V> map, final K key, final long defaultValue) {
        Long aLong = getLong(map, key);
        return null == aLong ? defaultValue : aLong;
    }

    /**
     * 从 Map 中获取 Long 值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Long。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 Long，若为 null 则返回 null
     */
    public static <K, V> Long getLong(final Map<K, V> map, final K key) {
        Number answer = getNumber(map, key);
        if (answer == null) {
            return null;
        } else if (answer instanceof Long) {
            return (Long) answer;
        }
        return answer.longValue();
    }

    /**
     * 从 Map 中获取 Long 值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Long。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 Long，若为 null 则返回 defaultValue
     */
    public static <K, V> Long getLong(final Map<K, V> map, final K key, final Long defaultValue) {
        Long aLong = getLong(map, key);
        return null == aLong ? defaultValue : aLong;
    }

    /**
     * 从 Map 中获取 Boolean 值。
     *
     * <p>值可为 Boolean、字符串或数字（非零视为 true）。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 Boolean，若值不存在或无法转换则返回 null
     */
    public static <K, V> Boolean getBoolean(final Map<K, V> map, final K key) {
        Object answer = getObject(map, key);
        if (answer != null) {
            if (answer instanceof Boolean) {
                return (Boolean) answer;

            } else if (answer instanceof String) {
                return Boolean.valueOf((String) answer);

            } else if (answer instanceof Number n) {
                return (n.intValue() != 0) ? Boolean.TRUE : Boolean.FALSE;
            }
        }
        return null;
    }

    /**
     * 从 Map 中获取 Boolean 值，取不到时返回默认值。
     *
     * <p>值可为 Boolean、字符串或数字（非零视为 true）。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 Boolean，若为 null 则返回 defaultValue
     */
    public static <K, V> Boolean getBoolean(final Map<K, V> map, final K key, final Boolean defaultValue) {
        Boolean aBoolean = getBoolean(map, key);
        return null == aBoolean ? defaultValue : aBoolean;
    }

/**
     * 从 Map 中获取 Byte 值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Byte。
     * 若 Number 已是 Byte 类型则直接返回，否则调用 {@code byteValue()} 转换。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 Byte，若 Map 为 null、值不存在或无法转换则返回 null
     */
    public static <K, V> Byte getByte(final Map<K, V> map, final K key) {
        Number answer = getNumber(map, key);
        if (answer == null) {
            return null;
        } else if (answer instanceof Byte) {
            return (Byte) answer;
        }
        return answer.byteValue();
    }

    /**
     * 从 Map 中获取 Byte 值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Byte。
     * 若 Number 已是 Byte 类型则直接返回，否则调用 {@code byteValue()} 转换。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 当取不到 Byte 时的默认值
     * @return 取到的 Byte，若为 null 则返回 defaultValue
     */
    public static <K, V> Byte getByte(final Map<K, V> map, final K key, final Byte defaultValue) {
        Byte aByte = getByte(map, key);
        return null == aByte ? defaultValue : aByte;
    }

    /**
     * 从 Map 中获取 byte 基本类型值，取不到时返回 0。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 byte。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 byte 值，若为 null 则返回 0
     */
    public static <K, V> byte getByteValue(final Map<K, V> map, final K key) {
        Byte aByte = getByte(map, key);
        return null == aByte ? (byte) 0 : aByte;
    }

    /**
     * 从 Map 中获取 byte 基本类型值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 byte。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 byte 值，若为 null 则返回 defaultValue
     */
    public static <K, V> byte getByteValue(final Map<K, V> map, final K key, final byte defaultValue) {
        Byte aByte = getByte(map, key);
        return null == aByte ? defaultValue : aByte;
    }

    /**
     * 从 Map 中获取 Float 值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Float。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 Float，若为 null 则返回 null
     */
    public static <K, V> Float getFloat(final Map<K, V> map, final K key) {
        Number answer = getNumber(map, key);
        if (answer == null) {
            return null;
        } else if (answer instanceof Float) {
            return (Float) answer;
        }
        return answer.floatValue();
    }

    /**
     * 从 Map 中获取 Float 值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Float。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 Float，若为 null 则返回 defaultValue
     */
    public static <K, V> Float getFloat(final Map<K, V> map, final K key, final Float defaultValue) {
        Float aFloat = getFloat(map, key);
        return null == aFloat ? defaultValue : aFloat;
    }

    /**
     * 从 Map 中获取 Float 值，若 key 取不到则尝试 key2，都为空时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Float。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          主键
     * @param key2         备用键
     * @param defaultValue 默认值
     * @return 取到的 Float，都为空时返回 defaultValue
     */
    public static <K, V> Float getFloat(final Map<K, V> map, final K key, final K key2, final Float defaultValue) {
        Float aFloat = getFloat(map, key);
        return null == aFloat ? (aFloat = getFloat(map, key2)) == null ? defaultValue : aFloat : aFloat;
    }

    /**
     * 从 Map 中获取 float 基本类型值，取不到时返回 0。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 float。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 float 值，若为 null 则返回 0
     */
    public static <K, V> float getFloatValue(final Map<K, V> map, final K key) {
        Float aFloat = getFloat(map, key);
        return null == aFloat ? 0f : aFloat;
    }

/**
     * 从 Map 中获取 float 基本类型值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 float。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 float 值，若为 null 则返回 defaultValue
     */
    public static <K, V> float getFloatValue(final Map<K, V> map, final K key, final float defaultValue) {
        Float aFloat = getFloat(map, key);
        return null == aFloat ? defaultValue : aFloat;
    }

/**
     * 从 Map 中获取 Short 值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Short。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 Short，若为 null 则返回 null
     */
    public static <K, V> Short getShort(final Map<K, V> map, final K key) {
        Number answer = getNumber(map, key);
        if (answer == null) {
            return null;
        } else if (answer instanceof Short) {
            return (Short) answer;
        }
        return answer.shortValue();
    }

/**
     * 从 Map 中获取 Short 值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Short。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 Short，若为 null 则返回 defaultValue
     */
    public static <K, V> Short getShort(final Map<K, V> map, final K key, final Short defaultValue) {
        Short aShort = getShort(map, key);
        return null == aShort ? defaultValue : aShort;
    }

    /**
     * 从 Map 中获取 short 基本类型值，取不到时返回 0。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 short。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 short 值，若为 null 则返回 0
     */
    public static <K, V> short getShortValue(final Map<K, V> map, final K key) {
        Short aShort = getShort(map, key);
        return null == aShort ? 0 : aShort;
    }

    /**
     * 从 Map 中获取 short 基本类型值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 short。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          Map
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 short 值，若为 null 则返回 defaultValue
     */
    public static <K, V> short getShortValue(final Map<K, V> map, final K key, final short defaultValue) {
        Short aShort = getShort(map, key);
        return null == aShort ? defaultValue : aShort;
    }


    /**
     * 从 Map 中获取 File 值。
     *
     * <p>值若为 File 则直接返回，否则通过转换器转为 File。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 取到的 File，若值为 null 则返回 null
     */
    public static <K, V> File getFile(final Map<K, V> map, final K key) {
        Object object = getObject(map, key);
        if (null == object) {
            return null;
        }
        if (object instanceof File) {
            return (File) object;
        }
        return Converter.convertIfNecessary(object, File.class);
    }

    /**
     * 若 key 在 Map 中不存在，则调用 function 计算值并放入 Map，返回最终值。
     *
     * @param <K>      键类型
     * @param <V>      值类型
     * @param map      Map
     * @param key      键
     * @param function 计算值的函数
     * @return Map 中最终存在的值
     */
    public static <K, V> V getComputeIfFunction(Map<K, V> map, K key, Function<K, V> function) {
        if (map == null) {
            return null;
        }
        V v = map.get(key);
        if (null == v && null != function) {
            v = map.put(key, function.apply(key));
        }
        v = map.get(key);
        return v;
    }

    /**
     * 将键值对字符串按指定字符分隔符解析为 Map。
     *
     * <p>委托 {@link #asMap(String, String, String)} 实现，内部将字符转为字符串分隔符。
     * 常用于解析 URL 查询字符串（{@code "a=1&b=2"}），值为空时放 null，
     * 值会经过 UTF-8 URL 解码，key 相同的值会合并为列表。
     *
     * @param value            键值对字符串，允许为空（返回空 Map）
     * @param valueSeparator   键值对之间的分隔符（如 {@code '&'}）
     * @param keyValueSeparator 键与值之间的分隔符（如 {@code '='}）
     * @return 解析后的 Map，value 为空时返回空 Map
     */
    public static Map<String, String> asMap(String value, char valueSeparator, char keyValueSeparator) {
        return asMap(value, String.valueOf(valueSeparator), String.valueOf(keyValueSeparator));
    }

    /**
     * 将键值对字符串按指定字符串分隔符解析为 Map。
     *
     * @param value            键值对字符串
     * @param valueSeparator   键值对之间的分隔符
     * @param keyValueSeparator 键与值之间的分隔符
     * @return 解析后的不可变 Map
     */
    public static Map<String, String> asMap(String value, String valueSeparator, String keyValueSeparator) {
        if (StringUtils.isEmpty(value)) {
            return Collections.emptyMap();
        }

        Map<String, Object> source = new HashMap<>(DEFAULT_INITIAL_CAPACITY);
        String[] split;
        if (StringUtils.isNotEmpty(valueSeparator)) {
            split = value.split(valueSeparator);
        } else {
            split = new String[]{value};
        }

        for (String item : split) {
            String[] strings = item.split(keyValueSeparator, 2);
            if (strings.length == 0) {
                continue;
            }
            String mapKey;
            String mapValue = null;
            if (strings.length == 1) {
                mapKey = strings[0].trim();
            } else {
                mapKey = strings[0].trim();
                mapValue = strings[1].trim();
            }

            convertToList(source, mapKey, null == mapValue ? null : URLDecoder.decode(mapValue, StandardCharsets.UTF_8));
        }
        Map<String, String> result = new HashMap<>(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value1 = entry.getValue();
            result.put(entry.getKey(), value1 instanceof Collection && ((Collection<?>) value1).size() == 1 ? CollectionUtils.findFirst((Collection) value1).toString() : value1.toString());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 将键值对按需转换为列表形式存入目标 Map。
     *
     * @param target 目标 Map
     * @param key    键
     * @param value  值，若为 null 则不做任何操作
     */
    private static void convertToList(Map<String, Object> target, String key, Object value) {
        Object computeIfAbsent = target.computeIfAbsent(key, (Function<String, List<Object>>) input -> new ArrayList());
        if (null == value) {
            return;
        }

        if (computeIfAbsent instanceof List) {
            ((List) computeIfAbsent).add(value);
        } else {
            List<Object> newValue = new ArrayList();
            newValue.add(computeIfAbsent);
            newValue.add(value);
            target.put(key, newValue);
        }
    }


    /**
     * 获取 Map 中的第一个键值对。
     *
     * @param <K>   键类型
     * @param <V>   值类型
     * @param kvMap Map
     * @return 第一个 Map.Entry，若 Map 为空则返回 null
     */
    public static <K, V> Map.Entry<K, V> getFirst(final Map<K, V> kvMap) {
        if (isEmpty(kvMap)) {
            return null;
        }
        return kvMap.entrySet().iterator().next();
    }

    /**
     * 获取 Map 中第一个键值对的值。
     *
     * @param <K>   键类型
     * @param <V>   值类型
     * @param kvMap Map
     * @return 第一个键值对的值，若 Map 为空则返回 null
     */
    public static <K, V> V getFirstValue(final Map<K, V> kvMap) {
        if (isEmpty(kvMap)) {
            return null;
        }
        return kvMap.entrySet().iterator().next().getValue();
    }


    /**
     * 将 Map 转为 Properties 对象。
     *
     * @param map Map
     * @return 对应的 Properties，null 值会被跳过
     */
    public static Properties asProp(Map<?, ?> map) {
        Properties properties = new Properties();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (null == entry.getValue()) {
                continue;
            }

            properties.put(entry.getKey(), entry.getValue());
        }

        return properties;
    }

    /**
     * 将对象转为 Properties，对象可为 Map、POJO 或数组。
     *
     * @param v 待转换的对象，数组按偶数对键值交替处理
     * @return 对应的 Properties
     */
    public static Properties asProp(Object v) {
        if (null == v) {
            return EMPTY_PROPERTIES;
        }

        Class<?> aClass = v.getClass();
        if (aClass.isArray()) {
            Properties properties = new Properties();
            int length = Array.getLength(v);
            if (length % 2 != 0) {
                length -= 1;
            }
            for (int i = 0; i < length; i += 2) {
                Object key = Array.get(v, i);
                Object value = Array.get(v, i + 1);
                if (null == value || null == key) {
                    continue;
                }
                properties.put(key, value);

            }
            return properties;
        }

        return asProp(beanToMap(v));
    }

    /**
     * 将 Properties 转为 Map。
     *
     * @param properties Properties 对象
     * @return 对应的 Map
     */
    public static Map<String, Object> asMap(Properties properties) {
        Map<String, Object> rs = new HashMap<>(properties.size());
        properties.forEach((k, v) -> {
            rs.put(k.toString(), v);
        });
        return rs;
    }

    /**
     * 为 Map 的所有键添加前缀。
     *
     * @param <K>   键类型
     * @param <V>   值类型
     * @param pre   要添加的前缀
     * @param kvMap Map
     * @return 键添加前缀后的新 Map
     */
    public static <K, V> Map<String, V> appendPre(String pre, Map<K, V> kvMap) {
        Map<String, V> tmp = new HashMap<>(kvMap.size());
        for (Map.Entry<K, V> entry : kvMap.entrySet()) {
            tmp.put(pre + entry.getKey().toString(), entry.getValue());
        }

        return tmp;
    }

    /**
     * 返回非空的 Map，若 source 为空则返回默认值。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param source       源 Map
     * @param defaultValue 默认 Map
     * @return source 如果不为空，否则返回 defaultValue
     */
    public static <K, V> Map<K, V> defaultValue(Map<K, V> source, Map<K, V> defaultValue) {
        return isEmpty(source) ? defaultValue : source;
    }

    /**
     * 以 source 为基础，将 defaultValue 中不存在的键值对补充到 source 中。
     *
     * <p>不会修改 source 和 defaultValue，返回一个新的 LinkedHashMap。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param source       源 Map
     * @param defaultValue 默认值 Map
     * @return 合并后的新 Map
     */
    public static <K, V> Map<K, V> compute(Map<K, V> source, Map<K, V> defaultValue) {
        if (isEmpty(source)) {
            return defaultValue;
        }

        Map<K, V> temp = new LinkedHashMap<>(source);
        for (Map.Entry<K, V> entry : defaultValue.entrySet()) {
            if (temp.containsKey(entry.getKey())) {
                continue;
            }

            temp.put(entry.getKey(), entry.getValue());
        }

        return temp;
    }

    /**
     * 从 Map 中依次获取多个键的值，非 null 的值传递给 consumer 处理。
     *
     * @param <V>      值类型
     * @param map      Map
     * @param consumer 处理非 null 值的回调
     * @param name     要查询的键列表
     */
    public static <V> void filterNone(Map<String, V> map, Consumer<V> consumer, String... name) {
        for (String s : name) {
            Object object = getObject(map, s);
            if (null == object) {
                continue;
            }

            consumer.accept((V) object);
        }
    }

    /**
     * 将 Map 的键转为 String、值保持原类型，返回新 Map。
     *
     * @param source 源 Map
     * @return 键为 String 的新 LinkedHashMap
     */
    public static Map<String, Object> asStringObjectMap(Map source) {
        Map<String, Object> tpl = new LinkedHashMap<>(source.size());
        source.forEach((k, v) -> {
            tpl.put(k.toString(), v);
        });
        return tpl;
    }

    /**
     * 将 Map 的键和值都转为 String，返回新 Map。
     *
     * @param source 源 Map
     * @return 键和值均为 String 的新 LinkedHashMap
     */
    public static Map<String, String> asStringMap(Map source) {
        Map<String, String> tpl = new LinkedHashMap<>(source.size());
        source.forEach((k, v) -> {
            tpl.put(k.toString(), v == null ? null : v.toString());
        });
        return tpl;
    }

    /**
     * 将 Properties 的键和值都转为 String，返回新 Map。
     *
     * @param source Properties 对象
     * @return 键和值均为 String 的新 LinkedHashMap
     */
    public static Map<String, String> asStringMap(Properties source) {
        Map<String, String> tpl = new LinkedHashMap<>(source.size());
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            tpl.put(entry.getKey().toString(), null == entry.getValue() ? null : entry.getValue().toString());
        }
        return tpl;
    }

    /**
     * 将列表转为索引 Map，元素的值为其在列表中的位置序号。
     *
     * @param <T>           元素类型
     * @param valuesInOrder 元素列表
     * @return 元素到索引的映射
     */
    public static <T> Map<T, Integer> indexMap(List<T> valuesInOrder) {
        Map<T, Integer> rs = new HashMap<>(valuesInOrder.size());
        int i = 0;
        for (T t : valuesInOrder) {
            rs.put(t, i++);
        }

        return rs;
    }

    /**
     * 返回提取 Map.Entry 键的 Function。
     *
     * @param <K> 键类型
     * @return Map.Entry::getKey 函数
     */
    public static <K extends Object> Function<Map.Entry<K, ?>, K> keyFunction() {
        return Map.Entry::getKey;
    }

    /**
     * 返回提取 Map.Entry 值的 Function。
     *
     * @param <V> 值类型
     * @return Map.Entry::getValue 函数
     */
    public static <V extends Object> Function<Map.Entry<?, V>, V> valueFunction() {
        return Map.Entry::getValue;
    }


    /**
     * 创建一个默认初始容量的新 HashMap。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新的 HashMap
     */
    public static <K, V> Map<K, V> newHashMap() {
        return new HashMap<>(DEFAULT_INITIAL_CAPACITY);
    }

    /**
     * 创建包含单个键值对的 HashMap。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param k   键
     * @param v   值
     * @return 包含指定键值对的新 HashMap
     */
    public static <K, V> Map<K, V> ofHashMap(K k, V v) {
        Map<K, V> rs = new HashMap<>(1 << 4);
        rs.put(k, v);
        return rs;
    }

    /**
     * 创建包含单个键值对的 LinkedHashMap。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param k   键
     * @param v   值
     * @return 包含指定键值对的新 LinkedHashMap
     */
    public static <K, V> Map<K, V> ofLinkedMap(K k, V v) {
        Map<K, V> rs = new LinkedHashMap<>();
        rs.put(k, v);
        return rs;
    }

    /**
     * 创建包含单个键值对的 MultiValueMap（支持一对多映射）。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param k   键
     * @param v   值
     * @return 新的 MultiLinkedValueMap
     */
    public static <K, V> MultiValueMap<K, V> ofMultiMap(K k, V v) {
        MultiValueMap<K, V> rs = new MultiLinkedValueMap<>();
        rs.add(k, v);
        return rs;
    }

    /**
     * 创建一个空的 MultiValueMap（支持一对多映射）。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新的空 MultiLinkedValueMap
     */
    public static <K, V> MultiValueMap<K, V> ofMultiMap() {
        return new MultiLinkedValueMap<>();
    }

    /**
     * 若 key 不存在则通过 function 计算并放入值，返回最终值。
     *
     * @param <K>      键类型
     * @param <V>      值类型
     * @param map      缓存 Map
     * @param key      键
     * @param function 计算函数
     * @return Map 中存在的值
     */
    public static <K, V> V computeIfAbsent(Map<K, V> map, K key, Function<K, V> function) {
        if (null == map) {
            return null;
        }

        V v = map.get(key);
        if (v == null) {
            v = function.apply(key);
            map.put(key, v);
        }

        return v;
    }

/**
     * 按字母顺序（ASCII）对 Map 的键进行排序，返回新 LinkedHashMap。
     *
     * @param <V>   值类型
     * @param param 待排序的 Map
     * @return 按键排序后的新 LinkedHashMap
     */
    public static <V> Map<String, V> sortKey(Map<String, V> param) {
        Map<String, V> rs = new LinkedHashMap<>();
        List<String> strings = new ArrayList<>(param.keySet());
        strings.sort(String::compareTo);
        for (String string : strings) {
            rs.put(string, param.get(string));
        }

        return rs;
    }

/**
     * 比较新旧两个 Map，返回新 Map 中值与旧 Map 不同的键值对。
     *
     * @param <K>     键类型
     * @param <V>     值类型
     * @param newData 新数据 Map
     * @param oldData 旧数据 Map
     * @return 值发生变化的键值对
     */
    public static <K, V> Map<K, V> removeSameData(Map<K, V> newData, Map<K, V> oldData) {
        Map<K, V> rs = new HashMap<>(newData.size());
        for (Map.Entry<K, V> entry : newData.entrySet()) {
            if (!oldData.containsKey(entry.getKey())) {
                continue;
            }
            V o = oldData.get(entry.getKey());
            if (!ObjectUtils.equals(o, entry.getValue())) {
                rs.put(entry.getKey(), entry.getValue());
            }
        }

        return rs;
    }

    /**
     * 判断 Map 中是否包含指定 key。
     *
     * @param arg Map
     * @param key 键
     * @return 若 Map 非空且包含 key 则返回 true
     */
    public static boolean hasKey(Map arg, String key) {
        return isNotEmpty(arg) && arg.containsKey(key);
    }

    /**
     * 从 Map 中获取配置值，支持驼峰和下划线命名自动转换查找。
     *
     * @param <K>  键类型
     * @param <V>  值类型
     * @param arg  Map
     * @param name 配置项名称
     * @return 取到的配置值
     */
    public static <K, V> V getConfig(Map<K, V> arg, String name) {
        V v = arg.get(name);
        if (null == v) {
            v = arg.get(CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, name));
        }

        if (null == v) {
            v = arg.get(CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, name));
        }


        return v;
    }

    /**
     * 从 Map 中获取字符串值并匹配为枚举。
     *
     * @param <K>      键类型
     * @param <V>      值类型
     * @param <T>      枚举类型
     * @param map      Map
     * @param name     键
     * @param enumType 枚举值数组
     * @return 匹配到的枚举值，若未匹配则返回 null
     */
    public static <K, V, T extends Enum<T>> T getEnum(Map<K, V> map, String name, T[] enumType) {
        String string = getString(map, name);
        for (T t : enumType) {
            if (t.name().equalsIgnoreCase(string)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 获取 Map 中指定索引位置的键值对。
     *
     * @param <K>    键类型
     * @param <V>    值类型
     * @param params Map
     * @param i      索引位置（0-based）
     * @return 指定位置的 Map.Entry，若超出范围则返回 null
     */
    public static <K, V> Map.Entry<K, V> getEntry(Map<K, V> params, int i) {
        int index = 0;
        for (Map.Entry<K, V> entry : params.entrySet()) {
            if (index++ == i) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 若 key 不存在则放入值，key、value 或 Map 任一为 null 时忽略。
     *
     * @param <K>   键类型
     * @param <V>   值类型
     * @param kvMap Map，允许为 null
     * @param name  键，允许为 null
     * @param value 值，允许为 null
     */
    public static <K, V> void putIfAbsent(Map<K, V> kvMap, K name, V value) {
        //                                  null         null                                       
        if (null == value || null == kvMap || null == name) {
            return;
        }
        //                                                                
        kvMap.putIfAbsent(name, value);
    }

    /**
     * 判断 Map 中指定 key 对应的值是否有效（非 null 且非空字符串）。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map Map
     * @param key 键
     * @return 若值非 null 且非空字符串则返回 true
     */
    public static <K, V> boolean containsValue(Map<K, V> map, K key) {
        if (isEmpty(map) || null == key) {
            return false;
        }

        if (!map.containsKey(key)) {
            return false;
        }
        Object value = map.get(key);

        if (null == value) {
            return false;
        }

        return !(value instanceof String) || StringUtils.isNotBlank((String) value);
    }

    /**
     * 判断 Map 是否非空且包含指定 key。
     *
     * @param <K>    键类型
     * @param <V>    值类型
     * @param source Map，允许为 null
     * @param name   键
     * @return 若 Map 非空且包含 key 则返回 true
     */
    public static <K, V> boolean containsKey(Map<K, V> source, K name) {
        //                                                 
        return !isEmpty(source) && source.containsKey(name);
    }

    /**
     * 从 Map 中安全获取值，Map 为空时返回 null。
     *
     * @param <K>    键类型
     * @param <V>    值类型
     * @param source Map
     * @param name   键
     * @return 取到的值，若 Map 为空则返回 null
     */
    public static <K, V> V get(Map<K, V> source, K name) {
        //                    name             
        return isEmpty(source) ? null : source.get(name);
    }

    /**
     * 通过类名字符串构造对象并放入 Map。
     *
     * @param <K>   键类型
     * @param <V>   值类型
     * @param map   Map
     * @param key   键
     * @param vType 类的全限定名
     * @param args  构造参数
     */
    public static <K, V> void put(Map<K, V> map, K key, String vType, Object... args) {
        if (null == map || null == key || StringUtils.isEmpty(vType)) {
            return;
        }

        Object object = ClassUtils.forObject(vType, args);
        if (null == object) {
            return;
        }

        map.put(key, (V) object);
    }

    /**
     * 通过 Class 对象构造实例并放入 Map。
     *
     * @param <K>   键类型
     * @param <V>   值类型
     * @param map   Map
     * @param key   键
     * @param vType 目标类型
     * @param args  构造参数
     */
    public static <K, V> void put(Map<K, V> map, K key, Class<V> vType, Object... args) {
        if (null == map || null == key || null == vType) {
            return;
        }

        Object object = ClassUtils.forObject(vType, args);
        if (null == object) {
            return;
        }

        map.put(key, (V) object);
    }

    /**
     * 按点号分隔的嵌套路径从 Map 中获取值，取不到时返回默认值。
     *
     * @param properties   Map
     * @param name         点号分隔的嵌套键路径（如 "a.b.c"）
     * @param defaultValue 默认值
     * @return 取到的值或默认值
     */
    public static Object getTreeOrDefault(Map properties, String name, Object defaultValue) {
        if (isEmpty(properties) || StringUtils.isEmpty(name)) {
            return defaultValue;
        }

        Object object = properties.get(name);
        if (null != object) {
            return ObjectUtils.defaultIfNull(object, defaultValue);
        }

        String[] split = name.split("\\.", 2);
        if (split.length <= 1) {
            return defaultValue;
        }

        Object object1 = properties.get(split[0]);
        if (null == object1 || !(object1 instanceof Map)) {
            return defaultValue;
        }

        return getTreeOrDefault((Map) object1, split[1], defaultValue);
    }

    /**
     * 将 Map 的每个键值对通过转换函数映射为列表。
     *
     * @param <T>         列表元素类型
     * @param beanOfTypes Map
     * @param function    转换函数，接收键和值，返回列表元素
     * @return 转换后的列表，若 Map 为空则返回空列表
     */
    public static <T> List<T> mapToList(Map<String, ?> beanOfTypes, BiFunction<String, Object, T> function) {
        if (isEmpty(beanOfTypes)) {
            return Collections.emptyList();
        }
        return beanOfTypes.entrySet().stream().map(entry -> function.apply(entry.getKey(), entry.getValue())).collect(Collectors.toList());
    }

    /**
     * 将嵌套 Map 展开为扁平化的点号键格式。
     *
     * <p>嵌套结构被展开为点号分隔的扁平键：
     * <pre>{@code {"user": {"name": "张三", "age": 25}} → {"user.name": "张三", "user.age": 25}}</pre>
     *
     * @param map 嵌套 Map
     * @return 扁平化的 Map
     */
    public static Map<String, Object> flattenToProperties(Map<String, Object> map) {
        return flattenToProperties(map, "");
    }

    /**
     * 将嵌套 Map 按 Properties 格式展开为扁平化的点号键格式。
     *
     * <p>
     * 嵌套结构被展开为点号分隔的扁平键：
     * {@code {"user": {"name": "张三", "age": 25}} → {"user.name": "张三", "user.age": 25}}
     *
     * @param properties Properties 对象
     * @return 扁平化的 Map
     */
    public static Map<String, Object> flattenToProperties(Properties properties) {
        return flattenToProperties(new HashMap(properties), "");
    }

    /**
     * 将嵌套 Map 按 Properties 格式展开为扁平化的点号键格式。
     *
     * <p>递归遍历 Map，将嵌套键拼接为点号分隔的扁平键。
     *
     * @param map    嵌套 Map
     * @param prefix 键前缀
     * @return 扁平化的 Map
     */
    public static Map<String, Object> flattenToProperties(Map<String, Object> map, String prefix) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (map == null) {
            return result;
        }

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            //                   
            String newKey = StringUtils.isEmpty(prefix) ? key : prefix + "." + key;

            if (value instanceof Map) {
                //                   Map
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                result.putAll(flattenToProperties(nestedMap, newKey));
            } else if (value instanceof Collection<?> collection) {
                //                   
                int index = 0;
                for (Object item : collection) {
                    String indexKey = newKey + "[" + index + "]";
                    if (item instanceof Map) {
                        Map<String, Object> itemMap = (Map<String, Object>) item;
                        result.putAll(flattenToProperties(itemMap, indexKey));
                    } else {
                        result.put(indexKey, item);
                    }
                    index++;
                }
            } else if (value != null && value.getClass().isArray()) {
                //                   
                Object[] array = (Object[]) value;
                for (int i = 0; i < array.length; i++) {
                    String indexKey = newKey + "[" + i + "]";
                    Object item = array[i];
                    if (item instanceof Map) {
                        Map<String, Object> itemMap = (Map<String, Object>) item;
                        result.putAll(flattenToProperties(itemMap, indexKey));
                    } else {
                        result.put(indexKey, item);
                    }
                }
            } else {
                //                            
                result.put(newKey, value);
            }
        }

        return result;
    }

    /**
     * 将扁平化的点号键 Map 还原为嵌套 Map 结构。
     *
     * <p>
     * 点号分隔的扁平键被还原为多层嵌套：
     * {@code {"user.name": "张三", "user.age": 25} → {"user": {"name": "张三", "age": 25}}}
     *
     * @param flatMap 扁平化的 Map
     * @return 还原后的嵌套 Map
     */
    public static Map<String, Object> unflattenFromProperties(Map<String, Object> flatMap) {
        if (flatMap == null || flatMap.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> result = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : flatMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            setNestedValue(result, key, value);
        }

        return result;
    }

    /**
     * 设置嵌套 Map 的值，根据点号分隔的键路径逐层深入。
     *
     * @param map   目标 Map
     * @param key   点号分隔的键
     * @param value 要设置的值
     */
    private static void setNestedValue(Map<String, Object> map, String key, Object value) {
        if (key == null || key.isEmpty()) {
            return;
        }

        String[] parts = key.split("\\.");
        Map<String, Object> current = map;

        //                                              
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            current = processMapPart(current, part);
        }

        //                
        String finalKey = parts[parts.length - 1];
        setFinalValue(current, finalKey, value);
    }

    /**
     * 处理嵌套键路径中的一个键片段，支持普通键和 {@code [index]} 数组下标语法。
     *
     * <p>若键片段包含 {@code [index]} 格式（如 "items[0]"），则提取数组名和索引，
     * 在 current Map 中创建或获取对应索引位置的 Map 节点。
     * 若为普通键，则调用 {@link #ensureMapExists(Map, String)} 创建或获取下一层级的 Map。
     *
     * @param current 当前层级的 Map
     * @param part    键路径片段，可为普通键名或 "arrayName[index]" 格式
     * @return 下一层级的 Map 节点
     */
    private static Map<String, Object> processMapPart(Map<String, Object> current, String part) {
        //                          "items[0]"
        if (part.contains("[") && part.contains("]")) {
            String arrayName = part.substring(0, part.indexOf('['));
            String indexStr = part.substring(part.indexOf('[') + 1, part.indexOf(']'));

            try {
                int index = Integer.parseInt(indexStr);

                //                   
                if (!current.containsKey(arrayName)) {
                    current.put(arrayName, new ArrayList<>());
                }

                Object arrayObj = current.get(arrayName);
                if (!(arrayObj instanceof List)) {
                    arrayObj = new ArrayList<>();
                    current.put(arrayName, arrayObj);
                }

                List<Object> list = (List<Object>) arrayObj;

                //                   
                while (list.size() <= index) {
                    list.add(new LinkedHashMap<String, Object>());
                }

                //                      Map
                if (!(list.get(index) instanceof Map)) {
                    list.set(index, new LinkedHashMap<String, Object>());
                }

                return (Map<String, Object>) list.get(index);
            } catch (NumberFormatException e) {
                //                                                    
                return ensureMapExists(current, part);
            }
        } else {
            //             
            return ensureMapExists(current, part);
        }
    }

    /**
     * 在当前 Map 中确保指定 key 存在对应的子 Map 节点。
     *
     * <p>若 key 不存在则创建新的 LinkedHashMap 作为值；若 key 已存在但值不是 Map 类型，
     * 则覆盖为新的 LinkedHashMap。保证下一级始终为 Map 类型。
     *
     * @param current 当前层级的 Map
     * @param key     要确保存在的键
     * @return key 对应的子 Map 节点，保证非 null 且为 Map 类型
     */
    private static Map<String, Object> ensureMapExists(Map<String, Object> current, String key) {
        if (!current.containsKey(key)) {
            current.put(key, new LinkedHashMap<String, Object>());
        }

        Object nextLevel = current.get(key);
        if (!(nextLevel instanceof Map)) {
            nextLevel = new LinkedHashMap<String, Object>();
            current.put(key, nextLevel);
        }
        return (Map<String, Object>) nextLevel;
    }

    /**
     * 设置最终值到当前 Map 节点，支持数组下标语法。
     *
     * @param current  当前层级的 Map
     * @param finalKey 最终键名，可包含 [index] 数组下标
     * @param value    要设置的值
     */
    private static void setFinalValue(Map<String, Object> current, String finalKey, Object value) {
        if (finalKey.contains("[") && finalKey.contains("]")) {
            String arrayName = finalKey.substring(0, finalKey.indexOf('['));
            String indexStr = finalKey.substring(finalKey.indexOf('[') + 1, finalKey.indexOf(']'));

            try {
                int index = Integer.parseInt(indexStr);

                if (!current.containsKey(arrayName)) {
                    current.put(arrayName, new ArrayList<>());
                }

                Object arrayObj = current.get(arrayName);
                if (!(arrayObj instanceof List)) {
                    arrayObj = new ArrayList<>();
                    current.put(arrayName, arrayObj);
                }

                List<Object> list = (List<Object>) arrayObj;
                while (list.size() <= index) {
                    list.add(null);
                }
                list.set(index, value);
            } catch (NumberFormatException e) {
                current.put(finalKey, value);
            }
        } else {
            current.put(finalKey, value);
        }
    }

    /**
     * 清除 Map 中所有值为 null 的条目。
     *
     * <p>遍历 Map 并移除所有值为 null 的键值对，与 {@link Map#remove(Object)} 不同，
     * 此方法不会移除不存在的键。
     * <p>示例：输入 {"name": "张三", "age": null} → 输出 {"name": "张三"}
     *
     * @param doc 待清理的 Map，键为 String 类型
     * @param <V> Map 的值类型
     */
    public static <V> void clearNullValue(Map<String, V> doc) {
        doc.entrySet().removeIf(entry -> entry.getValue() == null);
    }


    /**
     * 从 Map 中获取字符串值，并将其中的分隔符从 oldSplit 替换为 newSplit。
     *
     * <p>先通过 {@link #getString(Map, Object)} 取值，若值为 null 则返回 null；
     * 否则使用 {@link Splitter#onPattern(String)} 按旧分隔符拆分字符串，
     * 再用 {@link Joiner#on(String)} 按新分隔符重新拼接。拆分时自动去除空白和空片段。
     *
     * @param map      Map
     * @param name     键
     * @param oldSplit 原始分隔符（正则表达式模式）
     * @param newSplit 替换后的分隔符
     * @return 分隔符替换后的字符串，若原始值为 null 则返回 null
     */
    public static String getStringSplitter(Map<String, Object> map, String name, String oldSplit, String newSplit) {
        String value = getString(map, name);
        if (value == null) {
            return null;
        }

        List<String> strings = Splitter.onPattern(oldSplit).trimResults().omitEmptyStrings().splitToList(value);
        return Joiner.on(newSplit).join(strings);

    }

    /**
     * 比较期望值与 Map 中指定 key 对应的字符串值是否相等。
     *
     * <p>若 expectation 为 null，则 Map 为 null、Map 中不含该 key 或 key 对应值为 null 时返回 true；
     * 否则通过 {@code equals} 比较 expectation 与 Map 中取出的字符串值。
     *
     * @param expectation 期望的字符串值，允许为 null
     * @param key         Map 中的键
     * @param item        待比较的 Map，允许为 null
     * @return 若期望值与 Map 值相等则返回 true，不相等返回 false
     */
    public static Boolean isEquals(String expectation, String key, Map<String, Object> item) {
        if (null == expectation) {
            return null == item || !item.containsKey(key) || item.get(key) == null;
        }

        return expectation.equals(item.get(key));
    }

    /**
     * 比较 int 期望值与 Map 中指定 key 对应的值是否相等。
     *
     * <p>若 Map 为 null 或不含该 key 则返回 null；否则通过
     * {@link Converter#convertIfNecessary(Object, Class)} 将值转为 Integer，
     * 转换失败时返回 false，转换成功后用 {@code ==} 比较。
     *
     * @param expectation 期望的 int 值
     * @param key         Map 中的键
     * @param item        待比较的 Map，允许为 null
     * @return 相等返回 true，不相等返回 false，key 不存在返回 null
     */
    public static Boolean isEquals(int expectation, String key, Map<String, Object> item) {
        if (item == null || !item.containsKey(key)) {
            return null;
        }

        Object object = item.get(key);
        Integer i = Converter.convertIfNecessary(object, Integer.class);
        if (i == null) {
            return false;
        }
        return i == expectation;
    }

    /**
     * 比较 double 期望值与 Map 中指定 key 对应的值是否相等。
     *
     * <p>若 Map 为 null 或不含该 key 则返回 null；否则通过
     * {@link Converter#convertIfNecessary(Object, Class)} 将值转为 Double，
     * 转换失败时返回 false，转换成功后用 {@code ==} 比较。
     *
     * @param expectation 期望的 double 值
     * @param key         Map 中的键
     * @param item        待比较的 Map，允许为 null
     * @return 相等返回 true，不相等返回 false，key 不存在返回 null
     */
    public static Boolean isEquals(double expectation, String key, Map<String, Object> item) {
        if (item == null || !item.containsKey(key)) {
            return null;
        }

        Object object = item.get(key);
        Double i = Converter.convertIfNecessary(object, Double.class);
        if (i == null) {
            return false;
        }
        return i == expectation;
    }

    /**
     * 从 Map 中安全获取 LocalDateTime 值。
     *
     * <p>若 Map 为 null 或不含指定 key 则返回 null，否则通过
     * {@link Converter#parseLocalDateTimeSafe(Object)} 将值转为 LocalDateTime。
     *
     * @param key  键
     * @param item Map，允许为 null
     * @return 取到的 LocalDateTime，若 Map 为空、key 不存在或转换失败则返回 null
     */
    public static LocalDateTime parseLocalDateTimeSafe(String key, Map<String, Object> item) {
        if (item == null || !item.containsKey(key)) {
            return null;
        }
        return Converter.parseLocalDateTimeSafe(item.get(key));
    }

    private static Map<String, Object> beanToMap(Object bean) {
        if (bean instanceof Map) {
            return (Map<String, Object>) bean;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            java.beans.BeanInfo beanInfo = java.beans.Introspector.getBeanInfo(bean.getClass());
            for (java.beans.PropertyDescriptor pd : beanInfo.getPropertyDescriptors()) {
                if (!"class".equals(pd.getName()) && pd.getReadMethod() != null) {
                    result.put(pd.getName(), pd.getReadMethod().invoke(bean));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}