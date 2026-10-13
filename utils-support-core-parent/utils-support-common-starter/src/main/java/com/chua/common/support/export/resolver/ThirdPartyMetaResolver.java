package com.chua.common.support.export.resolver;

import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.spi.MetaResolver;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * 三方注解解析器 —— order = 0，介于兜底与自定义之间。
 *
 * <p><b>零编译期依赖</b>：以反射方式探测以下常见表格注解，类不存在时自动跳过。</p>
 *
 * <table border="1">
 *     <caption>已支持的三方注解</caption>
 *     <tr><th>注解全限定名</th><th>说明</th></tr>
 *     <tr><td>{@code cn.idev.excel.annotation.ExcelProperty}</td><td>FastExcel</td></tr>
 *     <tr><td>{@code com.alibaba.excel.annotation.ExcelProperty}</td><td>EasyExcel</td></tr>
 *     <tr><td>{@code cn.idev.excel.annotation.ExcelIgnore}</td><td>FastExcel</td></tr>
 *     <tr><td>{@code com.alibaba.excel.annotation.ExcelIgnore}</td><td>EasyExcel</td></tr>
 * </table>
 *
 * <p>解析规则（仅注册真正携带上述注解的成员，未标注的成员完全不影响结果）：</p>
 * <ul>
 *     <li>{@code ExcelProperty#value()} 非空 → 列标题</li>
 *     <li>{@code ExcelProperty#index()} &gt;= 0 → 列顺序</li>
 *     <li>{@code ExcelProperty#dateFormat()} 非空 → 日期格式</li>
 *     <li>{@code ExcelProperty#converter()} 非空 → 转换器类名</li>
 *     <li>{@code ExcelIgnore} 存在 → 忽略该列</li>
 * </ul>
 *
 * <p>这些结果随后会被 {@link ExportAnnotationMetaResolver} 覆盖。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"easyexcel", "fastexcel", "third-party"}, order = MetaResolverOrders.THIRD_PARTY)
public class ThirdPartyMetaResolver implements MetaResolver {

    /**
     * FastExcel 列注解。
     */
    private static final String FAST_EXCEL_PROPERTY = "cn.idev.excel.annotation.ExcelProperty";

    /**
     * FastExcel 忽略注解。
     */
    private static final String FAST_EXCEL_IGNORE = "cn.idev.excel.annotation.ExcelIgnore";

    /**
     * EasyExcel 列注解。
     */
    private static final String EASY_EXCEL_PROPERTY = "com.alibaba.excel.annotation.ExcelProperty";

    /**
     * EasyExcel 忽略注解。
     */
    private static final String EASY_EXCEL_IGNORE = "com.alibaba.excel.annotation.ExcelIgnore";

    /**
     * 已探测到的三方库标记。
     */
    private final boolean fastExcel;

    /**
     * 已探测到的三方库标记。
     */
    private final boolean easyExcel;

    /**
     * 构造解析器并探测类路径。
     */
    public ThirdPartyMetaResolver() {
        this.fastExcel = ClassUtils.isPresent(FAST_EXCEL_PROPERTY);
        this.easyExcel = ClassUtils.isPresent(EASY_EXCEL_PROPERTY);
    }

    @Override
    /**
     * 优先级
     * @return 优先级 对象
     */
    public int order() {
        return MetaResolverOrders.THIRD_PARTY;
    }

    @Override
    /**
     * 是否支持
     * @param type 数据行类型
     * @return 是否支持 对象
     */
    public boolean supports(Class<?> type) {
        return (fastExcel || easyExcel) && type != null && !type.isPrimitive();
    }

    @Override
    /**
     * 解析
     * @param type 数据行类型
     * @param meta 元数据容器
     */
    public void resolve(Class<?> type, DocumentMeta meta) {
        for (Field field : allFields(type)) {
            Annotation[] annotations = annotations(field);
            if (!supported(annotations)) {
                continue;
            }
            apply(meta.column(field.getName()).field(field), annotations);
        }
        for (Method method : allMethods(type)) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            String property = property(method);
            if (property == null) {
                continue;
            }
            Annotation[] annotations = annotations(method);
            if (!supported(annotations)) {
                continue;
            }
            apply(meta.column(property).getter(method), annotations);
        }
    }

    /**
     * 成员上是否携带受支持的三方注解。
     *
     * <p>没有则完全交由其他解析器处理，避免本解析器凭空注册空列。</p>
     *
     * @param annotations 成员上的全部注解
     * @return 是否携带受支持注解
     */
    private static boolean supported(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getName();
            if (FAST_EXCEL_PROPERTY.equals(name) || EASY_EXCEL_PROPERTY.equals(name)
                    || FAST_EXCEL_IGNORE.equals(name) || EASY_EXCEL_IGNORE.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 应用单个成员的第三方注解。
     *
     * @param column      列元数据
     * @param annotations 成员上的全部注解
     */
    private void apply(ColumnMeta column, Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getName();
            switch (name) {
                case FAST_EXCEL_PROPERTY, EASY_EXCEL_PROPERTY -> applyProperty(column, annotation);
                case FAST_EXCEL_IGNORE, EASY_EXCEL_IGNORE -> column.ignore(true);
                default -> {
                    // 非目标注解，忽略
                }
            }
        }
    }

    /**
     * 应用 {@code ExcelProperty}。
     *
     * @param column     列元数据
     * @param annotation 注解实例
     */
    private void applyProperty(ColumnMeta column, Annotation annotation) {
        try {
            Method value = annotation.annotationType().getMethod("value");
            value.setAccessible(true);
            Object names = value.invoke(annotation);
            if (names instanceof String[] texts && texts.length > 0 && texts[0] != null && !texts[0].isBlank()) {
                column.name(texts[0]);
            } else if (names instanceof CharSequence text && !text.toString().isBlank()) {
                column.name(text.toString());
            }
        } catch (Exception ignored) {
            // 旧版本 ExcelProperty 没有 value 方法，或反射受限
        }
        column.order(invokeInt(annotation, "index", -1));
        column.dateFormat(invokeString(annotation, "dateFormat"));
        column.converter(invokeClassName(annotation, "converter"));
    }

    /**
     * 读取注解的 int 属性。
     *
     * @param annotation 注解
     * @param method     方法名
     * @param fallback   默认值
     * @return 属性值
     */
    private int invokeInt(Annotation annotation, String method, int fallback) {
        try {
            java.lang.reflect.Method accessor = annotation.annotationType().getMethod(method);
            accessor.setAccessible(true);
            Object value = accessor.invoke(annotation);
            return value instanceof Integer number ? number : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 读取注解的 String 属性。
     *
     * @param annotation 注解
     * @param method     方法名
     * @return 属性值，失败返回空串
     */
    private String invokeString(Annotation annotation, String method) {
        try {
            java.lang.reflect.Method accessor = annotation.annotationType().getMethod(method);
            accessor.setAccessible(true);
            Object value = accessor.invoke(annotation);
            return value instanceof String text ? text : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读取注解的 Class 属性并转为类名。
     *
     * @param annotation 注解
     * @param method     方法名
     * @return 类名，失败返回空串
     */
    private String invokeClassName(Annotation annotation, String method) {
        try {
            java.lang.reflect.Method accessor = annotation.annotationType().getMethod(method);
            accessor.setAccessible(true);
            Object value = accessor.invoke(annotation);
            return value instanceof Class<?> type ? type.getName() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读取字段上的全部注解。
     *
     * @param field 字段
     * @return 注解数组
     */
    private static Annotation[] annotations(Field field) {
        return field.getAnnotations();
    }

    /**
     * 读取方法上的全部注解。
     *
     * @param method 方法
     * @return 注解数组
     */
    private static Annotation[] annotations(Method method) {
        return method.getAnnotations();
    }

    /**
     * 由 getter 方法名推导属性名，非 getter 返回 {@code null}。
     *
     * @param method 方法
     * @return 属性名
     */
    private static String property(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3 && method.getReturnType() != void.class) {
            return decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return decapitalize(name.substring(2));
        }
        return null;
    }

    /**
     * 首字母小写。
     *
     * @param name 名称
     * @return 小写化结果
     */
    private static String decapitalize(String name) {
        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    /**
     * 收集类型及父类的全部实例字段。
     *
     * @param type 类型
     * @return 字段数组
     */
    private Field[] allFields(Class<?> type) {
        List<Field> fields = com.chua.common.support.collection.wrap.Wraps.list();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }

    /**
     * 收集类型及父类的全部无参实例方法。
     *
     * @param type 类型
     * @return 方法数组
     */
    private Method[] allMethods(Class<?> type) {
        List<Method> methods = com.chua.common.support.collection.wrap.Wraps.list();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                method.setAccessible(true);
                methods.add(method);
            }
            current = current.getSuperclass();
        }
        return methods.toArray(new Method[0]);
    }
}