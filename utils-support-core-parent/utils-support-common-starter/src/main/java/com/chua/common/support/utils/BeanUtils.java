package com.chua.common.support.utils;

import com.chua.common.support.annotation.FieldProperty;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.converter.FieldConverter;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.converter.FieldMappingContext;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.exception.BeanNotInstantiationException;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.bean.BeanCopier;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
   * Bean 工具类，提供对象属性复制、对象与 映射 互转、对象类型转换等常用操作。
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
 * @since 4.0.0.42
 */
@SuppressWarnings("unchecked")
public final class BeanUtils {

    /**
      * 默认 Beancopier SPI 扩展名（asm 字节码实现）。
     */
    private static final String ASM_COUPIER = "asm";

    /**
      * 回退 Beancopier SPI 扩展名（jdk 反射实现）。
     */
    private static final String JDK_COUPIER = "jdk";

    /**
     * 上下文占位符起始标记：{@code #{}}。
     */
    private static final String CONTEXT_PLACEHOLDER_PREFIX = "#{";

    /**
     * 上下文占位符结束标记：{@code }}。
     */
    private static final String CONTEXT_PLACEHOLDER_SUFFIX = "}";

    /**
     * 上下文占位符起始标记长度。
     */
    private static final int CONTEXT_PLACEHOLDER_PREFIX_LENGTH = 2;

    /**
     * 全局共享的 {@link BeanCopier} 实例，在类加载阶段通过 SPI 选择最优实现。
     */
    private static final BeanCopier COPIER;

    static {
        ServiceProvider<BeanCopier> provider = ServiceProvider.of(BeanCopier.class);
        BeanCopier copier = provider.getExtension(ASM_COUPIER);
        if (ObjectUtils.isNull(copier)) {
            copier = provider.getExtension(JDK_COUPIER);
        }
        COPIER = copier;
    }

    /** 创建 Bean工具 实例 */
    private BeanUtils() {
    }

    /**
     * 将源对象的属性复制到目标类型的全新实例中。
     *
     * @param source 源对象，允许为 {@code null}
     * @param target 目标类型 类 对象
     * @param <T>    目标类型泛型
     * @return 复制完成的目标对象
     * @throws BeanNotInstantiationException 当无法实例化目标类型时抛出
     */
    public static <T> T copyProperties(Object source, Class<T> target) {
        if (ObjectUtils.isNull(COPIER) || ObjectUtils.isNull(target)) {
            throw new BeanNotInstantiationException("Failed to instantiate target bean of type " + target.getName());
        }
        T t = ClassUtils.newInstance(target);
        if (ObjectUtils.isNull(t)) {
            throw new BeanNotInstantiationException("Failed to instantiate target bean of type " + target.getName());
        }
        copyProperties(source, t);
        return t;
    }

    /**
     * 将源对象的属性复制到目标对象中。
     *
     * @param source 源对象
     * @param target 目标对象
     */
    public static void copyProperties(Object source, Object target) {
        if (!ObjectUtils.isNull(COPIER)) {
            COPIER.copyProperties(source, target);
        }
    }

    /**
     * 将源对象的属性复制到目标对象中，忽略指定属性。
     *
     * @param source           源对象
     * @param target           目标对象
     * @param ignoreProperties 需要忽略复制的属性名
     */
    public static void copyProperties(Object source, Object target, String... ignoreProperties) {
        if (!ObjectUtils.isNull(COPIER)) {
            COPIER.copyProperties(source, target, ignoreProperties);
        }
    }

    /**
      * 将 映射 中的值复制到目标对象的属性中。
     *
     * @param sourceMap 源 映射
     * @param target    目标对象
     */
    public static void copyProperties(Map<String, Object> sourceMap, Object target) {
        if (!ObjectUtils.isNull(COPIER)) {
            COPIER.copyProperties(sourceMap, target);
        }
    }

    /**
      * 将源对象的属性复制到目标 映射 中。
     *
     * @param source 源对象
     * @param target 目标 映射
     */
    public static void copyProperties(Object source, Map<String, Object> target) {
        if (!ObjectUtils.isNull(COPIER)) {
            COPIER.copyProperties(source, target);
        }
    }

    /**
     * 将源对象列表批量复制为目标类型列表。
     *
     * @param sourceList  源对象列表
     * @param targetClass 目标类型 类 对象
     * @param <S>         源类型泛型
     * @param <T>         目标类型泛型
     * @return 复制完成的目标类型列表，源列表为空时返回空列表
     */
    public static <S, T> List<T> copyPropertiesList(List<S> sourceList, Class<T> targetClass) {
        if (CollectionUtils.isEmpty(sourceList)) {
            return List.of();
        }
        return sourceList.stream().map(source -> copyProperties(source, targetClass)).toList();
    }

    /**
      * 将对象转为 映射（默认无上下文）。
     *
     * <p>如果字段标注了 {@link FieldProperty @FieldProperty}，则使用注解的映射规则。</p>
     *
     * @param source 源对象
     * @return 转换后的 映射
     */
    public static Map<String, Object> objectToMap(Object source) {
        return objectToMap(source, null);
    }

    /**
      * 将对象转为 映射（含上下文数据）。
     *
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
     * @param context 上下文数据（用于 #{键} 表达式和转换器）
     * @return 转换后的 映射
     */
    public static Map<String, Object> objectToMap(Object source, Map<String, Object> context) {
        if (ObjectUtils.isNull(source)) {
            return null;
        }
        if (source instanceof Map) {
            return (Map<String, Object>) source;
        }
 // 先通过 Beancopier 复制所有属性到 映射
        Map<String, Object> result = new LinkedHashMap<>();
        copyProperties(source, result);

 // 检查 @字段财产 注解，进行字段映射覆盖
        Class<?> clazz = source.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            FieldProperty fp = field.getAnnotation(FieldProperty.class);
            if (ObjectUtils.isNull(fp)) {
                continue;
            }

            String mappedName = resolveMappedName(fp, field);
            Object rawValue = result.remove(field.getName());

 // 构建上下文（含 原始值 供转换器使用）
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
            if (ObjectUtils.isNull(value)) {
 // 应用默认值（含 #{键} 上下文解析）
                value = resolveDefaultValue(fp, ctx);
            }
            if (ObjectUtils.isNull(value)) {
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
     * 获取字段的映射列名。优先取 {@code value}，其次 {@code name}，最后回退到字段原名。
     *
     * @param fp    字段上的 {@link FieldProperty} 注解
     * @param field 字段对象
     * @return 映射后的列名
     */
    private static String resolveMappedName(FieldProperty fp, Field field) {
        if (!StringUtils.isEmpty(fp.value())) {
            return fp.value();
        }
        if (!StringUtils.isEmpty(fp.name())) {
            return fp.name();
        }
        return field.getName();
    }

    /**
      * 解析格式。如果字段值本身已有格式信息（如 本地日期），优先使用。
     *
     * @param fp       字段上的 {@link FieldProperty} 注解
     * @param rawValue 字段原始值（未使用，保留以备扩展）
     * @return 格式字符串，未配置时返回空串
     */
    private static String resolveFormat(FieldProperty fp, Object rawValue) {
        if (!StringUtils.isEmpty(fp.fmt())) {
            return fp.fmt();
        }
        return "";
    }

    /**
      * 应用 Writer 转换器。如果成功返回非 空，直接使用转换结果。
     *
     * @param fp       字段上的 {@link FieldProperty} 注解
     * @param rawValue 字段原始值
     * @param ctx      字段映射上下文
     * @return 转换结果；无转换器或转换失败时返回 {@code null}
     */
    private static Object applyWriterConverter(FieldProperty fp, Object rawValue, FieldMappingContext ctx) {
        Class<? extends FieldConverter> converterClass = fp.writer();
        if (ObjectUtils.isNull(converterClass) || FieldConverter.class.equals(converterClass)) {
 // 没有配置转换器，直接返回 空
            return null;
        }
        try {
            FieldConverter converter = ReflectUtils.instantiate(converterClass);
            return converter.convert(rawValue, ctx);
        } catch (Exception e) {
            // 转换器失败，回退到默认处理
            return null;
        }
    }

    /**
     * 解析默认值。支持 {@code #{key}} 从上下文获取数据。
     *
     * @param fp  字段上的 {@link FieldProperty} 注解
     * @param ctx 字段映射上下文
     * @return 解析后的默认值；未配置时返回 {@code null}
     */
    private static Object resolveDefaultValue(FieldProperty fp, FieldMappingContext ctx) {
        String dv = fp.defaultValue();
        if (StringUtils.isEmpty(dv)) {
            return null;
        }

 // 处理 #{键} 表达式
        if (dv.startsWith(CONTEXT_PLACEHOLDER_PREFIX) && dv.endsWith(CONTEXT_PLACEHOLDER_SUFFIX)) {
            String key = dv.substring(CONTEXT_PLACEHOLDER_PREFIX_LENGTH, dv.length() - 1).trim();
            Map<String, Object> context = ctx.getContext();
            if (!MapUtils.isEmpty(context) && context.containsKey(key)) {
                return context.get(key);
            }
        }
        return dv;
    }

    /**
     * 应用日期格式化。支持 {@link LocalDateTime}、{@link LocalDate}、{@link Date}、
     * 以及 {@link java.time.temporal.Temporal} 类型。
     *
     * @param value 原始值
     * @param fp    字段上的 {@link FieldProperty} 注解
     * @return 格式化后的字符串；无需格式化或类型不匹配时原样返回
     */
    private static Object applyDateFormat(Object value, FieldProperty fp) {
        if (ObjectUtils.isNull(value)) {
            return null;
        }
        String fmt = fp.fmt();
        if (StringUtils.isEmpty(fmt)) {
            return value;
        }

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
     *
     * @param source     源对象
     * @param targetType 目标类型 类 对象
     * @param <T>        目标类型泛型
     * @return 转换后的目标对象，无法转换时返回 {@code null}
     */
    public static <T> T convert(Object source, Class<T> targetType) {
        if (ObjectUtils.isNull(source)) {
            return null;
        }
        if (targetType.isInstance(source)) {
            return (T) source;
        }

        var converted = Converter.convertIfNecessary(source, targetType);
        if (!ObjectUtils.isNull(converted)) {
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
