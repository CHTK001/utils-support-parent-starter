package com.example.demo;

import com.chua.runtime.agent.RuntimeAgent;
import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.DependencyGraphHandler;
import com.chua.runtime.apm.handler.HandleLeakHandler;
import com.chua.runtime.apm.handler.TraceHandler;
import com.chua.runtime.apm.handler.TransmissionHandler;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.Span;
import com.chua.runtime.protocol.TransmissionRecord;
import com.chua.runtime.protocol.DependencyEdge;
import com.chua.runtime.spy.RuntimeSpy;
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
 *   <li>GET /agent/transmissions — TransmissionHandler 当前记录</li>
 *   <li>GET /agent/dependencies — DependencyGraphHandler 当前边</li>
 *   <li>GET /agent/leaks — HandleLeakHandler 当前活跃句柄</li>
 * </ul>
 *
 * <p>注意：这些端点仅在 -javaagent 启动时才有数据（Handler 由 RuntimeAgent 启动）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@RestController
public class AgentController {

    /**
     * RuntimeAgent 启动状态。
     */
    @GetMapping("/agent/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("agentStarted", RuntimeAgent.isStarted());
        result.put("traceStackDepth", RuntimeSpy.getTraceStackSize());
        result.put("currentTraceId", RuntimeSpy.getCurrentTraceId());
        result.put("currentSpanId", RuntimeSpy.getCurrentSpanId());
        return result;
    }

    /**
     * TraceHandler 当前 Span 列表。
     */
    @GetMapping("/agent/traces")
    public List<Span> traces() {
        TraceHandler handler = ApmBootstrap.getGlobalHandler(TraceHandler.class);
        if (handler == null) {
            return new ArrayList<>();
        }
        return handler.getSpans();
    }

    /**
     * TransmissionHandler 当前记录。
     */
    @GetMapping("/agent/transmissions")
    public List<TransmissionRecord> transmissions() {
        TransmissionHandler handler = ApmBootstrap.getGlobalHandler(TransmissionHandler.class);
        if (handler == null) {
            return new ArrayList<>();
        }
        return handler.getRecords();
    }

    /**
     * DependencyGraphHandler 当前边。
     */
    @GetMapping("/agent/dependencies")
    public List<DependencyEdge> dependencies() {
        DependencyGraphHandler handler = ApmBootstrap.getGlobalHandler(DependencyGraphHandler.class);
        if (handler == null) {
            return new ArrayList<>();
        }
        return handler.getEdges();
    }

    /**
     * HandleLeakHandler 当前活跃句柄。
     */
    @GetMapping("/agent/leaks")
    public Map<String, Object> leaks() {
        HandleLeakHandler handler = ApmBootstrap.getGlobalHandler(HandleLeakHandler.class);
        if (handler == null) {
            return Map.of("active", 0);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("active", handler.getActiveHandles().size());
        return result;
    }

    /**
     * 当前 MDC 内容（traceId/spanId）。
     */
    @GetMapping("/agent/mdc")
    public Map<String, String> mdc() {
        Map<String, String> result = new HashMap<>();
        result.put("traceId", RuntimeSpy.getCurrentTraceId() == null ? "" : RuntimeSpy.getCurrentTraceId());
        result.put("spanId", RuntimeSpy.getCurrentSpanId() == null ? "" : RuntimeSpy.getCurrentSpanId());
        return result;
    }
}
