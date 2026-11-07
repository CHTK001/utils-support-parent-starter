package com.chua.common.support.utils;

import com.chua.common.support.collection.MultiLinkedValueMap;
import com.chua.common.support.collection.MultiValueMap;
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.reflection.ReflectUtils;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import lombok.extern.slf4j.Slf4j;

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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_COMMA;
import static com.chua.common.support.constant.NumberConstant.NUMBER_3;
import static com.chua.common.support.constant.ValueConstant.EMPTY_PROPERTIES;
import static com.chua.common.support.constant.ValueConstant.SYMBOL_EMPTY_STRING_ARRAY;

/**
 * 映射 工具类，提供 映射 操作的核心工具方法。
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
 * @since 4.0.0
 */
@SuppressWarnings({"unchecked", "ALL"})
@Slf4j
public class MapUtils {

    /**
     * 映射工具。
     */
    private MapUtils() {
    }

    /**
     * 默认初始容量，哈希映射 默认的桶数量。
     */
    public static final int DEFAULT_INITIAL_CAPACITY = 16;
    /**
     * 默认负载因子，当 映射 中元素数量达到 大小 * 加载_FACTOR 时触发扩容。
     */
    public static final float DEFAULT_LOAD_FACTOR = 0.75f;
    /**
     * 2 的最大幂次阈值，用于 {@link #capacity(int)} 中判断是否需要使用精确容量计算。
     * 值为 {@code 1 << 30}（约 10.7 亿），超过此值时直接返回 Integer.最大_值。
     */
    private static final int MAX_POWER_OF_TWO = 1 << (Integer.SIZE - 2);

    /**
     * 将嵌套 映射 展开为扁平化的 映射 结构。
     *
     * <p>递归遍历 Map 及其嵌套的 Map 或 List，将嵌套键拼接为点号分隔的扁平键，
     * 列表 元素以 {@code [index]} 形式标记位置。
     *
     * @param map 待展开的嵌套 映射，允许为 空
     * @return 扁平化后的 映射，若 映射 为 空 则返回空 映射
     */
    public static Map<String, Object> flattenMap(Map<String, Object> map) {
        if (null == map) {
            return Collections.emptyMap();
        }
        Map<String, Object> flattenedMap = Wraps.map(map.size());
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
 * 判断 属性 是否为空。
 *
 * <p>Properties 为 null 或无任何键值对时返回 true。
 *
 * @param properties 属性 对象，允许为 空
 * @return 若 属性 为 空 或为空则返回 true
 */
    public static boolean isEmpty(Properties properties) {
        return (properties == null || properties.isEmpty());
    }


    /**
     * 判断 Dictionary 是否为空。
     *
     * <p>Dictionary 为 null 或无任何键值对时返回 true。
     *
     * @param dictionary Dictionary 对象，允许为 空
     * @return 若 dictionary 为 空 或为空则返回 true
     */
    public static boolean isEmpty(Dictionary<?, ?> dictionary) {
        return (dictionary == null || dictionary.isEmpty());
    }


    /**
     * 判断 映射 是否非空。
     *
     * <p>Map 不为 null 且至少包含一个键值对时返回 true。
     *
     * @param map 映射 对象，允许为 空
     * @return 若 映射 非 空 且非空则返回 true
     */
    public static boolean isNotEmpty(Map map) {
        return !isEmpty(map);
    }

    /**
     * 从 映射 中获取 数字 值，取不到时返回默认值。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 当取不到 数字 时的默认值
     * @return 取到的 数字，若为 空 则返回 默认值
     */
    public static <K, V> Number getNumber(final Map<K, V> map, K key, Number defaultValue) {
        return null == answer ? defaultValue : answer;
    }

    /**
     * 从 映射 中获取 数字 值。
     *
     * <p>取出的值若为 Number 类型则直接返回；若为字符串则尝试通过
     * {@link NumberFormat} 解析为 Number。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map 映射
     * @param key 键
     * @return 取到的 数字，若不存在或无法转换则返回 空
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
                    log.debug("[MapUtils] 数字文本解析失败: {}", e.getMessage());
                }
        return null;
    }

    /**
     * 从 映射 中获取原始对象值。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map 映射
     * @param key 键
     * @return Map 中 键 对应的值，若 映射 为 空 或 键 为 空 则返回 空
     */
    public static <K, V> V getObject(final Map<K, V> map, final K key) {
        return null != key && map != null ? map.get(key) : null;
    }

    /**
     * 从 映射 中依次尝试多个 键，返回第一个非 空 的值。
     *
     * @param <K>  键类型
     * @param <V>  值类型
     * @param map  映射
     * @param keys 多个候选键
     * @return 第一个非 空 的值，若所有 键 对应的值均为 空 则返回 空
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
     * 从 映射 中获取原始对象值，取不到时返回默认值。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return Map 中 键 对应的值，若为 空 则返回 默认值
     */
    public static <K, V> Object getObject(final Map<K, V> map, final K key, final Object defaultValue) {
        Object object = getObject(map, key);
        if (object != null) {
            return object;
        }
        return defaultValue;
    }


    /**
     * 从 映射 中获取指定类型的值，类型不匹配时返回 空。
     *
     * @param <K>  键类型
     * @param <V>  值类型
     * @param <R>  期望的返回值类型
     * @param map  映射
     * @param key  键
     * @param type 期望的类型
     * @return 类型匹配的对象，不匹配或为 空 时返回 空
     */
    public static <K, V, R> R getType(final Map<K, V> map, final K key, final Class<R> type) {
        Object object = getObject(map, key);
        return null != object && type.isAssignableFrom(object.getClass()) ? (R) object : null;
    }

    /**
     * 从对象中获取字符串值，对象可以是 映射 或可转为 映射 的 POJO。
     *
     * <p>若对象为 Map 则直接取值，否则先通过 JavaBean 内省机制将对象转为 Map 再取值，
     * 值通过 {@code toString} 转为字符串。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map 映射 或可转为 映射 的 POJO 对象，允许为 空
     * @param key 键
     * @return Map 中 键 对应的字符串值，若 映射 为 空 或值不存在则返回 空
     */
    public static <K, V> String getString(final Object map, final K key) {
        if (map instanceof Map) {
            return getString((Map<?, ?>) map, key);
        }
        return getString(beanToMap(map), key);
    }

    /**
     * 从 映射 中获取字符串值，值通过 {@code toString} 转为字符串。
     *
     * <p>Map 为 null 或 key 对应值为 null 时返回 null。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map 映射，允许为 空
     * @param key 键
     * @return Map 中 键 对应值的字符串表示，若 映射 为 空 或值为 空 则返回 空
     */
    public static <K, V> String getString(final Map<K, V> map, final K key) {
        Object object = getObject(map, key);
        if (null == object) {
            return null;
        }
        return object.toString();
    }

    /**
     * 从 映射 中依次尝试多个 键（列表 形式），返回第一个非 空 值的字符串表示。
     *
     * <p>遍历 key 列表，对每个 key 通过 {@link #getObject(Map, Object)} 取值，
     * 第一个非 空 值通过 {@code toString} 转为字符串并返回。
     * 全部 键 取值均为 空 时返回 默认值。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射，允许为 空
     * @param key          候选键列表
     * @param defaultValue 当所有 键 均取不到值时的默认值
     * @return 第一个非 空 值的字符串表示，全为空时返回 默认值
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
     * 从 映射 中依次尝试多个 键，返回第一个非 空 值的字符串表示。
     *
     * <p>内部通过 {@code toString} 转换取值结果。
     *
     * @param <K>  键类型
     * @param <V>  值类型
     * @param map  映射
     * @param keys 多个候选键
     * @return 第一个非 空 值的字符串表示，全为空时返回 空
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
     * 从 映射 中获取字符串值，若 键 取不到值则尝试 键2，都为空时返回默认值。
     *
     * <p>内部通过 {@code toString} 转换取值结果。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          主键
     * @param key2         备用键
     * @param defaultValue 默认值
     * @return 取到的字符串值，都为空时返回 默认值
     */
    public static <K, V> String getString(final Map<K, V> map, final K key, final K key2, final String defaultValue) {
        if (null == map) {
            return defaultValue;
        }

        Object v = getObject(map, key);
        return null == v ? (v = getObject(map, key2)) == null ? defaultValue : v.toString() : v.toString();
    }

     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的字符串值，若为 空 则返回 默认值
     */
    public static <K, V> String getString(final Map<K, V> map, final K key, final String defaultValue) {
        String answer = getString(map, key);
        if (answer == null) {
            answer = defaultValue;
        }
        return answer;
    }


    /**
     * 从 映射 中获取字符串数组，取不到时返回默认值。
     *
     * <p>值若为字符串数组则直接返回；值为字符串时按逗号拆分。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的字符串数组，若值为 空 则返回 默认值
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
 * 从 映射 中获取字符串数组，默认使用逗号（{@code ,}）作为分隔符拆分字符串值。
 *
 * <p>内部调用 {@link #getStringArray(Map, Object, String)} 并以逗号为分隔符。
 * 值若为字符串数组则直接返回；值为字符串或对象时调用 转为字符串 后按逗号拆分。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @param map 映射
 * @param key 键
 * @return 拆分后的字符串数组，若值为 空 则返回空字符串数组
 */
    public static <K, V> String[] getStringArray(final Map<K, V> map, final K key) {
        return getStringArray(map, key, SYMBOL_COMMA);
    }



    /**
     * 从 映射 中获取 Double 值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Double。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 Double，若为 空 则返回 默认值
     */
    public static <K, V> Double getDouble(final Map<K, V> map, final K key, final Double defaultValue) {
        Double aDouble = getDouble(map, key);
        return null == aDouble ? defaultValue : aDouble;
    }

    /**
     * 从 映射 中获取 Double 值，若 键 取不到则尝试 键2，都为空时返回默认值。
     *
     * <p>内部通过 {@link #getDouble(Map, Object)} 获取 Double。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          主键
     * @param key2         备用键
     * @param defaultValue 默认值
     * @return 取到的 Double，都为空时返回 默认值
     */
    public static <K, V> Double getDouble(final Map<K, V> map, final K key, final K key2, final Double defaultValue) {
        if (null == map) {
            return defaultValue;
        }
        Double aDouble = getDouble(map, key);
        return null == aDouble ? (aDouble = getDouble(map, key2)) == null ? defaultValue : aDouble : aDouble;
    }


    /**
     * 从 映射 中获取 double 基本类型值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 double。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 double 值，若为 空 则返回 默认值
     */
    public static <K, V> double getDoubleValue(final Map<K, V> map, final K key, final double defaultValue) {
        Double aDouble = getDouble(map, key);
        return null == aDouble ? defaultValue : aDouble;
    }


    /**
     * 从 映射 中获取 int 基本类型值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getInteger(Map, Object)} 获取 Integer，再自动拆箱为 int。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 当取不到值时的默认值
     * @return 取到的 int 值，若为 空 则返回 默认值
     */
    public static <K, V> int getIntValue(final Map<K, V> map, final K key, final int defaultValue) {
        Integer integer = getInteger(map, key);
        return null == integer ? defaultValue : integer;
    }

    /**
     * 从 映射 中获取 Integer 值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Integer。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map 映射
     * @param key 键
     * @return 取到的 Integer，若为 空 则返回 空
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

     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 Integer，若为 空 则返回 默认值
     */
    public static <K, V> Integer getInteger(final Map<K, V> map, final K key, final Integer defaultValue) {
        Integer integer = getInteger(map, key);
        return null == integer ? defaultValue : integer;
    }



    /**
     * 从 映射 中获取 列表 值，取不到时返回默认值。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param <E>          列表 元素类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 列表，若为 空 则返回 默认值
     */
    public static <K, V, E> List<E> getList(final Map<K, V> map, final K key, final List<E> defaultValue) {
        List<E> list = getList(map, key);
        return list != null ? list : defaultValue;
    }

    /**
     * 从 映射 中获取内嵌的子 映射。
     *
     * @param <K>  外层 映射 的键类型
     * @param <V>  外层 映射 的值类型
     * @param <MK> 内嵌 映射 的键类型
     * @param <MV> 内嵌 映射 的值类型
     * @param map  映射
     * @param key  键
     * @return 取到的内嵌 映射，若值不存在或类型不匹配则返回 空
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


     */
    public static <K, V> long getLongValue(final Map<K, V> map, final K key, final long defaultValue) {
        Long aLong = getLong(map, key);
        return null == aLong ? defaultValue : aLong;
    }

    /**
     * 从 映射 中获取 Long 值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Long。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map 映射
     * @param key 键
     * @return 取到的 Long，若为 空 则返回 空
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

     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 Long，若为 空 则返回 默认值
     */
    public static <K, V> Long getLong(final Map<K, V> map, final K key, final Long defaultValue) {
        Long aLong = getLong(map, key);
        return null == aLong ? defaultValue : aLong;
    }

    /**
     * 从 映射 中获取 布尔值 值。
     *
     * <p>值可为 Boolean、字符串或数字（非零视为 true）。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map 映射
     * @param key 键
     * @return 取到的 布尔值，若值不存在或无法转换则返回 空
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
     * 从 映射 中获取 布尔值 值，取不到时返回默认值。
     *
     * <p>值可为 Boolean、字符串或数字（非零视为 true）。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 布尔值，若为 空 则返回 默认值
     */
    public static <K, V> Boolean getBoolean(final Map<K, V> map, final K key, final Boolean defaultValue) {
        Boolean aBoolean = getBoolean(map, key);
        return null == aBoolean ? defaultValue : aBoolean;
    }


     * 若 数字 已是 Byte 类型则直接返回，否则调用 {@code byteValue()} 转换。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 当取不到 Byte 时的默认值
     * @return 取到的 Byte，若为 空 则返回 默认值
     */
    public static <K, V> Byte getByte(final Map<K, V> map, final K key, final Byte defaultValue) {
        Byte aByte = getByte(map, key);
        return null == aByte ? defaultValue : aByte;
    }


    /**
     * 从 映射 中获取 byte 基本类型值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 byte。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 byte 值，若为 空 则返回 默认值
     */
    public static <K, V> byte getByteValue(final Map<K, V> map, final K key, final byte defaultValue) {
        Byte aByte = getByte(map, key);
        return null == aByte ? defaultValue : aByte;
    }

     * 从 映射 中获取 Float 值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Float。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 Float，若为 空 则返回 默认值
     */
    public static <K, V> Float getFloat(final Map<K, V> map, final K key, final Float defaultValue) {
        Float aFloat = getFloat(map, key);
        return null == aFloat ? defaultValue : aFloat;
    }

    /**
     * 从 映射 中获取 Float 值，若 键 取不到则尝试 键2，都为空时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Float。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          主键
     * @param key2         备用键
     * @param defaultValue 默认值
     * @return 取到的 Float，都为空时返回 默认值
     */
    public static <K, V> Float getFloat(final Map<K, V> map, final K key, final K key2, final Float defaultValue) {
        Float aFloat = getFloat(map, key);
        return null == aFloat ? (aFloat = getFloat(map, key2)) == null ? defaultValue : aFloat : aFloat;
    }


/**
 * 从 映射 中获取 float 基本类型值，取不到时返回默认值。
 *
 * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 float。
 *
 * @param <K>          键类型
 * @param <V>          值类型
 * @param map          映射
 * @param key          键
 * @param defaultValue 默认值
 * @return 取到的 float 值，若为 空 则返回 默认值
 */
    public static <K, V> float getFloatValue(final Map<K, V> map, final K key, final float defaultValue) {
        Float aFloat = getFloat(map, key);
        return null == aFloat ? defaultValue : aFloat;
    }


/**
 * 从 映射 中获取 Short 值，取不到时返回默认值。
 *
 * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 Short。
 *
 * @param <K>          键类型
 * @param <V>          值类型
 * @param map          映射
 * @param key          键
 * @param defaultValue 默认值
 * @return 取到的 Short，若为 空 则返回 默认值
 */
    public static <K, V> Short getShort(final Map<K, V> map, final K key, final Short defaultValue) {
        Short aShort = getShort(map, key);
        return null == aShort ? defaultValue : aShort;
    }
    /**
     * 从 映射 中获取 short 基本类型值，取不到时返回默认值。
     *
     * <p>内部通过 {@link #getNumber(Map, Object)} 获取 Number 再转为 short。
     *
     * @param <K>          键类型
     * @param <V>          值类型
     * @param map          映射
     * @param key          键
     * @param defaultValue 默认值
     * @return 取到的 short 值，若为 空 则返回 默认值
     */
    public static <K, V> short getShortValue(final Map<K, V> map, final K key, final short defaultValue) {
        Short aShort = getShort(map, key);
        return null == aShort ? defaultValue : aShort;
    }


     *
     * @param <K>      键类型
     * @param <V>      值类型
     * @param map      映射
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
     * 将键值对字符串按指定字符分隔符解析为 映射。
     *
     * <p>委托 {@link #asMap(String, String, String)} 实现，内部将字符转为字符串分隔符。
     * 常用于解析 URL 查询字符串（{@code "a=1&b=2"}），值为空时放 空，
     * 值会经过 UTF-8 URL 解码，键 相同的值会合并为列表。
     *
     * @param value            键值对字符串，允许为空（返回空 映射）
     * @param valueSeparator   键值对之间的分隔符（如 {@code '&'}）
     * @param keyValueSeparator 键与值之间的分隔符（如 {@code '='}）
     * @return 解析后的 映射，值 为空时返回空 映射
     */
    public static Map<String, String> asMap(String value, char valueSeparator, char keyValueSeparator) {
        return asMap(value, String.valueOf(valueSeparator), String.valueOf(keyValueSeparator));
    }

    /**
     * 将键值对字符串按指定字符串分隔符解析为 映射。
     *
     * @param value            键值对字符串
     * @param valueSeparator   键值对之间的分隔符
     * @param keyValueSeparator 键与值之间的分隔符
     * @return 解析后的不可变 映射
     */
    public static Map<String, String> asMap(String value, String valueSeparator, String keyValueSeparator) {
        if (StringUtils.isEmpty(value)) {
            return Collections.emptyMap();
        }
        if (StringUtils.isEmpty(keyValueSeparator)) {
            throw new IllegalArgumentException("键值分隔符不能为空");
        }

        Map<String, Object> source = Wraps.map(DEFAULT_INITIAL_CAPACITY);
        String[] split;
        if (StringUtils.isNotEmpty(valueSeparator)) {
            // 分隔符按字面量处理：'.'、'|'、'+' 等作为正则会匹配到完全不同甚至每个位置
            split = value.split(Pattern.quote(valueSeparator), -1);
        } else {
            split = new String[]{value};
        }

        // 键值分隔符同样按字面量处理
        String kvPattern = Pattern.quote(keyValueSeparator);
        for (String item : split) {
            String[] strings = item.split(kvPattern, 2);
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
            if (mapKey.isEmpty()) {
                continue;
            }

            convertToList(source, mapKey, null == mapValue ? null : safeUrlDecode(mapValue));
        }
        Map<String, String> result = Wraps.map(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value1 = entry.getValue();
            result.put(entry.getKey(),
                       value1 instanceof Collection && ((Collection<?>) value1)
                               .size() == 1 ? CollectionUtils.findFirst((Collection) value1)
                                       .toString() : value1.toString());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 容错地进行 UTF-8 URL 解码。
     *
     * <p>{@link URLDecoder#decode(String, Charset)} 遇到残缺百分号序列（如 {@code "100%"}、
     * {@code "%zz"}）会抛 {@link IllegalArgumentException}，导致整串解析失败。此处捕获该异常
     * 并原样返回，保证「个别值格式非法」不会连带丢弃整条配置。</p>
     *
     * <p>不含百分号时直接返回原值，避免无谓的解码开销与 {@code '+' → 空格} 的意外改写。</p>
     *
     * @param raw 原始值
     * @return 解码后的值；解码失败时返回原值
     */
    private static String safeUrlDecode(String raw) {
        if (raw.indexOf('%') < 0) {
            return raw;
        }
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    /**
     * 将键值对按需转换为列表形式存入目标 映射。
     *
     * @param target 目标 映射
     * @param key    键
     * @param value  值，若为 空 则不做任何操作
     */
    private static void convertToList(Map<String, Object> target, String key, Object value) {
        Object computeIfAbsent = target.computeIfAbsent(key, (Function<String, List<Object>>) input -> Wraps.list());
        if (null == value) {
            return;
        }

        if (computeIfAbsent instanceof List) {
            ((List) computeIfAbsent).add(value);
        } else {
            List<Object> newValue = Wraps.list();
            newValue.add(computeIfAbsent);
            newValue.add(value);
            target.put(key, newValue);
        }
    }

    /**
     * 获取 映射 中的第一个键值对。
     *
     * @param <K>   键类型
     * @param <V>   值类型
     * @param kvMap 映射
     * @return 第一个 映射.Entry，若 映射 为空则返回 空
     */
    public static <K, V> Map.Entry<K, V> getFirst(final Map<K, V> kvMap) {
        if (isEmpty(kvMap)) {
            return null;
        }
        return kvMap.entrySet().iterator().next();
    }



    /**
     * 将对象转为 属性，对象可为 映射、POJO 或数组。
     *
     * @param v 待转换的对象，数组按偶数对键值交替处理
     * @return 对应的 属性
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
                properties.put(key, value);

            }
            return properties;
        }

        return asProp(beanToMap(v));
    }

    /**
     * 将 属性 转为 映射。
     *
     * @param properties 属性 对象
     * @return 对应的 映射
     */
    public static Map<String, Object> asMap(Properties properties) {
        Map<String, Object> rs = Wraps.map(properties.size());
        properties.forEach((k, v) -> {
            rs.put(k.toString(), v);
        });
        return rs;
    }






    /**
     * 将 映射 的键和值都转为 字符串，返回新 映射。
     *
     * @param source 源 映射
     * @return 键和值均为 字符串 的新 链接哈希映射
     */
    public static Map<String, String> asStringMap(Map source) {
        Map<String, String> tpl = Wraps.linkedMap(source.size());
        return tpl;
    }

    /**
     * 将 属性 的键和值都转为 字符串，返回新 映射。
     *
     * @param source 属性 对象
     * @return 键和值均为 字符串 的新 链接哈希映射
     */
    public static Map<String, String> asStringMap(Properties source) {
        Map<String, String> tpl = Wraps.linkedMap(source.size());
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            tpl.put(entry.getKey().toString(), null == entry.getValue() ? null : entry.getValue().toString());
        }
        return tpl;
    }




    /**
     * 创建一个默认初始容量的新 哈希映射。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新的 哈希映射
     */
    public static <K, V> Map<K, V> newHashMap() {
        return Wraps.map(DEFAULT_INITIAL_CAPACITY);
    }


    /**
     * 创建一个空的 multi值映射（支持一对多映射）。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新的空 multi链接值映射
     */
    public static <K, V> MultiValueMap<K, V> ofMultiMap() {
        return new MultiLinkedValueMap<>();
    }

    /**
     * 若 键 不存在则通过 function 计算并放入值，返回最终值。
     *
     * @param <K>      键类型
     * @param <V>      值类型
     * @param map      缓存 映射
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
     * 从 映射 中获取配置值，支持驼峰和下划线命名自动转换查找。
     *
     * @param <K>  键类型
     * @param <V>  值类型
     * @param arg  映射
     * @param name 配置项名称
     * @return 取到的配置值
     */
    public static <K, V> V getConfig(Map<K, V> arg, String name) {
        V v = arg.get(name);
        if (null == v) {
            v = arg.get(CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, name));
        }

        return v;
    }




    /**
     * 判断 映射 中指定 键 对应的值是否有效（非 空 且非空字符串）。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param map 映射
     * @param key 键
     * @return 若值非 空 且非空字符串则返回 true
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
    }



    /**
     * 通过类名字符串构造对象并放入 映射。
     *
     * @param <K>   键类型
     * @param <V>   值类型
     * @param map   映射
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
     * 将 映射 的每个键值对通过转换函数映射为列表。
     *
     * @param <T>         列表元素类型
     * @param beanOfTypes 映射
     * @param function    转换函数，接收键和值，返回列表元素
     * @return 转换后的列表，若 映射 为空则返回空列表
     */
    public static <T> List<T> mapToList(Map<String, ?> beanOfTypes, BiFunction<String, Object, T> function) {
        if (isEmpty(beanOfTypes)) {
            return Collections.emptyList();
        }
        return beanOfTypes.entrySet().stream().map(entry -> function.apply(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }


     * <p>
     * 嵌套结构被展开为点号分隔的扁平键：
     * {@code {"user": {"name": "张三", "age": 25}} → {"user.name": "张三", "user.age": 25}}
     *
     * @param properties 属性 对象
     * @return 扁平化的 映射
     */
    public static Map<String, Object> flattenToProperties(Properties properties) {
        Map<String, Object> copy = Wraps.map(properties.size());
        for (String name : properties.stringPropertyNames()) {
            copy.put(name, properties.getProperty(name));
        }
        return flattenToProperties(copy, "");
    }

    /**
     * 将嵌套 映射 按 属性 格式展开为扁平化的点号键格式。
     *
     * <p>递归遍历 Map，将嵌套键拼接为点号分隔的扁平键。
     *
     * @param map    嵌套 映射
     * @param prefix 键前缀
     * @return 扁平化的 映射
     */
    public static Map<String, Object> flattenToProperties(Map<String, Object> map, String prefix) {
        Map<String, Object> result = Wraps.linkedMap();

        if (map == null) {
            return result;
        }

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            //
            String newKey = StringUtils.isEmpty(prefix) ? key : prefix + "." + key;

            if (value instanceof Map) {
 // 映射
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
     * 将扁平化的点号键 映射 还原为嵌套 映射 结构。
     *
     * <p>
     * 点号分隔的扁平键被还原为多层嵌套：
     * {@code {"user.name": "张三", "user.age": 25} → {"user": {"name": "张三", "age": 25}}}
     *
     * @param flatMap 扁平化的 映射
     * @return 还原后的嵌套 映射
     */
    public static Map<String, Object> unflattenFromProperties(Map<String, Object> flatMap) {
        if (flatMap == null || flatMap.isEmpty()) {
            return Wraps.linkedMap();
        }

        Map<String, Object> result = Wraps.linkedMap();

        for (Map.Entry<String, Object> entry : flatMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            setNestedValue(result, key, value);
        }

        return result;
    }

    /**
     * 设置嵌套 映射 的值，根据点号分隔的键路径逐层深入。
     *
     * @param map   目标 映射
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
     * 在 当前 映射 中创建或获取对应索引位置的 映射 节点。
     * 若为普通键，则调用 {@link #ensureMapExists(Map, String)} 创建或获取下一层级的 映射。
     *
     * @param current 当前层级的 映射
     * @param part    键路径片段，可为普通键名或 "array名称[索引]" 格式
     * @return 下一层级的 映射 节点
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
                    current.put(arrayName, Wraps.list());
                }

                Object arrayObj = current.get(arrayName);
                if (!(arrayObj instanceof List)) {
                    arrayObj = Wraps.list();
                    current.put(arrayName, arrayObj);
                }

                List<Object> list = (List<Object>) arrayObj;

                //
                while (list.size() <= index) {
                    list.add(Wraps.linkedMap());
                }

 // 映射
                if (!(list.get(index) instanceof Map)) {
                    list.set(index, Wraps.linkedMap());
                }

                return (Map<String, Object>) list.get(index);
            } catch (NumberFormatException e) {
            }
        } else {
            //
            return ensureMapExists(current, part);
        }
    }

     * @param key     要确保存在的键
     * @return key 对应的子 映射 节点，保证非 空 且为 映射 类型
     */
    private static Map<String, Object> ensureMapExists(Map<String, Object> current, String key) {
        if (!current.containsKey(key)) {
            current.put(key, Wraps.linkedMap());
        }

        Object nextLevel = current.get(key);
        if (!(nextLevel instanceof Map)) {
            nextLevel = Wraps.linkedMap();
            current.put(key, nextLevel);
        }
        return (Map<String, Object>) nextLevel;
    }

    /**
     * 设置最终值到当前 映射 节点，支持数组下标语法。
     *
     * @param current  当前层级的 映射
     * @param finalKey 最终键名，可包含 [索引] 数组下标
     * @param value    要设置的值
     */
    private static void setFinalValue(Map<String, Object> current, String finalKey, Object value) {
        if (finalKey.contains("[") && finalKey.contains("]")) {
            String arrayName = finalKey.substring(0, finalKey.indexOf('['));
            String indexStr = finalKey.substring(finalKey.indexOf('[') + 1, finalKey.indexOf(']'));

            try {
                int index = Integer.parseInt(indexStr);

                if (!current.containsKey(arrayName)) {
                    current.put(arrayName, Wraps.list());
                }

                Object arrayObj = current.get(arrayName);
                if (!(arrayObj instanceof List)) {
                    arrayObj = Wraps.list();
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

     * 转换失败时返回 false，转换成功后用 {@code ==} 比较。
     *
     * @param expectation 期望的 double 值
     * @param key         映射 中的键
     * @param item        待比较的 映射，允许为 空
     * @return 相等返回 true，不相等返回 false，键 不存在返回 空
     */
    public static Boolean isEquals(double expectation, String key, Map<String, Object> item) {
        if (item == null || !item.containsKey(key)) {
            return null;
        }

        Object object = item.get(key);
        if (i == null) {
            return false;
        }
        return i == expectation;
    }


    /**
     * Bean转为映射
     *
     * @param bean Bean
     * @return Bean转为映射的结果
     */
    private static Map<String, Object> beanToMap(Object bean) {
        if (bean instanceof Map) {
            return (Map<String, Object>) bean;
        }
        Map<String, Object> result = Wraps.linkedMap();
        try {
            java.beans.BeanInfo beanInfo = java.beans.Introspector.getBeanInfo(bean.getClass());
            for (java.beans.PropertyDescriptor pd : beanInfo.getPropertyDescriptors()) {
                if (!"class".equals(pd.getName()) && pd.getReadMethod() != null) {
                    result.put(pd.getName(),
                               ReflectUtils.invoke(bean, pd.getReadMethod().getName(),
                }
            }
        } catch (Exception e) {
            log.debug("[map] Bean 转 Map 失败，返回已收集属性: {}", e.getMessage());
        }
        return result;
    }
}
