package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.AgentDebugHook;
import com.chua.common.support.ai.agent.AgentHookEvent;
import com.chua.common.support.ai.agent.AgentPlanHook;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.*;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
    /**
    * Agent调试 middleware 类。
    * 拦截Agent推理/执行事件，统计迭代次数、工具调用次数与 token 用量，
    * 并转发给调试钩子与计划钩子。
    *
    * @author CH
    * @since 4.0.0
    */

public class AgentDebugMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentDebugMiddleware.class); // 日志
    private static final Set<String> PLAN_TOOLS = Set.of("plan_enter", "plan_write", "plan_exit"); // PLAN_TOOLS

    private final String agentId; // Agent标识
    private final AgentDebugHook debugHook; // 调试hook
    private final AgentPlanHook planHook; // planhook
    private final int planMaxTask; // plan最大任务

    private long startTime = System.currentTimeMillis(); // 启动时间
    private int iteration = 0; // 迭代
    private int toolCallCount = 0; // toolcall数量
    private long totalInputTokens = 0; // total输入令牌
    private long totalOutputTokens = 0; // total输出令牌

    public AgentDebugMiddleware(String agentId, AgentDebugHook debugHook,
                                 AgentPlanHook planHook, int planMaxTask) {
        this.agentId = agentId;
        this.debugHook = debugHook;
        this.planHook = planHook;
        this.planMaxTask = planMaxTask;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, io.agentscope.core.agent.RuntimeContext ctx,
                                     io.agentscope.core.middleware.AgentInput input,
                                     Function<io.agentscope.core.middleware.AgentInput, Flux<AgentEvent>> next) {
        iteration = 0;
        toolCallCount = 0;
        totalInputTokens = 0;
        totalOutputTokens = 0;
        startTime = System.currentTimeMillis();
        log.debug("[Middleware] onAgent called, onReasoning called, onActing called");
        return next.apply(input).doOnEach(signal -> {
            if (!signal.isOnError() && signal.get() != null) {
                onEvent(signal.get());
            }
        });
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, io.agentscope.core.agent.RuntimeContext ctx,
                                          io.agentscope.core.middleware.ReasoningInput input,
                                          Function<io.agentscope.core.middleware.ReasoningInput, Flux<AgentEvent>> next) {
        iteration++;
        log.debug("[Middleware] onReasoning called, iteration={}", iteration);
        return next.apply(input).doOnEach(signal -> {
            if (!signal.isOnError() && signal.get() != null) {
                onEvent(signal.get());
            }
        });
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, io.agentscope.core.agent.RuntimeContext ctx,
                                      io.agentscope.core.middleware.ActingInput input,
                                      Function<io.agentscope.core.middleware.ActingInput, Flux<AgentEvent>> next) {
        log.debug("[Middleware] onActing called");
        return next.apply(input).doOnEach(signal -> {
            if (!signal.isOnError() && signal.get() != null) {
                onEvent(signal.get());
            }
        });
    }

    /**
     * 响应Event。
     *
     * @param event 方法入参 event
     */
    private void onEvent(AgentEvent event) {
        log.debug("[Middleware] onEvent: {}", event != null ? event.getType() : "null");
        log.debug("[Middleware] onEvent: {}", event != null ? event.getType() : "null");
        if (event == null) {
            return;
        }
        try {
            String type = event.getType().name();
            String toolName = resolveToolName(event);
            if ("AGENT_END".equals(type)) {
                type = "POST_CALL";
            }
            Map<String, Object> attrs = new HashMap<>(16);
            attrs.put("eventClass", event.getClass().getSimpleName());
            if (toolName != null) {
                attrs.put("toolName", toolName);
            }
            if (planMaxTask > 0) {
                attrs.put("planMaxTask", planMaxTask);
            }
            long now = System.currentTimeMillis();
            long elapsed = now - startTime;
            attrs.put("elapsedMillis", elapsed);

            if (event instanceof ModelCallEndEvent endEvent) {
                var usage = endEvent.getUsage();
                if (usage != null) {
                    totalInputTokens += safeLong(usage.getInputTokens());
                    totalOutputTokens += safeLong(usage.getOutputTokens());
                }
                attrs.put("totalInputTokens", totalInputTokens);
                attrs.put("totalOutputTokens", totalOutputTokens);
                attrs.put("totalTokens", totalInputTokens + totalOutputTokens);
                attrs.put("iteration", iteration);
            }
            if (event instanceof ToolCallStartEvent) {
                toolCallCount++;
                attrs.put("toolCallCount", toolCallCount);
                attrs.put("iteration", iteration);
            }

            AgentHookEvent hookEvent = AgentHookEvent.builder()
                    .type(type)
                    .agentId(agentId)
                    .timestamp(now)
                    .attributes(attrs)
                    .iteration(iteration)
                    .toolCallCount(toolCallCount)
                    .totalTokens(totalInputTokens + totalOutputTokens)
                    .elapsedMillis(elapsed)
                    .build();

            log.debug("[Middleware] calling debugHook with type={}", hookEvent.getType());
            if (debugHook != null) {
                debugHook.onDebug(hookEvent);
            }
            if (planHook != null && isPlanRelated(type, toolName)) {
                planHook.onPlan(hookEvent);
            }
        } catch (Exception e) {
            log.debug("[AgentDebugMiddleware] event processing error: {}", e.getMessage());
        }
    }

    /**
    * 从事件中解析工具名称。
    * 当前仅支持 ToolCallStartEvent；其他事件返回 null。
    *
    * @param event Agent事件
    * @return 工具名称，无法解析时返回 null
    */
    private static String resolveToolName(AgentEvent event) {
        if (event instanceof ToolCallStartEvent start) {
            return start.getToolCallName();
        }
        return null;
    }

    /**
     * 是否PlanRelated。
     *
     * @param type 类型，不允许为 null
     * @param toolName tool名称，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    private static boolean isPlanRelated(String type, String toolName) {
        if (type != null && (type.toUpperCase(Locale.ROOT).startsWith("PLAN_") || type.contains("PLAN"))) {
            return true;
        }
        if (toolName != null && PLAN_TOOLS.contains(toolName.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return false;
    }

    private static long safeLong(int v) { return v < 0 ? 0L : (long) v; }
}





