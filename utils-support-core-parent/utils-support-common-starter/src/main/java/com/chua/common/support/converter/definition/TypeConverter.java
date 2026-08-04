package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.ArrayUtils;
import com.chua.common.support.utils.DateUtils;
import com.chua.common.support.utils.NumberUtils;
import com.chua.common.support.utils.StringUtils;

import java.awt.*;
import java.io.File;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullUnmarked;


/**
 * 类型转换器基础接口，定义类型转换的核心契约。
 * <p>
 * 所有转换器通过 SPI 机制加载，实现该接口以支持从任意类型到特定目标类型的转换。
 * 该接口提供了丰富的默认方法用于辅助转换：
 * <ul>
 *   <li>{@link #convert(Object)} — 核心转换方法，子类必须实现</li>
 *   <li>{@link #transToBigDecimal(Object)} — 将各种类型统一转为 BigDecimal 的中间转换</li>
 *   <li>{@link #transToArray(Object, Class)} 系列 — 数组类型转换辅助</li>
 *   <li>{@link #convertIfNecessary(Object)} — 兜底转换回调</li>
 * </ul>
 * </p>
 *
 * @param <O> 目标类型
 * @author CH
 * @version 1.0.0
 * @since 2020/10/30
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public interface TypeConverter<O> {

    /** 科学计数法标识字符（如 1e10 中的 e） */
    String E = "e";
    /** 数字后缀正则，匹配末尾的 f/F/d/D 标识 */
    Pattern NU = Pattern.compile("(f|F|d|D)");
    /** 存储容量单位与字节数的映射表 */
    Map<String, Long> MAPPING = new HashMap<>(10);

    /**
     * 初始化存储容量单位映射表。
     * <p>预定义 B、KB/K、MB/M、GB/G、PB/P 与字节数的对应关系。</p>
     */
    static void initial() {
        MAPPING.put("B", 1L);
        MAPPING.put("KB", 1024L);
        MAPPING.put("K", 1024L);
        MAPPING.put("MB", 1024 * 1024L);
        MAPPING.put("M", 1024 * 1024L);
        MAPPING.put("GB", 1024 * 1024 * 1024L);
        MAPPING.put("G", 1024 * 1024 * 1024L);
        MAPPING.put("PB", 1024 * 1024 * 1024 * 1024L);
        MAPPING.put("P", 1024 * 1024 * 1024 * 1024L);
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return 目标类型的 Class 对象
     */
    Class<O> getType();

    /**
     * 将给定值转换为当前转换器支持的目标类型。
     *
     * @param value 源值
     * @return 转换后的值，如果无法转换则返回 null
     */
    O convert(Object value);

    /**
     * 判断值是否是指定类型的实例。
     *
     * @param value 源值
     * @param type  目标类型
     * @return 如果 value 是 type 的实例返回 true
     */
    default boolean isAssignableFrom(Object value, Class<?> type) {
        return type.isAssignableFrom(value.getClass());
    }

    /**
     * 兜底转换方法，当主要转换逻辑无法处理时调用。
     * <p>子类可重写此方法以提供备选转换策略，默认返回 null。</p>
     *
     * @param value 源值
     * @return 转换后的值，默认返回 null
     */
    default O convertIfNecessary(Object value) {
        return null;
    }

    /**
     * 将 List 转换为指定元素类型的目标数组。
     *
     * @param value 源 List
     * @param type  目标元素类型
     * @param <T>   目标元素泛型类型
     * @return 转换后的数组
     */
    @SuppressWarnings("all")
    default <T> T[] transToArray(List value, Class<T> type) {
        return ArrayUtils.transToArray(value, type);
    }

    /**
     * 将对象数组转换为指定元素类型的目标数组。
     *
     * @param value 源对象数组
     * @param type  目标元素类型
     * @param <T>   目标元素泛型类型
     * @return 转换后的数组
     */
    default <T> T[] transToArray(Object[] value, Class<T> type) {
        return ArrayUtils.transToArray(value, type);
    }

    /**
     * 将任意对象尝试转换为指定元素类型的目标数组。
     * <p>支持包装类型数组和基本类型数组的自动识别与转换。</p>
     *
     * @param value 源对象
     * @param type  目标元素类型
     * @param <T>   目标元素泛型类型
     * @return 转换后的数组，如果无法转换则返回空数组
     */
    default <T> T[] transToArray(Object value, Class<T> type) {

        if (value instanceof Byte[]) {
            return transToArray((Byte[]) value, type);
        }

        if (value instanceof Long[]) {
            return transToArray((Long[]) value, type);
        }

        if (value instanceof Double[]) {
            return transToArray((Double[]) value, type);
        }

        if (value instanceof Float[]) {
            return transToArray((Float[]) value, type);
        }

        if (value instanceof Short[]) {
            return transToArray((Short[]) value, type);
        }

        if (value instanceof Integer[]) {
            return transToArray((Integer[]) value, type);
        }

        if (value instanceof Boolean[]) {
            return transToArray((Boolean[]) value, type);
        }

        if (value instanceof byte[]) {
            return transToArray((byte[]) value, type);
        }

        if (value instanceof long[]) {
            return transToArray((long[]) value, type);
        }

        if (value instanceof double[]) {
            return transToArray((double[]) value, type);
        }

        if (value instanceof float[]) {
            return transToArray((float[]) value, type);
        }

        if (value instanceof short[]) {
            return transToArray((short[]) value, type);
        }

        if (value instanceof int[]) {
            return transToArray((int[]) value, type);
        }

        if (value instanceof boolean[]) {
            return transToArray((boolean[]) value, type);
        }
        return (T[]) Array.newInstance(type, 0);
    }

    /**
     * 将 byte[] 基本类型数组转换为指定元素类型的目标数组。
     *
     * @param value 源 byte 数组
     * @param type  目标元素类型
     * @param <T>   目标元素泛型类型
     * @return 转换后的数组
     */
    default <T> T[] transToArray(byte[] value, Class<T> type) {
        return ArrayUtils.transToArray(value, type);
    }

    /**
     * 将 long[] 基本类型数组转换为指定元素类型的目标数组。
     *
     * @param value 源 long 数组
     * @param type  目标元素类型
     * @param <T>   目标元素泛型类型
     * @return 转换后的数组
     */
    default <T> T[] transToArray(long[] value, Class<T> type) {
        return ArrayUtils.transToArray(value, type);
    }

    /**
     * 将 boolean[] 基本类型数组转换为指定元素类型的目标数组。
     *
     * @param value 源 boolean 数组
     * @param type  目标元素类型
     * @param <T>   目标元素泛型类型
     * @return 转换后的数组
     */
    default <T> T[] transToArray(boolean[] value, Class<T> type) {
        return ArrayUtils.transToArray(value, type);
    }

    /**
     * 将 short[] 基本类型数组转换为指定元素类型的目标数组。
     *
     * @param value 源 short 数组
     * @param type  目标元素类型
     * @param <T>   目标元素泛型类型
     * @return 转换后的数组
     */
    default <T> T[] transToArray(short[] value, Class<T> type) {
        return ArrayUtils.transToArray(value, type);
    }

    /**
     * 将 int[] 基本类型数组转换为指定元素类型的目标数组。
     *
     * @param value 源 int 数组
     * @param type  目标元素类型
     * @param <T>   目标元素泛型类型
     * @return 转换后的数组
     */
    default <T> T[] transToArray(int[] value, Class<T> type) {
        return ArrayUtils.transToArray(value, type);
    }

    /**
     * 将 double[] 基本类型数组转换为指定元素类型的目标数组。
     *
     * @param value 源 double 数组
     * @param type  目标元素类型
     * @param <T>   目标元素泛型类型
     * @return 转换后的数组
     */
    default <T> T[] transToArray(double[] value, Class<T> type) {
        return ArrayUtils.transToArray(value, type);
    }

    /**
     * 将 float[] 基本类型数组转换为指定元素类型的目标数组。
     *
     * @param value 源 float 数组
     * @param type  目标元素类型
     * @param <T>   目标元素泛型类型
     * @return 转换后的数组
     */
    default <T> T[] transToArray(float[] value, Class<T> type) {
        return ArrayUtils.transToArray(value, type);
    }

    /**
     * 将任意对象转换为 BigDecimal 中间值。
     * <p>支持以下类型的转换：</p>
     * <ul>
     *   <li>{@link Number} — 直接通过 toString 构造</li>
     *   <li>{@link Date}/{@link java.time.LocalDateTime}/{@link java.time.LocalDate}/{@link java.time.LocalTime} — 转为时间戳</li>
     *   <li>{@link java.awt.Color} — 转为 RGB 整数值</li>
     *   <li>{@link File} — 转为文件长度</li>
     *   <li>{@link String} — 通过 {@link #stringTransToBigDecimal(String)} 解析数字字符串、千分位、容量单位、中文数字等</li>
     *   <li>{@code byte[]} — 通过 BigInteger 转为 BigDecimal</li>
     *   <li>{@code char[]} — 直接构造 BigDecimal</li>
     * </ul>
     *
     * @param value 源值
     * @return BigDecimal 值，如果无法转换则返回 null
     */
    default BigDecimal transToBigDecimal(Object value) {
        if (isAssignableFrom(value, Number.class)) {
            return new BigDecimal(value.toString());
        }

        if (isAssignableFrom(value, Date.class)) {
            return new BigDecimal(((Date) value).getTime());
        }


        if (value.getClass().isPrimitive()) {
            return new BigDecimal(value.toString());
        }

        if (isAssignableFrom(value, Color.class)) {
            return new BigDecimal(((Color) value).getRGB());
        }

        if (isAssignableFrom(value, File.class)) {
            return new BigDecimal(((File) value).length());
        }

        try {
            if (isAssignableFrom(value, String.class)) {
                return stringTransToBigDecimal(value.toString());
            }
        } catch (Exception e) {
            return null;
        }

        if (isAssignableFrom(value, LocalDateTime.class)) {
            return new BigDecimal(DateUtils.toDate((LocalDateTime) value).getTime());
        }

        if (isAssignableFrom(value, LocalDate.class)) {
            return new BigDecimal(DateUtils.toDate((LocalDate) value).getTime());
        }

        if (isAssignableFrom(value, LocalTime.class)) {
            return new BigDecimal(DateUtils.toDate((LocalTime) value).getTime());
        }

        if (isAssignableFrom(value, byte[].class)) {
            try {
                BigInteger bigInteger = new BigInteger((byte[]) value);
                return new BigDecimal(bigInteger);
            } catch (Exception ignored) {
            }
        }

        if (isAssignableFrom(value, char[].class)) {
            try {
                return new BigDecimal((char[]) value);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * 将字符串解析为 BigDecimal。
     * <p>支持以下格式：</p>
     * <ul>
     *   <li>标准数字 — 如 "123.45"</li>
     *   <li>千分位格式 — 如 "1,234,567.89"</li>
     *   <li>存储容量单位 — 如 "10MB"、"1.5GB"（需配合 {@link #clearSize(String)}）</li>
     *   <li>中文数字 — 如 "十二万三千四百五十六"</li>
     *   <li>科学计数法 — 如 "1.23e4"</li>
     *   <li>尾部 f/F/d/D 标识的数字 — 如 "123.45f"</li>
     * </ul>
     *
     * @param value 字符串
     * @return BigDecimal 值，如果无法解析则返回 null
     */
    static BigDecimal stringTransToBigDecimal(String value) {
        if (NumberUtils.isNumber(value)) {
            return new BigDecimal(value);
        }

        if (NumberUtils.isThousandSeparator(value)) {
            return new BigDecimal(value.replaceAll(",", ""));
        }


        long size = 0;
        if (0 != (size = isSize(value))) {
            String newValue = clearSize(value);
            if (NumberUtils.isNumber(newValue)) {
                return NumberUtils.toBigDecimal(newValue).multiply(BigDecimal.valueOf(size));
            }
        }

        // 中文数字解析（优先使用新解析器）
        if (NumberUtils.isChineseNumber(value)) {
            try {
                return new BigDecimal(NumberUtils.parseChineseNumber(value));
            } catch (Exception ignored) {
            }
        }

        // 遗留：原有中文数字解析（兼容旧格式）
        try {
            return new BigDecimal(NumberUtils.getNumberFromChinese(value));
        } catch (Exception ignored) {
        }

        // Simple fallback: remove trailing f/F/d/D
        String trimmed = NU.matcher(value).replaceFirst("");
        if (!trimmed.isEmpty()) {
            try {
                return BigDecimal.valueOf(Double.parseDouble(trimmed));
            } catch (NumberFormatException ignore) {
            }
        }

        if (NumberUtils.isDecimals(value)) {
            try {
                if (value.contains(E)) {
                    return new BigDecimal(value);
                } else {
                    return BigDecimal.valueOf(Double.parseDouble(value));
                }
            } catch (Exception ignore) {
            }
        }
        try {
            return NumberUtils.converterNumber(value, BigDecimal.class);
        } catch (Exception ignored) {
        }
        return null;
    }


    /**
     * 去除字符串末尾的存储容量单位后缀。
     * <p>如 "10MB" → "10"。</p>
     *
     * @param valueStr 带有单位后缀的字符串
     * @return 去除单位后缀后的字符串
     */
    static String clearSize(String valueStr) {
        if (MAPPING.isEmpty()) {
            initial();
        }

        String upperCase = valueStr.toUpperCase();
        for (Map.Entry<String, Long> entry : MAPPING.entrySet()) {
            if (upperCase.endsWith(entry.getKey())) {
                return StringUtils.endWithMove(valueStr, entry.getKey());
            }
        }
        return valueStr;
    }

    /**
     * 判断字符串末尾的存储容量单位并返回对应的字节数。
     * <p>如 "10MB" 返回 1048576L。</p>
     *
     * @param valueStr 带有单位后缀的字符串
     * @return 对应的字节数，如果没有匹配的单位则返回 0
     */
    static long isSize(String valueStr) {
        if (MAPPING.isEmpty()) {
            initial();
        }

        String upperCase = valueStr.toUpperCase();
        for (Map.Entry<String, Long> entry : MAPPING.entrySet()) {
            if (upperCase.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return 0;
    }
}
