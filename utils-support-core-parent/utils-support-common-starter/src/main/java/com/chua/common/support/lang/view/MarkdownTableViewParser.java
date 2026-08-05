package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Markdown 表格视图解析器，将数据渲染为 Markdown 表格格式。
 * <p>方便复制到文档、Issue、PR 等场景。</p>
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

    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        return data instanceof Iterable || data instanceof Map || data.getClass().isArray();
    }

    @Override
@SuppressWarnings("unchecked")
    public String render(Object data) {
        List<String[]> rows = extractRows(data);
        if (rows.isEmpty()) {
            return "(empty)";
        }

        int colCount = rows.stream().mapToInt(r -> r.length).max().orElse(1);
        int[] widths = new int[colCount];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i] != null ? row[i].length() : 0);
            }
        }

        StringBuilder sb = new StringBuilder();
        // 表头
        appendMdRow(sb, rows.get(0), widths);
        // 分隔
        sb.append('|');
        for (int w : widths) sb.append("-".repeat(w + 2)).append('|');
        sb.append('\n');
        // 数据
        for (int i = 1; i < rows.size(); i++) {
            appendMdRow(sb, padRow(rows.get(i), colCount), widths);
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static void appendMdRow(StringBuilder sb, String[] row, int[] widths) {
        sb.append('|');
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.length ? row[i] : "";
            sb.append(' ').append(cell);
            sb.append(" ".repeat(widths[i] - cell.length()));
            sb.append(" |");
        }
        sb.append('\n');
    }

    private static String[] padRow(String[] row, int colCount) {
        if (row.length >= colCount) {
            return row;
        }
        String[] r = new String[colCount];
        System.arraycopy(row, 0, r, 0, row.length);
        Arrays.fill(r, row.length, colCount, "");
        return r;
    }

    private List<String[]> extractRows(Object data) {
        if (data instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) data;
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Key", "Value"});
            for (var entry : map.entrySet()) {
                rows.add(new String[]{String.valueOf(entry.getKey()), String.valueOf(entry.getValue())});
            }
            return rows;
        }
        List<Object> items = toList(data);
        if (items.isEmpty()) {
            return List.of();
        }
        Object first = items.get(0);
        if (first instanceof Map) {
            Set<String> keys = new LinkedHashSet<>();
            for (Object item : items) keys.addAll(((Map<String, Object>) item).keySet());
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
        if (isSimpleType(first)) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"#"});
            for (Object item : items) rows.add(new String[]{item.toString()});
            return rows;
        }
        List<Field> fields = extractFields(first.getClass());
        List<String> cols = fields.stream().map(Field::getName).collect(Collectors.toList());
        if (cols.isEmpty()) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"#"});
            for (Object item : items) rows.add(new String[]{item.toString()});
            return rows;
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
                    vals[i] = "?";
                }
            }
            rows.add(vals);
        }
        return rows;
    }

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

    private static List<Field> extractFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        while (type != null && type != Object.class) {
            Collections.addAll(result, type.getDeclaredFields());
            type = type.getSuperclass();
        }
        return result;
    }

    private static boolean isSimpleType(Object obj) {
        return obj instanceof String || obj instanceof Number
                || obj instanceof Boolean || obj instanceof Character
                || obj instanceof java.util.Date || obj instanceof java.time.temporal.Temporal
                || obj instanceof Enum;
    }

    @Override
    public int getOrder() {
        return 8;
    }
}
