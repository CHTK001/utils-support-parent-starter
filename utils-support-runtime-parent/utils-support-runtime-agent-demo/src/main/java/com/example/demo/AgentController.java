package com.example.demo;

import com.chua.runtime.agent.RuntimeAgent;
import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.FileHandler;
import com.chua.runtime.apm.handler.LogEntry;
import com.chua.runtime.apm.handler.LogHandler;
import com.chua.runtime.apm.handler.NetHandler;
import com.chua.runtime.apm.handler.TraceHandler;
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
 *   <li>GET /agent/status — RuntimeAgent 是否启动</li>
 *   <li>GET /agent/traces — TraceHandler 当前 Span 列表</li>
 *   <li>GET /agent/logs — LogHandler 最近日志</li>
 *   <li>GET /agent/net — NetHandler 网络记录</li>
 *   <li>GET /agent/files — FileHandler 文件记录</li>
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
}