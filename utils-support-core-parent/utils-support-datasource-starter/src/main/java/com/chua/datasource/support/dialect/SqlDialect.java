package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.dialect.StorageEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 统一 SQL 方言实现，所有配置驱动。
 * <p>
 * 所有数据库特有配置（驱动、引用符、类型映射、DDL/DML 片段、触发器/存储过程查询 SQL）
 * 均从 {@code META-INF/dialect-env/{protocol}.env} 资源文件加载，外部 {@link #properties}
 * 可覆盖其中任意值。
 * </p>
 * <p>
 * 示例：{@code new SqlDialect("mysql")} 即可工作，子类只需传递协议名。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SqlDialect extends AbstractDialect {

    /**
     * 当前方言的协议名（对应 .env 文件名）
    */
    private final String protocol;

    /**
     * 构造 SQL 方言，自动加载 {@code META-INF/dialect-env/{protocol}.env}。
     *
     * @param protocol 协议名（如 {@code mysql}、{@code postgresql}、{@code oracle}）
     */
    public SqlDialect(String protocol) {
        this.protocol = protocol;
        // AbstractDialect 无参构造器先于本构造器执行并虚调用 loadDefaultEnv()，此时 protocol 为空，
        // 因此此处必须在 protocol 赋值后显式重新加载 .env 配置
        this.properties = loadDefaultEnv();
    }

    /**
     * 构造 SQL 方言，加载默认 env 并用外部 属性 覆盖。
     *
     * @param protocol     协议名
     * @param properties   外部属性（优先级高于 .env）
     */
    public SqlDialect(String protocol, Properties properties) {
        this.protocol = protocol;
        this.properties = loadDefaultEnv();
        if (properties != null) {
            this.properties = merge(this.properties, properties);
        }
    }

    // ==================== 基础方法（全部走 config） ====================

    @Override
    public String protocol() {
        return config("protocol", protocol);
    }

    @Override
    public String driver() {
        return config("driver", null);
    }

    @Override
    public String url() {
        return config("url", null);
    }

    @Override
    public char openQuote() {
        return firstChar("quote-open");
    }

    @Override
    public char closeQuote() {
        return firstChar("quote-close");
    }

    /**
     * 返回字符串第一个字符，空字符串时返回空格。
     *
     * @param key 配置键
     * @return 第一个字符或空格
     */
    private char firstChar(String key) {
        String v = config(key, "");
        if (v.isEmpty()) {
            return ' ';
        }
        return v.charAt(0);
    }

    @Override
    public boolean supportsLimit() {
        Boolean v = configBool("supports-limit");
        if (v != null) {
            return v;
        }
        return true;
    }

    /**
     * 读取布尔型配置，键 不存在时返回 空。
     *
     * @param key 配置键
     * @return 解析后的布尔值，key 不存在返回 空
     */
    private Boolean configBool(String key) {
        String v = config(key, null);
        if (v == null) {
            return null;
        }
        return Boolean.parseBoolean(v.trim());
    }

    @Override
    public String processSql(String sql, Pagination pagination) {
 // 优先读自定义分页模板（支持 {SQL} {偏移量} {限制} {offset_clause} 占位符）
        String template = config("pagination-sql", null);
        if (template != null) {
            // OFFSET/FETCH 方言（SQL Server/Oracle12c）必须有 ORDER BY，缺失时按 env 配置的伪排序补上
            if (Boolean.parseBoolean(config("pagination-requires-order-by", "false"))
                    && !sql.toUpperCase(java.util.Locale.ROOT).contains("ORDER BY")) {
                sql = sql + config("pagination-order-by-fallback", " ORDER BY (SELECT NULL)");
            }
            String offsetClause = pagination.getOffset() > 0
                    ? " OFFSET " + pagination.getOffset() : "";
            return template
                    .replace("{sql}", sql)
                    .replace("{offset_clause}", offsetClause)
                    .replace("{offset}", String.valueOf(pagination.getOffset()))
                    .replace("{limit}", String.valueOf(pagination.getLimit()));
        }
 // 默认 限制/偏移量
        return sql + " LIMIT " + pagination.getLimit() + " OFFSET " + pagination.getOffset();
    }

    @Override
    public String getTypeName(int jdbcType, long length, int precision, int scale) {
        // 带长度的类型，用 {len}/{prec}/{scl} 占位符替换
        String len = length > 0 ? String.valueOf(length) : "255";
        String prec = precision > 0 ? String.valueOf(precision) : "10";
        String scl = scale > 0 ? String.valueOf(scale) : "0";
        String configured = config("type." + jdbcTypeName(jdbcType), null);
        if (configured != null) {
            return configured
                    .replace("{len}", len)
                    .replace("{prec}", prec)
                    .replace("{scl}", scl);
        }
        String defaultType = config("type-default", "VARCHAR(" + len + ")");
        return defaultType
                .replace("{len}", len)
                .replace("{prec}", prec)
                .replace("{scl}", scl);
    }

    @Override
    public String getAutoIncrementKeyword() {
        return config("auto-increment-keyword", "AUTO_INCREMENT");
    }

    @Override
    public boolean supportsUpsert() {
        Boolean v = configBool("supports-upsert");
        if (v != null) {
            return v;
        }
        return false;
    }

    @Override
    public String getUpsertSql(String tableName, String columns, String values, String updateSet) {
        String template = config("upsert-template", null);
        if (template != null) {
            String conflict = config("upsert-conflict-columns", null);
            if (conflict == null || conflict.trim().isEmpty()) {
                // 未显式配置冲突列时取首列（仓库惯例主键在首）
                int comma = columns.indexOf(',');
                conflict = comma > 0 ? columns.substring(0, comma).trim() : columns.trim();
            }
            return template
                    .replace("{table}", quote(tableName))
                    .replace("{columns}", columns)
                    .replace("{values}", values)
                    .replace("{conflict}", conflict)
                    .replace("{updateSet}", updateSet);
        }
        return super.getUpsertSql(tableName, columns, values, updateSet);
    }

    @Override
    public String getAlterColumnString() {
        return config("alter-column-string", "modify column");
    }

    @Override
    public String getWriteLockString(int timeout) {
        String template = config("write-lock-template", " for update");
        if (template.contains("{timeout}")) {
            return template.replace("{timeout}", String.valueOf(timeout));
        }
        return template;
    }

    @Override
    public String getTableComment(String comment) {
        return renderComment("table-comment", comment);
    }

    @Override
    public String getColumnComment(String comment) {
        return renderComment("column-comment", comment);
    }

    /**
     * 渲染注释片段：模板含 {comment} 时注入转义后的注释文本。
     *
     * @param templateKey 模板配置键
     * @param comment     注释内容，空时不输出
     * @return 注释 SQL 片段，不支持或无内容时返回空串
     */
    private String renderComment(String templateKey, String comment) {
        if (comment == null || comment.isEmpty()) {
            return "";
        }
        String template = config(templateKey, null);
        if (template == null || template.isEmpty()) {
            // 方言未定义内联注释模板（如 Oracle/PostgreSQL 需单独 COMMENT ON 语句）
            return "";
        }
        if (template.contains("{comment}")) {
            return template.replace("{comment}", escape(comment));
        }
        return template;
    }

    @Override
    public String getCurrentTimestampSelectString() {
        return config("current-timestamp-sql", "SELECT CURRENT_TIMESTAMP");
    }

    @Override
    public boolean supportsInlineComment() {
        Boolean v = configBool("supports-inline-comment");
        if (v != null) {
            return v;
        }
        return false;
    }

    @Override
    public boolean supportsPartition() {
        Boolean v = configBool("supports-partition");
        if (v != null) {
            return v;
        }
        return false;
    }

    @Override
    public String getEngineKeyword() {
        return config("engine-keyword", "engine");
    }

    @Override
    public StorageEngine getStorageEngine() {
        String v = config("storage-engine", "DEFAULT").toUpperCase();
        try {
            return StorageEngine.valueOf(v);
        } catch (IllegalArgumentException e) {
            return StorageEngine.DEFAULT;
        }
    }

    @Override
    public String getTableTypeString() {
        return config("table-type", "");
    }

    @Override
    public boolean supportsVector() {
        Boolean v = configBool("supports-vector");
        if (v != null) {
            return v;
        }
        return false;
    }

    @Override
    public boolean supportsJson() {
        Boolean v = configBool("supports-json");
        if (v != null) {
            return v;
        }
        return false;
    }

    @Override
    public String queryLang() {
        return config("query-lang", "sql");
    }

    // ==================== 触发器 / 存储过程（走 env 模板） ====================

    @Override
    public String getTriggerListSql(String schema) {
        String template = config("trigger-list-sql", null);
        if (template == null) {
            return null;
        }
        return buildWithSchema(template, schema, "trigger_schema");
    }

    @Override
    public String getTriggerSql(String triggerName, String schema) {
        String template = config("trigger-sql", null);
        if (template == null) {
            return null;
        }
        String listSql = getTriggerListSql(schema);
        if (listSql == null) {
            return null;
        }
        return template
                .replace("{list_sql}", listSql)
                .replace("{trigger_name}", escape(triggerName));
    }

    @Override
    public String getProcedureListSql(String schema) {
        String template = config("procedure-list-sql", null);
        if (template == null) {
            return null;
        }
        return buildWithSchema(template, schema, "routine_schema");
    }

    @Override
    public String getProcedureSql(String procedureName, String schema) {
        String template = config("procedure-sql", null);
        if (template == null) {
            return null;
        }
        String listSql = getProcedureListSql(schema);
        if (listSql == null) {
            return null;
        }
        return template
                .replace("{list_sql}", listSql)
                .replace("{procedure_name}", escape(procedureName));
    }

    /**
     * 在模板末尾追加 模式 过滤条件。
     *
     * @param template       SQL 模板
     * @param schema         模式 名称，空 或空时不追加
     * @param schemaColumn   模式 列名
     * @return 追加条件后的 SQL
     */
    private String buildWithSchema(String template, String schema, String schemaColumn) {
        if (schema == null || schema.isEmpty()) {
            return template;
        }
        String condition = config("schema-condition-template", " AND {column} = '{value}'")
                .replace("{column}", schemaColumn)
                .replace("{value}", escape(schema));
        return template + condition;
    }

    // ==================== 配置加载 ====================

    /**
     * 从类路径加载 {@code META-INF/dialect-env/{protocol}.env}。
     *
     * @return 加载后的属性，文件不存在时返回空属性
     */
    @Override
    protected Properties loadDefaultEnv() {
        String resourceName = "META-INF/dialect-env/" + protocol + ".env";
        Properties props = new Properties();
        java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
        try {
            java.util.Enumeration<java.net.URL> found =
                    getClass().getClassLoader().getResources(resourceName);
            while (found.hasMoreElements()) {
                urls.add(found.nextElement());
            }
        } catch (IOException e) {
            return props;
        }
        // 跨 jar 合并同名 env：类路径靠前的（原单资源加载语义）逐键优先，后续 jar 只补缺
        for (java.net.URL url : urls) {
            try (InputStream is = url.openStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int idx = line.indexOf('=');
                    if (idx < 0) {
                        continue;
                    }
                    // 值侧不 trim：保留 "table-type= ENGINE=..." 这类模板的前导空格
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1);
                    while (value.endsWith("\r") || value.endsWith(" ")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    props.putIfAbsent(key, value);
                }
            } catch (IOException e) {
                // 单个资源读取失败不影响其余来源合并
            }
        }
        return props;
    }

    /**
     * 合并。
     *
     * @param defaults 方法入参 defaults
     * @param overrides 方法入参 overrides
     * @return 属性 对象
     */
    private static Properties merge(Properties defaults, Properties overrides) {
        Properties merged = new Properties(defaults);
        merged.putAll(overrides);
        return merged;
    }
}
