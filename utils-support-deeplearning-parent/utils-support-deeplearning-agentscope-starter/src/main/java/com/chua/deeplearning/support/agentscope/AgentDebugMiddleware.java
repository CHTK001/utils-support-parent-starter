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

public class AgentDebugMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentDebugMiddleware.class);
    private static final Set<String> PLAN_TOOLS = Set.of("plan_enter", "plan_write", "plan_exit");

    private final String agentId;
    private final AgentDebugHook debugHook;
    private final AgentPlanHook planHook;
    private final int planMaxTask;

    private long startTime = System.currentTimeMillis();
    private int iteration = 0;
    private int toolCallCount = 0;
    private long totalInputTokens = 0;
    private long totalOutputTokens = 0;

    public AgentDebugMiddleware(String agentId, AgentDebugHook debugHook,
                                 AgentPlanHook planHook, int planMaxTask) {
        this.agentId = agentId;
        this.debugHook = debugHook;
        this.planHook = planHook;
        this.planMaxTask = planMaxTask;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, io.agentscope.core.middleware.AgentInput input,
                                     Function<io.agentscope.core.middleware.AgentInput, Flux<AgentEvent>> next) {
        iteration = 0;
        toolCallCount = 0;
        totalInputTokens = 0;
        totalOutputTokens = 0;
        startTime = System.currentTimeMillis();
        return next.apply(input).doOnNext(this::onEvent);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, io.agentscope.core.middleware.ReasoningInput input,
                                         Function<io.agentscope.core.middleware.ReasoningInput, Flux<AgentEvent>> next) {
        iteration++;
        return next.apply(input).doOnNext(this::onEvent);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, io.agentscope.core.middleware.ActingInput input,
                                      Function<io.agentscope.core.middleware.ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(this::onEvent);
    }

    private void onEvent(AgentEvent event) {
        System.out.println("[Middleware] onEvent: " + (event != null ? event.getType() : "null"));
        if (event == null) return;
        try {
            String type = event.getType().name();
            String toolName = resolveToolName(event);
            if ("AGENT_END".equals(type)) type = "POST_CALL";
            Map<String, Object> attrs = new HashMap<>(16);
            attrs.put("eventClass", event.getClass().getSimpleName());
            if (toolName != null) attrs.put("toolName", toolName);
            if (planMaxTask > 0) attrs.put("planMaxTask", planMaxTask);
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

            if (debugHook != null) debugHook.onDebug(hookEvent);
            if (planHook != null && isPlanRelated(type, toolName)) planHook.onPlan(hookEvent);
        } catch (Exception e) {
            log.debug("[AgentDebugMiddleware] event processing error: {}", e.getMessage());
        }
    }

    private static String resolveToolName(AgentEvent event) {
        if (event instanceof ToolCallStartEvent start) return start.getToolCallName();
        return null;
    }

    private static boolean isPlanRelated(String type, String toolName) {
        if (type != null && (type.toUpperCase(Locale.ROOT).startsWith("PLAN_") || type.contains("PLAN"))) return true;
        if (toolName != null && PLAN_TOOLS.contains(toolName.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static long safeLong(int v) { return v < 0 ? 0L : (long) v; }
}

