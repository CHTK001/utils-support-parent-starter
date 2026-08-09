package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.lang.reflect.Field;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Markdown 表格视图解析器，将数据渲染为 Markdown 表格格式。
 *
 * <p>方便复制到文档、Issue、PR 等场景。支持三类数据：</p>
 * <ul>
 *   <li>{@link Map}：渲染为两列表格（Key / Value）</li>
 *   <li>{@link Iterable} / 数组：若元素为简单类型，按行号渲染；否则反射读取字段</li>
 * </ul>
 *
 * <pre>{@code
 * | Name  | Value |
 * |-------|-------|
 * | foo   | 123   |
 * | bar   | 456   |
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("md")
public class MarkdownTableViewParser implements ViewParser {

    /**
     * 单元格与列分隔符之间的填充长度（最少 1 个空格）
     */
    private static final int CELL_PADDING = 1;

    /**
     * 空数据占位文本
     */
    private static final String EMPTY_PLACEHOLDER = "(empty)";

    /**
     * 字段读取失败时的占位字符
     */
    private static final String UNKNOWN_CELL = "?";

    /**
     * 单值渲染时的占位列名
     */
    private static final String INDEX_COLUMN = "#";

    /**
     * Map 渲染时的列名（Key）
     */
    private static final String KEY_COLUMN = "Key";

    /**
     * Map 渲染时的列名（Value）
     */
    private static final String VALUE_COLUMN = "Value";

    /**
     * 单元格分隔符
     */
    private static final char CELL_DELIMITER = '|';

    /**
     * 分隔符行的填充字符
     */
    private static final String SEPARATOR_FILL = "-";

    /**
     * 解析器顺序：排在最后，作为兜底渲染器
     */
    private static final int ORDER_LAST = 8;

    /**
     * 判断当前解析器是否支持渲染指定数据。
     *
     * @param data 待渲染的数据
     * @return {@code Map} / {@link Iterable} / 数组 返回 true，其它返回 false
     */
    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        return data instanceof Iterable || data instanceof Map || data.getClass().isArray();
    }

    /**
     * 将数据渲染为 Markdown 表格字符串。
     *
     * @param data 待渲染的数据（{@link Map} / {@link Iterable} / 数组）
     * @return 渲染后的 Markdown 表格；空数据返回 {@value #EMPTY_PLACEHOLDER}
     */
    @Override
    @SuppressWarnings("unchecked")
    public String render(Object data) {
        List<String[]> rows = extractRows(data);
        if (rows.isEmpty()) {
            return EMPTY_PLACEHOLDER;
        }

        int colCount = rows.stream().mapToInt(r -> r.length).max().orElse(1);
        int[] widths = new int[colCount];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i] != null ? row[i].length() : 0);
            }
        }

        StringBuilder sb = new StringBuilder();
        // 渲染表头
        appendMdRow(sb, rows.get(0), widths);
        // 渲染 Markdown 分隔行
        sb.append(CELL_DELIMITER);
        for (int w : widths) {
            sb.append(SEPARATOR_FILL.repeat(w + 2)).append(CELL_DELIMITER);
        }
        sb.append('\n');
        // 渲染数据行
        for (int i = 1; i < rows.size(); i++) {
            appendMdRow(sb, padRow(rows.get(i), colCount), widths);
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 获取解析器顺序。
     *
     * @return 顺序值（{@value #ORDER_LAST}）
     */
    @Override
    public int getOrder() {
        return ORDER_LAST;
    }

    /**
     * 追加一行 Markdown 表格行（带单元格对齐填充）。
     *
     * @param sb     输出缓冲区
     * @param row    当前行单元格数组
     * @param widths 各列的目标宽度
     */
    private static void appendMdRow(StringBuilder sb, String[] row, int[] widths) {
        sb.append(CELL_DELIMITER);
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.length ? row[i] : "";
            sb.append(' ').append(cell);
            sb.append(" ".repeat(widths[i] - cell.length() + CELL_PADDING));
            sb.append(CELL_DELIMITER);
        }
        sb.append('\n');
    }

    /**
     * 将不足列数的行右侧填充空字符串至目标列数。
     *
     * @param row      原行
     * @param colCount 目标列数
     * @return 补齐后的行
     */
    private static String[] padRow(String[] row, int colCount) {
        if (row.length >= colCount) {
            return row;
        }
        String[] r = new String[colCount];
        System.arraycopy(row, 0, r, 0, row.length);
        Arrays.fill(r, row.length, colCount, "");
        return r;
    }

    /**
     * 提取数据为行集合，第一行是表头。
     *
     * @param data 待提取的数据
     * @return 二维行集合，外层每一项对应一行；空数据返回空列表
     */
    private List<String[]> extractRows(Object data) {
        if (data instanceof Map) {
            return extractFromMap((Map<Object, Object>) data);
        }
        List<Object> items = toList(data);
        if (items.isEmpty()) {
            return List.of();
        }
        Object first = items.get(0);
        if (first instanceof Map) {
            return extractFromMapList(items);
        }
        if (isSimpleType(first)) {
            return extractFromSimpleList(items);
        }
        return extractFromBeanList(items);
    }

    /**
     * 从 {@link Map} 中提取两列（Key / Value）表格。
     *
     * @param map 数据源
     * @return 两列表格行集合
     */
    private List<String[]> extractFromMap(Map<Object, Object> map) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{KEY_COLUMN, VALUE_COLUMN});
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            rows.add(new String[]{String.valueOf(entry.getKey()), String.valueOf(entry.getValue())});
        }
        return rows;
    }

    /**
     * 从 {@code List<Map>} 提取列名（所有 key 的并集），逐行取值。
     *
     * @param items Map 列表
     * @return 多列表格行集合
     */
    @SuppressWarnings("unchecked")
    private List<String[]> extractFromMapList(List<Object> items) {
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : items) {
            keys.addAll(((Map<String, Object>) item).keySet());
        }
        List<String> cols = new ArrayList<>(keys);
        List<String[]> rows = new ArrayList<>();
        rows.add(cols.toArray(new String[0]));
        for (Object item : items) {
            Map<String, Object> m = (Map<String, Object>) item;
            String[] vals = new String[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                Object v = m.get(cols.get(i));
                vals[i] = v != null ? v.toString() : "";
            }
            rows.add(vals);
        }
        return rows;
    }

    /**
     * 从简单类型列表中提取单列（#）表格。
     *
     * @param items 简单类型元素列表
     * @return 单列表格行集合
     */
    private List<String[]> extractFromSimpleList(List<Object> items) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{INDEX_COLUMN});
        for (Object item : items) {
            rows.add(new String[]{item.toString()});
        }
        return rows;
    }

    /**
     * 从 Bean 列表中通过反射提取字段为列，逐行取值。
     *
     * @param items Bean 列表
     * @return 反射得到的多列表格行集合
     */
    private List<String[]> extractFromBeanList(List<Object> items) {
        List<Field> fields = extractFields(items.get(0).getClass());
        List<String> cols = fields.stream().map(Field::getName).collect(Collectors.toList());
        if (cols.isEmpty()) {
            return extractFromSimpleList(items);
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(cols.toArray(new String[0]));
        for (Object bean : items) {
            String[] vals = new String[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                try {
                    Field f = fields.get(i);
                    f.setAccessible(true);
                    Object v = f.get(bean);
                    vals[i] = v != null ? v.toString() : "";
                } catch (Exception e) {
                    vals[i] = UNKNOWN_CELL;
                }
            }
            rows.add(vals);
        }
        return rows;
    }

    /**
     * 将各种数据形态（Iterable / 数组 / 标量）统一转为 {@link List}。
     *
     * @param data 待转换的数据
     * @return 元素列表（标量会被包装为单元素列表）
     */
    private static List<Object> toList(Object data) {
        if (data instanceof Iterable) {
            List<Object> r = new ArrayList<>();
            ((Iterable<?>) data).forEach(r::add);
            return r;
        }
        if (data.getClass().isArray()) {
            return Arrays.asList((Object[]) data);
        }
        return List.of(data);
    }

    /**
     * 递归提取类及其父类的全部声明字段。
     *
     * @param type 起始类型
     * @return 字段列表（按继承顺序：子类在前）
     */
    private static List<Field> extractFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        while (type != null && type != Object.class) {
            Collections.addAll(result, type.getDeclaredFields());
            type = type.getSuperclass();
        }
        return result;
    }

    /**
     * 判断对象是否为简单类型（数字、字符串、布尔等基础类型）。
     *
     * @param obj 待判断对象
     * @return 简单类型返回 true
     */
    private static boolean isSimpleType(Object obj) {
        return obj instanceof String || obj instanceof Number
                || obj instanceof Boolean || obj instanceof Character
                || obj instanceof Date || obj instanceof Temporal
                || obj instanceof Enum;
    }
}
