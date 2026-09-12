package com.chua.common.support.converter;

import com.chua.common.support.converter.definition.*;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
* 类型转换器核心类，提供对象类型转换的静态方法。
* <p>
* 通过 SPI 机制自动加载所有 {@link TypeConverter} 实现，支持以下功能：
* <ul>
*   <li><b>SPI 自动发现</b>：通过 {@link com.chua.common.support.spi.ServiceProvider} 加载所有 TypeConverter 实现</li>
*   <li><b>自定义转换器</b>：允许动态注册/注销自定义转换函数</li>
*   <li><b>泛型参数转换</b>：支持 {@link List}、{@link Set}、{@link Map}、{@link Optional} 等泛型类型的参数化转换</li>
*   <li><b>数组转换</b>：支持任意类型数组的转换</li>
*   <li><b>枚举转换</b>：支持按枚举名称或序号的转换</li>
*   <li><b>日期解析</b>：提供 LocalDate/LocalDateTime 的格式化解析</li>
* </ul>
* </p>
*
* @author CH
* @since 2020/12/19
 */
@SuppressWarnings({"ALL", "unchecked"})
@Slf4j
public final class Converter {

    /**
    * 通过 SPI 机制加载的类型转换器映射表，Key 为目标类型，Value 为对应的 TypeConverter 实例
     */
    private static final Map<Class<?>, TypeConverter> CONVERTER_MAP_LOADER = initConverterMap();

    /**
    * 枚举类型转换器实例（特殊处理）
     */
    private static final EnumTypeConverter ENUM_TYPE_CONVERTER = new EnumTypeConverter();

    /**
    * 对象数组类型转换器实例（特殊处理）
     */
    private static final ObjectArrayTypeConverter OBJECT_ARRAY_CONVERTER = new ObjectArrayTypeConverter();

    /**
    * Map 类型转换器实例（特殊处理）
     */
    private static final MapTypeConverter MAP_TYPE_CONVERTER = new MapTypeConverter();

    /**
    * 用户自定义转换器缓存，Key 为目标类型，Value 为转换函数
     */
    private static final Map<Class<?>, Function<Object, Object>> CUSTOM_CONVERTERS = new ConcurrentHashMap<>();

    /**
    * 基本类型的默认值映射，用于无法转换时返回各基本类型的零值
     */
    private static final Map<Class<?>, Object> DEFAULT_VALUE = new HashMap<>() {{
        put(long.class, 0L);
        put(short.class, (short) 0);
        put(byte.class, (byte) 0);
        put(float.class, 0F);
        put(double.class, 0D);
        put(boolean.class, false);
        put(char.class, ' ');
        put(int.class, 0);
    }};

    /** 创建 Converter 实例 */
    private Converter() {
    }

    /**
    * 通过 SPI 机制初始化并加载所有 {@link TypeConverter} 实现。
    * <p>遍历 SPI 注册的所有 TypeConverter，按 {@link TypeConverter#getType()} 返回的目标类型建立映射关系。</p>
    *
    * @return 类型转换器映射表
     */
    private static Map<Class<?>, TypeConverter> initConverterMap() {
        Map<Class<?>, TypeConverter> map = new ConcurrentHashMap<>();
        ServiceProvider.of(TypeConverter.class).forEach((name, converter) -> {
            map.put(converter.getType(), converter);
        });
        return map;
    }

    /**
    * 注册自定义类型转换器。
    * <p>注册后的转换器在 {@link #convertIfNecessary(Object, Class)} 中优先于 SPI 转换器执行。</p>
    *
    * @param targetType 目标类型
    * @param converter  转换函数，接收源对象，返回目标类型实例
     */
    public static void registerConverter(Class<?> targetType, Function<Object, Object> converter) {
        CUSTOM_CONVERTERS.put(targetType, converter);
        log.debug("注册自定义转换器：{}", targetType.getSimpleName());
    }

    /**
    * 移除指定目标类型的自定义转换器。
    *
    * @param targetType 目标类型
     */
    public static void unregisterConverter(Class<?> targetType) {
        CUSTOM_CONVERTERS.remove(targetType);
    }

    /**
    * 类型转换（支持泛型参数类型）。
    * <p>
    * 如果 {@code type} 为 {@link Class} 则直接按 Class 转换；
    * 如果为 {@link ParameterizedType} 则解析泛型参数后进行泛型转换（如 List&lt;String&gt;）。
    * </p>
    *
    * @param value 待转换的值
    * @param type  目标类型（支持 Class 和 ParameterizedType）
    * @return 转换后的值，如果无法转换则返回 null
     */
    public static Object convertIfNecessary(Object value, Type type) {
        if (value == null || type == null) {
            return null;
        }
        if (type instanceof Class) {
            return convertIfNecessary(value, (Class<?>) type);
        }
        if (type instanceof ParameterizedType pt) {
            return convertParameterized(value, pt);
        }
        return null;
    }

    /**
    * 类型转换（按 Class 目标类型）。
    * <p>转换优先级：</p>
    * <ol>
    *   <li>如果值类型可直接赋值给目标类型，直接返回</li>
    *   <li>检查是否有自定义转换器</li>
    *   <li>检查是否有 SPI 转换器</li>
    *   <li>如果是数组类型，使用数组转换器</li>
    *   <li>如果是枚举类型，使用枚举转换器</li>
    * </ol>
    *
    * @param value 待转换的值
    * @param type  目标类型
    * @param <E>   泛型类型
    * @return 转换后的值，如果无法转换则返回 null
     */
    public static <E> E convertIfNecessary(Object value, Class<E> type) {
        if (value == null) {
            return null;
        }

        Class<?> targetType = convertIfPrimitive(type);
        if (targetType.isAssignableFrom(value.getClass())) {
            return (E) value;
        }

        // 自定义转换器优先
        Function<Object, Object> customConverter = CUSTOM_CONVERTERS.get(targetType);
        if (customConverter != null) {
            return (E) customConverter.apply(value);
        }

        // SPI 类型转换器
        TypeConverter spiConverter = CONVERTER_MAP_LOADER.get(targetType);
        if (spiConverter != null) {
            E result = (E) spiConverter.convert(value);
            if (result != null) {
                return result;
            }
        }

        // 数组类型处理
        if (targetType.isArray()) {
            return (E) OBJECT_ARRAY_CONVERTER.convertFor(value, targetType);
        }
        // 枚举类型处理
        if (targetType.isEnum()) {
            return (E) ENUM_TYPE_CONVERTER.convertFor(value, targetType);
        }

        return null;
    }

    /**
    * 类型转换，转换失败时返回指定的默认值。
    *
    * @param value        待转换的值
    * @param type         目标类型
    * @param defaultValue 转换失败时返回的默认值
    * @param <E>          泛型类型
    * @return 转换后的值，如果无法转换则返回 {@code defaultValue}
     */
    public static <E> E convertIfNecessary(Object value, Class<E> type, E defaultValue) {
        E result = convertIfNecessary(value, type);
        return result != null ? result : defaultValue;
    }

    /**
    * 将值转换为 Optional 包装类型。
    *
    * @param value 待转换的值
    * @param type  目标类型
    * @param <E>   泛型类型
    * @return Optional 包装的转换结果，如果转换失败则返回 Optional.empty()
     */
    public static <E> Optional<E> convertOptional(Object value, Class<E> type) {
        return Optional.ofNullable(convertIfNecessary(value, type));
    }

    /**
    * 转换 List 集合中的每个元素为指定类型。
    *
    * @param source 源集合
    * @param type   目标元素类型
    * @param <T>    泛型类型
    * @return 转换后的 List，如果 source 为 null 则返回空列表
     */
    public static <T> List<T> convertList(Collection<?> source, Class<T> type) {
        if (source == null) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<>(source.size());
        for (Object item : source) {
            result.add(convertIfNecessary(item, type));
        }
        return result;
    }

    /**
    * 将任意对象转换为指定元素类型的 {@link List}。
    *
    * <p>支持输入类型：
    * <ul>
    *   <li>{@link List} / {@link Collection} — 直接转换元素</li>
    *   <li>{@link String} — 兼容 JSON 数组格式（{@code [a,b,c]}）与逗号/分号/空格/换行分隔格式</li>
    *   <li>数组类型 — 通过 {@link ListTypeConverter} 转换</li>
    * </ul>
    *
    * <p>元素类型可省略，省略时按 {@code Object} 处理（返回原始元素）。</p>
    *
    * @param value 源对象，可为 null（返回空列表）
    * @param types 目标元素类型（可选，最多取第一个）
    * @param <T>   泛型类型
    * @return 转换后的 List，值为 null 时返回空列表
     */
    public static <T> List<T> convertIfListNecessary(Object value, Type... types) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<?> source = ListTypeConverter.INSTANCE.convert(value);
        if (source == null) {
            return Collections.emptyList();
        }
        if (types == null || types.length == 0 || types[0] == null) {
            return (List<T>) source;
        }
        Type elementType = types[0];
        if (elementType instanceof Class<?> elementClass) {
            return convertList(source, (Class<T>) elementClass);
        }
        // 泛型元素类型（如 List<List<String>>）逐元素转换
        List<Object> result = new ArrayList<>(source.size());
        for (Object item : source) {
            result.add(convertIfNecessary(item, elementType));
        }
        return (List<T>) result;
    }

    /**
    * 将任意对象转换为指定元素类型的 {@link Set}。
    *
    * <p>支持输入类型：
    * <ul>
    *   <li>{@link Set} / {@link Collection} — 直接转换元素</li>
    *   <li>{@link String} — 兼容 JSON 数组格式（{@code [a,b,c]}）与逗号分隔格式</li>
    * </ul>
    *
    * @param value 源对象，可为 null（返回空 Set）
    * @param types 目标元素类型（可选，最多取第一个）
    * @param <T>   泛型类型
    * @return 转换后的 Set，值为 null 时返回空 Set
     */
    public static <T> Set<T> convertIfSetNecessary(Object value, Type... types) {
        if (value == null) {
            return Collections.emptySet();
        }
        Set<?> source = SetTypeConverter.INSTANCE.convert(value);
        if (source == null) {
            return Collections.emptySet();
        }
        if (types == null || types.length == 0 || types[0] == null) {
            return (Set<T>) source;
        }
        Type elementType = types[0];
        if (elementType instanceof Class<?> elementClass) {
            return convertSet(source, (Class<T>) elementClass);
        }
        Set<Object> result = new LinkedHashSet<>(Math.max(16, source.size()));
        for (Object item : source) {
            result.add(convertIfNecessary(item, elementType));
        }
        return (Set<T>) result;
    }

    /**
    * 将任意对象转换为 {@link Collection}。
    *
    * <p>支持输入类型：
    * <ul>
    *   <li>{@link Collection} — 直接返回</li>
    *   <li>{@link String} — 兼容 JSON 数组格式与逗号/分号/空格/换行分隔格式</li>
    *   <li>数组类型 — 通过 {@link ListTypeConverter} 转换</li>
    * </ul>
    *
    * @param value 源对象，可为 null（返回空集合）
    * @param <T>   泛型类型
    * @return 转换后的 Collection，值为 null 时返回空集合
     */
    public static <T> Collection<T> convertIfCollectionNecessary(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<?> source = ListTypeConverter.INSTANCE.convert(value);
        if (source == null) {
            return Collections.emptyList();
        }
        return (Collection<T>) source;
    }

    /**
    * 将任意对象转换为指定键值类型的 {@link Map}。
    *
    * <p>支持输入类型：
    * <ul>
    *   <li>{@link Map} / {@link java.util.Dictionary} — 直接转换键值</li>
    *   <li>{@link String} — 兼容大括号键值对（{@code {k=v,k2=v2}}）与 {@code k=v} / {@code k:v} 分隔格式</li>
    * </ul>
    *
    * @param value 源对象，可为 null（返回空 Map）
    * @param types 键类型与值类型（可选，最多取前两个；缺省按 Object 处理）
    * @param <K>   Key 类型
    * @param <V>   Value 类型
    * @return 转换后的 Map，值为 null 时返回空 Map
     */
    public static <K, V> Map<K, V> convertIfMapNecessary(Object value, Type... types) {
        if (value == null) {
            return Collections.emptyMap();
        }
        Map<?, ?> source = MAP_TYPE_CONVERTER.convert(value);
        if (source == null) {
            return Collections.emptyMap();
        }
        if (types == null || types.length == 0 || types[0] == null) {
            return (Map<K, V>) source;
        }
        Type keyType = types[0];
        Type valueType = types.length > 1 ? types[1] : Object.class;
        Map<Object, Object> result = new LinkedHashMap<>(Math.max(16, source.size()));
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            result.put(
                    convertIfNecessary(entry.getKey(), keyType),
                    convertIfNecessary(entry.getValue(), valueType)
            );
        }
        return (Map<K, V>) result;
    }

    /**
    * 将任意对象转换为指定元素类型的目标数组。
    *
    * <p>支持输入类型：
    * <ul>
    *   <li>数组 / {@link Collection} / {@link Map}（取 values）— 逐元素转换后组装</li>
    *   <li>{@link String} — 兼容 JSON 数组格式与逗号分隔格式</li>
    *   <li>其它单值 — 包装为单元素数组</li>
    * </ul>
    *
    * @param value 源对象，可为 null（返回空数组）
    * @param type  目标数组的元素类型
    * @param <T>   目标元素泛型类型
    * @return 转换后的目标类型数组，值为 null 时返回空数组
     */
    public static <T> T[] convertIfArrayNecessary(Object value, Class<T> type) {
        if (value == null) {
            return (T[]) java.lang.reflect.Array.newInstance(type, 0);
        }
        return (T[]) OBJECT_ARRAY_CONVERTER.convertFor(value, type);
    }

    /**
    * 转换 Set 集合中的每个元素为指定类型。
    *
    * @param source 源集合
    * @param type   目标元素类型
    * @param <T>    泛型类型
    * @return 转换后的 Set，如果 source 为 null 则返回空 Set
     */
    public static <T> Set<T> convertSet(Collection<?> source, Class<T> type) {
        if (source == null) {
            return Collections.emptySet();
        }
        Set<T> result = new LinkedHashSet<>(source.size());
        for (Object item : source) {
            result.add(convertIfNecessary(item, type));
        }
        return result;
    }

    /**
    * 转换 Map 的所有 Value 为指定类型（Key 保持不变）。
    *
    * @param source    源 Map
    * @param valueType 目标 Value 类型
    * @param <K>       Key 类型
    * @param <V>       Value 类型
    * @return 转换后的 Map，如果 source 为 null 则返回空 Map
     */
    public static <K, V> Map<K, V> convertMapValues(Map<K, ?> source, Class<V> valueType) {
        if (source == null) {
            return Collections.emptyMap();
        }
        Map<K, V> result = new LinkedHashMap<>(source.size());
        for (Map.Entry<K, ?> entry : source.entrySet()) {
            result.put(entry.getKey(), convertIfNecessary(entry.getValue(), valueType));
        }
        return result;
    }

    /**
    * 转换对象数组为指定元素类型的目标数组。
    *
    * @param source 源对象数组
    * @param type   目标元素类型
    * @param <T>    泛型类型
    * @return 转换后的目标类型数组，如果 source 为 null 则返回空数组
     */
    public static <T> T[] convertArray(Object[] source, Class<T> type) {
        if (source == null) {
            return (T[]) java.lang.reflect.Array.newInstance(type, 0);
        }
        T[] result = (T[]) java.lang.reflect.Array.newInstance(type, source.length);
        for (int i = 0; i < source.length; i++) {
            result[i] = convertIfNecessary(source[i], type);
        }
        return result;
    }

    /**
    * 转换为枚举常量。
    * <p>支持以下输入格式：</p>
    * <ul>
    *   <li>枚举实例本身 — 直接返回</li>
    *   <li>{@link Number} 类型 — 按枚举 ordinal 取值</li>
    *   <li>{@link String} 类型 — 按枚举名称匹配（先精确匹配，再忽略大小写匹配）</li>
    * </ul>
    *
    * @param value 值（支持枚举实例、序号、名称字符串）
    * @param type  枚举类型
    * @param <E>   泛型类型
    * @return 枚举常量，如果无法匹配则返回 null
     */
    public static <E extends Enum<E>> E toEnum(Object value, Class<E> type) {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return (E) value;
        }
        if (value instanceof Number) {
            int ordinal = ((Number) value).intValue();
            E[] constants = type.getEnumConstants();
            if (ordinal >= 0 && ordinal < constants.length) {
                return constants[ordinal];
            }
        }
        if (value instanceof String) {
            try {
                return Enum.valueOf(type, (String) value);
            } catch (IllegalArgumentException e) {
                for (E constant : type.getEnumConstants()) {
                    if (constant.name().equalsIgnoreCase((String) value)) {
                        return constant;
                    }
                }
            }
        }
        return null;
    }

    /**
    * 将枚举常量转换为其名称字符串。
    *
    * @param value 枚举常量
    * @return 枚举名称，如果 value 为 null 则返回 null
     */
    public static String enumToString(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    /**
    * 将枚举常量转换为其序号（ordinal）。
    *
    * @param value 枚举常量
    * @return 枚举序号，如果 value 为 null 则返回 null
     */
    public static Integer enumToInt(Enum<?> value) {
        return value != null ? value.ordinal() : null;
    }

    /**
    * 解析 LocalDate
    *
    * @param value   日期字符串
    * @param pattern 格式
    * @return LocalDate
     */
    public static LocalDate parseLocalDate(String value, String pattern) {
        if (value == null || pattern == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern));
        } catch (Exception e) {
            log.debug("LocalDate 解析失败：value={}, pattern={}", value, pattern);
            return null;
        }
    }

    /**
    * 解析 LocalDateTime
    *
    * @param value   日期字符串
    * @param pattern 格式
    * @return LocalDateTime
     */
    public static LocalDateTime parseLocalDateTime(String value, String pattern) {
        if (value == null || pattern == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern));
        } catch (Exception e) {
            log.debug("LocalDateTime 解析失败：value={}, pattern={}", value, pattern);
            return null;
        }
    }

    /**
    * 格式化 LocalDateTime
     */
    public static String formatDate(LocalDateTime dateTime, String pattern) {
        if (dateTime == null || pattern == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
    * 判断是否可以转换
    *
    * @param sourceType 源类型
    * @param targetType 目标类型
    * @return true 如果可以转换
     */
    public static boolean canConvert(Class<?> sourceType, Class<?> targetType) {
        if (sourceType == null || targetType == null) {
            return false;
        }
        if (targetType.isAssignableFrom(sourceType)) {
            return true;
        }
        if (CUSTOM_CONVERTERS.containsKey(targetType)) {
            return true;
        }
        if (CONVERTER_MAP_LOADER.containsKey(targetType)) {
            return true;
        }
        if (targetType.isEnum()) {
            return true;
        }
        if (targetType.isArray()) {
            return true;
        }
        return false;
    }

    /**
    * 泛型参数类型的转换处理
     */
    private static <E> E convertParameterized(Object value, ParameterizedType pt) {
        Class<?> rawClass = (Class<?>) pt.getRawType();
        Type[] args = pt.getActualTypeArguments();
        try {
            if (List.class.isAssignableFrom(rawClass)) {
                return (E) convertToListWithType(value, args);
            }
            if (Set.class.isAssignableFrom(rawClass)) {
                return (E) convertToSetWithType(value, args);
            }
            if (Optional.class.isAssignableFrom(rawClass)) {
                return (E) convertToOptionalWithType(value, args);
            }
            if (Map.class.isAssignableFrom(rawClass)) {
                return (E) convertToMapWithType(value, args);
            }
        } catch (Exception e) {
            log.debug("泛型转换异常：rawType={}", rawClass.getSimpleName());
        }
        return null;
    }

    /**
    * 创建基本类型默认值
     */
    private static <E> E createDefaultPrimitive(Class<?> type) {
        return (E) DEFAULT_VALUE.get(type);
    }

    /**
    * 转换为带类型的 List
     */
    private static <E> List<E> convertToListWithType(Object value, Type[] args) {
        Type elemType = args.length > 0 ? args[0] : Object.class;
        List<?> src = ListTypeConverter.INSTANCE.convert(value);
        if (!(elemType instanceof Class<?> elemClass)) {
            return (List<E>) src;
        }
        List<Object> result = new ArrayList<>(src.size());
        for (Object item : src) {
            result.add(convertIfNecessary(item, elemClass));
        }
        return (List<E>) result;
    }

    /**
    * 转换为带类型的 Set
     */
    private static <E> Set<E> convertToSetWithType(Object value, Type[] args) {
        Type elemType = args.length > 0 ? args[0] : Object.class;
        List<?> src = ListTypeConverter.INSTANCE.convert(value);
        if (!(elemType instanceof Class<?> elemClass)) {
            return (Set<E>) new LinkedHashSet<>(src);
        }
        Set<Object> result = new LinkedHashSet<>(src.size());
        for (Object item : src) {
            result.add(convertIfNecessary(item, elemClass));
        }
        return (Set<E>) result;
    }

    /**
    * 转换为带类型的 Optional
     */
    private static <E> Optional<E> convertToOptionalWithType(Object value, Type[] args) {
        Type elemType = args.length > 0 ? args[0] : Object.class;
        if (elemType instanceof Class<?> elemClass) {
            return (Optional<E>) Optional.ofNullable(convertIfNecessary(value, elemClass));
        }
        return (Optional<E>) Optional.ofNullable(value);
    }

    /**
    * 转换为带类型的 Map
     */
    private static <K, V> Map<K, V> convertToMapWithType(Object value, Type[] args) {
        Type keyType = args.length > 0 ? args[0] : Object.class;
        Type valType = args.length > 1 ? args[1] : Object.class;
        Map<?, ?> src = MAP_TYPE_CONVERTER.convert(value);
        if (!(keyType instanceof Class<?> keyClass) || !(valType instanceof Class<?> valClass)) {
            return (Map<K, V>) src;
        }
        Map<Object, Object> result = new LinkedHashMap<>(Math.max(16, src.size()));
        for (Map.Entry<?, ?> entry : src.entrySet()) {
            result.put(
                    convertIfNecessary(entry.getKey(), keyClass),
                    convertIfNecessary(entry.getValue(), valClass)
            );
        }
        return (Map<K, V>) result;
    }

    /**
    * 基本类型转包装类
     */
    public static <T> Class<T> convertIfPrimitive(Class<T> target) {
        if (target == null || !target.isPrimitive()) {
            return target;
        }
        return ClassUtils.fromPrimitive(target);
    }

    /**
    * 创建 Integer 转换
     */
    public static Integer createInteger(Object value) {
        return convertIfNecessary(value, Integer.class);
    }

    /**
    * 创建 Integer 转换并返回默认值
     */
    public static int createInteger(Object value, int defaultValue) {
        Integer result = createInteger(value);
        return result != null ? result : defaultValue;
    }

    /**
    * 创建 Float 转换
     */
    public static Float createFloat(String value) {
        return convertIfNecessary(value, Float.class);
    }

    /**
    * 判断是否存在转换器
     */
    public static boolean hasConverter(Class<?> returnType) {
        return CUSTOM_CONVERTERS.containsKey(returnType) || CONVERTER_MAP_LOADER.containsKey(returnType);
    }

    /**
    * 转换为 BigDecimal
     */
    public static BigDecimal toBigDecimal(String num) {
        return convertIfNecessary(num, BigDecimal.class);
    }

    /**
    * 安全的 LocalDateTime 解析
     */
    public static LocalDateTime parseLocalDateTimeSafe(Object o) {
        return convertIfNecessary(o, LocalDateTime.class);
    }

    /**
    * 转换为 BufferedImage
     */
    public static BufferedImage toBufferedImage(Object predict) {
        return convertIfNecessary(predict, BufferedImage.class);
    }
}