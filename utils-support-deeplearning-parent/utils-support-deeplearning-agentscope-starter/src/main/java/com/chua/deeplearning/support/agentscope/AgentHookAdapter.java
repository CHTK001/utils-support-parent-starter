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
 * 将项目 {@link AgentDebugHook} / {@link AgentPlanHook} 桥接为 AgentScope {@link Hook}。
 *
 * <p>识别 Harness PlanMode 工具名：plan_enter / plan_write / plan_exit。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AgentHookAdapter implements Hook {

    /** 计划工具名称集合 */
    /** Plan_tools */
    private static final Set<String> PLAN_TOOLS = Set.of(
            "plan_enter", "plan_write", "plan_exit");

    /** 代理标识 */
    /** AgentID */
    private final String agentId;
    /** 调试钩子 */
    private final AgentDebugHook debugHook;
    /** 计划钩子 */
    /** Plan钩子 */
    private final AgentPlanHook planHook;
    /** 计划最大任务数 */
    /** Plan最大值任务 */
    private final int planMaxTask;

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
    /** OnEvent */
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event == null) {
            return Mono.empty();
        }
        try {
            String toolName = resolveToolName(event);
            String type = resolveType(event, toolName);
            String message = resolveMessage(event, toolName);
            Map<String, Object> attrs = new HashMap<>(8);
            attrs.put("eventClass", event.getClass().getSimpleName());
            if (toolName != null) {
                attrs.put("toolName", toolName);
            }
            if (planMaxTask > 0) {
                attrs.put("planMaxTask", planMaxTask);
            }
            if (event instanceof PreActingEvent preActing) {
                ToolUseBlock use = preActing.getToolUse();
                if (use != null && use.getInput() != null) {
                    attrs.put("toolInput", use.getInput());
                    maybeEnforcePlanMaxTask(preActing, use, attrs);
                }
            }

            AgentHookEvent hookEvent = AgentHookEvent.builder()
                    .type(type)
                    .agentId(agentId)
                    .message(message)
                    .timestamp(System.currentTimeMillis())
                    .attributes(attrs)
                    .build();

            if (debugHook != null) {
                debugHook.onDebug(hookEvent);
            }
            if (planHook != null && isPlanRelated(type, toolName)) {
                planHook.onPlan(hookEvent);
            }
        } catch (Exception ignored) {
            // Hook 回调失败不影响主流程
        }
        return Mono.just(event);
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
