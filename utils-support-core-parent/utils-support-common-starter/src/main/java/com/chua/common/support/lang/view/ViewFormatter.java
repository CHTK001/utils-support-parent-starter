package com.chua.common.support.lang.view;

import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 * View 格式化器，提供表格渲染的公共逻辑。
 *
 * <p>所有 {@link ViewParser} 实现共享以下能力：</p>
 * <ul>
 *   <li>将 Map / Iterable / POJO 统一提取为二维字符串行</li>
 *   <li>计算列宽、补齐行、绘制水平线</li>
 *   <li>终端框线 / 纯文本 / Markdown 三种输出风格</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ViewFormatter {

    /**
     * 空数据占位文本
     */
    public static final String EMPTY_PLACEHOLDER = "(empty)";

    /**
     * 字段读取失败时的占位字符
     */
    public static final String UNKNOWN_CELL = "?";

    /**
     * 单值渲染时的占位列名
     */
    public static final String INDEX_COLUMN = "#";

    /**
     * Map 渲染时的 Key 列名
     */
    public static final String KEY_COLUMN = "Key";

    /**
     * Map 渲染时的 Value 列名
     */
    public static final String VALUE_COLUMN = "Value";

    private ViewFormatter() { }

    // -------------------------------------------------------------------------
    // 数据提取
    // -------------------------------------------------------------------------

    /**
     * 将数据统一提取为二维行集合，第一行为表头。
     *
     * @param data 待渲染的数据（Map / Iterable / 数组 / POJO）
     * @return 二维字符串行；空数据返回空列表
     */
    public static List<String[]> extractRows(Object data) {
        if (data == null) {
            return List.of();
        }
        if (data instanceof Map) {
            return extractFromMap((Map<Object, Object>) data);
        }
        List<Object> items = toList(data);
        if (items.isEmpty()) {
            return List.of();
        }
        Object first = items.getFirst();
        if (first instanceof Map) {
            return extractFromMapList(items);
        }
        if (isSimpleType(first)) {
            return extractFromSimpleList(items);
        }
        return extractFromBeanList(items);
    }

    /**
     * 将各种数据形态（Iterable / 数组 / 标量）统一转为 {@link List}。
     *
     * @param data 待转换的数据
     * @return 元素列表（标量会被包装为单元素列表）
     */
    public static List<Object> toList(Object data) {
        if (data instanceof Iterable) {
            List<Object> result = new ArrayList<>();
            ((Iterable<?>) data).forEach(result::add);
            return result;
        }
        if (data.getClass().isArray()) {
            return Arrays.asList((Object[]) data);
        }
        return List.of(data);
    }

    /**
     * 从 {@link Map} 中提取两列（Key / Value）表格。
     *
     * @param map 数据源
     * @return 两列表格行集合
     */
    public static List<String[]> extractFromMap(Map<Object, Object> map) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{KEY_COLUMN, VALUE_COLUMN});
        for (var entry : map.entrySet()) {
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
    public static List<String[]> extractFromMapList(List<Object> items) {
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : items) {
            if (item instanceof Map) {
                keys.addAll(((Map<String, Object>) item).keySet());
            }
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
    public static List<String[]> extractFromSimpleList(List<Object> items) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{INDEX_COLUMN});
        for (Object item : items) {
            rows.add(new String[]{String.valueOf(item)});
        }
        return rows;
    }

    /**
     * 从 Bean 列表中通过反射提取字段为列，逐行取值。
     *
     * @param items Bean 列表
     * @return 反射得到的多列表格行集合
     */
    public static List<String[]> extractFromBeanList(List<Object> items) {
        List<Field> fields = extractFields(items.getFirst().getClass());
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
                    Object v = ReflectUtils.getField(bean, f.getName());
                    vals[i] = v != null ? v.toString() : "";
                } catch (Exception e) {
                    vals[i] = UNKNOWN_CELL;
                }
            }
            rows.add(vals);
        }
        return rows;
    }

    // -------------------------------------------------------------------------
    // 类型判断
    // -------------------------------------------------------------------------

    /**
     * 判断对象是否为简单类型（数字、字符串、布尔等基础类型）。
     *
     * @param obj 待判断对象
     * @return 简单类型返回 true
     */
    public static boolean isSimpleType(Object obj) {
        if (obj == null) {
            return false;
        }
        Class<?> type = obj.getClass();
        if (type.isArray() || Iterable.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            return false;
        }
        return obj instanceof String || obj instanceof Number
                || obj instanceof Boolean || obj instanceof Character
                || obj instanceof Date || obj instanceof Temporal
                || obj instanceof Enum;
    }

    // -------------------------------------------------------------------------
    // 字段反射
    // -------------------------------------------------------------------------

    /**
     * 递归提取类及其父类的全部声明字段。
     *
     * @param type 起始类型
     * @return 字段列表（按继承顺序：子类在前）
     */
    public static List<Field> extractFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    result.add(field);
                }
            }
            type = type.getSuperclass();
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 格式化工具
    // -------------------------------------------------------------------------

    /**
     * 计算每列最大宽度。
     *
     * @param rows 二维行集合
     * @return 各列宽度数组
     */
    public static int[] calcColumnWidths(List<String[]> rows) {
        if (rows.isEmpty()) {
            return new int[0];
        }
        int colCount = 0;
        for (String[] row : rows) {
            colCount = Math.max(colCount, row.length);
        }
        int[] widths = new int[colCount];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i] != null ? row[i].length() : 0);
            }
        }
        return widths;
    }

    /**
     * 补齐行到指定列数，右侧填充空字符串。
     *
     * @param row      原行
     * @param colCount 目标列数
     * @return 补齐后的行
     */
    public static String[] padRow(String[] row, int colCount) {
        if (row.length >= colCount) {
            return row;
        }
        String[] result = new String[colCount];
        System.arraycopy(row, 0, result, 0, row.length);
        Arrays.fill(result, row.length, colCount, "");
        return result;
    }

    // -------------------------------------------------------------------------
    // 边框绘制
    // -------------------------------------------------------------------------

    /**
     * 绘制 ASCII 框线表格（含顶线、表头、分隔线、数据行、底线）。
     *
     * @param rows  二维行集合（第一行是表头）
     * @param pad   单元格左右内边距（各 pad 个空格）
     * @return 框线表格字符串
     */
    public static String drawBoxedTable(List<String[]> rows, int pad) {
        if (rows.isEmpty()) {
            return EMPTY_PLACEHOLDER;
        }
        int[] widths = calcColumnWidths(rows);
        for (int i = 0; i < widths.length; i++) {
            widths[i] += pad * 2;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(hLine(widths, '┌', '┬', '┐')).append('\n');
        appendRow(sb, rows.getFirst(), widths, '│');
        sb.append(hLine(widths, '├', '┼', '┤')).append('\n');
        for (int i = 1; i < rows.size(); i++) {
            appendRow(sb, padRow(rows.get(i), widths.length), widths, '│');
        }
        sb.append(hLine(widths, '└', '┴', '┘'));
        return sb.toString();
    }

    /**
     * 以无边框模式渲染表格：仅按列宽空格对齐。
     *
     * @param rows  二维行集合（第一行是表头）
     * @param pad   单元格左右内边距
     * @return 无边框表格字符串，行尾无多余空白
     */
    public static String drawBorderlessTable(List<String[]> rows, int pad) {
        if (rows.isEmpty()) {
            return EMPTY_PLACEHOLDER;
        }
        int[] widths = calcColumnWidths(rows);
        for (int i = 0; i < widths.length; i++) {
            widths[i] += pad;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(plainRow(rows.getFirst(), widths));
        for (int i = 1; i < rows.size(); i++) {
            sb.append('\n').append(plainRow(padRow(rows.get(i), widths.length), widths));
        }
        return sb.toString();
    }

    /**
     * 绘制 Markdown 表格。
     *
     * @param rows 二维行集合（第一行是表头）
     * @param pad  单元格左右内边距（最少 1）
     * @return Markdown 表格字符串
     */
    public static String drawMarkdownTable(List<String[]> rows, int pad) {
        if (rows.isEmpty()) {
            return EMPTY_PLACEHOLDER;
        }
        int colCount = rows.stream().mapToInt(r -> r.length).max().orElse(1);
        int[] widths = calcColumnWidths(rows);
        StringBuilder sb = new StringBuilder();
        appendMdRow(sb, rows.getFirst(), widths, pad);
        sb.append('|');
        for (int w : widths) {
            sb.append("-".repeat(w + pad * 2)).append('|');
        }
        sb.append('\n');
        for (int i = 1; i < rows.size(); i++) {
            appendMdRow(sb, padRow(rows.get(i), colCount), widths, pad);
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 绘制水平分隔线。
     *
     * @param widths 各列宽度
     * @param left   左端点字符
     * @param cross  交叉点字符
     * @param right  右端点字符
     * @return 水平线字符串
     */
    public static String hLine(int[] widths, char left, char cross, char right) {
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i]));
            if (i < widths.length - 1) {
                sb.append(cross);
            }
        }
        sb.append(right);
        return sb.toString();
    }

    /**
     * 追加一行 ASCII 框线表格行。
     *
     * @param sb    输出缓冲区
     * @param row   当前行单元格数组
     * @param widths 各列宽度（已含内边距）
     * @param sep   单元格分隔符
     */
    public static void appendRow(StringBuilder sb, String[] row, int[] widths, char sep) {
        sb.append(sep);
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.length ? row[i] : "";
            sb.append(' ').append(cell);
            sb.append(" ".repeat(widths[i] - cell.length() - 1));
            sb.append(sep);
        }
        sb.append('\n');
    }

    /**
     * 拼接无边框模式的单行文本：单元格左对齐补齐列宽。
     *
     * @param row    当前行单元格数组
     * @param widths 各列宽度（已含内边距）
     * @return 行文本，已去除行尾空白
     */
    public static String plainRow(String[] row, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.length ? row[i] : "";
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(cell);
            sb.append(" ".repeat(widths[i] - cell.length() - 1));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 追加一行 Markdown 表格行（带单元格内边距）。
     *
     * @param sb   输出缓冲区
     * @param row  当前行单元格数组
     * @param widths 各列内容宽度
     * @param pad  单元格左右内边距
     */
    public static void appendMdRow(StringBuilder sb, String[] row, int[] widths, int pad) {
        sb.append('|');
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.length ? row[i] : "";
            sb.append(' ').append(cell);
            sb.append(" ".repeat(widths[i] + pad - cell.length()));
            sb.append('|');
        }
        sb.append('\n');
    }
}
