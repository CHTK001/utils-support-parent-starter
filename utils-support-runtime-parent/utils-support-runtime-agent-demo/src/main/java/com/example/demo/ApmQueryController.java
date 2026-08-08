package com.example.demo;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.DependencyGraphHandler;
import com.chua.runtime.apm.storage.ApmStorage;
import com.chua.runtime.apm.storage.LeakRecord;
import com.chua.runtime.apm.storage.LogRecord;
import com.chua.runtime.apm.storage.Query;
import com.chua.runtime.apm.storage.StorageManager;
import com.chua.runtime.apm.storage.TransmissionEvent;
import com.chua.runtime.protocol.DependencyEdge;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * APM 数据查询 Controller — 纯读数据库 / 内存存储。
 *
 * <p>与 {@link AgentController} 的 {@code /agent/*} 实时诊断端点区别：</p>
 * <ul>
 *   <li>{@code /agent/*} 读 Handler 内存 ring buffer（最近 100 条左右），用于快速观察</li>
 *   <li>{@code /api/*} 读 {@link ApmStorage}（持久化层，支持分页、过滤），用于页面 / 报表</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@RestController
@RequestMapping("/api/apm")
public class ApmQueryController {

    /**
     * 仪表盘 — 各类型总数 + 错误数。
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();
        ApmStorage storage = StorageManager.get();
        Map<String, Long> raw = storage.stats();
        result.put("storage", storage.name());
        result.put("counts", raw);
        // 错误数（命中）
        Query errorQuery = new Query().setErrorOnly(true).setLimit(100000);
        result.put("transmissionErrors", storage.queryTransmissions(errorQuery).size());
        result.put("leakActive", storage.queryLeaks(new Query().setLimit(100000)
                .setStartTime(0L)).stream()
                .filter(LeakRecord::isActive).count());
        return result;
    }

    /**
     * 查询传输记录（分页 + 多条件过滤）。
     *
     * @param limit  每页条数（默认 50，最大 1000）
     * @param offset 偏移（默认 0）
     * @param protocol 协议过滤
     * @param software 软件栈过滤
     * @param traceId traceId 过滤
     * @param targetHost 目标 host 模糊匹配
     * @param errorOnly 是否仅错误
     */
    @GetMapping("/transmissions")
    public Map<String, Object> transmissions(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String software,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String targetHost,
            @RequestParam(defaultValue = "false") boolean errorOnly) {

        Query q = new Query()
                .setLimit(Math.min(limit, 1000))
                .setOffset(offset)
                .setProtocol(protocol)
                .setSoftware(software)
                .setTraceId(traceId)
                .setTargetHost(targetHost)
                .setErrorOnly(errorOnly);

        List<TransmissionEvent> events = StorageManager.get().queryTransmissions(q);

        Map<String, Object> result = new HashMap<>();
        result.put("total", events.size());
        result.put("limit", q.getLimit());
        result.put("offset", q.getOffset());
        result.put("items", events.stream().map(ApmQueryController::toMap).toList());
        return result;
    }

    /**
     * 查询依赖图边。
     */
    @GetMapping("/dependencies")
    public Map<String, Object> dependencies() {
        List<DependencyEdge> edges = StorageManager.get().queryDependencies(Query.all());
        Map<String, Object> result = new HashMap<>();
        result.put("total", edges.size());
        result.put("items", edges.stream().map(ApmQueryController::dependencyToMap).toList());
        return result;
    }

    /**
     * 查询句柄泄漏。
     */
    @GetMapping("/leaks")
    public Map<String, Object> leaks(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        Query q = new Query().setLimit(Math.min(limit, 1000)).setOffset(offset);
        List<LeakRecord> records = StorageManager.get().queryLeaks(q);
        long now = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        result.put("total", records.size());
        result.put("now", now);
        result.put("items", records.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("handleId", r.getHandleId());
            m.put("kind", r.getKind());
            m.put("name", r.getName());
            m.put("thread", r.getThread());
            m.put("createdAt", r.getCreatedAt());
            m.put("closedAt", r.getClosedAt());
            m.put("active", r.isActive());
            m.put("ageMs", r.getAgeMillis(now));
            m.put("stackTrace", r.getStackTrace());
            return m;
        }).toList());
        return result;
    }

    /**
     * 查询日志。
     */
    @GetMapping("/logs")
    public Map<String, Object> logs(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String level) {
        Query q = new Query()
                .setLimit(Math.min(limit, 1000))
                .setOffset(offset);
        List<LogRecord> records = StorageManager.get().queryLogs(q);
        Map<String, Object> result = new HashMap<>();
        result.put("total", records.size());
        result.put("items", records.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("timestamp", r.getTimestamp());
            m.put("level", r.getLevel());
            m.put("logger", r.getLogger());
            m.put("className", r.getClassName());
            m.put("methodName", r.getMethodName());
            m.put("message", r.getMessage());
            m.put("traceId", r.getTraceId());
            return m;
        }).toList());
        return result;
    }

    private static Map<String, Object> toMap(TransmissionEvent e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("traceId", e.getTraceId());
        m.put("spanId", e.getSpanId());
        m.put("protocol", e.getProtocol());
        m.put("software", e.getSoftware());
        m.put("operation", e.getOperation());
        m.put("status", e.getStatus() == null ? null : e.getStatus().name());
        m.put("statusCode", e.getStatusCode());
        m.put("startTime", e.getStartTime());
        m.put("endTime", e.getEndTime());
        m.put("duration", e.getDuration());
        m.put("bytesOut", e.getBytesOut());
        m.put("bytesIn", e.getBytesIn());
        m.put("errorType", e.getErrorType());
        m.put("errorMessage", e.getErrorMessage());
        Map<String, Object> source = new HashMap<>();
        source.put("protocol", e.getSourceProtocol());
        source.put("software", e.getSourceSoftware());
        source.put("host", e.getSourceHost());
        source.put("port", e.getSourcePort());
        source.put("path", e.getSourcePath());
        m.put("source", source);
        Map<String, Object> target = new HashMap<>();
        target.put("protocol", e.getTargetProtocol());
        target.put("software", e.getTargetSoftware());
        target.put("host", e.getTargetHost());
        target.put("port", e.getTargetPort());
        target.put("path", e.getTargetPath());
        m.put("target", target);
        return m;
    }

    private static Map<String, Object> dependencyToMap(DependencyEdge edge) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", edge.getSource() == null ? null : edge.getSource().nodeId());
        m.put("target", edge.getTarget() == null ? null : edge.getTarget().nodeId());
        m.put("protocol", edge.getProtocol() == null ? null : edge.getProtocol().name());
        m.put("software", edge.getSoftware() == null ? null : edge.getSoftware().name());
        m.put("callCount", edge.getCallCount());
        m.put("totalDuration", edge.getTotalDuration());
        m.put("avgDuration", edge.avgDuration());
        m.put("errorCount", edge.getErrorCount());
        m.put("lastError", edge.getLastError());
        m.put("lastCallTime", edge.getLastCallTime());
        return m;
    }
}