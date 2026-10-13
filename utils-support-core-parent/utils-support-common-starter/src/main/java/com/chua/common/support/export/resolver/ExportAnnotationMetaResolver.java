package com.chua.common.support.export.resolver;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.annotation.ExportColumn;
import com.chua.common.support.export.annotation.ExportSheet;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.spi.MetaResolver;
import com.chua.common.support.spi.annotations.Spi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * 自定义注解解析器 —— order = 100，最后执行，<b>最终覆盖</b>。
 *
 * <p>读取 {@link ExportColumn} / {@link ExportSheet}。只有注解中显式声明的属性
 * 才会写入，未声明的列保持三方注解或反射兜底的结果。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"export", "chua", "annotation"}, order = MetaResolverOrders.CUSTOM)
public class ExportAnnotationMetaResolver implements MetaResolver {

    @Override
    /**
     * 优先级
     * @return 优先级 对象
     */
    public int order() {
        return MetaResolverOrders.CUSTOM;
    }

    @Override
    /**
     * 是否支持
     *
     * <p>始终返回 {@code true}：类上可能只有 {@link ExportColumn} 而没有
     * {@link ExportSheet}，若以类注解作为门禁会漏解析。{@link #resolve} 内部
     * 已按注解存在与否跳过，成本可忽略。</p>
     *
     * @param type 数据行类型
     * @return 是否支持 对象
     */
    public boolean supports(Class<?> type) {
        return type != null && !type.isPrimitive();
    }

    @Override
    /**
     * 解析
     * @param type 数据行类型
     * @param meta 元数据容器
     */
    public void resolve(Class<?> type, DocumentMeta meta) {
        ExportSheet sheet = type.getAnnotation(ExportSheet.class);
        if (sheet != null) {
            meta.sheetName(sheet.name());
            meta.title(sheet.title());
            meta.headerRow(sheet.headerRow());
            meta.header(sheet.header());
            meta.freezeHeader(sheet.freezeHeader());
            meta.autoFilter(sheet.autoFilter());
            meta.rowsPerSheet(sheet.rowsPerSheet());
        }
        for (Field field : allFields(type)) {
            ExportColumn annotation = field.getAnnotation(ExportColumn.class);
            if (annotation == null) {
                continue;
            }
            ColumnMeta column = meta.column(field.getName());
            column.field(field);
            apply(column, annotation);
        }
        for (Method method : allMethods(type)) {
            ExportColumn annotation = method.getAnnotation(ExportColumn.class);
            if (annotation == null || method.getParameterCount() != 0) {
                continue;
            }
            ColumnMeta column = meta.column(property(method));
            column.getter(method);
            apply(column, annotation);
        }
    }

    /**
     * 把注解声明的属性写入列元数据。
     *
     * @param column     列元数据
     * @param annotation 注解
     */
    private void apply(ColumnMeta column, ExportColumn annotation) {
        column.name(annotation.name());
        if (annotation.order() != ExportColumn.UNSET) {
            column.order(annotation.order());
        }
        if (annotation.width() != ExportColumn.UNSET) {
            column.width(annotation.width());
        }
        column.ignore(annotation.ignore());
        column.type(annotation.type());
        column.dateFormat(annotation.dateFormat());
        column.enumNames(annotation.enumNames());
        column.prefix(annotation.prefix());
        column.suffix(annotation.suffix());
        column.defaultValue(annotation.defaultValue());
        column.align(annotation.align());
        column.wrap(annotation.wrap());
        column.converter(annotation.converter());
        column.expression(annotation.expression());
        column.tag(annotation.tag());
        column.scale(annotation.scale());
    }

    /**
     * 收集类型及父类的全部实例字段。
     *
     * @param type 类型
     * @return 字段数组
     */
    private Field[] allFields(Class<?> type) {
        List<Field> fields = Wraps.list();
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
     * 收集类型及父类的全部无参方法。
     *
     * @param type 类型
     * @return 方法数组
     */
    private Method[] allMethods(Class<?> type) {
        List<Method> methods = Wraps.list();
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

    /**
     * 由方法名推导属性名。
     *
     * @param method 方法
     * @return 属性名
     */
    private static String property(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2) {
            return decapitalize(name.substring(2));
        }
        return name;
    }

    /**
     * 首字母小写。
     *
     * @param name 名称
     * @return 小写化结果
     */
    private static String decapitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }
}