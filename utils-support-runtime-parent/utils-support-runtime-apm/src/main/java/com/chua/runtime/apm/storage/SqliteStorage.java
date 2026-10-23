package com.chua.runtime.apm.storage;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.Json;
import com.chua.runtime.protocol.DependencyEdge;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import lombok.extern.java.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * sqlite 存储 — 嵌入式本地落盘实现。
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
 * <p><b>JDBC 执行位置</b>：真正的 SQL 全部委托给 {@link SqliteSession}，本类不直接持有
 * {@code java.sql.Connection}。原因见 {@link ApmStorageClassLoader} 的类注释：agent 场景下
 * 只有隔离加载器内定义的类才能完整使用 JDBC，而本类同样由隔离加载器定义，
 * 因此可以与会话对象直接做类型收窄。</p>
 *
 * <p>线程安全：所有写操作使用 {@code synchronized} 串行化；会话内部复用同一个连接，
 * 读操作与写操作共用该连接但由 {@code writeLock} 保护写入路径。</p>
 *
 * <p>日志说明：本类使用 Lombok {@code @Log}（java.util.logging）而非 {@code @Slf4j}，
 * 这是刻意为之 —— 本类随 agent.jar 追加到 bootstrap 搜索路径，该命名空间无法解析
 * slf4j-api（由 agent shade relocation 到 {@code com.chua.runtime.shaded.slf4j}），
 * 故使用 JDK 自带 JUL。</p>
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
     * 传输记录列名（查询与映射共用，顺序即 {@code SELECT} 顺序）
     */
    private static final String[] TRANSMISSION_COLUMNS = {
            "id", "trace_id", "span_id", "parent_span_id",
            "source_protocol", "source_software", "source_host", "source_port", "source_path",
            "target_protocol", "target_software", "target_host", "target_port", "target_path",
            "protocol", "software", "operation", "status", "status_code",
            "start_time", "end_time", "duration", "bytes_out", "bytes_in",
            "error_type", "error_message", "attributes"
    };

    /**
     * 泄漏记录列名
     */
    private static final String[] LEAK_COLUMNS = {
            "id", "handle_id", "kind", "name", "thread", "created_at", "closed_at", "stack_trace"
    };

    /**
     * 日志记录列名
     */
    private static final String[] LOG_COLUMNS = {
            "id", "timestamp", "level", "logger", "class_name", "method_name", "message", "trace_id"
    };

    /**
     * 依赖边列名
     */
    private static final String[] DEPENDENCY_COLUMNS = {
            "source", "target", "protocol", "software", "call_count", "total_duration",
            "error_count", "last_error", "last_call_time"
    };

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
     * JDBC 会话（隔离加载器内实例，本类与之同一加载器）
     */
    private SqliteSession session;

    /**
     * 写锁 — sqlite 单写者，串行化所有写操作
     */
    private final Object writeLock = new Object();

    @Override
    /**
     * 开始
     */
    public void start(StorageConfig config) {
        this.dbPath = config.get(KEY_PATH, DEFAULT_DB_PATH);
        this.retentionMillis = config.getLong(KEY_RETENTION_MS, DEFAULT_RETENTION_MS);
        this.capacity = config.getInt(KEY_CAPACITY, DEFAULT_CAPACITY);
        ensureParentDir(dbPath);
        try {
            this.session = open(config);
            createTables();
            log.info(String.format("SqliteStorage 启动: path=%s, retentionMs=%d, capacity=%d",
                    Paths.get(dbPath).toAbsolutePath(), retentionMillis, capacity));
        } catch (Exception e) {
            throw new IllegalStateException("SQLite 存储启动失败: " + dbPath, e);
        }
    }

    /**
     * 按配置映射启动（供 {@link IsolatedStorageBridge} 调用）。
     *
     * <p>入参用 {@link Map} 而不是 {@link StorageConfig}：本类由隔离加载器定义时，
     * {@code StorageConfig} 是 bootstrap 里的另一份类型，无法直接接收。</p>
     *
     * @param properties 配置键值
     */
    public void start(Map<String, String> properties) {
        Map<String, String> values = properties == null ? Wraps.map() : new java.util.HashMap<>(properties);
        StorageConfig config = new StorageConfig();
        config.setProperties(values);
        start(config);
    }

    /**
     * 追加依赖边（供 {@link IsolatedStorageBridge} 调用）。
     *
     * <p>父侧传入的是 {@code Map} 而不是 {@code DependencyEdge}：{@code DependencyEdge}
     * 与 {@link Endpoint} 由 bootstrap 定义，两侧解析到的不是同一份类型，因此在隔离侧
     * 重新构造等价的边对象。</p>
     *
     * @param edge 依赖边的字段映射
     */
    public void appendDependency(Map<String, Object> edge) {
        if (edge == null) {
            return;
        }
        DependencyEdge value = new DependencyEdge();
        Object source = edge.get("source");
        if (source instanceof Endpoint) {
            value.setSource((Endpoint) source);
        }
        Object target = edge.get("target");
        if (target instanceof Endpoint) {
            value.setTarget((Endpoint) target);
        }
        Object protocol = edge.get("protocol");
        if (protocol instanceof Protocol) {
            value.setProtocol((Protocol) protocol);
        }
        Object software = edge.get("software");
        if (software instanceof Software) {
            value.setSoftware((Software) software);
        }
        value.setCallCount(toLong(edge.get("callCount")));
        value.setTotalDuration(toLong(edge.get("totalDuration")));
        value.setErrorCount(toLong(edge.get("errorCount")));
        Object lastError = edge.get("lastError");
        value.setLastError(lastError == null ? null : String.valueOf(lastError));
        value.setLastCallTime(toLong(edge.get("lastCallTime")));
        appendDependency(value);
    }

    /**
     * 追加传输记录（供 {@link IsolatedStorageBridge} 调用）。
     *
     * <p>父侧传入字段映射而非 {@link TransmissionEvent} 实例：本类由隔离加载器定义时，
     * 模型类是另一份类型，无法直接接收。</p>
     *
     * @param fields 字段映射
     */
    public void appendTransmission(Map<String, Object> fields) {
        if (fields == null) {
            return;
        }
        TransmissionEvent event = new TransmissionEvent();
        event.setTraceId(asString(fields.get("traceId")));
        event.setSpanId(asString(fields.get("spanId")));
        event.setParentSpanId(asString(fields.get("parentSpanId")));
        event.setSourceProtocol(asString(fields.get("sourceProtocol")));
        event.setSourceSoftware(asString(fields.get("sourceSoftware")));
        event.setSourceHost(asString(fields.get("sourceHost")));
        event.setSourcePort((int) toLong(fields.get("sourcePort")));
        event.setSourcePath(asString(fields.get("sourcePath")));
        event.setTargetProtocol(asString(fields.get("targetProtocol")));
        event.setTargetSoftware(asString(fields.get("targetSoftware")));
        event.setTargetHost(asString(fields.get("targetHost")));
        event.setTargetPort((int) toLong(fields.get("targetPort")));
        event.setTargetPath(asString(fields.get("targetPath")));
        event.setProtocol(asString(fields.get("protocol")));
        event.setSoftware(asString(fields.get("software")));
        event.setOperation(asString(fields.get("operation")));
        event.setStatus(parseStatus(asString(fields.get("status"))));
        event.setStatusCode((int) toLong(fields.get("statusCode")));
        event.setStartTime(toLong(fields.get("startTime")));
        event.setEndTime(toLong(fields.get("endTime")));
        event.setDuration(toLong(fields.get("duration")));
        event.setBytesOut(toLong(fields.get("bytesOut")));
        event.setBytesIn(toLong(fields.get("bytesIn")));
        event.setErrorType(asString(fields.get("errorType")));
        event.setErrorMessage(asString(fields.get("errorMessage")));
        Object attributes = fields.get("attributes");
        if (attributes instanceof Map) {
            Map<String, String> copy = Wraps.map();
            ((Map<?, ?>) attributes).forEach((k, v) -> copy.put(String.valueOf(k),
                    v == null ? null : String.valueOf(v)));
            event.setAttributes(copy);
        }
        appendTransmission(event);
    }

    /**
     * 追加泄漏记录（供 {@link IsolatedStorageBridge} 调用）。
     *
     * @param fields 字段映射
     */
    public void appendLeak(Map<String, Object> fields) {
        if (fields == null) {
            return;
        }
        LeakRecord record = new LeakRecord();
        record.setHandleId(asString(fields.get("handleId")));
        record.setKind(asString(fields.get("kind")));
        record.setName(asString(fields.get("name")));
        record.setThread(asString(fields.get("thread")));
        record.setCreatedAt(toLong(fields.get("createdAt")));
        record.setClosedAt(toLong(fields.get("closedAt")));
        record.setStackTrace(asString(fields.get("stackTrace")));
        appendLeak(record);
    }

    /**
     * 追加日志记录（供 {@link IsolatedStorageBridge} 调用）。
     *
     * @param fields 字段映射
     */
    public void appendLog(Map<String, Object> fields) {
        if (fields == null) {
            return;
        }
        LogRecord record = new LogRecord();
        record.setTimestamp(toLong(fields.get("timestamp")));
        record.setLevel(asString(fields.get("level")));
        record.setLogger(asString(fields.get("logger")));
        record.setClassName(asString(fields.get("className")));
        record.setMethodName(asString(fields.get("methodName")));
        record.setMessage(asString(fields.get("message")));
        record.setTraceId(asString(fields.get("traceId")));
        appendLog(record);
    }

    /**
     * 解析字符串。
     *
     * @param value 值
     * @return 字符串；{@code null} 原样返回
     */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 解析数值。
     *
     * @param value 值
     * @return 数值；非数字返回 0
     */
    private static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return parseLong(value == null ? null : String.valueOf(value));
    }

    @Override
    /**
     * 停止
     */
    public void stop() {
        log.info(String.format("SqliteStorage 停止: %s", dbPath));
        SqliteSession s = this.session;
        this.session = null;
        if (s != null) {
            s.close();
        }
    }

    @Override
    /**
     * 追加Transmission
     */
    public void appendTransmission(TransmissionEvent event) {
        if (event == null) {
            return;
        }
        String sql = "INSERT INTO transmissions (trace_id, span_id, parent_span_id, "
                + "source_protocol, source_software, source_host, source_port, source_path, "
                + "target_protocol, target_software, target_host, target_port, target_path, "
                + "protocol, software, operation, status, status_code, "
                + "start_time, end_time, duration, bytes_out, bytes_in, "
                + "error_type, error_message, attributes) VALUES ("
                + StringList.join(new Object[]{
                SqliteSession.quote(event.getTraceId()),
                SqliteSession.quote(event.getSpanId()),
                SqliteSession.quote(event.getParentSpanId()),
                SqliteSession.quote(event.getSourceProtocol()),
                SqliteSession.quote(event.getSourceSoftware()),
                SqliteSession.quote(event.getSourceHost()),
                String.valueOf(event.getSourcePort()),
                SqliteSession.quote(event.getSourcePath()),
                SqliteSession.quote(event.getTargetProtocol()),
                SqliteSession.quote(event.getTargetSoftware()),
                SqliteSession.quote(event.getTargetHost()),
                String.valueOf(event.getTargetPort()),
                SqliteSession.quote(event.getTargetPath()),
                SqliteSession.quote(event.getProtocol()),
                SqliteSession.quote(event.getSoftware()),
                SqliteSession.quote(event.getOperation()),
                SqliteSession.quote(event.getStatus() == null ? null : event.getStatus().name()),
                String.valueOf(event.getStatusCode()),
                String.valueOf(event.getStartTime()),
                String.valueOf(event.getEndTime()),
                String.valueOf(event.getDuration()),
                String.valueOf(event.getBytesOut()),
                String.valueOf(event.getBytesIn()),
                SqliteSession.quote(event.getErrorType()),
                SqliteSession.quote(event.getErrorMessage()),
                SqliteSession.quote(toJson(event.getAttributes()))
        }) + ")";
        executeWrite(sql, "写入传输记录失败");
    }

    @Override
    /**
     * 追加Dependency
     */
    public void appendDependency(DependencyEdge edge) {
        if (edge == null || edge.getSource() == null || edge.getTarget() == null) {
            return;
        }
        synchronized (writeLock) {
            SqliteSession s = session;
            if (s == null) {
                return;
            }
            try {
                String edgeId = edge.edgeId();
                List<String[]> existing = s.queryRows(
                        "SELECT call_count, total_duration, error_count FROM dependencies WHERE edge_id = "
                                + SqliteSession.quote(edgeId), new String[]{
                                "call_count", "total_duration", "error_count"});
                if (!existing.isEmpty()) {
                    String[] row = existing.get(0);
                    long callCount = parseLong(row[0]) + edge.getCallCount();
                    long totalDuration = parseLong(row[1]) + edge.getTotalDuration();
                    long errorCount = parseLong(row[2]) + edge.getErrorCount();
                    s.execute("UPDATE dependencies SET call_count=" + callCount
                            + ", total_duration=" + totalDuration
                            + ", error_count=" + errorCount
                            + ", last_error=" + SqliteSession.quote(edge.getLastError())
                            + ", last_call_time=" + System.currentTimeMillis()
                            + " WHERE edge_id=" + SqliteSession.quote(edgeId));
                } else {
                    s.execute("INSERT INTO dependencies (edge_id, source, target, protocol, software, "
                            + "call_count, total_duration, error_count, last_error, last_call_time) VALUES ("
                            + StringList.join(new Object[]{
                            SqliteSession.quote(edgeId),
                            SqliteSession.quote(toJson(edge.getSource())),
                            SqliteSession.quote(toJson(edge.getTarget())),
                            SqliteSession.quote(edge.getProtocol() == null ? null : edge.getProtocol().name()),
                            SqliteSession.quote(edge.getSoftware() == null ? null : edge.getSoftware().name()),
                            String.valueOf(edge.getCallCount()),
                            String.valueOf(edge.getTotalDuration()),
                            String.valueOf(edge.getErrorCount()),
                            SqliteSession.quote(edge.getLastError()),
                            String.valueOf(edge.getLastCallTime())
                    }) + ")");
                }
            } catch (Exception e) {
                log.warning(String.format("写入依赖边失败: %s", e.getMessage()));
            }
        }
    }

    @Override
    /**
     * 追加Leak
     */
    public void appendLeak(LeakRecord record) {
        if (record == null) {
            return;
        }
        String sql = "INSERT OR REPLACE INTO leaks (handle_id, kind, name, thread, created_at, "
                + "closed_at, stack_trace) VALUES ("
                + StringList.join(new Object[]{
                SqliteSession.quote(record.getHandleId()),
                SqliteSession.quote(record.getKind()),
                SqliteSession.quote(record.getName()),
                SqliteSession.quote(record.getThread()),
                String.valueOf(record.getCreatedAt()),
                String.valueOf(record.getClosedAt()),
                SqliteSession.quote(record.getStackTrace())
        }) + ")";
        executeWrite(sql, "写入泄漏记录失败");
    }

    @Override
    /**
     * 追加记录日志
     */
    public void appendLog(LogRecord record) {
        if (record == null) {
            return;
        }
        String sql = "INSERT INTO logs (timestamp, level, logger, class_name, method_name, message, "
                + "trace_id) VALUES ("
                + StringList.join(new Object[]{
                String.valueOf(record.getTimestamp()),
                SqliteSession.quote(record.getLevel()),
                SqliteSession.quote(record.getLogger()),
                SqliteSession.quote(record.getClassName()),
                SqliteSession.quote(record.getMethodName()),
                SqliteSession.quote(record.getMessage()),
                SqliteSession.quote(record.getTraceId())
        }) + ")";
        executeWrite(sql, "写入日志记录失败");
    }

    @Override
    /**
     * 查询Transmissions
     */
    public List<TransmissionEvent> queryTransmissions(Query query) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(StringList.join(TRANSMISSION_COLUMNS)).append(" FROM transmissions WHERE 1=1");
        appendTimeFilter(sql, "start_time", query);
        appendStringFilter(sql, "trace_id", query.getTraceId());
        appendStringFilter(sql, "source_host", query.getSourceHost());
        appendStringFilter(sql, "target_host", query.getTargetHost());
        appendStringFilter(sql, "protocol", query.getProtocol());
        appendStringFilter(sql, "software", query.getSoftware());
        appendStringFilter(sql, "status", query.getStatus());
        if (query.isErrorOnly()) {
            sql.append(" AND status = 'ERROR'");
        }
        sql.append(" ORDER BY start_time DESC LIMIT ").append(query.getLimit())
                .append(" OFFSET ").append(query.getOffset());

        List<TransmissionEvent> result = Wraps.list();
        SqliteSession s = session;
        if (s == null) {
            return result;
        }
        try {
            for (String[] row : s.queryRows(sql.toString(), TRANSMISSION_COLUMNS)) {
                result.add(mapTransmission(row));
            }
        } catch (Exception e) {
            log.warning(String.format("查询传输记录失败: %s", e.getMessage()));
        }
        return result;
    }

    @Override
    /**
     * 查询Dependencies
     */
    public List<DependencyEdge> queryDependencies(Query query) {
        List<DependencyEdge> result = Wraps.list();
        SqliteSession s = session;
        if (s == null) {
            return result;
        }
        try {
            for (String[] row : s.queryRows("SELECT " + StringList.join(DEPENDENCY_COLUMNS)
                    + " FROM dependencies", DEPENDENCY_COLUMNS)) {
                result.add(mapDependency(row));
            }
        } catch (Exception e) {
            log.warning(String.format("查询依赖边失败: %s", e.getMessage()));
        }
        return result;
    }

    @Override
    /**
     * 查询Leaks
     */
    public List<LeakRecord> queryLeaks(Query query) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(StringList.join(LEAK_COLUMNS)).append(" FROM leaks WHERE 1=1");
        appendTimeFilter(sql, "created_at", query);
        sql.append(" ORDER BY created_at DESC LIMIT ").append(query.getLimit())
                .append(" OFFSET ").append(query.getOffset());

        List<LeakRecord> result = Wraps.list();
        SqliteSession s = session;
        if (s == null) {
            return result;
        }
        try {
            for (String[] row : s.queryRows(sql.toString(), LEAK_COLUMNS)) {
                LeakRecord r = new LeakRecord();
                r.setId(parseLong(row[0]));
                r.setHandleId(row[1]);
                r.setKind(row[2]);
                r.setName(row[3]);
                r.setThread(row[4]);
                r.setCreatedAt(parseLong(row[5]));
                r.setClosedAt(parseLong(row[6]));
                r.setStackTrace(row[7]);
                result.add(r);
            }
        } catch (Exception e) {
            log.warning(String.format("查询泄漏记录失败: %s", e.getMessage()));
        }
        return result;
    }

    @Override
    /**
     * 查询日志
     */
    public List<LogRecord> queryLogs(Query query) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(StringList.join(LOG_COLUMNS)).append(" FROM logs WHERE 1=1");
        appendTimeFilter(sql, "timestamp", query);
        appendStringFilter(sql, "level", query.getStatus());
        sql.append(" ORDER BY timestamp DESC LIMIT ").append(query.getLimit())
                .append(" OFFSET ").append(query.getOffset());

        List<LogRecord> result = Wraps.list();
        SqliteSession s = session;
        if (s == null) {
            return result;
        }
        try {
            for (String[] row : s.queryRows(sql.toString(), LOG_COLUMNS)) {
                LogRecord r = new LogRecord();
                r.setId(parseLong(row[0]));
                r.setTimestamp(parseLong(row[1]));
                r.setLevel(row[2]);
                r.setLogger(row[3]);
                r.setClassName(row[4]);
                r.setMethodName(row[5]);
                r.setMessage(row[6]);
                r.setTraceId(row[7]);
                result.add(r);
            }
        } catch (Exception e) {
            log.warning(String.format("查询日志记录失败: %s", e.getMessage()));
        }
        return result;
    }

    @Override
    /**
     * Stats
     */
    public Map<String, Long> stats() {
        Map<String, Long> result = Wraps.map();
        SqliteSession s = session;
        if (s == null) {
            return result;
        }
        try {
            result.put("transmissions", count(s, "transmissions"));
            result.put("dependencies", count(s, "dependencies"));
            result.put("leaks", count(s, "leaks"));
            result.put("logs", count(s, "logs"));
        } catch (Exception e) {
            log.warning(String.format("统计失败: %s", e.getMessage()));
        }
        return result;
    }

    @Override
    /**
     * Cleanup
     */
    public long cleanup(long retentionMillis) {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        long removed = 0;
        synchronized (writeLock) {
            SqliteSession s = session;
            if (s == null) {
                return 0;
            }
            try {
                removed += s.execute("DELETE FROM transmissions WHERE start_time < " + cutoff);
                removed += s.execute("DELETE FROM logs WHERE timestamp < " + cutoff);
                removed += s.execute("DELETE FROM leaks WHERE closed_at > 0 AND closed_at < " + cutoff);
            } catch (Exception e) {
                log.warning(String.format("清理过期数据失败: %s", e.getMessage()));
            }
        }
        return removed;
    }

    @Override
    /**
     * 名称
     */
    public String name() {
        return "sqlite";
    }

    /**
     * 打开数据库会话。
     *
     * <p>优先使用隔离加载器内创建的会话（agent 场景）；否则在本加载器内直接创建
     * {@link SqliteSession}（宿主进程场景，{@code java.sql} 可见）。</p>
     *
     * @param config 存储配置
     * @return 已打开的会话
     * @throws Exception 打开失败
     */
    private SqliteSession open(StorageConfig config) throws Exception {
        Object isolated = ApmStorageClassLoader.newSession();
        SqliteSession s = isolated instanceof SqliteSession ready ? ready : new SqliteSession();
        s.open(JDBC_PREFIX + dbPath);
        if (!s.isOpen()) {
            throw new IllegalStateException("SQLite 连接未建立: " + dbPath);
        }
        return s;
    }

    /**
     * 执行一条写语句。
     *
     * @param sql      SQL
     * @param logLabel 日志前缀
     */
    private void executeWrite(String sql, String logLabel) {
        synchronized (writeLock) {
            SqliteSession s = session;
            if (s == null) {
                return;
            }
            try {
                s.execute(sql);
            } catch (Exception e) {
                log.warning(String.format("%s: %s", logLabel, e.getMessage()));
            }
        }
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
     * @throws Exception 建表失败
     */
    private void createTables() throws Exception {
        SqliteSession s = session;
        s.execute("CREATE TABLE IF NOT EXISTS transmissions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "trace_id TEXT, span_id TEXT, parent_span_id TEXT, "
                + "source_protocol TEXT, source_software TEXT, source_host TEXT, source_port INTEGER, source_path TEXT, "
                + "target_protocol TEXT, target_software TEXT, target_host TEXT, target_port INTEGER, target_path TEXT, "
                + "protocol TEXT, software TEXT, operation TEXT, status TEXT, status_code INTEGER, "
                + "start_time INTEGER, end_time INTEGER, duration INTEGER, bytes_out INTEGER, bytes_in INTEGER, "
                + "error_type TEXT, error_message TEXT, attributes TEXT)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_trans_start ON transmissions(start_time)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_trans_trace ON transmissions(trace_id)");

        s.execute("CREATE TABLE IF NOT EXISTS dependencies ("
                + "edge_id TEXT PRIMARY KEY, "
                + "source TEXT, target TEXT, protocol TEXT, software TEXT, "
                + "call_count INTEGER, total_duration INTEGER, error_count INTEGER, "
                + "last_error TEXT, last_call_time INTEGER)");

        s.execute("CREATE TABLE IF NOT EXISTS leaks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "handle_id TEXT UNIQUE, kind TEXT, name TEXT, thread TEXT, "
                + "created_at INTEGER, closed_at INTEGER, stack_trace TEXT)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_leak_created ON leaks(created_at)");

        s.execute("CREATE TABLE IF NOT EXISTS logs ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "timestamp INTEGER, level TEXT, logger TEXT, class_name TEXT, method_name TEXT, "
                + "message TEXT, trace_id TEXT)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_log_ts ON logs(timestamp)");
    }

    /**
     * 统计表行数。
     *
     * @param s     会话
     * @param table 表名
     * @return 行数
     * @throws Exception 查询失败
     */
    private long count(SqliteSession s, String table) throws Exception {
        return parseLong(s.queryFirstString("SELECT COUNT(*) FROM " + table));
    }

    /**
     * 追加时间范围过滤条件。
     *
     * @param sql    SQL 构建器
     * @param column 时间列名
     * @param query  查询条件
     */
    private void appendTimeFilter(StringBuilder sql, String column, Query query) {
        if (query.getStartTime() != null) {
            sql.append(" AND ").append(column).append(" >= ").append(query.getStartTime());
        }
        if (query.getEndTime() != null) {
            sql.append(" AND ").append(column).append(" <= ").append(query.getEndTime());
        }
    }

    /**
     * 追加字符串模糊过滤条件。
     *
     * @param sql    SQL 构建器
     * @param column 列名
     * @param value  过滤值
     */
    private void appendStringFilter(StringBuilder sql, String column, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" LIKE ")
                .append(SqliteSession.quote("%" + value + "%"));
    }

    /**
     * 将传输记录行映射为事件对象。
     *
     * @param row 行数据（与 {@link #TRANSMISSION_COLUMNS} 对齐）
     * @return 传输事件
     */
    private TransmissionEvent mapTransmission(String[] row) {
        TransmissionEvent e = new TransmissionEvent();
        e.setId(parseLong(row[0]));
        e.setTraceId(row[1]);
        e.setSpanId(row[2]);
        e.setParentSpanId(row[3]);
        e.setSourceProtocol(row[4]);
        e.setSourceSoftware(row[5]);
        e.setSourceHost(row[6]);
        e.setSourcePort((int) parseLong(row[7]));
        e.setSourcePath(row[8]);
        e.setTargetProtocol(row[9]);
        e.setTargetSoftware(row[10]);
        e.setTargetHost(row[11]);
        e.setTargetPort((int) parseLong(row[12]));
        e.setTargetPath(row[13]);
        e.setProtocol(row[14]);
        e.setSoftware(row[15]);
        e.setOperation(row[16]);
        e.setStatus(parseStatus(row[17]));
        e.setStatusCode((int) parseLong(row[18]));
        e.setStartTime(parseLong(row[19]));
        e.setEndTime(parseLong(row[20]));
        e.setDuration(parseLong(row[21]));
        e.setBytesOut(parseLong(row[22]));
        e.setBytesIn(parseLong(row[23]));
        e.setErrorType(row[24]);
        e.setErrorMessage(row[25]);
        e.setAttributes(fromJsonMap(row[26]));
        return e;
    }

    /**
     * 将依赖边行映射为边对象。
     *
     * @param row 行数据（与 {@link #DEPENDENCY_COLUMNS} 对齐）
     * @return 依赖边
     */
    private DependencyEdge mapDependency(String[] row) {
        DependencyEdge edge = new DependencyEdge();
        edge.setSource(fromJson(row[0], Endpoint.class));
        edge.setTarget(fromJson(row[1], Endpoint.class));
        edge.setProtocol(parseProtocol(row[2]));
        edge.setSoftware(parseSoftware(row[3]));
        edge.setCallCount(parseLong(row[4]));
        edge.setTotalDuration(parseLong(row[5]));
        edge.setErrorCount(parseLong(row[6]));
        edge.setLastError(row[7]);
        edge.setLastCallTime(parseLong(row[8]));
        return edge;
    }

    /**
     * 解析长整型。
     *
     * @param value 文本
     * @return 数值；无法解析返回 0
     */
    private static long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
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
     * @param <T>  泛型
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
     * 反序列化 JSON 字符串为映射。
     *
     * @param json JSON 字符串
     * @return Map
     */
    private Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isEmpty()) {
            return Wraps.map();
        }
        try {
            Map<String, Object> raw = Json.fromJson(json);
            Map<String, String> result = Wraps.map();
            raw.forEach((k, v) -> result.put(k, v == null ? null : String.valueOf(v)));
            return result;
        } catch (Exception e) {
            return Wraps.map();
        }
    }

    /**
     * 字符串工具（避免引入额外依赖）。
     */
    private static final class StringList {

        /**
         * 工具类构造器。
         */
        private StringList() {
        }

        /**
         * 以逗号连接。
         *
         * @param values 值
         * @return 连接结果
         */
        private static String join(Object[] values) {
            if (values == null || values.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(values[i]);
            }
            return sb.toString();
        }
    }

    /**
     * 空查询占位（保留 {@link Collections} 兼容）。
     *
     * @return 空列表
     */
    @SuppressWarnings("unused")
    private static List<Object> emptyList() {
        return Collections.emptyList();
    }
}
