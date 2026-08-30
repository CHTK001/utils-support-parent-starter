package com.chua.common.support.lang.json.annotation;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.ClassUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一门户注解的 Bean ↔ Map 桥接转换器。
 *
 * <p>为无法原生识别自定义注解的 {@code JsonProvider} 实现（如 fory-json）提供统一注解适配能力：
 * 将普通 Bean 对象转换为 {@code Map} 时按 {@link JsonName} 重命名、{@link JsonIgnore} 跳过字段、
 * {@link JsonFormat} 格式化日期；反向转换时按注解还原字段值。</p>
 *
 * <p>适配规则（与 {@code JsonProvider} 契约一致）：</p>
 * <ul>
 *     <li>{@link JsonName} — 字段序列化 / 反序列化的键名</li>
 *     <li>{@link JsonIgnore} — 双向忽略字段</li>
 *     <li>{@link JsonFormat} — 日期时间字段的格式化 pattern</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class JsonBeanMapper {

    /** 创建 JsonBeanMapper 实例 */
    private JsonBeanMapper() {
    }

    /**
     * 将 Bean 对象转换为 Map，应用统一门户注解规则。
     *
     * @param bean 待转换的对象
     * @return 转换后的 Map，bean 为 null 或基本类型时返回 null / 原值
     */
    public static Object toMap(Object bean) {
        if (bean == null) {
            return null;
        }
        if (isBasic(bean.getClass())) {
            return bean;
        }
        if (bean instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) bean;
            Map<String, Object> result = new LinkedHashMap<>(source.size());
            source.forEach((k, v) -> result.put(String.valueOf(k), toMap(v)));
            return result;
        }
        if (bean instanceof Collection) {
            java.util.List<Object> result = new java.util.ArrayList<>();
            for (Object item : (Collection<?>) bean) {
                result.add(toMap(item));
            }
            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Field field : ClassUtils.getFields(bean.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            JsonIgnore ignore = field.getAnnotation(JsonIgnore.class);
            if (ignore != null) {
                continue;
            }
            JsonName jsonName = field.getAnnotation(JsonName.class);
            String key = (jsonName != null && !jsonName.value().isEmpty()) ? jsonName.value() : field.getName();
            Object value = readField(field, bean);
            if (value == null) {
                continue;
            }
            JsonFormat jsonFormat = field.getAnnotation(JsonFormat.class);
            if (jsonFormat != null && !jsonFormat.value().isEmpty() && isDateType(value.getClass())) {
                value = formatDate(value, jsonFormat.value());
            } else if (isBasic(value.getClass())) {
                value = value;
            } else {
                value = toMap(value);
            }
            result.put(key, value);
        }
        return result;
    }

    /**
     * 将 Map 反向填充为 Bean 对象，应用统一门户注解规则。
     *
     * @param source Map 数据
     * @param target 目标类型
     * @param <T>    目标类型泛型
     * @return 填充后的 Bean 对象
     */
    public static <T> T fromMap(Map<String, Object> source, Class<T> target) {
        if (source == null) {
            return null;
        }
        T instance = ClassUtils.newInstance(target);
        if (instance == null) {
            return null;
        }
        for (Field field : ClassUtils.getFields(target)) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            JsonIgnore ignore = field.getAnnotation(JsonIgnore.class);
            if (ignore != null) {
                continue;
            }
            JsonName jsonName = field.getAnnotation(JsonName.class);
            String key = (jsonName != null && !jsonName.value().isEmpty()) ? jsonName.value() : field.getName();
            Object value = source.get(key);
            if (value == null) {
                continue;
            }
            JsonFormat jsonFormat = field.getAnnotation(JsonFormat.class);
            if (jsonFormat != null && !jsonFormat.value().isEmpty() && isDateType(field.getType())) {
                value = parseDate(value, field.getType(), jsonFormat.value());
            }
            writeField(field, instance, value);
        }
        return instance;
    }

    /**
     * 判断类型是否为基本类型 / 包装类型 / 字符串 / 枚举。
     *
     * @param type 类型
     * @return true 表示为基本类型
     */
    private static boolean isBasic(Class<?> type) {
        return type.isPrimitive() || type.isEnum()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class.isAssignableFrom(type)
                || Character.class.isAssignableFrom(type)
                || Date.class.isAssignableFrom(type)
                || java.util.Calendar.class.isAssignableFrom(type)
                || java.math.BigDecimal.class.isAssignableFrom(type)
                || java.math.BigInteger.class.isAssignableFrom(type);
    }

    /**
     * 判断是否为日期时间类型。
     *
     * @param type 类型
     * @return true 表示为日期时间类型
     */
    private static boolean isDateType(Class<?> type) {
        return Date.class.isAssignableFrom(type)
                || LocalDateTime.class.isAssignableFrom(type)
                || LocalDate.class.isAssignableFrom(type)
                || LocalTime.class.isAssignableFrom(type);
    }

    /**
     * 按 pattern 格式化日期时间值。
     *
     * @param value   日期时间值
     * @param pattern 格式 pattern
     * @return 格式化后的字符串
     */
    private static String formatDate(Object value, String pattern) {
        if (value instanceof Date) {
            return new java.text.SimpleDateFormat(pattern).format((Date) value);
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(formatter);
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).format(formatter);
        }
        if (value instanceof LocalTime) {
            return ((LocalTime) value).format(formatter);
        }
        return String.valueOf(value);
    }

    /**
     * 将值解析为指定日期时间类型。
     *
     * @param value   字符串或日期值
     * @param type    目标日期类型
     * @param pattern 格式 pattern
     * @return 解析后的日期值
     */
    private static Object parseDate(Object value, Class<?> type, String pattern) {
        if (Date.class.isAssignableFrom(type)) {
            if (value instanceof Date) {
                return value;
            }
            try {
                return new java.text.SimpleDateFormat(pattern).parse(String.valueOf(value));
            } catch (Exception e) {
                return value;
            }
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        try {
            if (LocalDateTime.class.isAssignableFrom(type)) {
                return LocalDateTime.parse(String.valueOf(value), formatter);
            }
            if (LocalDate.class.isAssignableFrom(type)) {
                return LocalDate.parse(String.valueOf(value), formatter);
            }
            if (LocalTime.class.isAssignableFrom(type)) {
                return LocalTime.parse(String.valueOf(value), formatter);
            }
        } catch (Exception e) {
            return value;
        }
        return value;
    }

    /**
     * 读取字段值（优先 getter，否则反射直接读）。
     *
     * @param field 字段
     * @param bean  目标对象
     * @return 字段值
     */
    private static Object readField(Field field, Object bean) {
        String getter = findGetterName(field);
        if (getter != null) {
            try {
                return ReflectUtils.invoke(bean, getter, Object.class);
            } catch (Exception ignore) {
            }
        }
        try {
            ClassUtils.setAccessible(field);
            return ClassUtils.getFieldValue(field, bean);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 写入字段值（优先 setter，否则反射直接写）。
     *
     * @param field 字段
     * @param bean  目标对象
     * @param value 值
     */
    private static void writeField(Field field, Object bean, Object value) {
        String setter = findSetterName(field);
        if (setter != null) {
            try {
                ReflectUtils.invoke(bean, setter, void.class, new Class<?>[]{field.getType()}, Converter.convertIfNecessary(value, field.getType()));
                return;
            } catch (Exception ignore) {
            }
        }
        try {
            ClassUtils.setAccessible(field);
            ClassUtils.setFieldValue(field, value, bean);
        } catch (Exception ignore) {
        }
    }

    /**
     * 查找字段的 getter 方法名（未找到返回 null）。
     *
     * @param field 字段
     * @return getter 方法名，不存在返回 null
     */
    private static String findGetterName(Field field) {
        String name = capitalize(field.getName());
        for (String prefix : new String[]{"get", "is"}) {
            String methodName = prefix + name;
            try {
                Class<?> clazz = field.getDeclaringClass();
                Object result = ReflectUtils.invoke(clazz, methodName, field.getType());
                if (result != null && field.getType().isAssignableFrom(result.getClass())) {
                    return methodName;
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    /**
     * 查找字段的 setter 方法名（未找到返回 null）。
     *
     * @param field 字段
     * @return setter 方法名，不存在返回 null
     */
    private static String findSetterName(Field field) {
        String name = capitalize(field.getName());
        try {
            Class<?> clazz = field.getDeclaringClass();
            Class<?> setterParamType = field.getType();
            ReflectUtils.findMethodHandle(clazz, "set" + name, void.class, setterParamType);
            return "set" + name;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将字段名首字母大写。
     *
     * @param name 字段名
     * @return 首字母大写后的名称
     */
    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
