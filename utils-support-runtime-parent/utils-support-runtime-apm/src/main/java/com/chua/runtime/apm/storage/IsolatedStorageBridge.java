package com.chua.runtime.apm.storage;

import com.chua.runtime.protocol.DependencyEdge;
import lombok.extern.java.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 隔离加载器内存储实现的父侧桥接。
 *
 * <p><b>为什么需要桥接</b>：agent 场景下存储实现必须由隔离 ClassLoader 定义
 * （详见 {@link ApmStorageClassLoader}），而 {@code ApmStorage} 接口本身由 bootstrap
 * 定义。同一个接口在两侧各有一份，实现类无法直接转成父侧的 {@code ApmStorage}
 * （实测：{@code ClassCastException: SqliteStorage cannot be cast to ... ApmStorage}）。</p>
 *
 * <p>所以桥接只通过<b>两侧共享的 JDK 类型</b>与 <b>两侧都从同一父加载器解析到的
 * 模型类</b>交互：</p>
 * <ul>
 *   <li>入参方向：{@code Map<String,String>} / 模型类实例（{@code TransmissionEvent} 等）</li>
 *   <li>出参方向：{@code Map<String,Object>}（列表、统计）、模型类实例</li>
 *   <li>异常：隔离侧统一抛 {@link Exception}，父侧包装（避免解析隔离侧的异常类型）</li>
 * </ul>
 *
 * <p>模型类（{@code TransmissionEvent} / {@code LeakRecord} / {@code LogRecord} /
 * {@code Query} / {@code DependencyEdge}）不在 child-first 名单里，两侧都由 bootstrap
 * 定义，对象可以直接互传。</p>
 *
 * <p>本类由 bootstrap 定义，因此<b>不得</b>出现任何 {@code java.sql} / {@code javax.sql} 引用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Log
final class IsolatedStorageBridge implements ApmStorage {

    /**
     * 隔离加载器内的实现实例
     */
    private final Object delegate;

    /**
     * 构造器。
     *
     * @param delegate 隔离加载器内的存储实现
     */
    IsolatedStorageBridge(Object delegate) {
        this.delegate = delegate;
    }

    /**
     * 探测给定类是否可由本桥接包装。
     *
     * @param type 隔离加载器加载到的实现类
     * @return 可实现 {@link ApmStorage} 语义时返回 true
     */
    static boolean supports(Class<?> type) {
        return type != null;
    }

    /**
     * 是否为需要桥接的存储实现类名。
     *
     * @param className 类名
     * @return 需要桥接返回 true
     */
    static boolean isBridgeable(String className) {
        return SqliteStorage.class.getName().equals(className);
    }

    @Override
    /**
     * 启动
     */
    public void start(StorageConfig config) {
        Map<String, String> properties = new HashMap<>();
        if (config != null) {
            Map<String, String> raw = config.getProperties();
            if (raw != null) {
                properties.putAll(raw);
            }
        }
        try {
            invoke("start", new Class<?>[]{Map.class}, new Object[]{properties});
        } catch (Exception e) {
            // 观测组件不得阻断宿主：启动失败按空实现继续
            log.warning("隔离存储启动失败，降级为空实现: " + e);
        }
    }

    @Override
    /**
     * 停止
     */
    public void stop() {
        try {
            invoke("stop", new Class<?>[0], new Object[0]);
        } catch (Exception e) {
            log.warning("隔离存储关闭失败: " + e.getMessage());
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
        Map<String, Object> fields = new HashMap<>();
        fields.put("traceId", event.getTraceId());
        fields.put("spanId", event.getSpanId());
        fields.put("parentSpanId", event.getParentSpanId());
        fields.put("sourceProtocol", event.getSourceProtocol());
        fields.put("sourceSoftware", event.getSourceSoftware());
        fields.put("sourceHost", event.getSourceHost());
        fields.put("sourcePort", event.getSourcePort());
        fields.put("sourcePath", event.getSourcePath());
        fields.put("targetProtocol", event.getTargetProtocol());
        fields.put("targetSoftware", event.getTargetSoftware());
        fields.put("targetHost", event.getTargetHost());
        fields.put("targetPort", event.getTargetPort());
        fields.put("targetPath", event.getTargetPath());
        fields.put("protocol", event.getProtocol());
        fields.put("software", event.getSoftware());
        fields.put("operation", event.getOperation());
        fields.put("status", event.getStatus() == null ? null : event.getStatus().name());
        fields.put("statusCode", event.getStatusCode());
        fields.put("startTime", event.getStartTime());
        fields.put("endTime", event.getEndTime());
        fields.put("duration", event.getDuration());
        fields.put("bytesOut", event.getBytesOut());
        fields.put("bytesIn", event.getBytesIn());
        fields.put("errorType", event.getErrorType());
        fields.put("errorMessage", event.getErrorMessage());
        fields.put("attributes", event.getAttributes());
        invokeQuietly("appendTransmission", new Class<?>[]{Map.class},
                new Object[]{fields}, "写入传输记录失败");
    }

    @Override
    /**
     * 追加Dependency
     */
    public void appendDependency(DependencyEdge edge) {
        if (edge == null) {
            return;
        }
        Map<String, Object> fields = new HashMap<>();
        fields.put("source", edge.getSource());
        fields.put("target", edge.getTarget());
        fields.put("protocol", edge.getProtocol());
        fields.put("software", edge.getSoftware());
        fields.put("callCount", edge.getCallCount());
        fields.put("totalDuration", edge.getTotalDuration());
        fields.put("errorCount", edge.getErrorCount());
        fields.put("lastError", edge.getLastError());
        fields.put("lastCallTime", edge.getLastCallTime());
        invokeQuietly("appendDependency", new Class<?>[]{Map.class},
                new Object[]{fields}, "写入依赖边失败");
    }

    @Override
    /**
     * 追加Leak
     */
    public void appendLeak(LeakRecord record) {
        if (record == null) {
            return;
        }
        Map<String, Object> fields = new HashMap<>();
        fields.put("handleId", record.getHandleId());
        fields.put("kind", record.getKind());
        fields.put("name", record.getName());
        fields.put("thread", record.getThread());
        fields.put("createdAt", record.getCreatedAt());
        fields.put("closedAt", record.getClosedAt());
        fields.put("stackTrace", record.getStackTrace());
        invokeQuietly("appendLeak", new Class<?>[]{Map.class}, new Object[]{fields}, "写入泄漏记录失败");
    }

    @Override
    /**
     * 追加记录日志
     */
    public void appendLog(LogRecord record) {
        if (record == null) {
            return;
        }
        Map<String, Object> fields = new HashMap<>();
        fields.put("timestamp", record.getTimestamp());
        fields.put("level", record.getLevel());
        fields.put("logger", record.getLogger());
        fields.put("className", record.getClassName());
        fields.put("methodName", record.getMethodName());
        fields.put("message", record.getMessage());
        fields.put("traceId", record.getTraceId());
        invokeQuietly("appendLog", new Class<?>[]{Map.class}, new Object[]{fields}, "写入日志记录失败");
    }

    @Override
    /**
     * 查询Transmissions
     */
    @SuppressWarnings("unchecked")
    public List<TransmissionEvent> queryTransmissions(Query query) {
        Object raw = invokeQuery("queryTransmissions", query, "查询传输记录失败");
        return raw instanceof List ? (List<TransmissionEvent>) raw : java.util.Collections.emptyList();
    }

    @Override
    /**
     * 查询Dependencies
     */
    @SuppressWarnings("unchecked")
    public List<DependencyEdge> queryDependencies(Query query) {
        Object raw = invokeQuery("queryDependencies", query, "查询依赖边失败");
        return raw instanceof List ? (List<DependencyEdge>) raw : java.util.Collections.emptyList();
    }

    @Override
    /**
     * 查询Leaks
     */
    @SuppressWarnings("unchecked")
    public List<LeakRecord> queryLeaks(Query query) {
        Object raw = invokeQuery("queryLeaks", query, "查询泄漏记录失败");
        return raw instanceof List ? (List<LeakRecord>) raw : java.util.Collections.emptyList();
    }

    @Override
    /**
     * 查询日志
     */
    @SuppressWarnings("unchecked")
    public List<LogRecord> queryLogs(Query query) {
        Object raw = invokeQuery("queryLogs", query, "查询日志记录失败");
        return raw instanceof List ? (List<LogRecord>) raw : java.util.Collections.emptyList();
    }

    /**
     * 以 {@link Query} 为参数调用隔离侧。
     *
     * <p>{@link Query} 在隔离侧是另一份类型，所以按 getter 复制成隔离侧的实例。</p>
     *
     * @param method 方法名
     * @param query  查询条件
     * @param label  日志前缀
     * @return 返回值；失败返回 null
     */
    private Object invokeQuery(String method, Query query, String label) {
        try {
            Class<?> queryType = Class.forName(delegate.getClass().getName().replace("SqliteStorage", "Query"),
                    true, delegate.getClass().getClassLoader());
            Object target = queryType.getDeclaredConstructor().newInstance();
            if (query != null) {
                call(target, "setStartTime", java.lang.Long.class, query.getStartTime());
                call(target, "setEndTime", java.lang.Long.class, query.getEndTime());
                call(target, "setLimit", int.class, query.getLimit());
                call(target, "setOffset", int.class, query.getOffset());
                call(target, "setTraceId", String.class, query.getTraceId());
                call(target, "setSourceHost", String.class, query.getSourceHost());
                call(target, "setTargetHost", String.class, query.getTargetHost());
                call(target, "setProtocol", String.class, query.getProtocol());
                call(target, "setSoftware", String.class, query.getSoftware());
                call(target, "setStatus", String.class, query.getStatus());
                call(target, "setErrorOnly", boolean.class, query.isErrorOnly());
            }
            return invoke(method, new Class<?>[]{queryType}, new Object[]{target});
        } catch (Exception e) {
            log.warning(String.format("%s: %s", label, e.getMessage()));
            return null;
        }
    }

    /**
     * 调用一个 setter。
     *
     * @param target 目标对象
     * @param method 方法名
     * @param type   参数类型
     * @param value  参数值
     */
    private void call(Object target, String method, Class<?> type, Object value) {
        try {
            target.getClass().getMethod(method, type).invoke(target, value);
        } catch (Exception ignored) {
            // 字段在隔离侧不存在时跳过
        }
    }

    @Override
    /**
     * Stats
     */
    @SuppressWarnings("unchecked")
    public Map<String, Long> stats() {
        Object raw = invokeQuietly("stats", new Class<?>[0], new Object[0], "统计失败");
        return raw instanceof Map ? (Map<String, Long>) raw : new HashMap<>();
    }

    @Override
    /**
     * Cleanup
     */
    public long cleanup(long retentionMillis) {
        Object raw = invokeQuietly("cleanup", new Class<?>[]{long.class},
                new Object[]{retentionMillis}, "清理过期数据失败");
        return raw instanceof Number ? ((Number) raw).longValue() : 0L;
    }

    @Override
    /**
     * 名称
     */
    public String name() {
        Object raw = invokeQuietly("name", new Class<?>[0], new Object[0], "读取存储名失败");
        return raw instanceof String ? (String) raw : "isolated";
    }

    /**
     * 反射调用并写入日志。
     *
     * @param method 方法名
     * @param types  参数类型
     * @param args   参数
     * @param label  日志前缀
     * @return 返回值；失败返回 null
     */
    private Object invokeQuietly(String method, Class<?>[] types, Object[] args, String label) {
        try {
            return invoke(method, types, args);
        } catch (Exception e) {
            log.warning(String.format("%s: %s", label, e.getMessage()));
            return null;
        }
    }

    /**
     * 反射调用隔离侧实现。
     *
     * @param method 方法名
     * @param types  参数类型
     * @param args   参数
     * @return 返回值
     * @throws Exception 调用失败（含目标方法抛出的异常）
     */
    private Object invoke(String method, Class<?>[] types, Object[] args) throws Exception {
        try {
            Object result = delegate.getClass().getMethod(method, types).invoke(delegate, args);
            return result;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new Exception(cause.toString(), cause);
        }
    }
}
