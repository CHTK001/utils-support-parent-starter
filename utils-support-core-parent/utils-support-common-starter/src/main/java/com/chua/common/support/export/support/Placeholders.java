package com.chua.common.support.export.support;

import com.chua.common.support.export.ExportConfig;
import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.TemplateContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量模板引擎 —— 处理标量占位符与循环区块。
 *
 * <p>语法（前后缀由 {@link ExportConfig#getTagPrefix()} / {@code getTagSuffix()} 决定，
 * 默认 {@code {{ }} }）：</p>
 * <ul>
 *     <li>{@code {{key}}} —— 标量占位符，取自 {@code variables}</li>
 *     <li>{@code {{tag.field}}} —— 嵌套路径，{@code a.b.c} 任意深度；
 *         循环行内会直接在<b>原始行对象</b>上取值，因此未导出为列的嵌套字段同样可用</li>
 *     <li>{@code {{items.0}}} / {@code {{items[0]}}} —— 集合按下标取值</li>
 *     <li>{@code {{#tag}}} … {@code {{/tag}}} —— 循环区块，可跨行、跨单元格</li>
 *     <li>{@code {{@index}}} / {@code {{@first}}} / {@code {{@last}}} —— 循环内元信息
 *         （{@code {{index}}} 等不带 {@code @} 的写法等价）</li>
 * </ul>
 *
 * <p>取值优先级（后者兜底）：当前循环行 → 循环表 → 标量变量 → 原始行对象。</p>
 * <p>未找到的占位符渲染为空串。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Placeholders {

    /**
     * 循环开始标记。
     */
    private static final String LOOP_OPEN = "#";

    /**
     * 循环结束标记。
     */
    private static final String LOOP_CLOSE = "/";

    /**
     * 循环元信息前缀。
     */
    private static final String LOOP_META = "@";

    /**
     * 行 Map 中保存原始行对象的键。
     */
    private static final String RAW = "_row";

    /**
     * 变量表。
     */
    private final Map<String, Object> variables;

    /**
     * 循环表格。
     */
    private final Map<String, List<Map<String, Object>>> tables;

    /**
     * 标签正则。
     */
    private final Pattern pattern;

    private Placeholders(TemplateContext context) {
        this.variables = context.variables();
        this.tables = context.tables();
        ExportConfig config = context.config();
        this.pattern = Pattern.compile(Pattern.quote(config.getTagPrefix())
                + "\\s*(.*?)\\s*" + Pattern.quote(config.getTagSuffix()));
    }

    /**
     * 创建引擎。
     *
     * @param context 模板上下文
     * @return 模板引擎
     */
    public static Placeholders of(TemplateContext context) {
        return new Placeholders(context);
    }

    /**
     * 判断文本是否包含占位符。
     *
     * @param text 文本
     * @return 是否包含
     */
    public boolean contains(String text) {
        return text != null && pattern.matcher(text).find();
    }

    /**
     * 判断文本是否包含循环开始标记。
     *
     * @param text 文本
     * @return 是否包含
     */
    public boolean isLoopStart(String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while (matcher.find()) {
            if (matcher.group(1).startsWith(LOOP_OPEN)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断文本是否包含循环结束标记。
     *
     * @param text 文本
     * @return 是否包含
     */
    public boolean isLoopEnd(String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while (matcher.find()) {
            if (matcher.group(1).startsWith(LOOP_CLOSE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析循环标签名。
     *
     * @param text   文本
     * @param prefix {@code "#} 或 {@code /}
     * @return 标签名
     * @throws ExportException 标记格式非法时抛出
     */
    public String loopTag(String text, String prefix) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String body = matcher.group(1);
            if (body.startsWith(prefix)) {
                return body.substring(1).trim();
            }
        }
        throw new ExportException("非法循环标记: " + text);
    }

    /**
     * 渲染标量占位符。
     *
     * @param text     原始文本
     * @param row      当前循环行，可为 {@code null}
     * @param rowIndex 当前循环行序号，从 0 开始
     * @param rowCount 当前循环总行数
     * @return 渲染结果
     */
    public String render(String text, Map<String, Object> row, int rowIndex, int rowCount) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 32);
        while (matcher.find()) {
            Object value = lookup(matcher.group(1).trim(), row, rowIndex, rowCount);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 渲染单元格内容。
     *
     * <p>整格恰好为一个占位符时返回原始类型（数值 / 布尔 / 文本），
     * 以便写入原生 Excel 类型；含多个占位符或夹杂文本时返回字符串。</p>
     *
     * @param text     原始文本
     * @param row      当前循环行，可为 {@code null}
     * @param rowIndex 当前循环行序号
     * @param rowCount 当前循环总行数
     * @return 渲染结果
     */
    public Object renderValue(String text, Map<String, Object> row, int rowIndex, int rowCount) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 注意不能用 matches()：正则的 .*? 能吞掉中间的 }} 与 {{，两个占位符的单元格
        // 也会被判定为"整格一个占位符"，从而拿到 null 并写成空单元格
        Matcher single = pattern.matcher(text);
        if (single.find() && single.start() == 0 && single.end() == text.length()) {
            return lookup(single.group(1).trim(), row, rowIndex, rowCount);
        }
        return render(text, row, rowIndex, rowCount);
    }

    /**
     * 解析占位符对应的值。
     *
     * <p>顺序：循环元信息 → 原始行对象的嵌套路径 → 当前行 → 循环表 → 标量变量 → 原始行对象。</p>
     *
     * @param tag      标签
     * @param row      当前循环行
     * @param rowIndex 当前循环行序号
     * @param rowCount 当前循环总行数
     * @return 值
     */
    public Object lookup(String tag, Map<String, Object> row, int rowIndex, int rowCount) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        String key = tag.startsWith(LOOP_META) ? tag.substring(1) : tag;
        if (row != null) {
            switch (key) {
                case "index" -> {
                    return rowIndex + 1;
                }
                case "first" -> {
                    return rowIndex == 0;
                }
                case "last" -> {
                    return rowIndex == rowCount - 1;
                }
                default -> {
                    // 继续按普通占位符解析
                }
            }
            if (tag.startsWith(LOOP_META)) {
                return null;
            }
        }

        String root = key;
        String path = null;
        int dot = key.indexOf('.');
        if (dot > 0) {
            root = key.substring(0, dot);
            path = key.substring(dot + 1);
        }

        // 嵌套路径优先在原始行对象上取值：展开后的行 Map 里同名列已是格式化后的展示值，
        // 再往下钻只会拿到 toString 结果而不是嵌套对象
        Object raw = row == null ? null : row.get(RAW);
        if (path != null && raw != null) {
            Object nested = Rows.byPath(raw, key);
            if (nested != null) {
                return nested;
            }
        }

        Object value = null;
        if (row != null && row.containsKey(root)) {
            value = row.get(root);
        } else if (tables.containsKey(root)) {
            value = tables.get(root);
        } else if (variables.containsKey(root)) {
            value = variables.get(root);
        } else if (raw != null) {
            // 根段不是导出列时回落到原始行对象，支持 {{customer.name}} 这类未导出嵌套字段
            value = raw;
        }
        if (path != null && value != null) {
            value = Rows.byPath(value, path);
        }
        if (value == null && variables.containsKey(key)) {
            return variables.get(key);
        }
        return value;
    }

    /**
     * 获取全部标量变量（用于预览）。
     *
     * @return 变量表副本
     */
    public Map<String, Object> scalars() {
        return new LinkedHashMap<>(variables);
    }

    /**
     * 获取指定循环标签的数据。
     *
     * @param tag 标签
     * @return 行列表
     */
    public List<Map<String, Object>> table(String tag) {
        return tables.getOrDefault(tag, new ArrayList<>());
    }
}
