package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.AgentDebugHook;
import com.chua.common.support.ai.agent.AgentHookEvent;
import com.chua.common.support.ai.agent.AgentPlanHook;
import io.agentscope.core.hook.ActingEvent;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将项目 {@link com.chua.common.support.ai.agent.AgentDebugHook} / {@link com.chua.common.support.ai.agent.AgentPlanHook} 桥接为 AgentScope {@link Hook}。
 *
 * <p>识别 Harness PlanMode 工具名：plan_enter / plan_write / plan_exit。
 * 同时自动追踪：执行轮次、工具调用次数、Token 用量累计、整体耗时。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AgentHookAdapter implements Hook {

    /** 计划工具名称集合 */
    private static final Set<String> PLAN_TOOLS = Set.of(
            "plan_enter", "plan_write", "plan_exit");

    /** Agent 标识 */
    private final String agentId;
    /** 调试钩子 */
    private final com.chua.common.support.ai.agent.AgentDebugHook debugHook;
    /** 规划钩子 */
    private final com.chua.common.support.ai.agent.AgentPlanHook planHook;
    /** 计划最大任务数 */
    private final int planMaxTask;

    /** Agent 启动时间（毫秒） */
    private long startTime = System.currentTimeMillis();
    /** 当前执行轮次（从 1 开始） */
    private int iteration = 0;
    /** 累计调用工具次数 */
    private int toolCallCount = 0;
    /** 累计输入 Token */
    private long totalInputTokens = 0;
    /** 累计输出 Token */
    private long totalOutputTokens = 0;
    /** 当前轮次工具调用次数 */
    private int currentIterationToolCalls = 0;
    /** 是否已开始首次 LLM 调用 */
    private boolean callStarted = false;

    /**
     * 创建 AgentHookAdapter 实例
     * @param agentId agentId
     * @param AgentDebugHook AgentDebugHook
     * @param AgentPlanHook AgentPlanHook
     */
    public AgentHookAdapter(String agentId, AgentDebugHook debugHook, AgentPlanHook planHook) {
        this(agentId, debugHook, planHook, 0);
    }

    /**
     * 创建 AgentHookAdapter 实例
     * @param agentId agentId
     * @param debugHook debugHook
     * @param planHook planHook
     * @param planMaxTask planMaxTask
     */
    public AgentHookAdapter(String agentId, AgentDebugHook debugHook,
                            AgentPlanHook planHook, int planMaxTask) {
        this.agentId = agentId;
        this.debugHook = debugHook;
        this.planHook = planHook;
        this.planMaxTask = planMaxTask;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event == null) {
            return Mono.empty();
        }
        try {
            String toolName = resolveToolName(event);
            String type = resolveType(event, toolName);
            String message = resolveMessage(event, toolName);
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

            if (event instanceof PreCallEvent) {
                iteration++;
                currentIterationToolCalls = 0;
                callStarted = true;
                attrs.put("iteration", iteration);
            }
            if (event instanceof PreActingEvent preActing) {
                currentIterationToolCalls++;
                toolCallCount++;
                attrs.put("toolCallCount", toolCallCount);
                attrs.put("iteration", iteration);
                ToolUseBlock use = preActing.getToolUse();
                if (use != null && use.getInput() != null) {
                    attrs.put("toolInput", use.getInput());
                    maybeEnforcePlanMaxTask(preActing, use, attrs);
                }
            }
            if (event instanceof PostCallEvent postCall) {
                totalInputTokens += extractInputTokens(postCall);
                totalOutputTokens += extractOutputTokens(postCall);
                long curTotal = totalInputTokens + totalOutputTokens;
                attrs.put("totalInputTokens", totalInputTokens);
                attrs.put("totalOutputTokens", totalOutputTokens);
                attrs.put("totalTokens", curTotal);
                attrs.put("iteration", iteration);
                attrs.put("toolCallCount", toolCallCount);
            }
            if (event instanceof ErrorEvent) {
                attrs.put("iteration", iteration);
                attrs.put("toolCallCount", toolCallCount);
                attrs.put("totalTokens", totalInputTokens + totalOutputTokens);
            }

            AgentHookEvent hookEvent = AgentHookEvent.builder()
                    .type(type)
                    .agentId(agentId)
                    .message(message)
                    .timestamp(now)
                    .attributes(attrs)
                    .iteration(iteration)
                    .toolCallCount(toolCallCount)
                    .totalInputTokens(totalInputTokens)
                    .totalOutputTokens(totalOutputTokens)
                    .totalTokens(totalInputTokens + totalOutputTokens)
                    .elapsedMillis(elapsed)
                    .build();

            if (debugHook != null) {
                debugHook.onDebug(hookEvent);
            }
            if (planHook != null && isPlanRelated(type, toolName)) {
                planHook.onPlan(hookEvent);
            }
        } catch (Exception ignored) {
        }
        return Mono.just(event);
    }

    /** 从 PostCallEvent 中提取输入 Token 数 */
    private long extractInputTokens(PostCallEvent postCall) {
        try {
            var msg = postCall.getFinalMessage();
            if (msg != null && msg.getMetadata() != null) {
                Object it = msg.getMetadata().get("inputTokens");
                if (it instanceof Number n) return n.longValue();
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    /** 从 PostCallEvent 中提取输出 Token 数 */
    private long extractOutputTokens(PostCallEvent postCall) {
        try {
            var msg = postCall.getFinalMessage();
            if (msg != null && msg.getMetadata() != null) {
                Object ot = msg.getMetadata().get("outputTokens");
                if (ot instanceof Number n) return n.longValue();
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    /**
     * plan_write 时若子任务数超过 planMaxTask，在 attributes 中标记并尽量截断提示。
     */
    private void maybeEnforcePlanMaxTask(PreActingEvent preActing, ToolUseBlock use,
                                         Map<String, Object> attrs) {
        if (planMaxTask <= 0 || use == null || use.getInput() == null) {
            return;
        }
        if (!"plan_write".equalsIgnoreCase(use.getName())) {
            return;
        }
        Object tasks = use.getInput().get("tasks");
        if (tasks == null) {
            tasks = use.getInput().get("subtasks");
        }
        if (tasks == null) {
            tasks = use.getInput().get("items");
        }
        int count = -1;
        if (tasks instanceof java.util.Collection<?> col) {
            count = col.size();
        } else if (tasks instanceof Object[] arr) {
            count = arr.length;
        }
        if (count > 0) {
            attrs.put("taskCount", count);
            if (count > planMaxTask) {
                attrs.put("planMaxTaskExceeded", true);
            }
        }
    }

    /** 解析ToolName */
    private static String resolveToolName(HookEvent event) {
        if (event instanceof ActingEvent acting) {
            ToolUseBlock use = acting.getToolUse();
            return use != null ? use.getName() : null;
        }
        return null;
    }

    /** 解析Type */
    private static String resolveType(HookEvent event, String toolName) {
        if (toolName != null) {
            String lower = toolName.toLowerCase(Locale.ROOT);
            if ("plan_enter".equals(lower)) {
                return event instanceof PostActingEvent ? "PLAN_ENTER_DONE" : "PLAN_ENTER";
            }
            if ("plan_write".equals(lower)) {
                return event instanceof PostActingEvent ? "PLAN_WRITE_DONE" : "PLAN_WRITE";
            }
            if ("plan_exit".equals(lower)) {
                return event instanceof PostActingEvent ? "PLAN_EXIT_DONE" : "PLAN_EXIT";
            }
        }
        if (event instanceof PreCallEvent) {
            return "PRE_CALL";
        }
        if (event instanceof PostCallEvent) {
            return "POST_CALL";
        }
        if (event instanceof PreReasoningEvent) {
            return "PRE_REASONING";
        }
        if (event instanceof PostReasoningEvent) {
            return "POST_REASONING";
        }
        if (event instanceof PreActingEvent) {
            return "PRE_ACTING";
        }
        if (event instanceof PostActingEvent) {
            return "POST_ACTING";
        }
        if (event instanceof ErrorEvent) {
            return "ERROR";
        }
        return event.getClass().getSimpleName();
    }

    /** 解析Message */
    private static String resolveMessage(HookEvent event, String toolName) {
        if (event instanceof ErrorEvent errorEvent) {
            Throwable err = errorEvent.getError();
            return err != null ? err.getMessage() : "error";
        }
        if (toolName != null) {
            return "tool=" + toolName;
        }
        if (event instanceof PreCallEvent preCall) {
            return "inputMessages=" + (preCall.getInputMessages() != null
                    ? preCall.getInputMessages().size() : 0);
        }
        if (event instanceof PostCallEvent postCall) {
            return postCall.getFinalMessage() != null ? "hasFinalMessage" : "noFinalMessage";
        }
        return event.getClass().getSimpleName();
    }

    /** 是否PlanRelated */
    private static boolean isPlanRelated(String type, String toolName) {
        if (type != null) {
            String upper = type.toUpperCase(Locale.ROOT);
            if (upper.startsWith("PLAN_") || upper.contains("PLAN")) {
                return true;
            }
        }
        if (toolName != null) {
            return PLAN_TOOLS.contains(toolName.toLowerCase(Locale.ROOT));
        }
        return false;
    }
}
