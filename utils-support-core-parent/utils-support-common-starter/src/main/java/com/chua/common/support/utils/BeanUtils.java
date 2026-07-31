package com.chua.common.support.utils;

import com.chua.common.support.annotation.FieldProperty;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.converter.FieldConverter;
import com.chua.common.support.converter.FieldMappingContext;
import com.chua.common.support.lang.bean.BeanCopier;
import com.chua.common.support.exception.BeanNotInstantiationException;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.ServiceProvider;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bean 工具类，提供对象属性复制等操作。
 *
 * <p>内部通过 SPI 机制自动选择最优的 {@link BeanCopier} 实现：
 * <ul>
 *   <li>当 ASM 可用时优先使用 {@code asm} 实现（字节码生成，性能最高）</li>
 *   <li>回退使用 {@code jdk} 实现（反射，通用性最强）</li>
 * </ul>
 * </p>
 *
 * <p>支持通过 {@link FieldProperty @FieldProperty} 注解控制字段映射行为，
 * 包括列名映射、日期格式化、默认值、自定义转换器等。</p>
 *
 * @author CH
 * @since 1.0.0
 */
@SuppressWarnings("ALL")
public final class BeanUtils {

    private static final BeanCopier COPIER;

    static {
        ServiceProvider<BeanCopier> provider = ServiceProvider.of(BeanCopier.class);
        BeanCopier copier = provider.getExtension("asm");
        if (copier == null) {
            copier = provider.getExtension("jdk");
        }
        COPIER = copier;
    }

    private BeanUtils() {
    }

    /**
     * 将源对象的属性复制到目标对象中。
     */
    public static <T> T copyProperties(Object source, Class<T> target) {
        if (COPIER != null && target != null) {
            T t = ClassUtils.newInstance(target);
            if (null != t) {
                copyProperties(source, t);
                return t;
            }
        }
        throw new BeanNotInstantiationException("Failed to instantiate target bean of type " + target.getName());

    }

    /**
     * 将源对象的属性复制到目标对象中。
     */
    public static void copyProperties(Object source, Object target) {
        if (COPIER != null) {
            COPIER.copyProperties(source, target);
        }
    }

    /**
     * 将源对象的属性复制到目标对象中，忽略指定属性。
     */
    public static void copyProperties(Object source, Object target, String... ignoreProperties) {
        if (COPIER != null) {
            COPIER.copyProperties(source, target, ignoreProperties);
        }
    }

    /**
     * 将 Map 中的值复制到目标对象的属性中。
     */
    public static void copyProperties(Map<String, Object> sourceMap, Object target) {
        if (COPIER != null) {
            COPIER.copyProperties(sourceMap, target);
        }
    }

    /**
     * 将源对象的属性复制到目标 Map 中。
     */
    public static void copyProperties(Object source, Map<String, Object> target) {
        if (COPIER != null) {
            COPIER.copyProperties(source, target);
        }
    }

    /**
     * 将源对象列表批量复制为目标类型列表。
     */
    public static <S, T> List<T> copyPropertiesList(List<S> sourceList, Class<T> targetClass) {
        if (sourceList == null) {
            return List.of();
        }
        return sourceList.stream().map(source -> copyProperties(source, targetClass)).toList();
    }

    /**
     * 将对象转为 Map（默认无上下文）。
     * <p>如果字段标注了 {@link FieldProperty @FieldProperty}，则使用注解的映射规则。</p>
     *
     * @param source 源对象
     * @return Map
     */
    public static Map<String, Object> objectToMap(Object source) {
        return objectToMap(source, null);
    }

    /**
     * 将对象转为 Map（含上下文数据）。
     * <p>字段标注了 {@link FieldProperty @FieldProperty} 时：
     * <ol>
     *   <li>使用 {@code value/name} 作为映射后的列名</li>
     *   <li>使用 {@code fmt} 格式化日期字段</li>
     *   <li>字段值 null 时使用 {@code defaultValue}，支持 {@code #{key}} 从 context 获取</li>
     *   <li>使用 {@code writer} 指定的转换器进行写入转换（优先级高于 defaultValue 和原始值）</li>
     * </ol>
     * </p>
     *
     * @param source  源对象
     * @param context 上下文数据（用于 #{key} 表达式和转换器）
     * @return Map
     */
    public static Map<String, Object> objectToMap(Object source, Map<String, Object> context) {
        if (source == null) {
            return null;
        }
        if (source instanceof Map) {
            return (Map<String, Object>) source;
        }
        // 先通过 BeanCopier 复制所有属性到 Map
        Map<String, Object> result = new LinkedHashMap<>();
        copyProperties(source, result);

        // 检查 @FieldProperty 注解，进行字段映射覆盖
        Class<?> clazz = source.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            FieldProperty fp = field.getAnnotation(FieldProperty.class);
            if (fp == null) continue;

            String mappedName = resolveMappedName(fp, field);
            Object rawValue = result.remove(field.getName());

            // 构建上下文（含 originalValue 供转换器使用）
            FieldMappingContext ctx = FieldMappingContext.builder()
                    .fieldName(field.getName())
                    .mappedName(mappedName)
                    .format(resolveFormat(fp, rawValue))
                    .defaultValue(fp.defaultValue())
                    .context(context)
                    .originalValue(rawValue)
                    .build();

            // 应用 Writer 转换器（优先级最高）
            Object value = applyWriterConverter(fp, rawValue, ctx);
            if (value == null) {
                // 应用默认值（含 #{key} 上下文解析）
                value = resolveDefaultValue(fp, ctx);
            }
            if (value == null) {
                // 回退到原始字段值
                value = rawValue;
            }

            // 应用日期格式化
            value = applyDateFormat(value, fp);

            result.put(mappedName, value);
        }
        return result;
    }

    /**
     * 获取字段的映射列名。
     */
    private static String resolveMappedName(FieldProperty fp, Field field) {
        if (!fp.value().isEmpty()) return fp.value();
        if (!fp.name().isEmpty()) return fp.name();
        return field.getName();
    }

    /**
     * 解析格式。如果字段值本身已有格式信息（如 LocalDate），优先使用。
     */
    private static String resolveFormat(FieldProperty fp, Object rawValue) {
        if (!fp.fmt().isEmpty()) return fp.fmt();
        return "";
    }

    /**
     * 应用 Writer 转换器。如果成功返回非 null，直接使用转换结果。
     */
    private static Object applyWriterConverter(FieldProperty fp, Object rawValue, FieldMappingContext ctx) {
        Class<? extends FieldConverter> converterClass = fp.writer();
        if (converterClass == null || converterClass == FieldConverter.class) {
            return null; // 没有配置转换器
        }
        try {
            FieldConverter converter = converterClass.getDeclaredConstructor().newInstance();
            return converter.convert(rawValue, ctx);
        } catch (Exception e) {
            // 转换器失败，回退到默认处理
            return null;
        }
    }

    /**
     * 解析默认值。支持 #{key} 从上下文获取数据。
     */
    private static Object resolveDefaultValue(FieldProperty fp, FieldMappingContext ctx) {
        String dv = fp.defaultValue();
        if (dv == null || dv.isEmpty()) return null;

        // 处理 #{key} 表达式
        if (dv.startsWith("#{") && dv.endsWith("}")) {
            String key = dv.substring(2, dv.length() - 1).trim();
            Map<String, Object> context = ctx.getContext();
            if (context != null && context.containsKey(key)) {
                return context.get(key);
            }
        }
        return dv;
    }

    /**
     * 应用日期格式化。
     */
    private static Object applyDateFormat(Object value, FieldProperty fp) {
        if (value == null) return null;
        String fmt = fp.fmt();
        if (fmt.isEmpty()) return value;

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(fmt);
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(formatter);
        }
        if (value instanceof LocalDate ld) {
            return ld.format(formatter);
        }
        if (value instanceof Date d) {
            return new java.text.SimpleDateFormat(fmt).format(d);
        }
        if (value instanceof java.time.temporal.Temporal temporal) {
            return formatter.format(temporal);
        }
        return value;
    }

    /**
     * 将源对象尽可能转换为目标类型的新实例。
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Object source, Class<T> targetType) {
        if (source == null) {
            return null;
        }
        if (targetType.isInstance(source)) {
            return (T) source;
        }

        var converted = Converter.convertIfNecessary(source, targetType);
        if (converted != null) {
            return converted;
        }

        if (source instanceof String && Json.isJson((String) source) && !targetType.isAssignableFrom(String.class)) {
            try {
                return Json.fromJson((String) source, targetType);
            } catch (Exception ignored) {
            }
        }

        try {
            return copyProperties(source, targetType);
        } catch (Exception ignored) {
            return null;
        }
    }
}
