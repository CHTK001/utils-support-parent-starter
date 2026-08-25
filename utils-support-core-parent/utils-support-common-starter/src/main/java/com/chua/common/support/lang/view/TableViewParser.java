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
 * <p>通过 {@code setBorderless(true)} 可切换为无边框模式，仅按列宽对齐，
 * 不绘制任何框线字符：</p>
 *
 * <pre>{@code
 * Name   Value
 * foo    123
 * bar    456
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("table")
public class TableViewParser implements ViewParser {

    /**
     * 是否以无边框模式渲染：true 时不绘制框线字符，仅按列宽以空格对齐
     */
    private boolean borderless;

    /**
     * 设置是否以无边框模式渲染。
     *
     * <p>无边框模式下不绘制任何框线字符（{@code ┌ ─ ├ │} 等），仅按列宽以空格对齐，
     * 适用于日志输出、Markdown 代码块或窄屏终端等不适合框线的场景。</p>
     *
     * @param borderless true 表示无边框模式，false 表示默认框线模式
     * @return 当前实例（支持链式调用）
     */
    public TableViewParser setBorderless(boolean borderless) {
        this.borderless = borderless;
        return this;
    }

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
     * 判断是否支持渲染指定数据。
     *
     * @param data 待渲染的数据
     * @return {@link Iterable} / {@link Map} / 数组 返回 true
     */
    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        return data instanceof Iterable || data instanceof Map || data.getClass().isArray();
    }

    /**
     * 将数据渲染为终端 ASCII 表格。
     *
     * @param data 待渲染的数据
     * @return 表格字符串；空数据返回 {@value #EMPTY_PLACEHOLDER}
     */
    @SuppressWarnings("unchecked")
    @Override
    public String render(Object data) {
        if (data instanceof Map) {
            return renderMap((Map<Object, Object>) data);
        }
        List<Object> rows = toList(data);
        if (rows.isEmpty()) {
            return EMPTY_PLACEHOLDER;
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
     *
     * @param data 待转换的数据
     * @return 元素列表
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
     *
     * @param map 数据源
     * @return 两列表格字符串
     */
    private String renderMap(Map<Object, Object> map) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{KEY_COLUMN, VALUE_COLUMN});
        for (var entry : map.entrySet()) {
            rows.add(new String[]{
                    String.valueOf(entry.getKey()),
                    String.valueOf(entry.getValue())
            });
        }
        return formatTable(rows);
    }

    /**
     * 渲染 List&lt;Map&gt; 为动态列表格。
     *
     * @param data Map 列表
     * @return 多列表格字符串
     */
    private String renderMapRows(List<Map<String, Object>> data) {
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
     *
     * @param data Bean 列表
     * @return 多列表格字符串
     */
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

    /**
     * 渲染简单类型列表（单列）。
     *
     * @param data 简单类型元素列表
     * @return 单列表格字符串
     */
    private String renderSimpleList(List<Object> data) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{INDEX_COLUMN});
        for (int i = 0; i < data.size(); i++) {
            rows.add(new String[]{String.valueOf(data.get(i))});
        }
        return formatTable(rows);
    }

    /**
     * 计算每列最大宽度并绘制表格。
     *
     * @param rows 二维行集合（第一行是表头）
     * @return 表格字符串
     */
    private String formatTable(List<String[]> rows) {
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
        // 左右各加一个空格内边距
        for (int i = 0; i < colCount; i++) {
            widths[i] += 2;
        }
        if (borderless) {
            return renderWithoutBorder(rows, widths, colCount);
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
     * 以无边框模式渲染表格：不绘制任何框线字符，仅按列宽对齐。
     *
     * @param rows     二维行集合（第一行是表头）
     * @param widths   各列宽度（含内边距）
     * @param colCount 列数
     * @return 无框线表格字符串，行尾不含多余空白
     */
    private static String renderWithoutBorder(List<String[]> rows, int[] widths, int colCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(plainRow(rows.get(0), widths));
        for (int i = 1; i < rows.size(); i++) {
            sb.append('\n').append(plainRow(padRow(rows.get(i), colCount), widths));
        }
        return sb.toString();
    }

    /**
     * 拼接无边框模式的单行文本：单元格左对齐补齐列宽，去除行尾多余空白。
     *
     * @param row    当前行单元格数组
     * @param widths 各列宽度（含内边距）
     * @return 行文本
     */
    private static String plainRow(String[] row, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.length ? row[i] : "";
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(cell);
            for (int j = cell.length() + 1; j < widths[i]; j++) {
                sb.append(' ');
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 补齐行到指定列数。
     *
     * @param row      原行
     * @param colCount 目标列数
     * @return 补齐后的行
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
     *
     * @param widths 各列宽度
     * @param left   左端点字符
     * @param cross  交叉点字符
     * @param right  右端点字符
     * @return 水平线字符串
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
     *
     * @param sb     输出缓冲区
     * @param row    当前行单元格数组
     * @param widths 各列宽度
     * @param sep    单元格分隔符
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
     * 提取类所有字段（含继承，过滤静态字段）。
     *
     * @param type 起始类型
     * @return 字段列表
     */
    private static List<Field> extractFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                // 跳过静态字段，仅展示实例字段
                if (!Modifier.isStatic(field.getModifiers())) {
                    result.add(field);
                }
            }
            type = type.getSuperclass();
        }
        return result;
    }

    /**
     * 判断是否为简单类型（直接 toString 即可）。
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

    /**
     * 获取解析器顺序。
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return 0;
    }
}