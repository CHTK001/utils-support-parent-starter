package com.chua.datasync.agent.support.source;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.Directional;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.sql.*;
import java.util.Map;

/**
 * JDBC 数据同步 源，从关系型数据库读取数据。
 * <p>
 * 基于 hikaricp 连接池，支持增量读取：
 * 当 参数["偏移量"] 有值时，追加 WHERE 标识 > 偏移量 条件过滤已读行。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class JdbcDataSyncAgentSource implements DataSyncAgentSource, Directional {

    /**
     * 数据源标识
    */
    private final String sourceId;
    /**
     * 输入标识
    */
    private final String inputId;
    /**
     * JDBC 连接地址
    */
    private final String jdbcUrl;
    /**
     * 用户名
    */
    private final String username;
    /**
     * 密码
    */
    private final String password;
    /**
     * SQL 语句
    */
    private final String sql;
    /**
     * 列名数组
    */
    private final String[] columnNames;
    /**
     * 数据源
    */
    private volatile HikariDataSource dataSource;
    /**
     * 增量列（按顺序构成复合水位）
     *
     * <p>为空表示全量读取。多列时按字典序比较，用于同一时间戳精度下
     * 仍能区分先后（单列水位会漏掉同精度时间戳的行）。</p>
     */
    private final java.util.List<String> incColumns;
    /**
     * 水位存储（每映射独立水位）
     */
    private final com.chua.datasync.agent.support.offset.SyncDataOffsetStorage offsetStorage;

    /**
     * 创建 jdbc数据同步Agent源 实例
     * @param sourceId 源标识
     * @param sourceId 字符串
     * @param sourceId 字符串
     * @param sourceId 字符串
     * @param sourceId 字符串
     * @param sourceId 字符串
     * @param sourceId 字符串
     * @param columnNames column名称
     * @param inputId 输入标识
     * @param jdbcUrl jdbcurl
     * @param username 用户名
     * @param password 密码
     * @param sql SQL
     */
    public JdbcDataSyncAgentSource(String sourceId, String inputId, String jdbcUrl, String username, String password, String sql, String... columnNames) {
        this(sourceId, inputId, jdbcUrl, username, password, sql, java.util.List.of(), null, columnNames);
    }

    /**
     * 创建支持增量的 jdbc数据同步Agent源 实例
     *
     * @param sourceId     源标识
     * @param inputId      输入标识
     * @param jdbcUrl      jdbcurl
     * @param username     用户名
     * @param password     密码
     * @param sql          SQL
     * @param incColumns   增量列（按顺序；空表示全量）
     * @param offsetStorage 水位存储（null 用默认文件存储）
     * @param columnNames  column名称
     */
    public JdbcDataSyncAgentSource(String sourceId, String inputId, String jdbcUrl, String username, String password,
                                   String sql, java.util.List<String> incColumns,
                                   com.chua.datasync.agent.support.offset.SyncDataOffsetStorage offsetStorage,
                                   String... columnNames) {
        this.sourceId = sourceId;
        this.inputId = inputId;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.sql = sql;
        this.columnNames = columnNames;
        this.incColumns = incColumns == null ? java.util.List.of() : java.util.List.copyOf(incColumns);
        this.offsetStorage = offsetStorage != null
                ? offsetStorage
                : new com.chua.datasync.agent.support.offset.FileSyncDataOffsetStorage();
    }

    /**
     * 创建数据源
     *
     * @param url url
     * @param user 用户
     * @param pass 通过
     * @return 创建数据源的结果
     */
    private HikariDataSource createDataSource(String url, String user, String pass) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(pass);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        return new HikariDataSource(config);
    }

    /**
     * 惰性获取连接池。
     *
     * <p><b>为什么不在构造里建池</b>：HikariCP 构造时就会解析驱动并建立连接，
     * 一旦驱动缺失或库暂时不可达，连「只想读 SQL 文本」的场景也会炸。
     * 改为首次读取时再建池，配置阶段（校验、预览、生成 SQL）完全不碰连接。</p>
     *
     * @return 连接池
     */
    private synchronized HikariDataSource dataSource() {
        if (dataSource == null || dataSource.isClosed()) {
            dataSource = createDataSource(jdbcUrl, username, password);
        }
        return dataSource;
    }

    /**
     * 源id
    */
    @Override
    public String sourceId() {
        return sourceId;
    }

    /**
     * 输入id
    */
    @Override
    public String inputId() {
        return inputId;
    }

    /**
     * 读取
    */
    @Override
    public Flux<Map<String, Object>> read(Map<String, Object> params) {
        Object offsetObj = null;
        if (params != null) {
            offsetObj = params.get("offset");
        }
        String sqlWithOffset = buildSqlWithOffset(offsetObj);

        log.info("[JdbcDataSyncAgentSource] 开始读取数据, sourceId={}, sql={}", sourceId, sqlWithOffset);

 // Connection/prepared对账单 需在订阅时创建，避免 Flux 惰性执行时语句已被关闭
        return Flux.create(sink -> {
            try (Connection conn = dataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sqlWithOffset,
                         ResultSet.TYPE_FORWARD_ONLY,
                         ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(500);
                ps.setFetchDirection(ResultSet.FETCH_FORWARD);
                try (ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    int colCount = columnNames.length;
                    while (rs.next()) {
                        Map<String, Object> row = Wraps.map();
                        for (int i = 0; i < colCount; i++) {
                            row.put(columnNames[i], rs.getObject(columnNames[i]));
                        }
                        sink.next(row);
                        count++;
                    }
                    sink.complete();
                    log.info("[JdbcDataSyncAgentSource] 读取完成, sourceId={}, count={}", sourceId, count);
                } catch (Exception e) {
                    sink.error(e);
                    log.error("[JdbcDataSyncAgentSource] 读取异常, sourceId={}", sourceId, e);
                }
            } catch (SQLException e) {
                sink.error(e);
                log.error("[JdbcDataSyncAgentSource] 连接数据库失败, sourceId={}", sourceId, e);
            }
        });
    }

    /**
     * 关闭
    */
    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[JdbcDataSyncAgentSource] 连接池已关闭, sourceId={}", sourceId);
        }
    }

    /**
     * Direction
    */
    @Override
    public Direction direction() {
        return Direction.INPUT;
    }

    /**
     * 构建sqlwith偏移量
     *
     * @param offsetValue 偏移量值
     * @return 构建sqlwith偏移量的结果
     */
    private String buildSqlWithOffset(Object offsetValue) {
        if (offsetValue == null || incColumns.isEmpty()) {
            return sql;
        }
        String inner = sql.trim();
        // 只包装 SELECT：非查询语句加 WHERE/ORDER BY 会直接语法错误
        if (!inner.toUpperCase(java.util.Locale.ROOT).startsWith("SELECT")) {
            return sql;
        }
        if (inner.endsWith(";")) {
            inner = inner.substring(0, inner.length() - 1).trim();
        }
        // 水位值是「按增量列顺序的 JSON 数组」，或单列时的裸值
        java.util.List<String> values = parseSeqValues(offsetValue, incColumns.size());
        StringBuilder wrapped = new StringBuilder("SELECT * FROM (").append(inner).append(") __sync_inc");
        if (!values.isEmpty()) {
            wrapped.append(" WHERE ").append(compositeWhere(values));
        }
        wrapped.append(" ORDER BY ")
                .append(incColumns.stream().map(c -> c + " ASC").collect(java.util.stream.Collectors.joining(", ")));
        return wrapped.toString();
    }

    /**
     * 复合水位的 WHERE 条件。
     *
     * <p>展开成「前 n 个字段都相等且第 n+1 个更大」的若干分支，避免依赖
     * {@code (a,b) > (?,?)} 行值比较——Oracle 不支持这种写法。</p>
     *
     * @param values 与增量列一一对应的水位值
     * @return WHERE 条件（不含 WHERE 关键字）
     */
    private String compositeWhere(java.util.List<String> values) {
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < incColumns.size(); i++) {
            if (i > 0) {
                where.append(" OR ");
            }
            where.append("(");
            for (int j = 0; j < i; j++) {
                where.append(incColumns.get(j)).append(" = ").append(sqlLiteral(values.get(j))).append(" AND ");
            }
            where.append(incColumns.get(i)).append(" > ").append(sqlLiteral(values.get(i))).append(")");
        }
        return where.toString();
    }

    /**
     * 水位值转 SQL 字面量。
     *
     * @param value 水位值
     * @return SQL 字面量
     */
    private static String sqlLiteral(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.matches("-?\\d+(\\.\\d+)?")) {
            return trimmed;
        }
        return "'" + trimmed.replace("'", "''") + "'";
    }

    /**
     * 把水位值解析成与增量列一一对应的字符串列表。
     *
     * @param offsetValue 水位值（JSON 数组串或裸值）
     * @param count       增量列数量
     * @return 值列表；无法对应时返回空列表（按首跑处理）
     */
    private static java.util.List<String> parseSeqValues(Object offsetValue, int count) {
        if (offsetValue == null || count <= 0) {
            return java.util.List.of();
        }
        String text = String.valueOf(offsetValue).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            java.util.List<String> parsed = new java.util.ArrayList<>();
            for (String item : text.substring(1, text.length() - 1).split(",")) {
                String value = item.trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1).replace("\\\"", "\"");
                }
                parsed.add(value);
            }
            return parsed.size() == count ? parsed : java.util.List.of();
        }
        return count == 1 ? java.util.List.of(text) : java.util.List.of();
    }

    /**
     * 按映射读取偏移量（每目标独立水位）
     *
     * @param params 读取参数
     * @param mappingId 映射标识
     * @return 偏移量
     */
    @Override
    public com.chua.datasync.agent.support.model.SyncDataOffset readOffset(Map<String, Object> params, String mappingId) {
        return offsetStorage.read(sourceId, mappingId, null);
    }

    /**
     * 读取偏移量
     *
     * @param params 读取参数
     * @return 偏移量
     */
    @Override
    public com.chua.datasync.agent.support.model.SyncDataOffset readOffset(Map<String, Object> params) {
        return offsetStorage.read(sourceId, null);
    }

    /**
     * 写入偏移量
     *
     * @param offset 偏移量
     */
    @Override
    public void writeOffset(com.chua.datasync.agent.support.model.SyncDataOffset offset) {
        if (offset == null) {
            return;
        }
        offsetStorage.write(offset);
    }
}
