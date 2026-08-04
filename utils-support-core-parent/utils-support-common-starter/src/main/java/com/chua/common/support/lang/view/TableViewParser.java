package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 表格视图解析器，将结构化数据渲染为终端 ASCII 表格。
 * <p>
 * 支持的数据类型：
 * <ul>
 *   <li>{@link List} / 数组 / {@link Iterable} — 每元素一行，字段映射为列</li>
 *   <li>{@link Map} — 键值对画为两列表</li>
 *   <li>POJO — 单行表格展示所有字段</li>
 * </ul>
 * </p>
 *
 * <pre>{@code
 * ┌───────┬───────────┐
 * │ Name  │ Value     │
 * ├───────┼───────────┤
 * │ foo   │ 123       │
 * │ bar   │ 456       │
 * └───────┴───────────┘
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("table")
public class TableViewParser implements ViewParser {

    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        return data instanceof Iterable || data instanceof Map || data.getClass().isArray();
    }

    @Override
@SuppressWarnings({"unchecked"})
    public String render(Object data) {
        if (data instanceof Map) {
            return renderMap((Map<Object, Object>) data);
        }
        List<Object> rows = toList(data);
        if (rows.isEmpty()) {
            return "(empty)";
        }
        if (rows.get(0) instanceof Map) {
            List<Map<String, Object>> mapRows = (List<Map<String, Object>>) (List<?>) rows;
            return renderMapRows(mapRows);
        }
        if (isSimpleType(rows.get(0))) {
            return renderSimpleList(rows);
        }
        return renderBeanRows(rows);
    }

    /**
     * 将数据转为列表。
     */
    private static List<Object> toList(Object data) {
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
     * 渲染 Map 键值对为两列表格。
     */
    private static String renderMap(Map<Object, Object> map) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Key", "Value"});
        for (var entry : map.entrySet()) {
            rows.add(new String[]{
                    String.valueOf(entry.getKey()),
                    String.valueOf(entry.getValue())
            });
        }
        return formatTable(rows);
    }

    /**
     * 渲染 List<Map> 为动态列表格。
     */
    private static String renderMapRows(List<Map<String, Object>> data) {
        Set<String> allKeys = new LinkedHashSet<>();
        for (var row : data) {
            allKeys.addAll(row.keySet());
        }
        List<String> columns = new ArrayList<>(allKeys);
        List<String[]> rows = new ArrayList<>();
        rows.add(columns.toArray(new String[0]));
        for (var row : data) {
            String[] values = new String[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                Object val = row.get(columns.get(i));
                values[i] = val != null ? val.toString() : "";
            }
            rows.add(values);
        }
        return formatTable(rows);
    }

    /**
     * 渲染 POJO 列表为字段列表格。
     */
    private static String renderBeanRows(List<Object> data) {
        if (data.isEmpty()) {
            return "(empty)";
        }
        List<Field> fields = extractFields(data.get(0).getClass());
        List<String> columns = fields.stream().map(Field::getName).collect(Collectors.toList());
        if (columns.isEmpty()) {
            return renderSimpleList(data);
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(columns.toArray(new String[0]));
        for (Object bean : data) {
            String[] values = new String[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                try {
                    Field f = fields.get(i);
                    f.setAccessible(true);
                    Object val = f.get(bean);
                    values[i] = val != null ? val.toString() : "";
                } catch (Exception e) {
                    values[i] = "?";
                }
            }
            rows.add(values);
        }
        return formatTable(rows);
    }

    /**
     * 渲染简单类型列表（单列）。
     */
    private static String renderSimpleList(List<Object> data) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"#"});
        for (int i = 0; i < data.size(); i++) {
            rows.add(new String[]{String.valueOf(data.get(i))});
        }
        return formatTable(rows);
    }

    /**
     * 计算每列最大宽度并绘制带框线的表格。
     */
    private static String formatTable(List<String[]> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        int colCount = 0;
        for (String[] row : rows) {
            colCount = Math.max(colCount, row.length);
        }
        // 计算每列宽度
        int[] widths = new int[colCount];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i] != null ? row[i].length() : 0);
            }
        }
        // padding
        for (int i = 0; i < colCount; i++) {
            widths[i] += 2;
        }
        StringBuilder sb = new StringBuilder();
        // 顶线
        sb.append(hLine(widths, '┌', '┬', '┐')).append('\n');
        // 表头
        appendRow(sb, rows.get(0), widths, '│');
        // 分隔线
        sb.append(hLine(widths, '├', '┼', '┤')).append('\n');
        // 数据行
        for (int i = 1; i < rows.size(); i++) {
            appendRow(sb, padRow(rows.get(i), colCount), widths, '│');
        }
        // 底线
        sb.append(hLine(widths, '└', '┴', '┘'));
        return sb.toString();
    }

    /**
     * 补齐行到指定列数。
     */
    private static String[] padRow(String[] row, int colCount) {
        if (row.length >= colCount) {
            return row;
        }
        String[] result = new String[colCount];
        System.arraycopy(row, 0, result, 0, row.length);
        for (int i = row.length; i < colCount; i++) {
            result[i] = "";
        }
        return result;
    }

    /**
     * 绘制水平线。
     */
    private static String hLine(int[] widths, char left, char cross, char right) {
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < widths.length; i++) {
            for (int j = 0; j < widths[i]; j++) {
                sb.append('─');
            }
            if (i < widths.length - 1) {
                sb.append(cross);
            }
        }
        sb.append(right);
        return sb.toString();
    }

    /**
     * 追加一行数据。
     */
    private static void appendRow(StringBuilder sb, String[] row, int[] widths, char sep) {
        sb.append(sep);
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.length ? row[i] : "";
            sb.append(' ').append(cell);
            for (int j = cell.length() + 1; j < widths[i]; j++) {
                sb.append(' ');
            }
            sb.append(sep);
        }
        sb.append('\n');
    }

    /**
     * 提取类所有字段（含继承）。
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
     * 判断是否为简单类型（直接 toString 即可）。
     */
    private static boolean isSimpleType(Object obj) {
        return obj instanceof String || obj instanceof Number
                || obj instanceof Boolean || obj instanceof Character
                || obj instanceof java.util.Date || obj instanceof java.time.temporal.Temporal
                || obj instanceof Enum;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
