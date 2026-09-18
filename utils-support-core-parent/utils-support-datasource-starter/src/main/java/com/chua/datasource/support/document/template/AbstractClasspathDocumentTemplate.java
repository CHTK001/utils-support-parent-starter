package com.chua.datasource.support.document.template;

import com.chua.common.support.lang.document.*;
import com.chua.common.support.lang.json.Json;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    * 渲染 HTML 文档。
    *
    * @param data   文档数据
    * @param config 导出配置
    * @return HTML 文本
    */
    @Override
    public String renderHtml(DocumentData data, DocumentExportConfig config) {
        if (config != null && config.getCustomHtmlTemplate() != null && !config.getCustomHtmlTemplate().isBlank()) {
            return resolve(config.getCustomHtmlTemplate(), buildVariables(data, config));
        }
        return resolve(loadTemplate(htmlTemplatePath()), buildVariables(data, config));
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
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", nullToEmpty(data.getTitle()));
        vars.put("databaseName", nullToEmpty(data.getDatabaseName(), "All Databases"));
        vars.put("productName", nullToEmpty(data.getProductName()));
        vars.put("productVersion", nullToEmpty(data.getProductVersion()));
        vars.put("url", nullToEmpty(data.getUrl()));
        vars.put("version", nullToEmpty(data.getVersion()));
        vars.put("description", nullToEmpty(data.getDescription()));
        vars.put("dataJson", toDataJson(data));
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
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", data.getTitle());
        root.put("databaseName", data.getDatabaseName());
        root.put("productName", data.getProductName());
        root.put("productVersion", data.getProductVersion());
        root.put("url", data.getUrl());
        root.put("version", data.getVersion());
        root.put("description", data.getDescription());
        root.put("total", data.getTables() == null ? 0 : data.getTables().size());

        List<Map<String, Object>> tables = new ArrayList<>();
        List<Map<String, Object>> db = new ArrayList<>();
        List<Map<String, Object>> mo = new ArrayList<>();
        List<Map<String, Object>> ts = new ArrayList<>();
        Map<String, Integer> dbCount = new LinkedHashMap<>();

        if (data.getTables() != null) {
            for (TableData table : data.getTables()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("tableName", table.getTableName());
                t.put("remark", table.getRemark());
                t.put("schema", table.getSchema());
                t.put("schemaName", table.getSchema());
                t.put("tableSchema", table.getSchema());
                t.put("type", table.getType() != null ? table.getType() : "TABLE");
                List<Map<String, Object>> importedKeys = new ArrayList<>();
                if (table.getImportedKeys() != null) {
                    for (RelationshipData r : table.getImportedKeys()) {
                        Map<String, Object> rMap = new LinkedHashMap<>();
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
                List<Map<String, Object>> exportedKeys = new ArrayList<>();
                if (table.getExportedKeys() != null) {
                    for (RelationshipData r : table.getExportedKeys()) {
                        Map<String, Object> rMap = new LinkedHashMap<>();
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
                List<Map<String, Object>> columns = new ArrayList<>();
                List<Map<String, Object>> fields = new ArrayList<>();
                if (table.getColumns() != null) {
                    for (ColumnData col : table.getColumns()) {
                        Map<String, Object> c = new LinkedHashMap<>();
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

                        Map<String, Object> f = new LinkedHashMap<>();
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

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("t", table.getTableName());
                row.put("c", table.getRemark());
                row.put("d", schemaName);
                row.put("m", "");
                row.put("f", fields);
                ts.add(row);
            }
        }

        for (Map.Entry<String, Integer> e : dbCount.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("n", e.getKey());
            item.put("c", e.getValue());
            db.add(item);
        }

        root.put("tables", tables);
        root.put("db", db);
        root.put("mo", mo);
        root.put("ts", ts);

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        if (data.getTables() != null) {
            for (TableData table : data.getTables()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", table.getTableName());
                node.put("label", (table.getRemark() != null && !table.getRemark().isEmpty()) ? table.getRemark() : table.getTableName());
                node.put("schema", table.getSchema());
                node.put("type", table.getType() != null ? table.getType() : "TABLE");

                List<String> pkCols = new ArrayList<>();
                List<String> fkCols = new ArrayList<>();
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
                        Map<String, Object> edge = new LinkedHashMap<>();
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
            String title = nullToEmpty(table.getTableName());
            if (table.getRemark() != null && !table.getRemark().isEmpty()) {
                title += "（" + table.getRemark() + "）";
            }
            sb.append("## ").append(title).append("\n\n");
            sb.append("| 序号 | 列名 | 类型 | 大小 | 小数位 | 可空 | 主键 | 默认值 | 备注 |\n");
            sb.append("|------|------|------|------|--------|------|------|--------|------|\n");
            if (table.getColumns() != null) {
                for (ColumnData col : table.getColumns()) {
                    sb.append("| ").append(col.getOrdinalPosition())
                            .append(" | ").append(nullToEmpty(col.getColumnName()))
                            .append(" | ").append(nullToEmpty(col.getTypeName()))
                            .append(" | ").append(col.getColumnSize())
                            .append(" | ").append(col.getDecimalDigits() != null ? col.getDecimalDigits() : "")
                            .append(" | ").append(col.isNullable() ? "是" : "否")
                            .append(" | ").append(col.isPrimaryKey() ? "是" : "否")
                            .append(" | ").append(nullToEmpty(col.getDefaultValue()))
                            .append(" | ").append(nullToEmpty(col.getRemark()))
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
