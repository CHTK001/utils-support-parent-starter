package com.chua.common.support.export.resolver;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.meta.ColumnType;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.spi.MetaResolver;
import com.chua.common.support.export.support.Rows;
import com.chua.common.support.spi.annotations.Spi;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.List;

/**
 * 反射兜底解析器 —— order = -100，最后执行。
 *
 * <p>扫描数据类型的全部可读字段（含父类），生成初始列集合并按属性类型推断
 * {@link ColumnType} 与列标题（驼峰转中文友好标题）。本解析器只写入"没有任何
 * 其他来源"的内容，因此先执行，为后续解析器打底。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"javabean", "bean", "reflection"}, order = MetaResolverOrders.FALLBACK)
public class JavabeanMetaResolver implements MetaResolver {

    @Override
    /**
     * 优先级
     * @return 优先级 对象
     */
    public int order() {
        return MetaResolverOrders.FALLBACK;
    }

    @Override
    /**
     * 是否支持
     * @param type 数据行类型
     * @return 是否支持 对象
     */
    public boolean supports(Class<?> type) {
        return type != null && type != Object.class && type != String.class;
    }

    @Override
    /**
     * 解析
     * @param type 数据行类型
     * @param meta 元数据容器
     */
    public void resolve(Class<?> type, DocumentMeta meta) {
        meta.type(type);
        int index = 0;
        for (Field field : readableFields(type)) {
            String property = field.getName();
            if (!meta.hasColumn(property)) {
                meta.column(property);
            }
            var column = meta.column(property);
            column.field(field);
            if (!column.isDefined(com.chua.common.support.export.meta.ColumnMeta.Attribute.ORDER)) {
                column.order(index);
            }
            if (!column.isDefined(com.chua.common.support.export.meta.ColumnMeta.Attribute.NAME)) {
                column.name(humanize(property));
            }
            if (!column.isDefined(com.chua.common.support.export.meta.ColumnMeta.Attribute.TYPE)) {
                column.type(infer(field.getType()));
            }
            index++;
        }
    }

    /**
     * 收集类型及其父类的全部可读字段。
     *
     * @param type 类型
     * @return 字段列表
     */
    private List<Field> readableFields(Class<?> type) {
        List<Field> fields = Wraps.list();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                field.setAccessible(true);
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * 驼峰转标题：{@code userName} → {@code User Name}；{@code ID} → {@code ID}。
     *
     * @param property 属性名
     * @return 标题
     */
    public static String humanize(String property) {
        if (property == null || property.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(property.length() + 8);
        for (int i = 0; i < property.length(); i++) {
            char ch = property.charAt(i);
            if (i == 0) {
                sb.append(Character.toUpperCase(ch));
                continue;
            }
            if (Character.isUpperCase(ch)) {
                char prev = property.charAt(i - 1);
                boolean boundary = !Character.isUpperCase(prev)
                        || (i + 1 < property.length() && Character.isLowerCase(property.charAt(i + 1)));
                if (boundary) {
                    sb.append(' ');
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * 按属性类型推断列类型。
     *
     * @param type 属性类型
     * @return 列类型
     */
    public static ColumnType infer(Class<?> type) {
        if (type == null) {
            return ColumnType.AUTO;
        }
        if (type == boolean.class || type == Boolean.class) {
            return ColumnType.BOOLEAN;
        }
        if (type == byte.class || type == short.class || type == int.class
                || type == long.class || type == Byte.class || type == Short.class
                || type == Integer.class || type == Long.class
                || type == BigInteger.class) {
            return ColumnType.INTEGER;
        }
        if (type == float.class || type == double.class || type == Float.class
                || type == Double.class || type == BigDecimal.class) {
            return ColumnType.DECIMAL;
        }
        if (type == LocalDate.class) {
            return ColumnType.DATE;
        }
        if (type == LocalTime.class) {
            return ColumnType.TIME;
        }
        if (type == LocalDateTime.class || type == java.util.Date.class
                || type == java.sql.Date.class || type == java.sql.Timestamp.class) {
            return ColumnType.DATETIME;
        }
        if (type.isEnum()) {
            return ColumnType.ENUM;
        }
        if (type == String.class || type == Character.class || type == char.class
                || type == StringBuilder.class) {
            return ColumnType.TEXT;
        }
        if (Date.class.isAssignableFrom(type)) {
            return ColumnType.DATETIME;
        }
        return ColumnType.TEXT;
    }

    /**
     * 反射字段是否存在（供其他解析器判断是否需要注册列）。
     *
     * @param type     类型
     * @param property 属性名
     * @return 是否存在
     */
    public static boolean hasField(Class<?> type, String property) {
        return Rows.findField(type, property) != null;
    }
}