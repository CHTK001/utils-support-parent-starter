package com.chua.datasource.support.document.template;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.document.*;
import com.chua.common.support.lang.json.Json;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 类路径 模板文件的文档模板基类。
 *
 * <p>占位符格式：{@code #key#}。复杂结构通过 {@code #dataJson#} / {@code #tablesMarkdown#} 注入。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractClasspathDocumentTemplate implements DocumentTemplate {

    /**
     * 占位符正则
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("#([\\w.]+)#");

    /**
     * HTML 模板资源路径
     *
     * @return classpath 路径
     */
    protected abstract String htmlTemplatePath();

    /**
     * Markdown 模板资源路径
     *
     * @return classpath 路径
     */
    protected abstract String markdownTemplatePath();

    /**
     * HTML 输出中需要按 HTML 文本转义的变量。
     *
     * <p>这些值全部来自数据库元数据（库名、表备注、产品名……），会直接落进
     * 模板的文本节点或属性里。不转义时一个 {@code <} 就能把版面顶坏，
     * 一个 {@code <script>} 就能在导出文件里执行任意脚本。</p>
     *
     * <p>{@code dataJson} / {@code tablesMarkdown} 不在此列：前者走
     * {@link #toScriptJson(String)}，后者是 Markdown 原文，各自有专门口径。</p>
     */
    private static final String[] HTML_TEXT_KEYS = {
            "title", "databaseName", "productName", "productVersion",
            "url", "version", "description", "exportTime"
    };

    /**
     * 渲染 HTML 文档。
     *
     * <p>HTML 模板的文本型占位符必须先做 HTML 转义再替换：这些值来自库表元数据，
     * 未转义时会被当成标签解析（轻则版面错乱，重则注入脚本）。</p>
     *
     * @param data   文档数据
     * @param config 导出配置
     * @return HTML 文本
     */
    @Override
    public String renderHtml(DocumentData data, DocumentExportConfig config) {
        Map<String, Object> vars = buildVariables(data, config);
        escapeHtmlVars(vars);
        if (config != null && config.getCustomHtmlTemplate() != null && !config.getCustomHtmlTemplate().isBlank()) {
            return resolve(config.getCustomHtmlTemplate(), vars);
        }
        return resolve(loadTemplate(htmlTemplatePath()), vars);
    }

    /**
     * 渲染 Markdown 文档。
     *
     * @param data   文档数据
     * @param config 导出配置
     * @return Markdown 文本
     */
    @Override
    public String renderMarkdown(DocumentData data, DocumentExportConfig config) {
        return resolve(loadTemplate(markdownTemplatePath()), buildVariables(data, config));
    }

    /**
     * 构建模板变量。
     *
     * @param data   文档数据
     * @param config 导出配置
     * @return 变量表
     */
    protected Map<String, Object> buildVariables(DocumentData data, DocumentExportConfig config) {
        Map<String, Object> vars = Wraps.linkedMap();
        vars.put("title", nullToEmpty(data.getTitle()));
        vars.put("databaseName", nullToEmpty(data.getDatabaseName(), "All Databases"));
        vars.put("productName", nullToEmpty(data.getProductName()));
        vars.put("productVersion", nullToEmpty(data.getProductVersion()));
        vars.put("url", nullToEmpty(data.getUrl()));
        vars.put("version", nullToEmpty(data.getVersion()));
        vars.put("description", nullToEmpty(data.getDescription()));
        // 内联进 <script> 的 JSON 必须做脚本级转义：JSON 本身合法不代表能安全
        // 放进 script 元素 —— 备注里的 </script> 会提前闭合脚本块，把文档截断。
        vars.put("dataJson", toScriptJson(toDataJson(data)));
        vars.put("tablesMarkdown", toTablesMarkdown(data));
        vars.put("exportTime", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
        if (config != null && config.getOptions() != null) {
            vars.putAll(config.getOptions());
        }
        return vars;
    }

    /**
     * 文档数据 → 前端 JSON（E10 / Swagger 壳共用）
     *
     * @param data 文档数据
     * @return JSON
     */
    protected String toDataJson(DocumentData data) {
        Map<String, Object> root = Wraps.linkedMap();
        root.put("title", data.getTitle());
        root.put("databaseName", data.getDatabaseName());
        root.put("productName", data.getProductName());
        root.put("productVersion", data.getProductVersion());
        root.put("url", data.getUrl());
        root.put("version", data.getVersion());
        root.put("description", data.getDescription());
        root.put("total", data.getTables() == null ? 0 : data.getTables().size());

        List<Map<String, Object>> tables = Wraps.list();
        List<Map<String, Object>> db = Wraps.list();
        List<Map<String, Object>> mo = Wraps.list();
        List<Map<String, Object>> ts = Wraps.list();
        Map<String, Integer> dbCount = Wraps.linkedMap();

        if (data.getTables() != null) {
            for (TableData table : data.getTables()) {
                Map<String, Object> t = Wraps.linkedMap();
                t.put("tableName", table.getTableName());
                t.put("remark", table.getRemark());
                t.put("schema", table.getSchema());
                t.put("schemaName", table.getSchema());
                t.put("tableSchema", table.getSchema());
                t.put("type", table.getType() != null ? table.getType() : "TABLE");
                List<Map<String, Object>> importedKeys = Wraps.list();
                if (table.getImportedKeys() != null) {
                    for (RelationshipData r : table.getImportedKeys()) {
                        Map<String, Object> rMap = Wraps.linkedMap();
                        rMap.put("fkName", r.getFkName());
                        rMap.put("fkTableName", r.getFkTableName());
                        rMap.put("fkColumnName", r.getFkColumnName());
                        rMap.put("pkTableName", r.getPkTableName());
                        rMap.put("pkColumnName", r.getPkColumnName());
                        rMap.put("updateRule", r.getUpdateRule());
                        rMap.put("deleteRule", r.getDeleteRule());
                        importedKeys.add(rMap);
                    }
                }
                t.put("importedKeys", importedKeys);
                List<Map<String, Object>> exportedKeys = Wraps.list();
                if (table.getExportedKeys() != null) {
                    for (RelationshipData r : table.getExportedKeys()) {
                        Map<String, Object> rMap = Wraps.linkedMap();
                        rMap.put("fkName", r.getFkName());
                        rMap.put("fkTableName", r.getFkTableName());
                        rMap.put("fkColumnName", r.getFkColumnName());
                        rMap.put("pkTableName", r.getPkTableName());
                        rMap.put("pkColumnName", r.getPkColumnName());
                        rMap.put("updateRule", r.getUpdateRule());
                        rMap.put("deleteRule", r.getDeleteRule());
                        exportedKeys.add(rMap);
                    }
                }
                t.put("exportedKeys", exportedKeys);
                List<Map<String, Object>> columns = Wraps.list();
                List<Map<String, Object>> fields = Wraps.list();
                if (table.getColumns() != null) {
                    for (ColumnData col : table.getColumns()) {
                        Map<String, Object> c = Wraps.linkedMap();
                        c.put("ordinalPosition", col.getOrdinalPosition());
                        c.put("columnName", col.getColumnName());
                        c.put("typeName", col.getTypeName());
                        c.put("columnSize", col.getColumnSize());
                        c.put("decimalDigits", col.getDecimalDigits());
                        c.put("nullable", col.isNullable());
                        c.put("primaryKey", col.isPrimaryKey());
                        c.put("defaultValue", col.getDefaultValue());
                        c.put("remark", col.getRemark());
                        columns.add(c);

                        Map<String, Object> f = Wraps.linkedMap();
                        f.put("n", col.getColumnName());
                        f.put("c", col.getRemark());
                        f.put("tp", col.getTypeName());
                        f.put("ln", col.getColumnSize());
                        f.put("pk", col.isPrimaryKey());
                        fields.add(f);
                    }
                }
                t.put("columns", columns);
                tables.add(t);

                String schemaName = table.getSchema();
                if (schemaName == null) {
                    schemaName = "";
                }
                if (!schemaName.isEmpty()) {
                    dbCount.merge(schemaName, 1, Integer::sum);
                }

                Map<String, Object> row = Wraps.linkedMap();
                row.put("t", table.getTableName());
                row.put("c", table.getRemark());
                row.put("d", schemaName);
                row.put("m", "");
                row.put("f", fields);
                ts.add(row);
            }
        }

        for (Map.Entry<String, Integer> e : dbCount.entrySet()) {
            Map<String, Object> item = Wraps.linkedMap();
            item.put("n", e.getKey());
            item.put("c", e.getValue());
            db.add(item);
        }

        root.put("tables", tables);
        root.put("db", db);
        root.put("mo", mo);
        root.put("ts", ts);

        List<Map<String, Object>> nodes = Wraps.list();
        List<Map<String, Object>> edges = Wraps.list();
        if (data.getTables() != null) {
            for (TableData table : data.getTables()) {
                Map<String, Object> node = Wraps.linkedMap();
                node.put("id", table.getTableName());
                node.put("label",
                         (table.getRemark() != null && !table.getRemark().isEmpty())
                                 ? table.getRemark()
                                 : table.getTableName());
                node.put("schema", table.getSchema());
                node.put("type", table.getType() != null ? table.getType() : "TABLE");

                List<String> pkCols = Wraps.list();
                List<String> fkCols = Wraps.list();
                if (table.getColumns() != null) {
                    for (ColumnData col : table.getColumns()) {
                        if (col.isPrimaryKey()) {
                            pkCols.add(col.getColumnName());
                        }
                    }
                }
                if (table.getImportedKeys() != null) {
                    for (RelationshipData r : table.getImportedKeys()) {
                        if (!fkCols.contains(r.getFkColumnName())) {
                            fkCols.add(r.getFkColumnName());
                        }
                    }
                }
                node.put("pk", pkCols);
                node.put("fk", fkCols);
                nodes.add(node);
            }

            for (TableData table : data.getTables()) {
                if (table.getExportedKeys() != null) {
                    for (RelationshipData r : table.getExportedKeys()) {
                        Map<String, Object> edge = Wraps.linkedMap();
                        edge.put("from", r.getFkTableName());
                        edge.put("to", r.getPkTableName());
                        edge.put("fromColumn", r.getFkColumnName());
                        edge.put("toColumn", r.getPkColumnName());
                        edge.put("updateRule", r.getUpdateRule());
                        edge.put("deleteRule", r.getDeleteRule());
                        edges.add(edge);
                    }
                }
            }
        }
        root.put("graph", Map.of("nodes", nodes, "edges", edges));
        return Json.toJson(root);
    }

    /**
     * 表结构 Markdown 片段
     *
     * @param data 文档数据
     * @return markdown
     */
    protected String toTablesMarkdown(DocumentData data) {
        StringBuilder sb = new StringBuilder();
        if (data.getTables() == null) {
            return "";
        }
        for (TableData table : data.getTables()) {
            String title = mdCell(table.getTableName());
            if (table.getRemark() != null && !table.getRemark().isEmpty()) {
                title = title.concat("（" + mdCell(table.getRemark()) + "）");
            }
            sb.append("## ").append(title).append("\n\n");
            sb.append("| 序号 | 列名 | 类型 | 大小 | 小数位 | 可空 | 主键 | 默认值 | 备注 |\n");
            sb.append("|------|------|------|------|--------|------|------|--------|------|\n");
            if (table.getColumns() != null) {
                for (ColumnData col : table.getColumns()) {
                    sb.append("| ").append(col.getOrdinalPosition())
                            .append(" | ").append(mdCell(col.getColumnName()))
                            .append(" | ").append(mdCell(col.getTypeName()))
                            .append(" | ").append(col.getColumnSize())
                            .append(" | ").append(col.getDecimalDigits() != null ? col.getDecimalDigits() : "")
                            .append(" | ").append(col.isNullable() ? "是" : "否")
                            .append(" | ").append(col.isPrimaryKey() ? "是" : "否")
                            .append(" | ").append(mdCell(col.getDefaultValue()))
                            .append(" | ").append(mdCell(col.getRemark()))
                            .append(" |\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 加载 类路径 模板
     *
     * @param path 资源路径
     * @return 模板文本
     */
    protected String loadTemplate(String path) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }
        InputStream stream = cl.getResourceAsStream(path);
        if (stream == null) {
            stream = getClass().getClassLoader().getResourceAsStream(path);
        }
        if (stream == null) {
            throw new IllegalStateException("模板文件不存在: " + path);
        }
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("加载模板失败: " + path, e);
        }
    }

    /**
     * 替换 #键# 占位符
     *
     * @param template 模板
     * @param vars     变量
     * @return 结果
     */
    protected String resolve(String template, Map<String, Object> vars) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = vars.get(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value.toString() : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 把构建好的变量表里的文本型值做 HTML 转义（就地修改）。
     *
     * @param vars 变量表
     */
    private static void escapeHtmlVars(Map<String, Object> vars) {
        for (String key : HTML_TEXT_KEYS) {
            Object value = vars.get(key);
            if (value != null) {
                vars.put(key, escapeHtml(value.toString()));
            }
        }
    }

    /**
     * HTML 文本转义。
     *
     * @param value 原值
     * @return 转义后的文本
     */
    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * JSON 的脚本级转义，用于把 JSON 内联进 {@code <script>}。
     *
     * <p>把 {@code < > &} 换成对应的 {@code \}{@code uXXXX} 形式：
     * 对 JSON 解析结果没有影响，但 HTML 解析器再也看不到 {@code </script>}
     * 这个结束标记。{@code U+2028/2029} 也要转义，它们在 JS 字符串里是换行符。</p>
     *
     * @param json JSON 文本
     * @return 可安全内联的 JSON
     */
    private static String toScriptJson(String json) {
        String lineSeparator = String.valueOf((char) 0x2028);
        String paragraphSeparator = String.valueOf((char) 0x2029);
        return json.replace("&", "\\u0026")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace(lineSeparator, "\\u2028")
                .replace(paragraphSeparator, "\\u2029");
    }

    /**
     * Markdown 表格单元格转义：竖线会拆列，换行会断行。
     *
     * @param value 原值
     * @return 可安全放进单元格的文本
     */
    private static String mdCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    /**
     * 空转为空
     *
     * @param value 值
     * @return 空转为空的结果
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 空转为空
     *
     * @param value 值
     * @param defaultValue 默认值
     * @return 空转为空的结果
     */
    private static String nullToEmpty(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
