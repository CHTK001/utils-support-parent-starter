package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.AgentDebugHook;
import com.chua.common.support.ai.agent.AgentHookEvent;
import com.chua.common.support.ai.agent.AgentPlanHook;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private final String feedback; // 用户对计划的修改意见（仅 resume 拒绝时非空）

    private long startTime = System.currentTimeMillis(); // 启动时间
    private int iteration = 0; // 迭代
    private int toolCallCount = 0; // toolcall数量
    private long totalInputTokens = 0; // total输入令牌
    private long totalOutputTokens = 0; // total输出令牌
    private boolean feedbackInjected = false; // 修改意见是否已注入（保证仅一次）

    public AgentDebugMiddleware(String agentId, AgentDebugHook debugHook,
                                 AgentPlanHook planHook, int planMaxTask, String feedback) {
        this.agentId = agentId;
        this.debugHook = debugHook;
        this.planHook = planHook;
        this.planMaxTask = planMaxTask;
        this.feedback = feedback;
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
        log.info("[AgentTrace][{}] ▶ Agent 运行开始", agentId);
        return next.apply(input).doOnEach(signal -> {
            if (signal.isOnError()) {
                log.error("[AgentTrace][{}] ✗ Agent 流异常: {}", agentId,
                        signal.getThrowable() != null ? signal.getThrowable().getMessage() : "unknown", signal.getThrowable());
            } else if (signal.get() != null) {
                onEvent(signal.get());
            }
        });
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, io.agentscope.core.agent.RuntimeContext ctx,
                                          io.agentscope.core.middleware.ReasoningInput input,
                                          Function<io.agentscope.core.middleware.ReasoningInput, Flux<AgentEvent>> next) {
        iteration++;
        log.info("[AgentTrace][{}] ────── 第 {} 轮 · 推理(Reasoning) ──────", agentId, iteration);
        io.agentscope.core.middleware.ReasoningInput effectiveInput = injectFeedback(ctx, input);
        return next.apply(effectiveInput).doOnEach(signal -> {
            if (!signal.isOnError() && signal.get() != null) {
                onEvent(signal.get());
            }
        });
    }

    /**
     * 在状态已激活、模型调用之前，把用户的修改意见注入上下文。
     * 此时 activateSlotForContext 已从存储重新加载状态（会覆盖 streamEvents 前的预注入），
     * 故必须在 onReasoning 内、next 调用前注入：既写入状态上下文（保证持久化与后续迭代），
     * 又保证本次模型入参可见（input.messages 与状态上下文可能是不同列表/不可变副本）。
     *
     * @param ctx   运行时上下文（含已激活的 AgentState）
     * @param input 本次推理入参
     * @return 实际用于 next 的推理入参（可能为追加反馈后的新实例）
     */
    private io.agentscope.core.middleware.ReasoningInput injectFeedback(
            io.agentscope.core.agent.RuntimeContext ctx,
            io.agentscope.core.middleware.ReasoningInput input) {
        if (feedbackInjected || feedback == null || feedback.isBlank() || ctx == null || input == null) {
            return input;
        }
        try {
            AgentState state = ctx.getAgentState();
            List<Msg> stateCtx = state != null ? state.contextMutable() : null;
            String feedbackText = "用户要求修改计划，请根据以下意见重新调整，"
                    + "然后再次调用 plan_exit 提交修订后的计划：\n" + feedback;
            Msg feedbackMsg = Msg.builder().role(MsgRole.USER)
                    .content(TextBlock.builder().text(feedbackText).build())
                    .build();
            // 1) 写入状态上下文：保证随状态持久化，并在后续迭代可见
            if (stateCtx != null) {
                stateCtx.add(feedbackMsg);
            }
            feedbackInjected = true;
            log.info("[AgentTrace][{}] ✎ 已注入用户修改意见，模型将据此重新规划", agentId);
            // 2) 保证本次模型调用可见：input.messages 与状态上下文不是同一引用时补充
            List<Msg> orig = input.messages();
            if (orig != null && orig != stateCtx) {
                try {
                    orig.add(feedbackMsg);
                } catch (UnsupportedOperationException uoe) {
                    List<Msg> copy = new ArrayList<>(orig);
                    copy.add(feedbackMsg);
                    return new io.agentscope.core.middleware.ReasoningInput(
                            copy, input.tools(), input.options());
                }
            }
        } catch (Exception e) {
            log.warn("[AgentTrace][{}] 注入修改意见失败: {}", agentId, e.getMessage());
        }
        return input;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, io.agentscope.core.agent.RuntimeContext ctx,
                                      io.agentscope.core.middleware.ActingInput input,
                                      Function<io.agentscope.core.middleware.ActingInput, Flux<AgentEvent>> next) {
        log.info("[AgentTrace][{}] ────── 第 {} 轮 · 行动/工具(Acting) ──────", agentId, iteration);
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
        if (event == null) {
            return;
        }
        trace(event);
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
     * 全量执行轨迹日志：把 thinking / 正文(命令) / 轮次 / 工具调用与结果 / 用量 /
     * 以及会导致“卡住”的边缘事件（等待确认、等待外部执行、工具被拒、超最大迭代）
     * 全部以 INFO/WARN 输出，便于定位卡死位置。
     *
     * @param event Agent 事件
     */
    private void trace(AgentEvent event) {
        try {
            if (event instanceof AgentStartEvent e) {
                log.info("[AgentTrace][{}] ▶ Agent 启动: name={}, sessionId={}", agentId, e.getName(), e.getSessionId());
            } else if (event instanceof ModelCallStartEvent) {
                log.info("[AgentTrace][{}] ↻ 模型调用开始 (第 {} 轮)", agentId, iteration);
            } else if (event instanceof ThinkingBlockStartEvent) {
                log.info("[AgentTrace][{}] ▼ 深度思考(Thinking) 开始:", agentId);
            } else if (event instanceof ThinkingBlockDeltaEvent e) {
                if (e.getDelta() != null && !e.getDelta().isEmpty()) {
                    log.info("[AgentTrace][{}][思考] {}", agentId, e.getDelta());
                }
            } else if (event instanceof ThinkingBlockEndEvent) {
                log.info("[AgentTrace][{}] ▲ 深度思考(Thinking) 结束", agentId);
            } else if (event instanceof TextBlockStartEvent) {
                log.info("[AgentTrace][{}] ▼ 模型输出(正文/命令) 开始:", agentId);
            } else if (event instanceof TextBlockDeltaEvent e) {
                if (e.getDelta() != null && !e.getDelta().isEmpty()) {
                    log.info("[AgentTrace][{}][输出] {}", agentId, e.getDelta());
                }
            } else if (event instanceof TextBlockEndEvent) {
                log.info("[AgentTrace][{}] ▲ 模型输出 结束", agentId);
            } else if (event instanceof ToolCallStartEvent e) {
                toolCallCount++;
                log.info("[AgentTrace][{}] ⚙ 调用工具 #{}: name={}, id={}",
                        agentId, toolCallCount, e.getToolCallName(), e.getToolCallId());
            } else if (event instanceof ToolCallDeltaEvent e) {
                if (e.getDelta() != null && !e.getDelta().isEmpty()) {
                    log.info("[AgentTrace][{}][工具参数][{}] {}", agentId, e.getToolCallName(), e.getDelta());
                }
            } else if (event instanceof ToolCallEndEvent e) {
                log.info("[AgentTrace][{}] ⚙ 工具调用已生成: name={}", agentId, e.getToolCallName());
            } else if (event instanceof ToolResultStartEvent e) {
                log.info("[AgentTrace][{}] ↩ 工具结果开始: name={}", agentId, e.getToolCallName());
            } else if (event instanceof ToolResultTextDeltaEvent e) {
                if (e.getDelta() != null && !e.getDelta().isEmpty()) {
                    log.info("[AgentTrace][{}][工具结果][{}] {}", agentId, e.getToolCallName(), e.getDelta());
                }
            } else if (event instanceof ToolResultEndEvent e) {
                log.info("[AgentTrace][{}] ↩ 工具结果结束: name={}, state={}",
                        agentId, e.getToolCallName(), e.getState());
            } else if (event instanceof ModelCallEndEvent e) {
                var u = e.getUsage();
                if (u != null) {
                    log.info("[AgentTrace][{}] ✓ 模型调用结束: 输入={}, 输出={}, 缓存={}",
                            agentId, u.getInputTokens(), u.getOutputTokens(), u.getCachedTokens());
                } else {
                    log.info("[AgentTrace][{}] ✓ 模型调用结束 (无 usage)", agentId);
                }
            } else if (event instanceof HintBlockEvent e) {
                log.info("[AgentTrace][{}] 💡 提示[{}]: {}", agentId, e.getHintSource(), e.getHint());
            } else if (event instanceof RequireUserConfirmEvent e) {
                log.warn("[AgentTrace][{}] ⏸ 等待【用户确认】(未确认会卡住): 工具数={}",
                        agentId, e.getToolCalls() != null ? e.getToolCalls().size() : 0);
            } else if (event instanceof RequireExternalExecutionEvent e) {
                log.warn("[AgentTrace][{}] ⏸ 等待【外部执行】(未执行会卡住): 工具数={}",
                        agentId, e.getToolCalls() != null ? e.getToolCalls().size() : 0);
            } else if (event instanceof AllToolsDeniedEvent e) {
                log.warn("[AgentTrace][{}] ✗ 工具调用全部被拒绝(AllToolsDenied): 数量={}",
                        agentId, e.getDeniedToolCalls() != null ? e.getDeniedToolCalls().size() : 0);
            } else if (event instanceof ExceedMaxItersEvent e) {
                log.warn("[AgentTrace][{}] ✗ 超过最大迭代次数(ExceedMaxIters): current={}, max={}",
                        agentId, e.getCurrentIter(), e.getMaxIters());
            } else if (event instanceof AgentEndEvent) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[AgentTrace][{}] ■ Agent 运行结束: 轮次={}, 工具调用={}, 总输入Token={}, 总输出Token={}, 耗时={}ms",
                        agentId, iteration, toolCallCount, totalInputTokens, totalOutputTokens, elapsed);
            }
        } catch (Exception ex) {
            log.debug("[AgentTrace][{}] trace 日志异常: {}", agentId, ex.getMessage());
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





