package com.example.demo;

import com.chua.runtime.agent.RuntimeAgent;
import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.DependencyGraphHandler;
import com.chua.runtime.apm.handler.FileHandler;
import com.chua.runtime.apm.handler.HandleLeakHandler;
import com.chua.runtime.apm.handler.JedisHandler;
import com.chua.runtime.apm.handler.KafkaHandler;
import com.chua.runtime.apm.handler.LogEntry;
import com.chua.runtime.apm.handler.LogHandler;
import com.chua.runtime.apm.handler.NetHandler;
import com.chua.runtime.apm.handler.TraceHandler;
import com.chua.runtime.apm.handler.TransmissionHandler;
import com.chua.runtime.apm.handler.ZooKeeperHandler;
import com.chua.runtime.protocol.DependencyEdge;
import com.chua.runtime.protocol.TraceContextPropagator;
import com.chua.runtime.protocol.TransmissionRecord;
import com.chua.runtime.protocol.W3CTraceContext;
import com.chua.runtime.spy.RuntimeSpy;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 状态端点 — 提供 RuntimeAgent + 各 Handler 运行时数据查询。
 *
 * <p>端点：</p>
 * <ul>
 *   <li>GET /agent/status — RuntimeAgent 是否启动 + 拦截计数</li>
 *   <li>GET /agent/traces — TraceHandler 当前 Span 列表</li>
 *   <li>GET /agent/logs — LogHandler 最近日志</li>
 *   <li>GET /agent/net — NetHandler 网络记录</li>
 *   <li>GET /agent/files — FileHandler 文件记录</li>
 *   <li>GET /agent/transmissions — TransmissionHandler 传输记录</li>
 *   <li>GET /agent/dependencies — DependencyGraphHandler 依赖图边</li>
 *   <li>GET /agent/leaks — HandleLeakHandler 句柄泄漏</li>
 *   <li>GET /agent/mdc — 当前 MDC（traceId/spanId）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@RestController
public class AgentController {

    private static final Logger LOG = Logger.getLogger(AgentController.class.getName());
    @GetMapping("/agent/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("agentStarted", RuntimeAgent.isStarted());
        result.put("traceStackDepth", RuntimeSpy.getTraceStackSize());
        result.put("currentTraceId", RuntimeSpy.getCurrentTraceId());
        result.put("currentSpanId", RuntimeSpy.getCurrentSpanId());
        result.put("interceptCount", RuntimeSpy.getInterceptCount());
        return result;
    }

    @GetMapping("/agent/traces")
    public List<Map<String, Object>> traces() {
        List<Map<String, Object>> spans = new ArrayList<>();
        TraceHandler handler = ApmBootstrap.getGlobalHandler(TraceHandler.class);
        if (handler == null) {
            return spans;
        }
        for (TraceHandler.Span span : handler.getSpans()) {
            Map<String, Object> m = new HashMap<>();
            m.put("spanId", span.getSpanId());
            m.put("parentSpanId", span.getParentSpanId());
            m.put("className", span.getClassName());
            m.put("methodName", span.getMethodName());
            m.put("duration", span.getDuration());
            m.put("status", span.getStatus());
            spans.add(m);
        }
        return spans;
    }

    @GetMapping("/agent/logs")
    public List<Map<String, Object>> logs() {
        List<Map<String, Object>> result = new ArrayList<>();
        LogHandler handler = ApmBootstrap.getGlobalHandler(LogHandler.class);
        if (handler == null) {
            return result;
        }
        for (LogEntry entry : handler.tail(50)) {
            Map<String, Object> m = new HashMap<>();
            m.put("level", entry.getLevel());
            m.put("message", entry.getMessage());
            m.put("logger", entry.getLogger());
            m.put("className", entry.getClassName());
            m.put("methodName", entry.getMethodName());
            result.add(m);
        }
        return result;
    }

    @GetMapping("/agent/net")
    public List<Map<String, Object>> net() {
        List<Map<String, Object>> result = new ArrayList<>();
        NetHandler handler = ApmBootstrap.getGlobalHandler(NetHandler.class);
        if (handler == null) {
            return result;
        }
        for (NetHandler.NetRecord record : handler.tail(50)) {
            Map<String, Object> m = new HashMap<>();
            m.put("protocol", record.getProtocol());
            m.put("status", record.getStatus());
            m.put("className", record.getClassName());
            m.put("methodName", record.getMethodName());
            result.add(m);
        }
        return result;
    }

    @GetMapping("/agent/files")
    public List<Map<String, Object>> files() {
        List<Map<String, Object>> result = new ArrayList<>();
        FileHandler handler = ApmBootstrap.getGlobalHandler(FileHandler.class);
        if (handler == null) {
            return result;
        }
        for (FileHandler.FileRecord record : handler.tail(50)) {
            Map<String, Object> m = new HashMap<>();
            m.put("operation", record.getOperation());
            m.put("path", record.getPath());
            m.put("className", record.getClassName());
            m.put("methodName", record.getMethodName());
            result.add(m);
        }
        return result;
    }

    @GetMapping("/agent/mdc")
    public Map<String, String> mdc() {
        Map<String, String> result = new HashMap<>();
        result.put("traceId", RuntimeSpy.getCurrentTraceId() != null ? RuntimeSpy.getCurrentTraceId() : "");
        result.put("spanId", RuntimeSpy.getCurrentSpanId() != null ? RuntimeSpy.getCurrentSpanId() : "");
        return result;
    }

    /**
     * 传输记录 — Socket connect/accept/HttpURLConnection 事件。
     */
    @GetMapping("/agent/transmissions")
    public List<Map<String, Object>> transmissions() {
        List<Map<String, Object>> result = new ArrayList<>();
        TransmissionHandler handler = ApmBootstrap.getGlobalHandler(TransmissionHandler.class);
        if (handler == null) {
            return result;
        }
        for (TransmissionRecord record : handler.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("traceId", record.getTraceId());
            m.put("spanId", record.getSpanId());
            m.put("source", record.getSource() == null ? null : record.getSource().nodeId());
            m.put("target", record.getTarget() == null ? null : record.getTarget().nodeId());
            m.put("protocol", record.getProtocol() == null ? null : record.getProtocol().name());
            m.put("software", record.getSoftware() == null ? null : record.getSoftware().name());
            m.put("operation", record.getOperation());
            m.put("status", record.getStatus() == null ? null : record.getStatus().name());
            m.put("statusCode", record.getStatusCode());
            m.put("duration", record.getDuration());
            m.put("bytesOut", record.getBytesOut());
            m.put("bytesIn", record.getBytesIn());
            m.put("errorType", record.getErrorType());
            m.put("errorMessage", record.getErrorMessage());
            result.add(m);
        }
        return result;
    }

    /**
     * 依赖图边 — source → target 聚合边。
     */
    @GetMapping("/agent/dependencies")
    public List<Map<String, Object>> dependencies() {
        List<Map<String, Object>> result = new ArrayList<>();
        DependencyGraphHandler handler = ApmBootstrap.getGlobalHandler(DependencyGraphHandler.class);
        if (handler == null) {
            return result;
        }
        for (DependencyEdge edge : handler.getEdges()) {
            Map<String, Object> m = new HashMap<>();
            m.put("source", edge.getSource() == null ? null : edge.getSource().nodeId());
            m.put("target", edge.getTarget() == null ? null : edge.getTarget().nodeId());
            m.put("protocol", edge.getProtocol() == null ? null : edge.getProtocol().name());
            m.put("software", edge.getSoftware() == null ? null : edge.getSoftware().name());
            m.put("callCount", edge.getCallCount());
            m.put("totalDuration", edge.getTotalDuration());
            m.put("errorCount", edge.getErrorCount());
            m.put("avgDuration", edge.avgDuration());
            result.add(m);
        }
        return result;
    }

    /**
     * 句柄泄漏 — 当前活跃未释放的句柄数。
     */
    @GetMapping("/agent/leaks")
    public Map<String, Object> leaks() {
        Map<String, Object> result = new HashMap<>();
        HandleLeakHandler handler = ApmBootstrap.getGlobalHandler(HandleLeakHandler.class);
        if (handler == null) {
            result.put("active", 0);
            result.put("leaks", java.util.Collections.emptyList());
            return result;
        }
        Map<String, HandleLeakHandler.HandleRecord> handles = handler.getHandles();
        long thresholdMs = Long.parseLong(System.getProperty("leak.threshold.ms", "1000"));
        result.put("active", handles.size());
        result.put("threshold", thresholdMs);
        List<Map<String, Object>> leakList = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, HandleLeakHandler.HandleRecord> e : handles.entrySet()) {
            long ageMs = now - e.getValue().getCreatedAt();
            if (ageMs >= thresholdMs) {
                Map<String, Object> leak = new HashMap<>();
                leak.put("handleId", e.getKey());
                leak.put("kind", e.getValue().getKind() == null ? null : e.getValue().getKind().name());
                leak.put("name", e.getValue().getName());
                leak.put("thread", e.getValue().getOwnerThread());
                leak.put("age", ageMs);
                StackTraceElement[] st = e.getValue().getStackTrace();
                List<Map<String, String>> stackFrames = new ArrayList<>();
                if (st != null) {
                    for (StackTraceElement frame : st) {
                        Map<String, String> f = new HashMap<>();
                        f.put("class", frame.getClassName());
                        f.put("method", frame.getMethodName());
                        f.put("file", frame.getFileName());
                        f.put("line", String.valueOf(frame.getLineNumber()));
                        stackFrames.add(f);
                    }
                }
                leak.put("stackTrace", stackFrames);
                leakList.add(leak);
            }
        }
        result.put("leaks", leakList);
        return result;
    }

    /**
     * 应用层 Handler — ZooKeeper 调用记录。
     */
    @GetMapping("/agent/zk")
    public List<Map<String, Object>> zk() {
        List<Map<String, Object>> result = new ArrayList<>();
        ZooKeeperHandler handler = ApmBootstrap.getGlobalHandler(ZooKeeperHandler.class);
        if (handler == null) {
            return result;
        }
        for (TransmissionRecord record : handler.getRecords()) {
            result.add(toTransmissionMap(record));
        }
        return result;
    }

    /**
     * 应用层 Handler — Jedis 调用记录。
     */
    @GetMapping("/agent/jedis")
    public List<Map<String, Object>> jedis() {
        List<Map<String, Object>> result = new ArrayList<>();
        JedisHandler handler = ApmBootstrap.getGlobalHandler(JedisHandler.class);
        if (handler == null) {
            return result;
        }
        for (TransmissionRecord record : handler.getRecords()) {
            result.add(toTransmissionMap(record));
        }
        return result;
    }

    /**
     * 应用层 Handler — Kafka 调用记录。
     */
    @GetMapping("/agent/kafka")
    public List<Map<String, Object>> kafka() {
        List<Map<String, Object>> result = new ArrayList<>();
        KafkaHandler handler = ApmBootstrap.getGlobalHandler(KafkaHandler.class);
        if (handler == null) {
            return result;
        }
        for (TransmissionRecord record : handler.getRecords()) {
            result.add(toTransmissionMap(record));
        }
        return result;
    }

    /**
     * 把 TransmissionRecord 序列化为 Map。
     */
    private Map<String, Object> toTransmissionMap(TransmissionRecord record) {
        Map<String, Object> m = new HashMap<>();
        m.put("traceId", record.getTraceId());
        m.put("spanId", record.getSpanId());
        m.put("source", record.getSource() == null ? null : record.getSource().nodeId());
        m.put("target", record.getTarget() == null ? null : record.getTarget().nodeId());
        m.put("protocol", record.getProtocol() == null ? null : record.getProtocol().name());
        m.put("software", record.getSoftware() == null ? null : record.getSoftware().name());
        m.put("operation", record.getOperation());
        m.put("status", record.getStatus() == null ? null : record.getStatus().name());
        m.put("duration", record.getDuration());
        m.put("errorType", record.getErrorType());
        m.put("errorMessage", record.getErrorMessage());
        return m;
    }

    /**
     * 生成当前线程追踪上下文的 W3C traceparent header — 用于测试分布式追踪传播。
     */
    @GetMapping("/agent/traceparent")
    public Map<String, Object> traceparent() {
        Map<String, Object> result = new HashMap<>();
        String header = W3CTraceContext.inject();
        result.put("traceparent", header);
        if (header != null) {
            W3CTraceContext ctx = W3CTraceContext.extract(header);
            if (ctx != null) {
                result.put("traceId", ctx.getTraceId());
                result.put("spanId", ctx.getSpanId());
                result.put("sampled", ctx.isSampled());
            }
        }
        result.put("currentTraceId", RuntimeSpy.getCurrentTraceId());
        result.put("currentSpanId", RuntimeSpy.getCurrentSpanId());
        return result;
    }

    /**
     * 创建带 traceparent 的出站 header 集合 — 用于手动注入 HTTP 客户端。
     */
    @GetMapping("/agent/outgoing-headers")
    public Map<String, String> outgoingHeaders() {
        return TraceContextPropagator.newOutgoingHeaders();
    }
}