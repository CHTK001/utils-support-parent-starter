package com.chua.common.support.lang.datasource.dialect;

import com.chua.common.support.lang.datasource.dialect.meta.ColumnMetadata;
import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;
import com.chua.common.support.lang.datasource.dialect.meta.TableMetadata;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据库方言 SPI 接口，定义不同数据库的 SQL 语法差异。
 * <p>
 * 每种数据库对应一个 Dialect 实现，通过 {@code META-INF/extensions/} SPI 机制注册，
 * SPI 键为 {@link #protocol()} 返回值（例如 {@code mysql}、{@code postgresql}、{@code oracle}）。
 * </p>
 * <p>
 * 方言接口提供以下核心能力：
 * <ul>
 *   <li>分页 SQL 生成 — {@link #processSql(String, Pagination)} / {@link #getPaginationSql(String, int, int)}</li>
 *   <li>引用符号 — {@link #openQuote()} / {@link #closeQuote()} / {@link #quote(String)}</li>
 *   <li>DDL 片段 — CREATE TABLE、ALTER TABLE、DROP TABLE、ADD/DROP COLUMN</li>
 *   <li>DML 片段 — INSERT、UPDATE、DELETE、UPSERT</li>
 *   <li>类型映射 — {@link #getTypeName(int, long, int, int)}</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // SPI 获取方言
 * Dialect dialect = Dialect.getExtension("mysql");
 *
 * // 分页
 * String pageSql = dialect.getPaginationSql("select * from user", 1, 10);
 *
 * // 引用标识符
 * String quoted = dialect.quote("user_name"); // `user_name`
 *
 * // 获取 JDBC URL
 * String url = dialect.getUrl("localhost", 3306, "mydb");
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
public interface Dialect {

    /**
     * 数据库协议名称，同时也是 SPI 扩展键。
     * <p>例如：{@code mysql}、{@code postgresql}、{@code oracle}、{@code sqlserver}、{@code clickhouse}。</p>
     *
     * @return 协议名
     */
    String protocol();

    /**
     * JDBC 驱动类的全限定名。
     * <p>例如 MySQL 返回 {@code com.mysql.cj.jdbc.Driver}，
     * PostgreSQL 返回 {@code org.postgresql.Driver}。</p>
     *
     * @return 驱动类名
     */
    String driver();

    /**
     * JDBC URL 模板，包含占位符。
     * <p>支持的占位符：
     * <ul>
     *   <li>{@code <IP>} — 主机地址</li>
     *   <li>{@code <PORT>} — 端口号</li>
     *   <li>{@code <DATABASE>} — 数据库名</li>
     * </ul>
     * </p>
     * <p>例如 MySQL 返回 {@code jdbc:mysql://<IP>:<PORT>/<DATABASE>?useSSL=false&serverTimezone=Asia/Shanghai}。</p>
     *
     * @return URL 模板
     */
    String url();

    /**
     * 根据主机、端口、数据库名生成完整的 JDBC URL。
     * <p>通过替换 {@link #url()} 模板中的占位符实现。</p>
     *
     * @param host     主机地址
     * @param port     端口号
     * @param database 数据库名（可为 null）
     * @return 完整的 JDBC URL
     */
    default String getUrl(String host, int port, String database) {
        return url().replace("<IP>", host)
                .replace("<PORT>", String.valueOf(port))
                .replace("<DATABASE>", database != null ? database : "");
    }

    /**
     * 判断当前数据库是否支持物理分页（LIMIT / OFFSET 或 ROWNUM 等）。
     * <p>返回 false 表示仅支持逻辑分页（一次性查出全部数据，在内存中截取）。</p>
     *
     * @return true 支持物理分页
     */
    boolean supportsLimit();

    /**
     * 对原始 SQL 进行分页包装，生成数据库特定的分页语句。
     * <p>不同类型的数据库分页语法差异很大，例如：</p>
     * <ul>
     *   <li>MySQL: {@code SELECT * FROM t LIMIT ?, ?}</li>
     *   <li>PostgreSQL: {@code SELECT * FROM t LIMIT ? OFFSET ?}</li>
     *   <li>Oracle: {@code SELECT * FROM (SELECT t.*, ROWNUM r FROM ...) WHERE r BETWEEN ? AND ?}</li>
     *   <li>SQL Server: {@code SELECT * FROM t OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}</li>
     * </ul>
     *
     * @param sql        原始 SQL
     * @param pagination 分页参数（含 offset / limit）
     * @return 分页后的 SQL
     */
    String processSql(String sql, Pagination pagination);

    /**
     * 简洁的分页 SQL 生成方法，直接传入页码和每页条数。
     *
     * @param sql      原始 SQL
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页后的 SQL
     */
    default String getPaginationSql(String sql, int pageNum, int pageSize) {
        Pagination p = new Pagination().setPageNum(pageNum).setPageSize(pageSize);
        return processSql(sql, p);
    }

    /**
     * 左引用符，用于包裹标识符（表名、列名）。
     * <p>不同数据库的引用符不同：
     * <ul>
     *   <li>MySQL / MariaDB: {@code `}</li>
     *   <li>PostgreSQL / Oracle / SQL Server: {@code "}</li>
     *   <li>Access / SQLite: {@code [} 和 {@code ]}</li>
     * </ul>
     * 返回空格表示不使用引用符。
     * </p>
     *
     * @return 左引用字符
     */
    default char openQuote() {
        return ' ';
    }

    /**
     * 右引用符。
     *
     * @return 右引用字符
     * @see #openQuote()
     */
    default char closeQuote() {
        return ' ';
    }

    /**
     * 引用标识符，自动加上数据库特定的引用符。
     * <p>如果 {@link #openQuote()} 或 {@link #closeQuote()} 返回空格，则原样返回名称。</p>
     *
     * @param name 标识符名称
     * @return 引用后的名称，例如 {@code `user_name`}
     */
    default String quote(String name) {
        char oq = openQuote(), cq = closeQuote();
        if (oq == ' ' || cq == ' ') {
            return name;
        }
        return oq + name + cq;
    }

    /**
     * 将 JDBC 类型转换为数据库特定的类型名称。
     * <p>例如：{@code Types.BIGINT} → {@code BIGINT}、{@code Types.VARCHAR} → {@code VARCHAR(255)}。</p>
     *
     * @param jdbcType JDBC 类型代码（{@link java.sql.Types}）
     * @param length   长度（字符串/二进制类型使用）
     * @param precision 精度（数字类型使用）
     * @param scale    小数位数（数字类型使用）
     * @return 数据库类型字符串
     */
    String getTypeName(int jdbcType, long length, int precision, int scale);

    /**
     * 获取自增列的关键字。
     * <p>不同数据库的差异：
     * <ul>
     *   <li>MySQL / MariaDB: {@code AUTO_INCREMENT}</li>
     *   <li>SQLite: {@code AUTOINCREMENT}</li>
     *   <li>PostgreSQL: {@code GENERATED BY DEFAULT AS IDENTITY}</li>
     *   <li>H2: {@code AUTO_INCREMENT}</li>
     * </ul>
     * </p>
     *
     * @return 自增关键字
     */
    default String getAutoIncrementKeyword() {
        return "AUTO_INCREMENT";
    }

    // ==================== DDL 片段 ====================

    /**
     * 获取 CREATE TABLE 语句的关键词。
     *
     * @return 默认返回 {@code create table}
     */
    default String getCreateTableString() {
        return "create table";
    }

    /**
     * 获取 DROP TABLE 语句。
     *
     * @param tableName 表名
     * @return 完整的 DROP TABLE 语句，例如 {@code drop table if exists `user`}
     */
    default String getDropTableString(String tableName) {
        return "drop table if exists " + quote(tableName);
    }

    /**
     * 获取 ALTER TABLE 语句的前缀。
     *
     * @param tableName 表名
     * @return 例如 {@code alter table `user`}
     */
    default String getAlterTableString(String tableName) {
        return "alter table " + quote(tableName);
    }

    /**
     * 获取 ADD COLUMN 语句的关键词。
     *
     * @return 默认返回 {@code add column}
     */
    default String getAddColumnString() {
        return "add column";
    }

    /**
     * 获取 DROP COLUMN 语句的关键词。
     *
     * @return 默认返回 {@code drop column}
     */
    default String getDropColumnString() {
        return "drop column";
    }

    /**
     * 获取 RENAME TABLE 语句。
     *
     * @param oldName 原表名
     * @param newName 新表名
     * @return 完整的 RENAME 语句，例如 {@code alter table `user` rename to `user_new`}
     */
    default String getRenameTableString(String oldName, String newName) {
        return "alter table " + quote(oldName) + " rename to " + quote(newName);
    }

    // ==================== DML 片段 ====================

    /**
     * 生成 INSERT 语句。
     * <p>例如：{@code insert into `user` (name, age) values (?, ?)}</p>
     *
     * @param tableName 表名
     * @param columns   列名列表（逗号分隔）
     * @param values    参数占位符列表（逗号分隔）
     * @return INSERT 语句
     */
    default String getInsertSql(String tableName, String columns, String values) {
        return "insert into " + quote(tableName) + " (" + columns + ") values (" + values + ")";
    }

    /**
     * 生成 UPDATE 语句。
     * <p>例如：{@code update `user` set name = ?, age = ? where id = ?}</p>
     *
     * @param tableName  表名
     * @param setClause  SET 子句
     * @param whereClause WHERE 子句（可为 null 或空）
     * @return UPDATE 语句
     */
    default String getUpdateSql(String tableName, String setClause, String whereClause) {
        StringBuilder sb = new StringBuilder("update ").append(quote(tableName)).append(" set ").append(setClause);
        if (whereClause != null && !whereClause.isEmpty()) {
            sb.append(" where ").append(whereClause);
        }
        return sb.toString();
    }

    /**
     * 生成 DELETE 语句。
     * <p>例如：{@code delete from `user` where id = ?}</p>
     *
     * @param tableName   表名
     * @param whereClause WHERE 子句（可为 null 或空）
     * @return DELETE 语句
     */
    default String getDeleteSql(String tableName, String whereClause) {
        StringBuilder sb = new StringBuilder("delete from ").append(quote(tableName));
        if (whereClause != null && !whereClause.isEmpty()) {
            sb.append(" where ").append(whereClause);
        }
        return sb.toString();
    }

    /**
     * 判断当前数据库是否支持 UPSERT（INSERT ... ON DUPLICATE KEY UPDATE / MERGE / ON CONFLICT）。
     *
     * @return true 支持 UPSERT
     */
    default boolean supportsUpsert() {
        return false;
    }

    /**
     * 生成 UPSERT 语句。
     * <p>MySQL 示例：
     * {@code insert into `user` (id, name) values (?, ?) on duplicate key update name = values(name)}</p>
     * <p>PostgreSQL 示例：
     * {@code insert into "user" (id, name) values (?, ?) on conflict (id) do update set name = excluded.name}</p>
     *
     * @param tableName  表名
     * @param columns    列名列表
     * @param values     值占位符列表
     * @param updateSet  冲突时更新的 SET 子句
     * @return UPSERT 语句
     * @throws UnsupportedOperationException 如果数据库不支持 UPSERT
     */
    default String getUpsertSql(String tableName, String columns, String values, String updateSet) {
        throw new UnsupportedOperationException("当前数据库不支持 UPSERT: " + protocol());
    }

    // ==================== 高级 DDL 片段 ====================

    /**
     * 获取 ALTER/MODIFY COLUMN 语句的关键词。
     * <p>不同数据库差异：MySQL/MariaDB/ClickHouse 使用 {@code MODIFY COLUMN}，
     * PostgreSQL/H2 使用 {@code ALTER COLUMN}，Oracle/达梦使用 {@code MODIFY}。</p>
     *
     * @return 默认 {@code modify column}
     */
    default String getAlterColumnString() {
        return "modify column";
    }

    /**
     * 从 JDBC URL 中提取数据库名称。
     * <p>默认实现解析 {@code jdbc:xxx://host:port/database?params} 格式。</p>
     *
     * @param url JDBC URL
     * @return 数据库名，无法解析返回 null
     */
    default String getDatabaseName(String url) {
        if (url == null || url.isEmpty()) { return null; }
        int protocolEnd = url.indexOf("://");
        if (protocolEnd < 0) { return null; }
        String rest = url.substring(protocolEnd + 3);
        int slashIndex = rest.indexOf('/');
        if (slashIndex < 0) { return null; }
        String db = rest.substring(slashIndex + 1);
        int paramIndex = db.indexOf('?');
        if (paramIndex >= 0) { db = db.substring(0, paramIndex); }
        return db.isEmpty() ? null : db;
    }

    /**
     * 获取写锁语句（SELECT FOR UPDATE 语法变体）。
     * <p>MySQL/PostgreSQL: {@code for update}；Oracle/达梦: 支持 {@code for update nowait}。</p>
     *
     * @param timeout 超时秒数（0 表示 NOWAIT）
     * @return 写锁语句后缀
     */
    default String getWriteLockString(int timeout) {
        return " for update";
    }

    /**
     * 带 Schema 的 RENAME TABLE 语句。
     *
     * @param schemaName  Schema 名
     * @param oldTableName 原表名
     * @param newTableName 新表名
     * @return 重命名语句
     */
    default String getRenameTableString(String schemaName, String oldTableName, String newTableName) {
        return "rename table " + quote(oldTableName) + " to " + quote(newTableName);
    }

    /**
     * 获取 CREATE TABLE 中表注释的 SQL 片段。
     * <p>MySQL 返回 {@code COMMENT 'comment'}，其他数据库可能返回空字符串（如使用独立的 COMMENT ON 语句）。</p>
     *
     * @param comment 注释内容
     * @return 表注释 SQL 片段
     */
    default String getTableComment(String comment) {
        return "";
    }

    /**
     * 获取 CREATE TABLE 中列注释的 SQL 片段。
     *
     * @param comment 注释内容
     * @return 列注释 SQL 片段
     */
    default String getColumnComment(String comment) {
        return "";
    }

    /**
     * 获取当前时间戳查询语句。
     * <p>MySQL: {@code SELECT NOW()}；PostgreSQL/Oscar: {@code SELECT CURRENT_TIMESTAMP}；
     * Oracle/达梦: {@code SELECT SYSDATE FROM DUAL}。</p>
     *
     * @return 当前时间戳查询 SQL
     */
    default String getCurrentTimestampSelectString() {
        return "SELECT CURRENT_TIMESTAMP";
    }

    /**
     * 生成自增触发器的触发器体。
     * <p>适用于 Oracle 等没有原生 AUTO_INCREMENT 的数据库。</p>
     *
     * @param triggerName  触发器名
     * @param tableName    表名
     * @param columnName   自增列名
     * @param sequenceName 序列名
     * @return 触发器体 SQL
     */
    default String getAutoIncrementTriggerBody(String triggerName, String tableName, String columnName, String sequenceName) {
        throw new UnsupportedOperationException("当前数据库不需要触发器实现自增: " + protocol());
    }

    // ==================== 分区支持 ====================

    /**
     * 判断当前数据库是否支持内联注释。
     * <p>不同数据库的注释语法差异较大：</p>
     * <ul>
     *   <li><b>MySQL / MariaDB / OceanBase(MySQL) / PolarDB(MySQL) / TDSQL(MySQL) / GBase</b> —
     *       支持内联 {@code COMMENT 'xxx'}，可在 CREATE TABLE 语句中直接追加到列定义或表定义末尾，
     *       由 {@link #getTableComment(String)} 和 {@link #getColumnComment(String)} 返回对应的 SQL 片段。</li>
     *   <li><b>PostgreSQL / GaussDB / Kingbase / Oscar</b> —
     *       不支持内联注释，需要使用独立的 {@code COMMENT ON TABLE/COLUMN ... IS 'xxx'} 语句。</li>
     *   <li><b>Oracle / 达梦</b> —
     *       不支持内联注释，需要使用独立的 {@code COMMENT ON TABLE/COLUMN ... IS 'xxx'} 语句。</li>
     *   <li><b>ClickHouse</b> —
     *       不支持内联注释（建表时不支持 COMMENT 语法）。</li>
     *   <li><b>H2</b> —
     *       不支持内联注释。</li>
     * </ul>
     * <p>此方法主要被 {@code HibernateDdlManager} 等 DDL 生成器用于判断注释的生成策略：</p>
     * <ul>
     *   <li>返回 {@code true} 时，DDL 生成器将 {@link #getColumnComment(String)} 和 {@link #getTableComment(String)}
     *       的返回值追加到 CREATE TABLE 语句末尾作为内联注释。</li>
     *   <li>返回 {@code false} 时，DDL 生成器将生成独立的 {@code COMMENT ON} 语句。</li>
     * </ul>
     *
     * @return true 支持内联注释（如 MySQL 兼容数据库），false 需要独立的 COMMENT ON 语句
     * @see #getTableComment(String)
     * @see #getColumnComment(String)
     */
    default boolean supportsInlineComment() {
        return false;
    }

    /**
     * 判断当前数据库是否支持表分区。
     *
     * @return true 支持分区
     */
    default boolean supportsPartition() {
        return false;
    }

    /**
     * 格式化分区 SQL。默认实现检查支持状态后委托给 {@link #doFormatPartitionSql}。
     *
     * @param tableMetadata 表元数据
     * @return 分区 SQL 片段
     */
    default String formatPartitionSql(TableMetadata tableMetadata) {
        if (!supportsPartition() || tableMetadata == null || !tableMetadata.hasPartitions()) {
            return "";
        }
        String partitionType = tableMetadata.getPartitionType();
        ColumnMetadata partitionCol = tableMetadata.getPartitionColumn();
        if (partitionType == null || partitionType.isEmpty() || partitionCol == null) {
            return "";
        }
        return doFormatPartitionSql(tableMetadata, partitionType.toUpperCase(), partitionCol);
    }

    /**
     * 特定数据库的分区 SQL 生成钩子，由 {@link #formatPartitionSql} 调用。
     *
     * @param tableMetadata 表元数据
     * @param partitionType 大写分区类型（RANGE / LIST / HASH / KEY 等）
     * @param partitionCol  分区列元数据
     * @return 分区 SQL 片段
     */
    default String doFormatPartitionSql(TableMetadata tableMetadata, String partitionType, ColumnMetadata partitionCol) {
        return "";
    }

    /**
     * 获取分区解析器（用于分区管理操作）。
     *
     * @return 分区解析器实例，不支持返回 null
     */
    default PartitionResolver partition() {
        return null;
    }

    // ==================== 索引支持 ====================

    /**
     * 生成 CREATE INDEX 语句。
     *
     * @param indexMetadata 索引元数据
     * @return CREATE INDEX 语句
     */
    default String getCreateIndexString(IndexMetadata indexMetadata) {
        StringBuilder sql = new StringBuilder();
        if (indexMetadata.isUnique()) {
            sql.append("CREATE UNIQUE INDEX ");
        } else {
            sql.append("CREATE INDEX ");
        }
        sql.append(quote(indexMetadata.getName()))
                .append(" ON ")
                .append(quote(indexMetadata.getTableName()))
                .append(" (");
        if (indexMetadata.getColumns() != null && !indexMetadata.getColumns().isEmpty()) {
            sql.append(indexMetadata.getColumns().stream()
                    .map(this::quote)
                    .collect(Collectors.joining(", ")));
        } else if (indexMetadata.getColumnName() != null && !indexMetadata.getColumnName().isEmpty()) {
            sql.append(quote(indexMetadata.getColumnName()));
        }
        sql.append(")");
        return sql.toString();
    }

    /**
     * 生成 DROP INDEX 语句。
     *
     * @param indexName 索引名
     * @param tableName 表名
     * @return DROP INDEX 语句
     */
    default String getDropIndexString(String indexName, String tableName) {
        return "DROP INDEX " + indexName;
    }

    /**
     * 生成 RENAME INDEX 语句。
     *
     * @param oldIndexName 原索引名
     * @param newIndexName 新索引名
     * @param tableName    表名
     * @return RENAME INDEX 语句
     */
    default String getRenameIndexString(String oldIndexName, String newIndexName, String tableName) {
        return "ALTER INDEX " + oldIndexName + " RENAME TO " + newIndexName;
    }

    // ==================== 存储引擎 ====================

    /**
     * 获取存储引擎关键字。
     * <p>MySQL 8.0+ 使用 {@code ENGINE}，旧版本使用 {@code TYPE}。</p>
     *
     * @return 默认 {@code engine}
     */
    default String getEngineKeyword() {
        return "engine";
    }

    /**
     * 获取默认存储引擎。
     *
     * @return 存储引擎枚举
     */
    default StorageEngine getStorageEngine() {
        return StorageEngine.DEFAULT;
    }

    /**
     * 获取表的类型字符串（引擎信息等），追加在 CREATE TABLE 末尾。
     *
     * @return 表类型字符串
     */
    default String getTableTypeString() {
        return "";
    }

    // ==================== 触发器 / 存储过程 SQL ====================

    /**
     * 生成查询触发器列表的 SQL。
     * <p>
     * 返回的查询结果需包含统一的列别名（大写），供 {@code JdbcEngine} 解析为 {@link TriggerDefinition}：
     * <ul>
     *   <li>{@code TRIGGER_NAME} — 触发器名</li>
     *   <li>{@code TRIGGER_SCHEMA}（或 {@code TRIGGER_SCHEM}）— schema</li>
     *   <li>{@code TABLE_NAME}（或 {@code EVENT_OBJECT_TABLE}）— 关联表</li>
     *   <li>{@code ACTION_TIMING} — 触发时机</li>
     *   <li>{@code EVENT_MANIPULATION} — 触发事件</li>
     *   <li>{@code ACTION_STATEMENT} — 触发器体内容</li>
     *   <li>{@code STATUS} — 触发器状态（可选）</li>
     * </ul>
     * </p>
     * <p>默认返回 {@code null} 表示该数据库不支持获取触发器。</p>
     *
     * @param schema schema 名称，null 表示不限定
     * @return 查询 SQL，不支持时返回 null
     */
    default String getTriggerListSql(String schema) {
        return null;
    }

    /**
     * 生成查询单个触发器定义的 SQL。
     *
     * @param triggerName 触发器名
     * @param schema      schema 名称，null 表示不限定
     * @return 查询 SQL，不支持时返回 null
     * @see #getTriggerListSql(String)
     */
    default String getTriggerSql(String triggerName, String schema) {
        return null;
    }

    /**
     * 生成查询存储过程列表的 SQL。
     * <p>
     * 返回的查询结果需包含统一的列别名（大写），供 {@code JdbcEngine} 解析为 {@link ProcedureDefinition}：
     * <ul>
     *   <li>{@code ROUTINE_NAME}（或 {@code PROCEDURE_NAME}）— 存储过程名</li>
     *   <li>{@code ROUTINE_SCHEMA}（或 {@code PROCEDURE_SCHEM}）— schema</li>
     *   <li>{@code DATA_TYPE}（或 {@code RETURN_TYPE}）— 返回值类型</li>
     *   <li>{@code ROUTINE_DEFINITION}（或 {@code BODY}）— 过程体内容</li>
     *   <li>{@code ROUTINE_COMMENT}（或 {@code REMARKS}）— 注释</li>
     *   <li>{@code SECURITY_TYPE} — 安全类型</li>
     *   <li>{@code ROUTINE_BODY}（或 {@code LANGUAGE}）— 语言（可选）</li>
     *   <li>{@code STATUS} — 状态（可选）</li>
     * </ul>
     * </p>
     * <p>默认返回 {@code null} 表示该数据库不支持获取存储过程。</p>
     *
     * @param schema schema 名称，null 表示不限定
     * @return 查询 SQL，不支持时返回 null
     */
    default String getProcedureListSql(String schema) {
        return null;
    }

    /**
     * 生成查询单个存储过程定义的 SQL。
     *
     * @param procedureName 存储过程名
     * @param schema        schema 名称，null 表示不限定
     * @return 查询 SQL，不支持时返回 null
     * @see #getProcedureListSql(String)
     */
    default String getProcedureSql(String procedureName, String schema) {
        return null;
    }

    // ==================== 向量 / JSON 支持检测 ====================

    /**
     * 判断当前数据库是否原生支持向量类型及相似度计算。
     * <p>返回 {@code true} 时，{@code JdbcEngine} 可尝试创建 MySQL/PostgreSQL 原生向量存储；
     * 返回 {@code false} 时，向量操作将自动降级到内存/jvector 存储。</p>
     * <ul>
     *   <li>MySQL 8.0.31+（含 VECTOR 类型）：默认 true</li>
     *   <li>PostgreSQL + pgvector 扩展：默认 true</li>
     *   <li>其他数据库：默认 false</li>
     * </ul>
     *
     * @return true 支持原生向量操作
     */
    default boolean supportsVector() {
        return false;
    }

    /**
     * 判断当前数据库是否支持 JSON 类型。
     * <p>MySQL 5.7+、PostgreSQL 9.4+、MariaDB 10.2+ 默认支持。</p>
     *
     * @return true 支持 JSON 类型
     */
    default boolean supportsJson() {
        return false;
    }

    // ==================== SPI 工厂 ====================

    /**
     * 通过 SPI 获取指定协议的方言实现。
     *
     * @param protocol 数据库协议名（如 {@code mysql}、{@code postgresql}）
     * @return 方言实例，未找到返回 null
     */
    static Dialect getExtension(String protocol) {
        return ServiceProvider.of(Dialect.class).getExtension(protocol);
    }

    /**
     * 获取所有已注册的方言实现。
     *
     * @return SPI 键 → 方言实例的映射
     */
    static Map<String, Dialect> listAll() {
        return ServiceProvider.of(Dialect.class).list();
    }
}