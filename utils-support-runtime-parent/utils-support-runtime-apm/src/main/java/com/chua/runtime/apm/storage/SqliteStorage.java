package com.chua.runtime.apm.storage;

import com.chua.common.support.lang.json.Json;
import com.chua.runtime.protocol.DependencyEdge;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import lombok.extern.java.Log;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite 存储 — 嵌入式本地落盘实现。
 *
 * <p>通过 {@code apm.storage.type=sqlite} 启用，数据持久化到本地 SQLite 文件，
 * 重启后数据不丢失。默认路径为 {@code ./apm.db}，可通过
 * {@code apm.storage.path} 指定。</p>
 *
 * <p>表结构：</p>
 * <ul>
 *   <li>{@code transmissions} — 传输事件</li>
 *   <li>{@code dependencies} — 依赖图边（按 source→target 聚合）</li>
 *   <li>{@code leaks} — 句柄泄漏记录</li>
 *   <li>{@code logs} — 日志记录</li>
 * </ul>
 *
 * <p>线程安全：所有写操作使用 {@code synchronized} 串行化，避免 SQLite 单写者限制；
 * 读操作使用独立连接，互不阻塞。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Log
public class SqliteStorage implements ApmStorage {

    /**
     * 默认数据库文件路径
     */
    private static final String DEFAULT_DB_PATH = "./apm.db";

    /**
     * 配置键：数据库文件路径
     */
    private static final String KEY_PATH = "apm.storage.path";

    /**
     * 配置键：保留时长（毫秒）
     */
    private static final String KEY_RETENTION_MS = "apm.storage.retention.ms";

    /**
     * 配置键：单表最大行数
     */
    private static final String KEY_CAPACITY = "apm.storage.capacity";

    /**
     * 默认保留时长（7 天）
     */
    private static final long DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * 默认单表最大行数
     */
    private static final int DEFAULT_CAPACITY = 100_000;

    /**
     * 数据库连接 URL 前缀
     */
    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    /**
     * 数据库文件路径
     */
    private String dbPath;

    /**
     * 保留时长（毫秒）
     */
    private long retentionMillis;

    /**
     * 单表最大行数
     */
    private int capacity;

    /**
     * 写锁 — SQLite 单写者，串行化所有写操作
     */
    private final Object writeLock = new Object();

    @Override
    public void start(StorageConfig config) {
        this.dbPath = config.get(KEY_PATH, DEFAULT_DB_PATH);
        this.retentionMillis = config.getLong(KEY_RETENTION_MS, DEFAULT_RETENTION_MS);
        this.capacity = config.getInt(KEY_CAPACITY, DEFAULT_CAPACITY);
        try {
            ensureParentDir(dbPath);
            try (Connection conn = open()) {
                createTables(conn);
            }
            log.info(String.format("SqliteStorage 启动: path=%s, retentionMs=%d, capacity=%d", dbPath, retentionMillis, capacity));
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 存储启动失败: " + dbPath, e);
        }
    }

    @Override
    public void stop() {
        log.info(String.format("SqliteStorage 停止: %s", dbPath));
    }

    @Override
    public void appendTransmission(TransmissionEvent event) {
        if (event == null) {
            return;
        }
        synchronized (writeLock) {
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO transmissions (trace_id, span_id, parent_span_id, "
                                 + "source_protocol, source_software, source_host, source_port, source_path, "
                                 + "target_protocol, target_software, target_host, target_port, target_path, "
                                 + "protocol, software, operation, status, status_code, "
                                 + "start_time, end_time, duration, bytes_out, bytes_in, "
                                 + "error_type, error_message, attributes) "
                                 + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, event.getTraceId());
                ps.setString(2, event.getSpanId());
                ps.setString(3, event.getParentSpanId());
                ps.setString(4, event.getSourceProtocol());
                ps.setString(5, event.getSourceSoftware());
                ps.setString(6, event.getSourceHost());
                ps.setInt(7, event.getSourcePort());
                ps.setString(8, event.getSourcePath());
                ps.setString(9, event.getTargetProtocol());
                ps.setString(10, event.getTargetSoftware());
                ps.setString(11, event.getTargetHost());
                ps.setInt(12, event.getTargetPort());
                ps.setString(13, event.getTargetPath());
                ps.setString(14, event.getProtocol());
                ps.setString(15, event.getSoftware());
                ps.setString(16, event.getOperation());
                ps.setString(17, event.getStatus() == null ? null : event.getStatus().name());
                ps.setInt(18, event.getStatusCode());
                ps.setLong(19, event.getStartTime());
                ps.setLong(20, event.getEndTime());
                ps.setLong(21, event.getDuration());
                ps.setLong(22, event.getBytesOut());
                ps.setLong(23, event.getBytesIn());
                ps.setString(24, event.getErrorType());
                ps.setString(25, event.getErrorMessage());
                ps.setString(26, toJson(event.getAttributes()));
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warning(String.format("写入传输记录失败: %s", e.getMessage()));
            }
        }
    }

    @Override
    public void appendDependency(DependencyEdge edge) {
        if (edge == null || edge.getSource() == null || edge.getTarget() == null) {
            return;
        }
        synchronized (writeLock) {
            try (Connection conn = open()) {
                String edgeId = edge.edgeId();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT call_count, total_duration, error_count FROM dependencies WHERE edge_id = ?")) {
                    ps.setString(1, edgeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long callCount = rs.getLong(1) + edge.getCallCount();
                            long totalDuration = rs.getLong(2) + edge.getTotalDuration();
                            long errorCount = rs.getLong(3) + edge.getErrorCount();
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE dependencies SET call_count=?, total_duration=?, error_count=?, "
                                            + "last_error=?, last_call_time=? WHERE edge_id=?")) {
                                up.setLong(1, callCount);
                                up.setLong(2, totalDuration);
                                up.setLong(3, errorCount);
                                up.setString(4, edge.getLastError());
                                up.setLong(5, System.currentTimeMillis());
                                up.setString(6, edgeId);
                                up.executeUpdate();
                            }
                        } else {
                            try (PreparedStatement ins = conn.prepareStatement(
                                    "INSERT INTO dependencies (edge_id, source, target, protocol, software, "
                                            + "call_count, total_duration, error_count, last_error, last_call_time) "
                                            + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                                ins.setString(1, edgeId);
                                ins.setString(2, toJson(edge.getSource()));
                                ins.setString(3, toJson(edge.getTarget()));
                                ins.setString(4, edge.getProtocol() == null ? null : edge.getProtocol().name());
                                ins.setString(5, edge.getSoftware() == null ? null : edge.getSoftware().name());
                                ins.setLong(6, edge.getCallCount());
                                ins.setLong(7, edge.getTotalDuration());
                                ins.setLong(8, edge.getErrorCount());
                                ins.setString(9, edge.getLastError());
                                ins.setLong(10, edge.getLastCallTime());
                                ins.executeUpdate();
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                log.warning(String.format("写入依赖边失败: %s", e.getMessage()));
            }
        }
    }

    @Override
    public void appendLeak(LeakRecord record) {
        if (record == null) {
            return;
        }
        synchronized (writeLock) {
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT OR REPLACE INTO leaks (handle_id, kind, name, thread, created_at, closed_at, stack_trace) "
                                 + "VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, record.getHandleId());
                ps.setString(2, record.getKind());
                ps.setString(3, record.getName());
                ps.setString(4, record.getThread());
                ps.setLong(5, record.getCreatedAt());
                ps.setLong(6, record.getClosedAt());
                ps.setString(7, record.getStackTrace());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warning(String.format("写入泄漏记录失败: %s", e.getMessage()));
            }
        }
    }

    @Override
    public void appendLog(LogRecord record) {
        if (record == null) {
            return;
        }
        synchronized (writeLock) {
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO logs (timestamp, level, logger, class_name, method_name, message, trace_id) "
                                 + "VALUES (?,?,?,?,?,?,?)")) {
                ps.setLong(1, record.getTimestamp());
                ps.setString(2, record.getLevel());
                ps.setString(3, record.getLogger());
                ps.setString(4, record.getClassName());
                ps.setString(5, record.getMethodName());
                ps.setString(6, record.getMessage());
                ps.setString(7, record.getTraceId());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warning(String.format("写入日志记录失败: %s", e.getMessage()));
            }
        }
    }

    @Override
    public List<TransmissionEvent> queryTransmissions(Query query) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, trace_id, span_id, parent_span_id, "
                        + "source_protocol, source_software, source_host, source_port, source_path, "
                        + "target_protocol, target_software, target_host, target_port, target_path, "
                        + "protocol, software, operation, status, status_code, "
                        + "start_time, end_time, duration, bytes_out, bytes_in, "
                        + "error_type, error_message, attributes FROM transmissions WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendTimeFilter(sql, params, "start_time", query);
        appendStringFilter(sql, params, "trace_id", query.getTraceId());
        appendStringFilter(sql, params, "source_host", query.getSourceHost());
        appendStringFilter(sql, params, "target_host", query.getTargetHost());
        appendStringFilter(sql, params, "protocol", query.getProtocol());
        appendStringFilter(sql, params, "software", query.getSoftware());
        appendStringFilter(sql, params, "status", query.getStatus());
        if (query.isErrorOnly()) {
            sql.append(" AND status = 'ERROR'");
        }
        sql.append(" ORDER BY start_time DESC LIMIT ? OFFSET ?");
        params.add(query.getLimit());
        params.add(query.getOffset());

        List<TransmissionEvent> result = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapTransmission(rs));
                }
            }
        } catch (SQLException e) {
            log.warning(String.format("查询传输记录失败: %s", e.getMessage()));
        }
        return result;
    }

    @Override
    public List<DependencyEdge> queryDependencies(Query query) {
        List<DependencyEdge> result = new ArrayList<>();
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT source, target, protocol, software, call_count, total_duration, "
                             + "error_count, last_error, last_call_time FROM dependencies")) {
            while (rs.next()) {
                result.add(mapDependency(rs));
            }
        } catch (SQLException e) {
            log.warning(String.format("查询依赖边失败: %s", e.getMessage()));
        }
        return result;
    }

    @Override
    public List<LeakRecord> queryLeaks(Query query) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, handle_id, kind, name, thread, created_at, closed_at, stack_trace FROM leaks WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendTimeFilter(sql, params, "created_at", query);
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(query.getLimit());
        params.add(query.getOffset());

        List<LeakRecord> result = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LeakRecord r = new LeakRecord();
                    r.setId(rs.getLong("id"));
                    r.setHandleId(rs.getString("handle_id"));
                    r.setKind(rs.getString("kind"));
                    r.setName(rs.getString("name"));
                    r.setThread(rs.getString("thread"));
                    r.setCreatedAt(rs.getLong("created_at"));
                    r.setClosedAt(rs.getLong("closed_at"));
                    r.setStackTrace(rs.getString("stack_trace"));
                    result.add(r);
                }
            }
        } catch (SQLException e) {
            log.warning(String.format("查询泄漏记录失败: %s", e.getMessage()));
        }
        return result;
    }

    @Override
    public List<LogRecord> queryLogs(Query query) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, timestamp, level, logger, class_name, method_name, message, trace_id FROM logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendTimeFilter(sql, params, "timestamp", query);
        appendStringFilter(sql, params, "level", query.getStatus());
        sql.append(" ORDER BY timestamp DESC LIMIT ? OFFSET ?");
        params.add(query.getLimit());
        params.add(query.getOffset());

        List<LogRecord> result = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LogRecord r = new LogRecord();
                    r.setId(rs.getLong("id"));
                    r.setTimestamp(rs.getLong("timestamp"));
                    r.setLevel(rs.getString("level"));
                    r.setLogger(rs.getString("logger"));
                    r.setClassName(rs.getString("class_name"));
                    r.setMethodName(rs.getString("method_name"));
                    r.setMessage(rs.getString("message"));
                    r.setTraceId(rs.getString("trace_id"));
                    result.add(r);
                }
            }
        } catch (SQLException e) {
            log.warning(String.format("查询日志记录失败: %s", e.getMessage()));
        }
        return result;
    }

    @Override
    public Map<String, Long> stats() {
        Map<String, Long> result = new HashMap<>();
        try (Connection conn = open()) {
            result.put("transmissions", count(conn, "transmissions"));
            result.put("dependencies", count(conn, "dependencies"));
            result.put("leaks", count(conn, "leaks"));
            result.put("logs", count(conn, "logs"));
        } catch (SQLException e) {
            log.warning(String.format("统计失败: %s", e.getMessage()));
        }
        return result;
    }

    @Override
    public long cleanup(long retentionMillis) {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        long removed = 0;
        synchronized (writeLock) {
            try (Connection conn = open()) {
                removed += deleteOlder(conn, "transmissions", "start_time", cutoff);
                removed += deleteOlder(conn, "logs", "timestamp", cutoff);
                removed += deleteClosedLeaks(conn, cutoff);
            } catch (SQLException e) {
                log.warning(String.format("清理过期数据失败: %s", e.getMessage()));
            }
        }
        return removed;
    }

    @Override
    public String name() {
        return "sqlite";
    }

    /**
     * 打开数据库连接。
     *
     * @return 数据库连接
     * @throws SQLException 连接失败
     */
    private Connection open() throws SQLException {
        return DriverManager.getConnection(JDBC_PREFIX + dbPath);
    }

    /**
     * 确保数据库文件父目录存在。
     *
     * @param path 数据库文件路径
     */
    private void ensureParentDir(String path) {
        try {
            Path parent = Paths.get(path).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            log.warning(String.format("创建数据库目录失败: %s", e.getMessage()));
        }
    }

    /**
     * 建表。
     *
     * @param conn 数据库连接
     * @throws SQLException 建表失败
     */
    private void createTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS transmissions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "trace_id TEXT, span_id TEXT, parent_span_id TEXT, "
                    + "source_protocol TEXT, source_software TEXT, source_host TEXT, source_port INTEGER, source_path TEXT, "
                    + "target_protocol TEXT, target_software TEXT, target_host TEXT, target_port INTEGER, target_path TEXT, "
                    + "protocol TEXT, software TEXT, operation TEXT, status TEXT, status_code INTEGER, "
                    + "start_time INTEGER, end_time INTEGER, duration INTEGER, bytes_out INTEGER, bytes_in INTEGER, "
                    + "error_type TEXT, error_message TEXT, attributes TEXT)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trans_start ON transmissions(start_time)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trans_trace ON transmissions(trace_id)");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS dependencies ("
                    + "edge_id TEXT PRIMARY KEY, "
                    + "source TEXT, target TEXT, protocol TEXT, software TEXT, "
                    + "call_count INTEGER, total_duration INTEGER, error_count INTEGER, "
                    + "last_error TEXT, last_call_time INTEGER)");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS leaks ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "handle_id TEXT UNIQUE, kind TEXT, name TEXT, thread TEXT, "
                    + "created_at INTEGER, closed_at INTEGER, stack_trace TEXT)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_leak_created ON leaks(created_at)");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS logs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "timestamp INTEGER, level TEXT, logger TEXT, class_name TEXT, method_name TEXT, "
                    + "message TEXT, trace_id TEXT)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_log_ts ON logs(timestamp)");
        }
    }

    /**
     * 统计表行数。
     *
     * @param conn 数据库连接
     * @param table 表名
     * @return 行数
     * @throws SQLException 查询失败
     */
    private long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /**
     * 删除指定时间列早于 cutoff 的行。
     *
     * @param conn 数据库连接
     * @param table 表名
     * @param timeColumn 时间列名
     * @param cutoff 截止时间
     * @return 删除行数
     * @throws SQLException 删除失败
     */
    private long deleteOlder(Connection conn, String table, String timeColumn, long cutoff) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + table + " WHERE " + timeColumn + " < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }

    /**
     * 删除已关闭且过期的泄漏记录（活跃泄漏永远保留）。
     *
     * @param conn 数据库连接
     * @param cutoff 截止时间
     * @return 删除行数
     * @throws SQLException 删除失败
     */
    private long deleteClosedLeaks(Connection conn, long cutoff) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM leaks WHERE closed_at > 0 AND closed_at < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }

    /**
     * 追加时间范围过滤条件。
     *
     * @param sql SQL 构建器
     * @param params 参数列表
     * @param column 时间列名
     * @param query 查询条件
     */
    private void appendTimeFilter(StringBuilder sql, List<Object> params, String column, Query query) {
        if (query.getStartTime() != null) {
            sql.append(" AND ").append(column).append(" >= ?");
            params.add(query.getStartTime());
        }
        if (query.getEndTime() != null) {
            sql.append(" AND ").append(column).append(" <= ?");
            params.add(query.getEndTime());
        }
    }

    /**
     * 追加字符串模糊过滤条件。
     *
     * @param sql SQL 构建器
     * @param params 参数列表
     * @param column 列名
     * @param value 过滤值
     */
    private void appendStringFilter(StringBuilder sql, List<Object> params, String column, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" LIKE ?");
        params.add("%" + value + "%");
    }

    /**
     * 绑定参数。
     *
     * @param ps 预编译语句
     * @param params 参数列表
     * @throws SQLException 绑定失败
     */
    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Long) {
                ps.setLong(i + 1, (Long) p);
            } else if (p instanceof Integer) {
                ps.setInt(i + 1, (Integer) p);
            } else {
                ps.setString(i + 1, String.valueOf(p));
            }
        }
    }

    /**
     * 将传输记录行映射为事件对象。
     *
     * @param rs 结果集
     * @return 传输事件
     * @throws SQLException 读取失败
     */
    private TransmissionEvent mapTransmission(ResultSet rs) throws SQLException {
        TransmissionEvent e = new TransmissionEvent();
        e.setId(rs.getLong("id"));
        e.setTraceId(rs.getString("trace_id"));
        e.setSpanId(rs.getString("span_id"));
        e.setParentSpanId(rs.getString("parent_span_id"));
        e.setSourceProtocol(rs.getString("source_protocol"));
        e.setSourceSoftware(rs.getString("source_software"));
        e.setSourceHost(rs.getString("source_host"));
        e.setSourcePort(rs.getInt("source_port"));
        e.setSourcePath(rs.getString("source_path"));
        e.setTargetProtocol(rs.getString("target_protocol"));
        e.setTargetSoftware(rs.getString("target_software"));
        e.setTargetHost(rs.getString("target_host"));
        e.setTargetPort(rs.getInt("target_port"));
        e.setTargetPath(rs.getString("target_path"));
        e.setProtocol(rs.getString("protocol"));
        e.setSoftware(rs.getString("software"));
        e.setOperation(rs.getString("operation"));
        e.setStatus(parseStatus(rs.getString("status")));
        e.setStatusCode(rs.getInt("status_code"));
        e.setStartTime(rs.getLong("start_time"));
        e.setEndTime(rs.getLong("end_time"));
        e.setDuration(rs.getLong("duration"));
        e.setBytesOut(rs.getLong("bytes_out"));
        e.setBytesIn(rs.getLong("bytes_in"));
        e.setErrorType(rs.getString("error_type"));
        e.setErrorMessage(rs.getString("error_message"));
        e.setAttributes(fromJsonMap(rs.getString("attributes")));
        return e;
    }

    /**
     * 将依赖边行映射为边对象。
     *
     * @param rs 结果集
     * @return 依赖边
     * @throws SQLException 读取失败
     */
    private DependencyEdge mapDependency(ResultSet rs) throws SQLException {
        DependencyEdge edge = new DependencyEdge();
        edge.setSource(fromJson(rs.getString("source"), Endpoint.class));
        edge.setTarget(fromJson(rs.getString("target"), Endpoint.class));
        edge.setProtocol(parseProtocol(rs.getString("protocol")));
        edge.setSoftware(parseSoftware(rs.getString("software")));
        edge.setCallCount(rs.getLong("call_count"));
        edge.setTotalDuration(rs.getLong("total_duration"));
        edge.setErrorCount(rs.getLong("error_count"));
        edge.setLastError(rs.getString("last_error"));
        edge.setLastCallTime(rs.getLong("last_call_time"));
        return edge;
    }

    /**
     * 解析状态枚举。
     *
     * @param name 枚举名
     * @return 状态枚举
     */
    private StatusCode parseStatus(String name) {
        if (name == null) {
            return StatusCode.UNSET;
        }
        try {
            return StatusCode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return StatusCode.UNSET;
        }
    }

    /**
     * 解析协议枚举。
     *
     * @param name 枚举名
     * @return 协议枚举
     */
    private Protocol parseProtocol(String name) {
        if (name == null) {
            return Protocol.UNKNOWN;
        }
        try {
            return Protocol.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Protocol.UNKNOWN;
        }
    }

    /**
     * 解析软件栈枚举。
     *
     * @param name 枚举名
     * @return 软件栈枚举
     */
    private Software parseSoftware(String name) {
        if (name == null) {
            return Software.UNKNOWN;
        }
        try {
            return Software.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Software.UNKNOWN;
        }
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * @param value 对象
     * @return JSON 字符串
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Json.toJson(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 反序列化 JSON 字符串为对象。
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @param <T> 泛型
     * @return 对象
     */
    private <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return Json.fromJson(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 反序列化 JSON 字符串为 Map。
     *
     * @param json JSON 字符串
     * @return Map
     */
    private Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> raw = Json.fromJson(json);
            Map<String, String> result = new HashMap<>();
            raw.forEach((k, v) -> result.put(k, v == null ? null : String.valueOf(v)));
            return result;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
