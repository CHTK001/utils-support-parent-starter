package com.chua.runtime.apm.storage;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.Json;
import com.chua.runtime.protocol.DependencyEdge;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
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
import java.util.concurrent.atomic.AtomicLong;

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
     * 配置键：写入模式（{@code async} 异步批量，{@code sync} 逐条同步）
     */
    private static final String KEY_ASYNC = "apm.storage.async";

    /**
     * 配置键：关闭时等待落盘的最长时间（毫秒）
     */
    private static final String KEY_FLUSH_TIMEOUT = "apm.storage.flush.timeout.ms";

    /**
     * 配置键：自维护周期（毫秒）—— 按保留期与容量自动淘汰的检查间隔
     */
    private static final String KEY_MAINTENANCE_INTERVAL = "apm.storage.maintenance.interval.ms";

    /**
     * 默认关闭等待时间（毫秒）
     */
    private static final long DEFAULT_FLUSH_TIMEOUT = 5_000L;

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
     * JSON 提供者是否可用（不可用时属性列降级为扁平文本）
     */
    private static final boolean JSON_READY = probeJson();

    /**
     * 降级文本标记：出现该前缀表示内容为 Base64 编码的键值对
     */
    private static final String KV_ENCODED = "#b64:";

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
     * 异步写队列（{@code apm.storage.async=true} 时非 null）
     */
    private SqliteWriteQueue queue;

    /**
     * 关闭时等待落盘的最长时间（毫秒）
     */
    private long flushTimeoutMillis = DEFAULT_FLUSH_TIMEOUT;

    /**
     * 写锁 — sqlite 单写者，串行化所有写操作
     */
    private final Object writeLock = new Object();

    /**
     * 自维护线程（按保留期与容量自动淘汰，detail 见 {@link #maintenanceLoop()}）
     */
    private volatile Thread maintainer;

    /**
     * 自维护是否已请求停止
     */
    private volatile boolean maintenanceStopping;

    /**
     * 自维护周期（毫秒），{@code apm.storage.maintenance.interval.ms}
     */
    private long maintenanceIntervalMillis = DEFAULT_MAINTENANCE_INTERVAL;

    /**
     * 默认自维护周期（1 分钟）
     */
    private static final long DEFAULT_MAINTENANCE_INTERVAL = 60_000L;

    /**
     * 自维护周期下限 — 再快就没有意义了，纯粹浪费 CPU
     */
    private static final long MIN_MAINTENANCE_INTERVAL = 5_000L;

    /**
     * 累计淘汰行数（保留期 + 容量）
     */
    private long totalEvicted;

    /**
     * 最近一次自维护时间
     */
    private volatile long lastMaintenanceTime;

    @Override
    /**
     * 开始
     */
    public void start(StorageConfig config) {
        this.dbPath = config.get(KEY_PATH, DEFAULT_DB_PATH);
        this.retentionMillis = config.getLong(KEY_RETENTION_MS, DEFAULT_RETENTION_MS);
        this.capacity = config.getInt(KEY_CAPACITY, DEFAULT_CAPACITY);
        this.flushTimeoutMillis = config.getLong(KEY_FLUSH_TIMEOUT, DEFAULT_FLUSH_TIMEOUT);
        this.maintenanceIntervalMillis = Math.max(MIN_MAINTENANCE_INTERVAL,
                config.getLong(KEY_MAINTENANCE_INTERVAL, DEFAULT_MAINTENANCE_INTERVAL));
        boolean async = config.getBoolean(KEY_ASYNC, true);
        ensureParentDir(dbPath);
        try {
            this.session = open(config);
            createTables();
            verifyWritable();
            if (async) {
                SqliteWriteQueue q = new SqliteWriteQueue(this::applyBatch);
                q.start();
                this.queue = q;
            }
            startMaintenance();
            log.info(String.format("SqliteStorage 启动: path=%s, retentionMs=%d, capacity=%d, async=%s, 自维护周期=%dms",
                    Paths.get(dbPath).toAbsolutePath(), retentionMillis, capacity, async,
                    maintenanceIntervalMillis));
        } catch (Exception e) {
            // 启动失败必须把半开的连接与写线程收干净，否则热更新时旧连接会一直占着文件
            closeQuietly();
            throw new IllegalStateException("SQLite 存储启动失败: " + dbPath, e);
        }
    }

    /**
     * 校验目标文件确实可写。
     *
     * <p><b>为什么需要</b>：SQLite 在目标路径不可写时<b>不会报错</b>，而是悄悄回退成
     * 内存数据库 —— 进程内读写一切正常，但重启后数据全丢。这类「假成功」最难发现，
     * 所以启动时主动校验：目录可写、库文件真实存在、不是内存库。</p>
     *
     * @throws IllegalStateException 落盘不可用
     */
    private void verifyWritable() {
        Path file = Paths.get(dbPath).toAbsolutePath();
        Path parent = file.getParent();
        if (parent != null) {
            // 目录不存在 / 无写权限 → 立刻失败，而不是等 SQLite 静默降级
            if (!Files.isDirectory(parent) || !Files.isWritable(parent)) {
                throw new IllegalStateException("目录不可写: " + parent);
            }
        }
        if (!Files.exists(file) || !Files.isWritable(file)) {
            throw new IllegalStateException("数据库文件未能创建或无写权限: " + file);
        }
        SqliteSession s = session;
        if (s == null || !s.isOpen()) {
            throw new IllegalStateException("数据库连接未建立: " + file);
        }
        try {
            // database_list 首列是 name，第二列是 file；用 queryRows 取 file 列
            List<String[]> rows = s.queryRows("PRAGMA database_list", new String[]{"name", "file"});
            for (String[] row : rows) {
                if (row == null || row.length < 2) {
                    continue;
                }
                if ("main".equals(row[0]) && (row[1] == null || row[1].isEmpty())) {
                    // file 为空 = 内存库，说明指定路径没能打开
                    throw new IllegalStateException("数据库退化为内存模式（路径不可写）: " + file);
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("校验数据库文件失败: " + file, e);
        }
    }

    /**
     * 尽力关闭当前连接与写队列（启动失败路径使用）。
     */
    private void closeQuietly() {
        stopMaintenance();
        SqliteWriteQueue q = this.queue;
        this.queue = null;
        if (q != null) {
            try {
                q.shutdown(1_000L);
            } catch (Throwable ignored) {
                // 清理失败不影响异常上抛
            }
        }
        SqliteSession s = this.session;
        this.session = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
                // 清理失败不影响异常上抛
            }
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
        // 先停自维护，避免它和关闭流程同时操作连接
        stopMaintenance();
        SqliteWriteQueue q = this.queue;
        this.queue = null;
        boolean drained = true;
        if (q != null) {
            // 先把队列排空再关连接，否则关闭瞬间的积压会丢
            drained = q.shutdown(flushTimeoutMillis);
            log.info(String.format("SqliteStorage 停止: %s, 已落盘 %d 条, 丢弃 %d 条%s", dbPath,
                    q.flushed(), q.dropped(), drained ? "" : "（超时未排空）"));
        } else {
            log.info(String.format("SqliteStorage 停止: %s", dbPath));
        }
        SqliteSession s = this.session;
        this.session = null;
        if (s != null) {
            try {
                s.checkpoint();
            } catch (Exception e) {
                // 收敛失败不影响关闭
            }
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
        // 事件可能来自业务线程且之后会被复用，先冻结成不可变的 SQL 文本再入队
        final String sql = "INSERT INTO transmissions (trace_id, span_id, parent_span_id, "
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
        final String edgeId = edge.edgeId();
        final String rawSource = toJson(edge.getSource());
        final String rawTarget = toJson(edge.getTarget());
        final String protocol = edge.getProtocol() == null ? null : edge.getProtocol().name();
        final String software = edge.getSoftware() == null ? null : edge.getSoftware().name();
        final long callCount = edge.getCallCount();
        final long totalDuration = edge.getTotalDuration();
        final long errorCount = edge.getErrorCount();
        final String lastError = edge.getLastError();
        final long lastCallTime = edge.getLastCallTime();
        // 增量写入交给写线程串行执行：同一条边来自多个业务线程时，
        // 若在业务线程做「读旧值 + 加增量」，并发会互相覆盖。
        submit(() -> mergeDependency(edgeId, rawSource, rawTarget, protocol, software,
                callCount, totalDuration, errorCount, lastError, lastCallTime));
    }

    /**
     * 合并写入一条依赖边（{@code ON CONFLICT} 原子累加）。
     *
     * <p>用 {@code INSERT ... ON CONFLICT(edge_id) DO UPDATE} 而不是「先查后改」：
     * 累加由 SQLite 原子完成，且少一次往返。</p>
     *
     * @param edgeId        边标识
     * @param rawSource     序列化后的源端点
     * @param rawTarget     序列化后的目标端点
     * @param protocol      协议名
     * @param software      软件栈名
     * @param callCount     本次调用次数增量
     * @param totalDuration 本次耗时增量
     * @param errorCount    本次错误数增量
     * @param lastError     最近错误
     * @param lastCallTime  最近调用时间
     * @throws Exception 写入失败
     */
    private void mergeDependency(String edgeId, String rawSource, String rawTarget, String protocol,
                                 String software, long callCount, long totalDuration, long errorCount,
                                 String lastError, long lastCallTime) throws Exception {
        String sql = "INSERT INTO dependencies (edge_id, source, target, protocol, software, "
                + "call_count, total_duration, error_count, last_error, last_call_time) VALUES ("
                + StringList.join(new Object[]{
                SqliteSession.quote(edgeId),
                SqliteSession.quote(rawSource),
                SqliteSession.quote(rawTarget),
                SqliteSession.quote(protocol),
                SqliteSession.quote(software),
                String.valueOf(callCount),
                String.valueOf(totalDuration),
                String.valueOf(errorCount),
                SqliteSession.quote(lastError),
                String.valueOf(lastCallTime)
        }) + ") ON CONFLICT(edge_id) DO UPDATE SET "
                + "call_count = call_count + excluded.call_count, "
                + "total_duration = total_duration + excluded.total_duration, "
                + "error_count = error_count + excluded.error_count, "
                + "last_error = COALESCE(excluded.last_error, last_error), "
                + "last_call_time = excluded.last_call_time";
        session.execute(sql);
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
        // 异步模式下先排空队列，否则「刚写完就读」会读到尚未落盘的旧数字
        awaitQueueIdle(Math.min(flushTimeoutMillis, 3_000L));
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

    /**
     * 启动自维护线程。
     *
     * <p>保留期与容量这类配置如果只写不执行，就是一句空话：磁盘会一直涨到耗尽。
     * 本线程按固定周期做两件事，全部在写线程内串行执行，不与业务写入抢锁：</p>
     * <ol>
     *   <li>按保留期删除过期记录（各表按自己的时间列）</li>
     *   <li>按容量上限淘汰最旧记录（只对可能无限增长的表：传输、日志）</li>
     * </ol>
     *
     * <p>用守护线程：宿主退出时不阻止进程结束。</p>
     */
    private void startMaintenance() {
        maintenanceStopping = false;
        Thread t = new Thread(this::maintenanceLoop, "apm-sqlite-maintenance");
        t.setDaemon(true);
        t.setContextClassLoader(null);
        this.maintainer = t;
        t.start();
    }

    /**
     * 自维护主循环。
     */
    private void maintenanceLoop() {
        while (!maintenanceStopping) {
            try {
                Thread.sleep(maintenanceIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (maintenanceStopping) {
                return;
            }
            try {
                long evicted = maintain();
                if (evicted > 0) {
                    log.info(String.format("SQLite 自维护: 淘汰 %d 行（保留期 %dms / 容量 %d）",
                            evicted, retentionMillis, capacity));
                }
                lastMaintenanceTime = System.currentTimeMillis();
            } catch (Throwable e) {
                // 自维护失败绝不能拖垮存储，下次周期再试
                log.fine(String.format("SQLite 自维护异常: %s", e.getMessage()));
            }
        }
    }

    /**
     * 执行一次自维护（过期清理 + 容量淘汰）。
     *
     * <p>等待完成后返回淘汰行数，便于日志与 {@code describe()} 反映真实效果。</p>
     *
     * @return 淘汰行数
     */
    long maintain() {
        final long cutoff = retentionMillis > 0
                ? System.currentTimeMillis() - retentionMillis : Long.MIN_VALUE;
        final AtomicLong evicted = new AtomicLong(0L);
        final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        submit(() -> {
            try {
                SqliteSession s = session;
                if (s == null) {
                    return;
                }
                long n = 0;
                if (retentionMillis > 0) {
                    n += s.execute("DELETE FROM transmissions WHERE start_time < " + cutoff);
                    n += s.execute("DELETE FROM logs WHERE timestamp < " + cutoff);
                    n += s.execute("DELETE FROM leaks WHERE closed_at > 0 AND closed_at < " + cutoff);
                }
                if (capacity > 0) {
                    // 只保留最新的 capacity 行；用 id 做子查询而不是 LIMIT+OFFSET，
                    // 避免在并发写入下漏删或多删
                    n += s.execute("DELETE FROM transmissions WHERE id NOT IN "
                            + "(SELECT id FROM transmissions ORDER BY id DESC LIMIT " + capacity + ")");
                    n += s.execute("DELETE FROM logs WHERE id NOT IN "
                            + "(SELECT id FROM logs ORDER BY id DESC LIMIT " + capacity + ")");
                }
                synchronized (writeLock) {
                    totalEvicted += n;
                }
                evicted.set(n);
            } finally {
                done.set(true);
            }
        });
        if (queue != null) {
            long deadline = System.currentTimeMillis() + Math.min(flushTimeoutMillis, 10_000L);
            while (!done.get() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return evicted.get();
    }

    /**
     * 停止自维护线程。
     */
    private void stopMaintenance() {
        maintenanceStopping = true;
        Thread t = maintainer;
        this.maintainer = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(Math.min(maintenanceIntervalMillis, 3_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    /**
     * Cleanup
     */
    public long cleanup(long retentionMillis) {
        final long cutoff = System.currentTimeMillis() - retentionMillis;
        final AtomicLong removed = new AtomicLong(0L);
        final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        // 清理也必须走写线程：否则会与批量事务并发抢同一个 SQLite 写锁
        submit(() -> {
            try {
                SqliteSession s = session;
                if (s == null) {
                    return;
                }
                long n = 0;
                n += s.execute("DELETE FROM transmissions WHERE start_time < " + cutoff);
                n += s.execute("DELETE FROM logs WHERE timestamp < " + cutoff);
                n += s.execute("DELETE FROM leaks WHERE closed_at > 0 AND closed_at < " + cutoff);
                removed.set(n);
            } finally {
                done.set(true);
            }
        });
        if (queue == null) {
            return removed.get();
        }
        // 异步模式下等待本次清理真正执行完，返回值才有意义
        long deadline = System.currentTimeMillis() + Math.min(flushTimeoutMillis, 3_000L);
        while (!done.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return removed.get();
    }

    @Override
    /**
     * 刷新写队列
     *
     * @param timeoutMillis 最长等待时间
     * @return 是否在超时前排空
     */
    public boolean flush(long timeoutMillis) {
        SqliteWriteQueue q = queue;
        if (q == null) {
            return true;
        }
        // 入队一个空操作作为栅栏：它被执行时，之前提交的写操作必然已经全部提交
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        if (!q.submit(latch::countDown)) {
            return false;
        }
        try {
            return latch.await(Math.max(1L, timeoutMillis), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 等待队列排空（不提交栅栏，仅等待积压归零）。
     *
     * @param timeoutMillis 最长等待时间
     */
    private void awaitQueueIdle(long timeoutMillis) {
        SqliteWriteQueue q = queue;
        if (q == null) {
            return;
        }
        // 只看队列是否为空是不够的：写线程可能刚取走一批、正在事务里执行，
        // 此时 pending=0 但数据尚未提交。以「处理数追平提交数」为判据。
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMillis);
        while (q.processed() < q.submitted() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    /**
     * 名称
     */
    public String name() {
        return "sqlite";
    }

    @Override
    /**
     * 运行状态
     *
     * @return 运行中返回 true
     */
    public boolean available() {
        SqliteSession s = session;
        return s != null && s.isOpen();
    }

    @Override
    /**
     * 运行时状态（运维自检用）
     *
     * @return 状态映射
     */
    public Map<String, Object> describe() {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("type", name());
        state.put("available", available());
        state.put("path", dbPath == null ? null : Paths.get(dbPath).toAbsolutePath().toString());
        state.put("retentionMs", retentionMillis);
        state.put("capacity", capacity);
        SqliteWriteQueue q = queue;
        state.put("async", q != null);
        if (q != null) {
            awaitQueueIdle(Math.min(flushTimeoutMillis, 3_000L));
        }
        state.put("flushed", q == null ? 0L : q.flushed());
        state.put("dropped", q == null ? 0L : q.dropped());
        state.put("pending", q == null ? 0 : q.pending());
        state.put("maintenanceIntervalMs", maintenanceIntervalMillis);
        state.put("lastMaintenanceAt", lastMaintenanceTime);
        synchronized (writeLock) {
            state.put("evicted", totalEvicted);
        }
        state.put("maintenanceActive", maintainer != null && maintainer.isAlive());
        SqliteSession s = session;
        if (s != null) {
            try {
                state.put("journalMode", s.queryFirstString("PRAGMA journal_mode"));
                state.put("dbBytes", parseLong(s.queryFirstString(
                        "SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size()")));
            } catch (Exception e) {
                // 状态读取失败不影响调用方
            }
        }
        return state;
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
        // 宿主进程里 java.sql 可见，但隔离加载器不构建（SQLite driver 只在 agent 场景才需要）。
        // 这种情况必须显式拿到本加载器里的会话，否则只有一个 null，症状是「存储一直不可用」。
        long t0 = System.currentTimeMillis();
        SqliteSession s = isolated instanceof SqliteSession ready ? ready : new SqliteSession();
        s.open(JDBC_PREFIX + dbPath);
        if (!s.isOpen()) {
            throw new IllegalStateException("SQLite 连接未建立: " + dbPath);
        }
        long cost = System.currentTimeMillis() - t0;
        if (cost > 1_000L) {
            log.warning(String.format("SQLite 打开耗时 %dms（%s，隔离加载器=%s），"
                    + "通常是文件锁竞争或磁盘繁忙", cost, dbPath, isolated != null));
        }
        return s;
    }

    /**
     * 提交一次写操作。
     *
     * <p>异步模式：入队即返回，永不阻塞业务线程。同步模式：当场在写锁内执行。
     * 无论哪种模式，失败都只记日志 —— 观测组件不得影响宿主。</p>
     *
     * @param action 写操作
     */
    private void submit(SqliteWriteQueue.Action action) {
        SqliteWriteQueue q = queue;
        if (q != null) {
            q.submit(action);
            return;
        }
        synchronized (writeLock) {
            try {
                action.run();
            } catch (Exception e) {
                log.warning(String.format("SQLite 写入失败: %s", e.getMessage()));
            }
        }
    }

    /**
     * 执行一条写语句。
     *
     * @param sql      SQL
     * @param logLabel 日志前缀
     */
    private void executeWrite(String sql, String logLabel) {
        submit(() -> {
            SqliteSession s = session;
            if (s == null) {
                return;
            }
            try {
                s.execute(sql);
            } catch (Exception e) {
                log.warning(String.format("%s: %s", logLabel, e.getMessage()));
            }
        });
    }

    /**
     * 在一个事务里执行一批写操作（由 {@link SqliteWriteQueue} 调用）。
     *
     * <p>整批一个事务是异步模式的核心收益：SQLite 一次提交只刷一次盘，
     * 而不是每条一次（实测单条约 21ms）。</p>
     *
     * @param actions 本批操作
     * @throws Exception 失败（整批回滚）
     */
    private void applyBatch(List<SqliteWriteQueue.Action> actions) throws Exception {
        synchronized (writeLock) {
            SqliteSession s = session;
            if (s == null) {
                return;
            }
            s.execute("BEGIN IMMEDIATE");
            try {
                for (SqliteWriteQueue.Action action : actions) {
                    action.run();
                }
                s.execute("COMMIT");
            } catch (Exception e) {
                try {
                    s.execute("ROLLBACK");
                } catch (Exception ignored) {
                    // 回滚失败按连接已损坏处理，由上层重连
                }
                throw e;
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
        edge.setSource(restoreEndpoint(row[0]));
        edge.setTarget(restoreEndpoint(row[1]));
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
     * 还原端点。
     *
     * <p>先按 JSON 解析；隔离环境（agent fat jar 不带 Jackson）里属性列是扁平文本，
     * 此时 {@link #toJson(Object)} 写入的是 {@code toString()} 结果（形如
     * {@code Endpoint(host=1.2.3.4, port=80, ...)}），按字段还原。</p>
     *
     * @param text 属性列内容
     * @return 端点；无法还原返回 null
     */
    private Endpoint restoreEndpoint(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Endpoint parsed = fromJson(text, Endpoint.class);
        if (parsed != null) {
            return parsed;
        }
        if (!text.startsWith("Endpoint(")) {
            return null;
        }
        Map<String, String> fields = new java.util.HashMap<>();
        String body = text.substring("Endpoint(".length());
        if (body.endsWith(")")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String part : body.split(", ")) {
            int index = part.indexOf('=');
            if (index > 0) {
                fields.put(part.substring(0, index).trim(), part.substring(index + 1).trim());
            }
        }
        Endpoint endpoint = new Endpoint();
        endpoint.setKind(parseKind(noneToNull(fields.get("kind"))));
        endpoint.setHost(noneToNull(fields.get("host")));
        endpoint.setPort((int) parseLong(fields.get("port")));
        endpoint.setPath(noneToNull(fields.get("path")));
        endpoint.setSsl(Boolean.parseBoolean(fields.get("ssl")));
        endpoint.setInstanceId(noneToNull(fields.get("instanceId")));
        endpoint.setProtocol(parseProtocol(noneToNull(fields.get("protocol"))));
        endpoint.setSoftware(parseSoftware(noneToNull(fields.get("software"))));
        return endpoint;
    }

    /**
     * 解析端点类型枚举。
     *
     * <p>属性列里记录的是 {@code toString()} 文本，缺失时返回 {@code null} 而不是默认值，
     * 避免把「未知」伪装成某个具体类型。</p>
     *
     * @param name 枚举名
     * @return 端点类型；未知返回 null
     */
    private EndpointKind parseKind(String name) {
        if (name == null) {
            return null;
        }
        try {
            return EndpointKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 把 {@code "null"} 文本还原为 null。
     *
     * @param value 文本
     * @return 文本或 null
     */
    private static String noneToNull(String value) {
        return value == null || "null".equals(value) ? null : value;
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
        if (!JSON_READY) {
            return fallbackJson(value);
        }
        try {
            return Json.toJson(value);
        } catch (Throwable e) {
            return fallbackJson(value);
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
        if (json == null || json.isEmpty() || !JSON_READY) {
            return null;
        }
        try {
            return Json.fromJson(json, type);
        } catch (Throwable e) {
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
        if (!JSON_READY) {
            return fallbackMap(json);
        }
        try {
            Map<String, Object> raw = Json.fromJson(json);
            Map<String, String> result = Wraps.map();
            raw.forEach((k, v) -> result.put(k, v == null ? null : String.valueOf(v)));
            return result;
        } catch (Throwable e) {
            return fallbackMap(json);
        }
    }

    /**
     * 探测 JSON 能力是否可用。
     *
     * <p>{@code com.chua.common.support.lang.json.Json} 依赖第三方 Jackson；agent fat jar
     * 已打包 Jackson，正常情况下走 JSON。但宿主环境可能装配了不兼容的 JSON 提供者，
     * 因此仍先探测再降级，保证观测组件在任何环境下都不会因序列化失败而丢数据。</p>
     *
     * @return 可用返回 true
     */
    private static boolean probeJson() {
        try {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper", false, StorageConfig.class.getClassLoader());
            Json.toJson(Wraps.map());
            return true;
        } catch (Throwable e) {
            log.info("JSON 提供者不可用，属性列降级为扁平文本: " + e);
            return false;
        }
    }

    /**
     * 降级序列化：{@code k1=v1&k2=v2}，仅当键或值里出现 {@code &} / {@code =} 时整体改用 Base64。
     *
     * <p>只在 JSON 提供者不可用时使用。优先输出 {@code k1=v1&k2=v2}；一旦键或值里出现
     * {@code &} / {@code =}（例如 {@code Endpoint} 的 {@code toString()}），整体改用 Base64，
     * 保证能无损还原。</p>
     *
     * @param value 对象
     * @return 文本
     */
    private String fallbackJson(Object value) {
        StringBuilder sb = new StringBuilder();
        if (value instanceof Map) {
            boolean needEncode = false;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String k = String.valueOf(entry.getKey());
                String v = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                if (k.indexOf('&') >= 0 || k.indexOf('=') >= 0 || v.indexOf('&') >= 0 || v.indexOf('=') >= 0) {
                    needEncode = true;
                    break;
                }
            }
            if (needEncode) {
                return KV_ENCODED + encodeMap((Map<?, ?>) value);
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(entry.getKey()).append('=')
                        .append(entry.getValue() == null ? "" : entry.getValue());
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    /**
     * 降级反序列化：解析 {@link #fallbackJson(Object)} 的文本。
     *
     * @param text 文本
     * @return Map
     */
    private Map<String, String> fallbackMap(String text) {
        Map<String, String> result = Wraps.map();
        if (text.startsWith(KV_ENCODED)) {
            return decodeMap(text.substring(KV_ENCODED.length()));
        }
        for (String part : text.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int index = part.indexOf('=');
            if (index < 0) {
                result.put(part, "");
            } else {
                result.put(part.substring(0, index), part.substring(index + 1));
            }
        }
        return result;
    }

    /**
     * Base64 编码键值对。
     *
     * @param map 映射
     * @return 编码文本
     */
    private static String encodeMap(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sb.append(entry.getKey()).append('=')
                    .append(entry.getValue() == null ? "" : entry.getValue()).append('\n');
        }
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Base64 解码键值对。
     *
     * @param text 编码文本
     * @return 映射
     */
    private static Map<String, String> decodeMap(String text) {
        Map<String, String> result = Wraps.map();
        String raw;
        try {
            raw = new String(java.util.Base64.getUrlDecoder().decode(text),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return result;
        }
        for (String line : raw.split("\n")) {
            int index = line.indexOf('=');
            if (index > 0) {
                result.put(line.substring(0, index), line.substring(index + 1));
            }
        }
        return result;
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
