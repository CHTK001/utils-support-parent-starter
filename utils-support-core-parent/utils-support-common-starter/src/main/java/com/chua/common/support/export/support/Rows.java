package com.chua.common.support.export.support;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.convert.Converters;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.ColumnType;
import com.chua.common.support.export.spi.ValueConverter;
import com.chua.common.support.lang.json.Json;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 行数据工具 —— 取值、归一化与展示值转换。
 *
 * <p>取值优先级：{@link ColumnMeta#getExpression()} 指定的嵌套路径 →
 * {@code Map} 按 key → getter 方法 → 反射字段。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Rows {

    /**
     * 默认日期格式。
     */
    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";

    /**
     * 默认日期时间格式。
     */
    public static final String DEFAULT_DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /**
     * 默认时间格式。
     */
    public static final String DEFAULT_TIME_FORMAT = "HH:mm:ss";

    private Rows() {
    }

    /**
     * 把任意输入归一化为行列表。
     *
     * @param rows 列表、数组、{@link Iterable}、{@link java.util.stream.Stream} 或单个对象
     * @return 行列表，永不为 {@code null}
     */
    public static List<Object> normalize(Object rows) {
        List<Object> list = Wraps.list();
        if (rows == null) {
            return list;
        }
        if (rows instanceof List<?> source) {
            list.addAll(source);
            return list;
        }
        if (rows instanceof Collection<?> source) {
            list.addAll(source);
            return list;
        }
        if (rows instanceof Iterable<?> source) {
            source.forEach(list::add);
            return list;
        }
        if (rows instanceof java.util.stream.Stream<?> source) {
            source.forEach(list::add);
            return list;
        }
        if (rows.getClass().isArray()) {
            int length = Array.getLength(rows);
            for (int i = 0; i < length; i++) {
                list.add(Array.get(rows, i));
            }
            return list;
        }
        list.add(rows);
        return list;
    }

    /**
     * 推断行类型：取第一个非 {@code null} 元素的类型，全部为 {@code null} 时返回 {@link Object}。
     *
     * @param rows 行列表
     * @return 行类型
     */
    public static Class<?> typeOf(List<Object> rows) {
        for (Object row : rows) {
            if (row != null) {
                return row.getClass();
            }
        }
        return Object.class;
    }

    /**
     * 读取原始值（未做任何格式化）。
     *
     * @param row    行对象
     * @param column 列元数据
     * @return 原始值
     */
    public static Object raw(Object row, ColumnMeta column) {
        if (row == null || column == null) {
            return null;
        }
        String expression = column.getExpression();
        if (!expression.isEmpty()) {
            return byPath(row, expression);
        }
        String property = column.getProperty();
        if (row instanceof Map<?, ?> map) {
            if (map.containsKey(property)) {
                return map.get(property);
            }
            if (column.getName() != null && !column.getName().isEmpty() && map.containsKey(column.getName())) {
                return map.get(column.getName());
            }
            return null;
        }
        MethodAccessor accessor = new MethodAccessor(row, column);
        return accessor.invoke();
    }

    /**
     * 按 {@code a.b.c} 形式的嵌套路径取值。
     *
     * @param row  行对象
     * @param path 路径
     * @return 取到的值
     */
    public static Object byPath(Object row, String path) {
        Object current = row;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
                continue;
            }
            int index = index(segment);
            if (index >= 0 && current instanceof List<?> list) {
                current = index < list.size() ? list.get(index) : null;
                continue;
            }
            Field field = findField(current.getClass(), segment);
            if (field == null) {
                return null;
            }
            try {
                current = field.get(current);
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return current;
    }

    /**
     * 解析路径段中的下标，支持 {@code 0} 与 {@code [0]} 两种写法。
     *
     * @param segment 路径段
     * @return 下标；不是下标返回 {@code -1}
     */
    private static int index(String segment) {
        String value = segment;
        if (value.length() > 2 && value.charAt(0) == '[' && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return -1;
            }
        }
        return Integer.parseInt(value);
    }

    /**
     * 在类及其父类中查找字段。
     *
     * @param type  类型
     * @param name  字段名
     * @return 字段，找不到返回 {@code null}
     */
    public static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 计算展示值：取值 → 自定义转换器 → 内置格式化 → 空值默认值 → 前后缀。
     *
     * @param row    行对象
     * @param column 列元数据
     * @return 展示值
     */
    public static Object display(Object row, ColumnMeta column) {
        Object value = raw(row, column);
        ValueConverter converter = Converters.find(column.getConverter());
        if (converter != null && converter.supports(column)) {
            value = converter.convert(value, column, row);
        } else {
            value = builtIn(value, column);
        }
        return decorate(value, column);
    }

    /**
     * 内置格式化。
     *
     * @param value  原始值
     * @param column 列元数据
     * @return 格式化后的值
     */
    public static Object builtIn(Object value, ColumnMeta column) {
        if (value == null) {
            return null;
        }
        return switch (column.getType()) {
            case DATE -> temporal(value, pattern(column, DEFAULT_DATE_FORMAT), column);
            case TIME -> temporal(value, pattern(column, DEFAULT_TIME_FORMAT), column);
            case DATETIME -> temporal(value, pattern(column, DEFAULT_DATETIME_FORMAT), column);
            case ENUM -> enumText(value, column);
            case PERCENT -> decimal(value, column);
            case AMOUNT -> decimal(value, column, 2);
            case DECIMAL -> decimal(value, column);
            case INTEGER -> toNumber(value, column);
            case JSON -> Json.toJson(value);
            default -> value;
        };
    }

    private static String pattern(ColumnMeta column, String fallback) {
        String format = column.getDateFormat();
        return format.isEmpty() ? fallback : format;
    }

    private static Object temporal(Object value, String pattern, ColumnMeta column) {
        if (value instanceof CharSequence text) {
            return text;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(formatter);
        }
        if (value instanceof LocalDate date) {
            return date.format(formatter);
        }
        if (value instanceof LocalTime time) {
            return time.format(formatter);
        }
        if (value instanceof Date date) {
            LocalDateTime dateTime = LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
            if (column.getType() == ColumnType.DATE) {
                return dateTime.toLocalDate().format(formatter);
            }
            if (column.getType() == ColumnType.TIME) {
                return dateTime.toLocalTime().format(formatter);
            }
            return dateTime.format(formatter);
        }
        return value;
    }

    private static Object enumText(Object value, ColumnMeta column) {
        Map<String, String> mapping = column.getEnumNames();
        if (mapping.isEmpty()) {
            return value instanceof Enum<?> e ? e.name() : value;
        }
        String text = mapping.get(String.valueOf(value));
        if (text != null) {
            return text;
        }
        return value instanceof Enum<?> e ? e.name() : value;
    }

    private static Object decimal(Object value, ColumnMeta column) {
        return decimal(value, column, column.getScale());
    }

    private static Object decimal(Object value, ColumnMeta column, int scale) {
        if (value instanceof CharSequence text) {
            return text;
        }
        BigDecimal number = toBigDecimal(value);
        if (number == null) {
            return value;
        }
        int target = scale >= 0 ? scale : (column.getType() == ColumnType.AMOUNT ? 2 : -1);
        return target < 0 ? number.stripTrailingZeros() : number.setScale(target, RoundingMode.HALF_UP);
    }

    private static Object toNumber(Object value, ColumnMeta column) {
        if (value instanceof CharSequence text) {
            return text;
        }
        BigDecimal number = toBigDecimal(value);
        if (number == null) {
            return value;
        }
        return number.stripTrailingZeros().scale() <= 0
                ? (Object) number.longValue()
                : number.setScale(Math.max(column.getScale(), 0), RoundingMode.HALF_UP);
    }

    /**
     * 宽松地把任意值转成 {@link BigDecimal}。
     *
     * @param value 原始值
     * @return 数值，失败返回 {@code null}
     */
    public static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof Boolean flag) {
            return flag ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Object decorate(Object value, ColumnMeta column) {
        String prefix = column.getPrefix();
        String suffix = column.getSuffix();
        boolean decorated = !prefix.isEmpty() || !suffix.isEmpty();
        if (value == null) {
            String fallback = column.getDefaultValue();
            return decorated && !fallback.isEmpty() ? prefix + fallback + suffix : fallback;
        }
        if (!decorated) {
            return value;
        }
        return prefix + (value instanceof CharSequence ? value.toString() : String.valueOf(value)) + suffix;
    }

    /**
     * 反射调用适配器：优先 getter，其次字段。
     */
    private static final class MethodAccessor {

        /**
         * 行对象。
         */
        private final Object row;

        /**
         * 列元数据。
         */
        private final ColumnMeta column;

        /**
         * 构造访问器。
         *
         * @param row    行对象
         * @param column 列元数据
         */
        private MethodAccessor(Object row, ColumnMeta column) {
            this.row = row;
            this.column = column;
        }

        /**
         * 执行读取。
         *
         * @return 读取结果
         */
        private Object invoke() {
            if (column.getGetter() != null) {
                try {
                    column.getGetter().setAccessible(true);
                    return column.getGetter().invoke(row);
                } catch (Exception e) {
                    throw new ExportException("读取属性失败: " + column.getProperty(), e);
                }
            }
            if (column.getField() != null) {
                try {
                    return column.getField().get(row);
                } catch (Exception e) {
                    throw new ExportException("读取字段失败: " + column.getProperty(), e);
                }
            }
            String property = column.getProperty();
            Field field = findField(row.getClass(), property);
            if (field == null) {
                throw new ExportException("无法定位属性: " + row.getClass().getName() + "#" + property);
            }
            try {
                return field.get(row);
            } catch (Exception e) {
                throw new ExportException("读取字段失败: " + property, e);
            }
        }
    }
}