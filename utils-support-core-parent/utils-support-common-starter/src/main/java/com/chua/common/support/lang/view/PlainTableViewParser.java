package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

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
 * 纯文本表格渲染器，无边框、无 Unicode 字符，适合窄屏终端或纯文本输出场景。
 *
 * <p>输出格式示例：</p>
 * <pre>
 * Name    Value
 * foo     123
 * bar     456
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("plain")
public class PlainTableViewParser implements ViewParser {

    private static final String EMPTY_PLACEHOLDER = "(empty)";
    private static final String UNKNOWN_CELL = "?";
    private static final String INDEX_COLUMN = "#";
    private static final String KEY_COLUMN = "Key";
    private static final String VALUE_COLUMN = "Value";

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
        if (data instanceof Map) {
            return renderMap((Map<Object, Object>) data);
        }
        List<Object> rows = toList(data);
        if (rows.isEmpty()) {
            return EMPTY_PLACEHOLDER;
        }
        if (rows.get(0) instanceof Map) {
            return renderMapRows((List<Map<String, Object>>) (List<?>) rows);
        }
        if (isSimpleType(rows.get(0))) {
            return renderSimpleList(rows);
        }
        return renderBeanRows(rows);
    }

    private String renderMap(Map<Object, Object> map) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{KEY_COLUMN, VALUE_COLUMN});
        map.forEach((k, v) -> rows.add(new String[]{String.valueOf(k), String.valueOf(v)}));
        return formatTable(rows);
    }

    private String renderMapRows(List<Map<String, Object>> data) {
        Set<String> allKeys = new LinkedHashSet<>();
        data.forEach(row -> allKeys.addAll(row.keySet()));
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

    private String renderBeanRows(List<Object> data) {
        if (data.isEmpty()) {
            return EMPTY_PLACEHOLDER;
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
                    values[i] = UNKNOWN_CELL;
                }
            }
            rows.add(values);
        }
        return formatTable(rows);
    }

    private String renderSimpleList(List<Object> data) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{INDEX_COLUMN});
        for (int i = 0; i < data.size(); i++) {
            rows.add(new String[]{String.valueOf(data.get(i))});
        }
        return formatTable(rows);
    }

    private String formatTable(List<String[]> rows) {
        if (rows.isEmpty()) {
            return "";
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
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) {
                sb.append('\n');
            }
            String[] row = rows.get(r);
            for (int i = 0; i < colCount; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                String cell = i < row.length && row[i] != null ? row[i] : "";
                sb.append(cell);
                for (int j = cell.length(); j < widths[i]; j++) {
                    sb.append(' ');
                }
            }
        }
        return sb.toString();
    }

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

    private static List<Field> extractFields(Class<?> type) {
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

    private static boolean isSimpleType(Object obj) {
        return obj instanceof String || obj instanceof Number
                || obj instanceof Boolean || obj instanceof Character
                || obj instanceof Date || obj instanceof Temporal
                || obj instanceof Enum;
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
