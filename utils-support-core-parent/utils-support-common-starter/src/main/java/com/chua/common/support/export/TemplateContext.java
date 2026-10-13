package com.chua.common.support.export;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.support.Rows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板上下文 —— 接口二（模板替换）的输入载体。
 *
 * <p>提供两类数据：</p>
 * <ul>
 *     <li><b>标量变量</b> {@link #variables()} —— 对应 {@code {{key}}} 与
 *         {@code {{a.b}}} 占位符</li>
 *     <li><b>循环表格</b> {@link #tables()} —— 对应 {@code {{#tag}}}…{@code {{/tag}}} 循环块，
 *         如 xlsx 行块、docx 表格行、PDF/HTML 的循环区</li>
 * </ul>
 *
 * <p>行数据在绑定时会被自动展开为以"属性名 / 列标题 / 模板标签"为 key 的
 * {@code Map}，并附 {@code _index}（从 1 开始）与 {@code _row}（原始行对象），
 * 实现方无需自行做取值与格式化。</p>
 *
 * <p>模板语法与取值优先级见 {@link com.chua.common.support.export.support.Placeholders}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TemplateContext {

    /**
     * 文档元数据。
     */
    private final DocumentMeta meta;

    /**
     * 标量变量。
     */
    private final Map<String, Object> variables;

    /**
     * 循环表格：标签 → 展开后的行 Map 列表。
     */
    private final Map<String, List<Map<String, Object>>> tables;

    /**
     * 原始行数据。
     */
    private final List<Object> rows;

    /**
     * 导出配置。
     */
    private final ExportConfig config;

    /**
     * 构造模板上下文。
     *
     * @param meta      文档元数据
     * @param variables 标量变量
     * @param tables    循环表格
     * @param rows      原始行数据
     * @param config    导出配置
     */
    public TemplateContext(DocumentMeta meta, Map<String, Object> variables,
                           Map<String, List<Map<String, Object>>> tables,
                           List<Object> rows, ExportConfig config) {
        this.meta = meta;
        this.variables = variables == null ? Wraps.map() : variables;
        this.tables = tables == null ? Wraps.map() : tables;
        this.rows = rows == null ? Wraps.list() : rows;
        this.config = config == null ? ExportConfig.EMPTY : config;
    }

    /**
     * 获取文档元数据。
     *
     * @return 文档元数据
     */
    public DocumentMeta meta() {
        return meta;
    }

    /**
     * 获取标量变量。
     *
     * @return 变量表
     */
    public Map<String, Object> variables() {
        return variables;
    }

    /**
     * 获取循环表格。
     *
     * @return 表格表
     */
    public Map<String, List<Map<String, Object>>> tables() {
        return tables;
    }

    /**
     * 获取指定标签的循环表格。
     *
     * @param tag 标签
     * @return 行 Map 列表，不存在时返回空列表
     */
    public List<Map<String, Object>> table(String tag) {
        return tables.getOrDefault(tag, Wraps.list());
    }

    /**
     * 获取原始行数据。
     *
     * @return 行数据
     */
    public List<Object> rows() {
        return rows;
    }

    /**
     * 获取导出配置。
     *
     * @return 导出配置
     */
    public ExportConfig config() {
        return config;
    }

    /**
     * 获取可见列。
     *
     * @return 可见列
     */
    public List<ColumnMeta> columns() {
        return meta.visibleColumns();
    }

    /**
     * 把一行原始数据展开为 {@code Map}，同时以"属性名 / 模板标签 / 列标题"为 key 写入。
     *
     * @param row 行对象
     * @param index 行序号，从 0 开始
     * @return 展开后的行 Map
     */
    public Map<String, Object> flatten(Object row, int index) {
        Map<String, Object> target = new LinkedHashMap<>();
        for (ColumnMeta column : meta.visibleColumns()) {
            Object value = Rows.display(row, column);
            target.put(column.getProperty(), value);
            if (!column.getTag().isEmpty()) {
                target.putIfAbsent(column.getTag(), value);
            }
            if (!column.getName().isEmpty()) {
                target.putIfAbsent(column.getName(), value);
            }
        }
        target.put("_index", index + 1);
        target.put("_row", row);
        return target;
    }

    /**
     * 把全部行展开为 {@code Map} 列表。
     *
     * @param rawRows 原始行数据
     * @return 展开后的行 Map 列表
     */
    public List<Map<String, Object>> flattenAll(List<?> rawRows) {
        List<Map<String, Object>> list = new ArrayList<>(rawRows.size());
        for (int i = 0; i < rawRows.size(); i++) {
            list.add(flatten(rawRows.get(i), i));
        }
        return list;
    }

    /**
     * 合并视图：标量变量 + 各循环表格合并为一个 {@code Map}，便于一次性取出全部数据。
     *
     * <p>框架自身的填充器不走这条路径（它们用 {@link #variables()} 与 {@link #table(String)}），
     * 该方法面向自定义实现或排障打印。</p>
     *
     * @return 模板模型
     */
    public Map<String, Object> model() {
        Map<String, Object> model = new LinkedHashMap<>(variables);
        tables.forEach(model::put);
        return model;
    }
}