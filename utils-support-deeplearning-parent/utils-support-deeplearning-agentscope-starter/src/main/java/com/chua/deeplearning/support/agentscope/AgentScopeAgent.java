package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentCompressionConfig;
import com.chua.common.support.ai.agent.AgentDebugHook;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentEvent;
import com.chua.common.support.ai.agent.AgentHookEvent;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentPlanHook;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.agent.AgentRetryConfig;
import com.chua.common.support.ai.agent.AgentStreamEvent;
import com.chua.common.support.ai.agent.AgentTrace;
import com.chua.common.support.ai.agent.AgentSystemPromptBuilder;
import com.chua.common.support.ai.agent.ConfirmationDecision;
import com.chua.common.support.ai.agent.ConfirmationItem;
import com.chua.common.support.ai.agent.ImageDefinition;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.utils.StringUtils;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Flux;

/**
 * Agentscope 实现：将项目通用 {@link ChatClient} 桥接到 Agentscope Harness，
 * 支持多 Agent 编排、思考上限配置与链式调用。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AgentScopeAgent implements Agent {

    /**
     * 日志记录器
    */
    private static final Logger log = LoggerFactory.getLogger(AgentScopeAgent.class);

    /**
     * 重试相关
     */
    private int maxRetries = 0;
    /**
     * 重试基础延迟
    */
    private long retryBaseDelay = 1000;
    /**
     * 重试退避策略
    */
    private AgentRetryConfig.BackoffStrategy retryStrategy = AgentRetryConfig.BackoffStrategy.EXPONENTIAL;

    /**
     * 代理定义
    */
    private AgentDefinition definition;
    /**
     * 模式
    */
    private AgentMode mode = AgentMode.AUTO;
    /**
     * 最大工具迭代次数
    */
    private int maxToolIterations = 5;
    /**
     * 聊天客户端映射
    */
    private final Map<String, ChatClient> chatClients = new HashMap<>();
    /**
     * 子代理定义列表
    */
    private final List<AgentDefinition> subAgentDefinitions = new ArrayList<>();
    /**
     * 全局 MCP 管理器
    */
    private McpManager globalMcpManager;
    /**
     * 全局技能管理器
    */
    private SkillManager globalSkillManager;
    /**
     * 记忆配置
    */
    private MemoryConfig memoryConfig;
    /**
     * 压缩配置
    */
    private AgentCompressionConfig compressionConfig;
    /**
     * 是否启用计划
    */
    private boolean planEnabled;
    /**
     * 是否在运行开始时即进入 Plan 只读模式（首轮只读，不等待模型调用 plan_enter）
    */
    private boolean planStartActive;
    /**
     * 默认会话标识：ReActAgent 按 sessionId 维护/读取状态，进入 Plan 等操作必需
    */
    private String defaultSessionId;
    /**
     * 工作目录：文件类工具与 shell 工具以此为根，由系统设置「工作目录基路径」解析
     */
    private String workspacePath;
    /**
     * 是否打印配置
    */
    private boolean printConfig = false;
    /**
     * 是否开启调试日志
    */
    private boolean debugLogging = false;
    /**
     * 计划最大任务数
    */
    private int planMaxTask;
    /**
     * 调试钩子
    */
    private AgentDebugHook debugHook;
    /**
     * 计划钩子
    */
    private AgentPlanHook planHook;
    /**
     * 是否启用 MCP
    */
    private boolean mcpEnabled = true;

    /**
     * 注册的技能列表（名称 → 处理器）
    */
    private final Map<String, com.chua.common.support.ai.skill.SkillHandler> skills = new java.util.LinkedHashMap<>();
    private final Map<String, String> skillDescriptions = new java.util.LinkedHashMap<>(); // skilldescriptions

    /**
     * 每次 运行 注册的 模型id 追踪，用于清理
     */
    private final List<String> registeredModelIds = new ArrayList<>();

    // ==================== Agent 接口覆写 ====================

    @Override
    /**
     * Mode
    */
    public Agent mode(AgentMode mode) {
        this.mode = mode != null ? mode : AgentMode.AUTO;
        return this;
    }

    @Override
    /**
     * 对话客户端
    */
    public Agent chatClient(ChatClient chatClient) {
        this.chatClients.put(null, chatClient);
        return this;
    }

    @Override
    /**
     * 对话客户端
    */
    public Agent chatClient(String agentId, ChatClient chatClient) {
        this.chatClients.put(agentId, chatClient);
        return this;
    }

    @Override
    /**
     * mcp管理器
    */
    public Agent mcpManager(McpManager mcpManager) {
        this.globalMcpManager = mcpManager;
        return this;
    }

    @Override
    /**
     * mcp管理器
    */
    public Agent mcpManager(String agentId, McpManager mcpManager) {
        if (this.globalMcpManager == null) {
            this.globalMcpManager = mcpManager;
        }
        return this;
    }

    @Override
    /**
     * skill管理器
    */
    public Agent skillManager(SkillManager skillManager) {
        this.globalSkillManager = skillManager;
        return this;
    }

    @Override
    /**
     * skill管理器
    */
    public Agent skillManager(String agentId, SkillManager skillManager) {
        if (this.globalSkillManager == null) {
            this.globalSkillManager = skillManager;
        }
        return this;
    }

    @Override
    /**
     * subAgent
    */
    public Agent subAgent(AgentDefinition subAgent) {
        if (subAgent != null) {
            this.subAgentDefinitions.add(subAgent);
        }
        return this;
    }

    @Override
    public Agent skill(String name, String description, com.chua.common.support.ai.skill.SkillHandler handler) {
        if (name != null && handler != null) {
            this.skills.put(name, handler);
            if (description != null) { this.skillDescriptions.put(name, description); }
        }
        return this;
    }

    @Override
    /**
     * Mcp
    */
    public Agent mcp(boolean mcp) {
        this.mcpEnabled = mcp;
        return this;
    }

    @Override
    /**
     * 最大值tooliterations
    */
    public Agent maxToolIterations(int maxIterations) {
        this.maxToolIterations = maxIterations;
        return this;
    }

    @Override
    /**
     * 内存配置
    */
    public Agent memoryConfig(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
        return this;
    }

    @Override
    /**
     * compression配置
    */
    public Agent compressionConfig(AgentCompressionConfig compressionConfig) {
        this.compressionConfig = compressionConfig;
        return this;
    }

    @Override
    /**
     * compression配置
    */
    public AgentCompressionConfig compressionConfig() {
        return compressionConfig;
    }

    @Override
    /**
     * Plan
    */
    public Agent plan(boolean plan) {
        this.planEnabled = plan;
        return this;
    }

    @Override
    /**
     * 首轮即进入 Plan 只读模式
    */
    public Agent startInPlanMode(boolean active) {
        this.planStartActive = active;
        // 预进入 Plan 依赖 Plan 工具与中间件，置 true 时一并确保规划能力已注册
        if (active) {
            this.planEnabled = true;
        }
        return this;
    }

    @Override
    /**
     * 默认会话标识
     */
    public Agent sessionId(String sessionId) {
        this.defaultSessionId = sessionId;
        return this;
    }

    @Override
    /**
     * 工作目录
     */
    public Agent workspace(String workspace) {
        this.workspacePath = workspace;
        return this;
    }

    @Override
    /**
     * Plan最大值任务
    */
    public Agent planMaxTask(int planMaxTask) {
        this.planMaxTask = planMaxTask;
        return this;
    }

    @Override
    /**
     * 调试Hook
    */
    public Agent debugHook(AgentDebugHook debugHook) {
        this.debugHook = debugHook;
        return this;
    }

    @Override
    /**
     * print配置
    */
    public Agent printConfig(boolean printConfig) {
                this.printConfig = printConfig;
        if (printConfig) {
            log.info("[Agent] printConfig已弃用，请使用debug(true)启用日志调试");
        }
        return this;
    }

    @Override
    /**
     * 调试
    */
    public Agent debug(boolean debug) {
        this.debugLogging = debug;
        return this;
    }

    @Override
    /**
     * planhook
    */
    public Agent planHook(AgentPlanHook planHook) {
        this.planHook = planHook;
        return this;
    }

    @Override
    /**
     * 最大值Retries
    */
    public Agent maxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    @Override
    /**
     * 重试退避
    */
    public Agent retryBackoff(AgentRetryConfig.BackoffStrategy strategy) {
        this.retryStrategy = strategy;
        return this;
    }

    @Override
    /**
     * 重试base延迟
    */
    public Agent retryBaseDelay(long baseDelayMillis) {
        this.retryBaseDelay = baseDelayMillis;
        return this;
    }

    @Override
    /**
     * 重试配置
    */
    public Agent retryConfig(AgentRetryConfig retryConfig) {
        if (retryConfig != null) {
            this.maxRetries = retryConfig.getMaxRetries();
            this.retryStrategy = retryConfig.getBackoffStrategy();
            this.retryBaseDelay = retryConfig.getBaseDelayMillis();
        }
        return this;
    }

    @Override
    /**
     * 获取Definition
    */
    public AgentDefinition getDefinition() {
        return definition;
    }

    @Override
    /**
     * 获取subAgent
    */
    public List<AgentDefinition> getSubAgents() {
        return List.copyOf(subAgentDefinitions);
    }

    @Override
    /**
     * 运行
    */
    public AgentResponse run(String input) {
        RunPlan plan = prepareRun(input);

        AgentResponse result = null;
        HarnessAgent harnessAgent = null;

        int attempt = 0;
        int maxRetriesE = maxRetries;
        while (true) {
            attempt++;
            try {
                harnessAgent = buildHarness(plan.agentId, plan.sysPrompt,
                        plan.primaryModelId, plan.declarations, plan.effectivePlan,
                        plan.effectivePlanMaxTask, plan.effectiveDebugHook,
                        plan.effectivePlanHook, plan.effectiveMaxIters, null);

                // 复用同一个 RuntimeContext：Plan 模式需在调用模型前预先进入，
                // 使首轮即处于只读状态；call 也用同一 ctx 加载该状态
                RuntimeContext ctx = buildRuntimeContext();
                if (plan.planStartActive) {
                    harnessAgent.enterPlanMode(ctx);
                }
                Msg resultMsg = harnessAgent.call(plan.userMsg, ctx).block();
                String output = resultMsg != null ? resultMsg.getTextContent() : "";
                result = AgentResponse.builder()
                        .output(output)
                        .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                        .metadata(Map.of("attempts", attempt))
                        .build();
                break;
            } catch (Exception e) {
                log.error("[AgentScope] run执行失败 attempt={}/{}, agentId={}",
                        attempt, maxRetriesE + 1, plan.agentId, e);
                if (attempt > maxRetriesE) {
                    result = AgentResponse.builder()
                            .output("")
                            .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                            .metadata(Map.of("error", e.getMessage(), "attempts", attempt))
                            .build();
                    break;
                }
                long delay = computeDelay(attempt, maxRetriesE);
                log.debug("[AgentScope] 重试等待 {}ms (attempt={}/{})", delay, attempt + 1, maxRetriesE + 1);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                if (harnessAgent != null) {
                    try {
                        harnessAgent.close();
                    } catch (Exception ignored) {
                        log.debug("[AgentScope] HarnessAgent关闭异常: agentId={}", plan.agentId, ignored);
                    }
                }
            }
        }

        cleanupModelRegistrations();
        return result;
    }

    @Override
    /**
     * 流式运行
    */
    public Flux<AgentStreamEvent> runStream(String input) {
        final RunPlan plan;
        try {
            plan = prepareRun(input);
        } catch (Exception e) {
            log.error("[AgentScope] runStream准备失败: {}", e.getMessage(), e);
            return Flux.error(e);
        }
        return drive(plan, true);
    }

    /**
     * 待批完整工具块缓存：replyId → (toolCallId → 完整 ToolUseBlock)。
     *
     * <p>必须为 {@code static}：初始轮（runStream）与人工确认恢复轮（resumeStream）由
     * 不同的 AgentScopeAgent 实例承载（facade 每请求新建），实例字段无法跨请求保留。
     * Harness 应用 ConfirmResult 时会用回传的 ToolUseBlock <b>整体替换</b>已存储的待批块，
     * 若只回传 id（name/input 缺失），会导致：① AgentTraceMiddleware 以 null name 写入
     * ConcurrentHashMap 触发 NPE；② 工具入参丢失。故在 RequireUserConfirmEvent 缓存完整块，
     * resume 时原样回传（harness 仅 withState 翻转允许状态，其余字段保留）。
     */
    private static final Map<String, Map<String, ToolUseBlock>> PENDING_BLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Flux<AgentStreamEvent> resumeStream(String replyId, List<ConfirmationDecision> decisions) {
        return resumeStream(replyId, decisions, null);
    }

    @Override
    public Flux<AgentStreamEvent> resumeStream(String replyId, List<ConfirmationDecision> decisions,
                                              String feedback) {
        final RunPlan plan;
        try {
            // 复用 prepareRun 完成模型注册 / 系统提示词 / 规划配置；触发消息稍后替换为确认结果消息
            plan = prepareRun(null);
        } catch (Exception e) {
            log.error("[AgentScope] resumeStream准备失败: {}", e.getMessage(), e);
            return Flux.error(e);
        }
        List<ConfirmResult> confirmResults = new ArrayList<>();
        Map<String, ToolUseBlock> cached = replyId == null ? null : PENDING_BLOCKS.get(replyId);
        if (decisions != null) {
            for (ConfirmationDecision d : decisions) {
                ToolUseBlock full = cached != null && d.getToolCallId() != null
                        ? cached.get(d.getToolCallId()) : null;
                ToolUseBlock ref;
                if (full != null) {
                    // 原样回传完整待批块（id/name/input 齐全），harness 仅 withState 翻转允许状态
                    ref = full;
                } else {
                    // 缓存缺失（如后端重启后恢复）：至少保证 name 非空，
                    // 避免 AgentTraceMiddleware 对 null name 落盘 NPE
                    ref = ToolUseBlock.builder()
                            .id(d.getToolCallId())
                            .name("pending_tool")
                            .build();
                }
                confirmResults.add(new ConfirmResult(d.isConfirmed(), ref));
            }
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID, replyId);
        meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
        plan.userMsg = Msg.builder().role(MsgRole.USER).metadata(meta).build();
        // 用户修改意见：drive 在 streamEvents 前注入 Agent 上下文，模型据此重新规划
        plan.feedback = feedback;
        return drive(plan, false);
    }

    /**
     * 驱动一次 Harness 流式运行（runStream / resumeStream 共用）。
     *
     * @param plan       运行计划
     * @param initialRun 是否首轮：仅首轮按 planStartActive 预进入 Plan；恢复轮不重复进入
     * @return 流式事件 Flux
     */
    private Flux<AgentStreamEvent> drive(final RunPlan plan, boolean initialRun) {
        final long startTime = System.currentTimeMillis();
        return Flux.defer(() -> {
            final HarnessAgent harnessAgent;
            try {
                harnessAgent = buildHarness(plan.agentId, plan.sysPrompt,
                        plan.primaryModelId, plan.declarations, plan.effectivePlan,
                        plan.effectivePlanMaxTask, plan.effectiveDebugHook,
                        plan.effectivePlanHook, plan.effectiveMaxIters, plan.feedback);
            } catch (Exception e) {
                return Flux.error(e);
            }
            final long[] inputTokens = {0L};
            final long[] outputTokens = {0L};
            final long[] cachedTokens = {0L};
            // 记录每个 agent 是否已通过 delta 流式输出过正文；
            // 非流式模型适配器不产生 TextBlockDeltaEvent，需在 AgentResultEvent 补正文
            final java.util.Map<String, Boolean> textDeltaEmitted = new java.util.HashMap<>();

            // 复用同一个 RuntimeContext：Plan 模式在订阅流前预先进入，首轮即只读
            RuntimeContext ctx = buildRuntimeContext();
            if (initialRun && plan.planStartActive) {
                harnessAgent.enterPlanMode(ctx);
            }

            // 用户「需要修改」的意见由 AgentDebugMiddleware.onReasoning 在状态激活后、
            // 模型调用前注入（activateSlotForContext 会从存储重新加载状态并覆盖此前的预注入，
            // 故不能在 streamEvents 前注入）。

            return harnessAgent.streamEvents(plan.userMsg, ctx)
                    .<AgentStreamEvent>handle((event, sink) -> {
                        try {
                            String aid = plan.agentId;
                            if (event instanceof TextBlockDeltaEvent delta) {
                                if (delta.getDelta() != null && !delta.getDelta().isEmpty()) {
                                    // 正文增量实时下发（真实流式）；输出全文在 AgentResultEvent 统一记一条 trace
                                    sink.next(AgentStreamEvent.reasoningDelta(aid, delta.getDelta()));
                                    textDeltaEmitted.put(aid, Boolean.TRUE);
                                }
                            } else if (event instanceof ThinkingBlockDeltaEvent delta) {
                                if (delta.getDelta() != null && !delta.getDelta().isEmpty()) {
                                    sink.next(AgentStreamEvent.thinkingDelta(aid, delta.getDelta()));
                                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                            AgentTrace.KIND_THINKING, aid, null, delta.getDelta())));
                                }
                            } else if (event instanceof ToolCallStartEvent start) {
                                sink.next(AgentStreamEvent.builder()
                                        .type(AgentStreamEvent.Type.TOOL_START)
                                        .agentId(aid)
                                        .toolName(start.getToolCallName())
                                        .timestamp(System.currentTimeMillis())
                                        .build());
                                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_TOOL_CALL, aid, start.getToolCallName(),
                                        "调用工具: " + start.getToolCallName())));
                            } else if (event instanceof ToolCallDeltaEvent d) {
                                if (d.getDelta() != null && !d.getDelta().isEmpty()) {
                                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                            AgentTrace.KIND_TOOL_ARGS, aid, d.getToolCallName(), d.getDelta())));
                                }
                            } else if (event instanceof ToolResultTextDeltaEvent d) {
                                if (d.getDelta() != null && !d.getDelta().isEmpty()) {
                                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                            AgentTrace.KIND_TOOL_RESULT, aid, d.getToolCallName(), d.getDelta())));
                                }
                            } else if (event instanceof ModelCallStartEvent) {
                                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_TURN, aid, null, "模型调用开始")));
                            } else if (event instanceof ModelCallEndEvent end) {
                                ChatUsage usage = end.getUsage();
                                if (usage != null) {
                                    inputTokens[0] += nonNegative(usage.getInputTokens());
                                    outputTokens[0] += nonNegative(usage.getOutputTokens());
                                    cachedTokens[0] += nonNegative(usage.getCachedTokens());
                                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                            AgentTrace.KIND_USAGE, aid, null,
                                            "输入=" + usage.getInputTokens()
                                                    + ", 输出=" + usage.getOutputTokens()
                                                    + ", 缓存=" + usage.getCachedTokens())));
                                }
                            } else if (event instanceof AgentStartEvent e) {
                                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_AGENT_START, aid, null,
                                        "Agent 启动: " + e.getName())));
                            } else if (event instanceof AgentResultEvent re) {
                                // 非流式模型适配器不产生 TextBlockDeltaEvent，完整正文在结果消息里：
                                // 统一记一条“模型输出”轨迹；若此前未通过 delta 输出正文，则补一次正文供渲染
                                Msg resultMsg = re.getResult();
                                String fullText = resultMsg != null ? resultMsg.getTextContent() : "";
                                if (fullText != null && !fullText.isEmpty()) {
                                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                            AgentTrace.KIND_TEXT, aid, null, fullText)));
                                    if (!Boolean.TRUE.equals(textDeltaEmitted.get(aid))) {
                                        sink.next(AgentStreamEvent.reasoningDelta(aid, fullText));
                                        textDeltaEmitted.put(aid, Boolean.TRUE);
                                    }
                                }
                            } else if (event instanceof AgentEndEvent) {
                                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_AGENT_END, aid, null, "Agent 结束")));
                            } else if (event instanceof HintBlockEvent e) {
                                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_HINT, aid, null,
                                        "提示[" + e.getHintSource() + "]: " + e.getHint())));
                            } else if (event instanceof RequireUserConfirmEvent e) {
                                List<ConfirmationItem> confirmItems = new ArrayList<>();
                                List<ToolUseBlock> pending = e.getToolCalls();
                                if (pending != null) {
                                    // 缓存完整待批块（按 replyId 分组），resume 轮原样回传
                                    Map<String, ToolUseBlock> blockById = new LinkedHashMap<>();
                                    for (ToolUseBlock tb : pending) {
                                        if (tb.getId() != null) {
                                            blockById.put(tb.getId(), tb);
                                        }
                                        confirmItems.add(ConfirmationItem.builder()
                                                .toolCallId(tb.getId())
                                                .toolName(tb.getName())
                                                .title("等待确认：" + tb.getName())
                                                .description(describeToolInput(tb))
                                                .build());
                                    }
                                    if (e.getReplyId() != null && !blockById.isEmpty()) {
                                        PENDING_BLOCKS.put(e.getReplyId(), blockById);
                                    }
                                }
                                // 下发人工确认请求：前端渲染批准/拒绝卡片，决策回传后恢复
                                sink.next(AgentStreamEvent.confirmationRequest(
                                        aid, e.getReplyId(), confirmItems));
                                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_EDGE, aid, null,
                                        "等待【用户确认】(未确认会卡住), 工具数="
                                                + (pending != null ? pending.size() : 0))));
                            } else if (event instanceof RequireExternalExecutionEvent e) {
                                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_EDGE, aid, null,
                                        "等待【外部执行】(未执行会卡住), 工具数="
                                                + (e.getToolCalls() != null ? e.getToolCalls().size() : 0))));
                            } else if (event instanceof AllToolsDeniedEvent e) {
                                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_EDGE, aid, null,
                                        "工具调用全部被拒绝, 数量="
                                                + (e.getDeniedToolCalls() != null ? e.getDeniedToolCalls().size() : 0))));
                            } else if (event instanceof ExceedMaxItersEvent e) {
                                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_EDGE, aid, null,
                                        "超过最大迭代次数: current=" + e.getCurrentIter()
                                                + ", max=" + e.getMaxIters())));
                            } else {
                                log.info("[TraceDiag] 未匹配事件 class={}, str={}",
                                        event.getClass().getName(),
                                        String.valueOf(event).length() > 120
                                                ? String.valueOf(event).substring(0, 120)
                                                : String.valueOf(event));
                            }
                        } catch (Exception ignored) {
                            // 单事件映射异常不中断整个流
                        }
                    })
                    .concatWith(Flux.defer(() -> {
                        long duration = System.currentTimeMillis() - startTime;
                        int in = (int) inputTokens[0];
                        int out = (int) outputTokens[0];
                        AiUsage usage = AiUsage.builder()
                                .inputTokens(in)
                                .outputTokens(out)
                                .cacheTokens((int) cachedTokens[0])
                                .totalTokens(in + out)
                                .startTime(startTime)
                                .durationMillis(duration)
                                .build();
                        return Flux.just(AgentStreamEvent.builder()
                                .type(AgentStreamEvent.Type.COMPLETE)
                                .agentId(plan.agentId)
                                .usage(usage)
                                .timestamp(System.currentTimeMillis())
                                .build());
                    }))
                    .doFinally(signal -> {
                        try {
                            harnessAgent.close();
                        } catch (Exception ignored) {
                            log.debug("[AgentScope] 流式HarnessAgent关闭异常: agentId={}", plan.agentId, ignored);
                        }
                        cleanupModelRegistrations();
                    });
        });
    }

    /**
     * 准备一次运行的全部配置（模型注册、子代理声明、提示词、用户消息）。
     *
     * @param input 用户输入
     * @return 运行计划
     */
    private RunPlan prepareRun(String input) {
        ChatClient primaryClient = this.chatClients.get(null);
        if (primaryClient == null) {
            throw new IllegalStateException("未配置主 ChatClient，请先调用 .chatClient(...)");
        }

        int effectiveMaxIters = resolveMaxIters(
                this.maxToolIterations,
                definition != null ? definition.getMaxToolIterations() : 0);

        if (printConfig) {
            printArchitectureDiagram();
        }
        if (debugLogging) {
            printSystemPromptsTree();
            logArchitecture(effectiveMaxIters);
        }

        String primaryModelId = resolveModelId("primary", primaryClient);
        Model primaryModel = new ChatClientModelAdapter(primaryClient, "primary");
        try {
            ModelRegistry.register(primaryModelId, primaryModel);
        } catch (Exception ignored) {
            log.warn("[AgentScope] Model注册冲突，跳过: modelId={}", primaryModelId);
        }
        registeredModelIds.add(primaryModelId);

        String compressionModelId = null;
        if (compressionConfig != null && compressionConfig.isEnabled()
                && compressionConfig.getCompressionChatClient() != null) {
            ChatClient compressionClient = compressionConfig.getCompressionChatClient();
            compressionModelId = resolveModelId("compression", compressionClient);
            Model compressionModel = new CompressionChatClientModelAdapter(
                    compressionClient, "compression-" + (definition != null ? definition.getId() : "agent"));
            try {
                ModelRegistry.register(compressionModelId, compressionModel);
                registeredModelIds.add(compressionModelId);
            } catch (Exception e) {
                log.warn("[AgentScope] Compression模型注册冲突: modelId={}", compressionModelId);
            }
        }

        if (compressionConfig != null && compressionConfig.isEnabled()) {
            String workspace = resolveWorkspace();
            primaryModel = new CompressionAwareModel(
                    primaryModel, primaryClient, compressionConfig,
                    definition != null ? definition.getId() : "agent", workspace,
                    compressionModelId);
        }

        List<SubagentDeclaration> declarations = new ArrayList<>();
        for (int i = 0; i < subAgentDefinitions.size(); i++) {
            AgentDefinition subDef = subAgentDefinitions.get(i);
            String subId = StringUtils.defaultString(subDef.getId(), "sub-" + i);

            ChatClient subChatClient = this.chatClients.get(subId);
            String subModelId;
            if (subDef instanceof ImageDefinition imgDef) {
                subModelId = "agentscope:" + subId + ":image:" + imgDef.getImageModel();
                try {
                    ModelRegistry.register(subModelId, new ImageGenerationModel(imgDef.getImageClient(), imgDef.getImageModel()));
                } catch (Exception e) {
                    log.warn("[AgentScope] ImageModel注册冲突: modelId={}", subModelId);
                }
                registeredModelIds.add(subModelId);
            } else {
                if (subChatClient == null) {
                    subChatClient = primaryClient;
                }
                subModelId = resolveModelId(subId, subChatClient);
                try {
                    ModelRegistry.register(subModelId, new ChatClientModelAdapter(subChatClient, subId));
                } catch (Exception e) {
                    log.warn("[AgentScope] 子Agent模型注册冲突: modelId={}", subModelId);
                }
                registeredModelIds.add(subModelId);
            }

            int subMaxIters = resolveMaxIters(subDef.getMaxToolIterations(), effectiveMaxIters);

            SubagentDeclaration decl = SubagentDeclaration.builder()
                    .name(subId)
                    .description(StringUtils.defaultString(subDef.getDescription(), subDef.getName()))
                    .inlineAgentsBody(subDef.getInstruction())
                    .model(subModelId)
                    .steps(subMaxIters)
                    .workspaceMode(WorkspaceMode.SHARED)
                    .mode(SubagentDeclaration.Mode.ALL)
                    .build();
            declarations.add(decl);
        }

        boolean effectivePlan = this.planEnabled || this.planStartActive
                || (definition != null && definition.isPlanning());
        int effectivePlanMaxTask = this.planMaxTask > 0
                ? this.planMaxTask
                : (definition != null ? definition.getPlanMaxTask() : 0);
        AgentDebugHook effectiveDebugHook = this.debugHook != null
                ? this.debugHook
                : (definition != null ? definition.getDebugHook() : null);
        AgentPlanHook effectivePlanHook = this.planHook != null
                ? this.planHook
                : (definition != null ? definition.getPlanHook() : null);

        String agentId = definition != null && definition.getId() != null ? definition.getId() : "agent";
        String sysPrompt;
        if (definition != null) {
            boolean hasSubAgent = !subAgentDefinitions.isEmpty() && definition.isLeader();
            if (hasSubAgent) {
                sysPrompt = AgentSystemPromptBuilder.build(definition.getInstruction(), subAgentDefinitions);
            } else {
                sysPrompt = definition.getSystemPrompt();
            }
        } else {
            sysPrompt = "You are a helpful assistant.";
        }

        Msg.Builder userMsgBuilder = Msg.builder().role(MsgRole.USER);
        // resume 路径 input 为 null（稍后替换为确认结果消息），不构造文本块避免 NPE
        if (input != null) {
            userMsgBuilder.content(TextBlock.builder().text(input).build());
        }
        Msg userMsg = userMsgBuilder.build();

        RunPlan plan = new RunPlan();
        plan.agentId = agentId;
        plan.sysPrompt = sysPrompt;
        plan.primaryModelId = primaryModelId;
        plan.declarations = declarations;
        plan.effectivePlan = effectivePlan;
        plan.planStartActive = this.planStartActive;
        plan.effectivePlanMaxTask = effectivePlanMaxTask;
        plan.effectiveDebugHook = effectiveDebugHook;
        plan.effectivePlanHook = effectivePlanHook;
        plan.effectiveMaxIters = effectiveMaxIters;
        plan.userMsg = userMsg;
        return plan;
    }

    /**
     * 运行计划（prepareRun 产物）
     */
    private static final class RunPlan {
        /** Agent 标识 */
        private String agentId;
        /** 系统提示词 */
        private String sysPrompt;
        /** 主模型标识 */
        private String primaryModelId;
        /** 子代理声明 */
        private List<SubagentDeclaration> declarations;
        /** 是否启用规划 */
        private boolean effectivePlan;
        /** 是否首轮即进入 Plan 只读模式 */
        private boolean planStartActive;
        /** 规划最大任务 */
        private int effectivePlanMaxTask;
        /** 调试 Hook */
        private AgentDebugHook effectiveDebugHook;
        /** 规划 Hook */
        private AgentPlanHook effectivePlanHook;
        /** 最大工具迭代 */
        private int effectiveMaxIters;
        /** 用户消息 */
        private Msg userMsg;
        /** 用户修改意见（规划模式「需要修改」） */
        private String feedback;
    }

    /**
     * 非负转换
     *
     * @param v 原始值
     * @return 非负结果
     */
    private static long nonNegative(int v) {
        return v > 0 ? (long) v : 0L;
    }

    /**
     * 提取待确认工具的可读说明。
     *
     * <p>规划模式 plan_exit 的计划摘要在 input.summary，优先取它；
     * 其余工具回退为 input 的 key: value 列表。
     *
     * @param tb 待确认工具块
     * @return 可读说明
     */
    private static String describeToolInput(ToolUseBlock tb) {
        if (tb == null) {
            return "";
        }
        Map<String, Object> in = tb.getInput();
        if (in == null || in.isEmpty()) {
            return tb.getContent() != null ? tb.getContent() : "";
        }
        Object summary = in.get("summary");
        if (summary != null && !summary.toString().isBlank()) {
            return summary.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> en : in.entrySet()) {
            sb.append(en.getKey()).append(": ").append(String.valueOf(en.getValue())).append('\n');
        }
        return sb.toString().trim();
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        cleanupModelRegistrations();
        for (ChatClient client : chatClients.values()) {
            try {
                client.close();
            } catch (Exception ig) {
                log.debug("[AgentScope] ChatClient关闭异常", ig);
            }
        }
    }

    @Override
    /**
     * Definition
    */
    public Agent definition(AgentDefinition definition) {
        this.definition = definition;
        return this;
    }

    /**
     * cleanup模型registrations
    */
    private void cleanupModelRegistrations() {
        registeredModelIds.clear();
    }

    /**
     * 构建Harness
     * @param agentId Agent标识
     * @param sysPrompt sys提示符
     * @param primaryModelId primary模型标识
     * @param declarations declarations
     * @param effectivePlan effectiveplan
     * @param effectivePlanMaxTask effectiveplan最大任务
     * @param effectiveDebugHook effective调试hook
     * @param effectivePlanHook effectiveplanhook
     * @param effectiveMaxIters effective最大iters
     */
    /**
     * 构建运行时上下文，并把当前会话 ID 透传给 AgentScope。
     * 注意：{@code enterPlanMode} / {@code call} / {@code streamEvents} 等方法
     * 直接读取 {@link RuntimeContext#getSessionId()}，仅在 Builder 上设置
     * defaultSessionId 不足以让这些路径工作，因此必须将其写入上下文。
     */
    private RuntimeContext buildRuntimeContext() {
        RuntimeContext.Builder builder = RuntimeContext.builder();
        if (this.defaultSessionId != null && !this.defaultSessionId.isBlank()) {
            builder.sessionId(this.defaultSessionId);
        }
        return builder.build();
    }

    private HarnessAgent buildHarness(String agentId, String sysPrompt,
                                      String primaryModelId, List<SubagentDeclaration> declarations,
                                      boolean effectivePlan, int effectivePlanMaxTask,
                                      AgentDebugHook effectiveDebugHook,
                                      AgentPlanHook effectivePlanHook, int effectiveMaxIters,
                                      String feedback) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentId)
                .description(definition != null ? definition.getName() : "Agent")
                .sysPrompt(sysPrompt)
                .model(ModelRegistry.resolve(primaryModelId))
                .maxIters(effectiveMaxIters)
                .modelResolver(ModelRegistry::resolve);

        // 设置默认会话标识：进入 Plan / 读取 AgentState 时按 sessionId 定位，缺失会抛 NPE
        if (this.defaultSessionId != null && !this.defaultSessionId.isBlank()) {
            builder.defaultSessionId(this.defaultSessionId);
        }

        // 设置工作目录：文件类工具 / shell 工具以此为根，相对路径在此目录下解析
        if (this.workspacePath != null && !this.workspacePath.isBlank()) {
            builder.workspace(this.workspacePath);
        }

        // 工具结果截断：超大目录/文件清单（如 list_files 返回数百文件、多 MB）会撑爆
        // 下一轮模型调用的 prompt 并导致卡死。这里显式收紧阈值：单条工具结果超过
        // 8000 字符即只保留 1500 字符预览，完整内容落盘到 large_tool_results，
        // 避免无界清单进入下一轮上下文。
        builder.toolResultEviction(ToolResultEvictionConfig.builder()
                .maxResultChars(8000)
                .previewChars(1500)
                .build());

        // 跨轮恢复 pending 工具：人工确认（plan_exit）后再次调用时，agent 据此恢复待执行工具
        builder.enablePendingToolRecovery(true);

        for (SubagentDeclaration decl : declarations) {
            builder.subagent(decl);
        }

        if (effectivePlan) {
            builder.enablePlanMode(true);
        }

        // 汇总工具：技能工具 + MCP 工具，统一注册到 Toolkit
        List<McpToolDescriptor> mcpTools = new ArrayList<>();
        if (mcpEnabled && globalMcpManager != null) {
            try {
                List<McpToolDescriptor> descriptors = globalMcpManager.listAllTools();
                if (descriptors != null) {
                    mcpTools.addAll(descriptors);
                }
            } catch (Exception e) {
                log.warn("[AgentScope] 列举 MCP 工具失败: {}", e.getMessage());
            }
        }
        if (!skills.isEmpty() || !mcpTools.isEmpty()) {
            io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
            for (Map.Entry<String, com.chua.common.support.ai.skill.SkillHandler> entry : skills.entrySet()) {
                final String skillName = entry.getKey();
                final com.chua.common.support.ai.skill.SkillHandler handler = entry.getValue();
                String desc = skillDescriptions.get(skillName);
                toolkit.registerAgentTool(new SkillAgentTool(skillName, desc != null ? desc : "Skill: " + skillName, handler));
            }
            for (McpToolDescriptor descriptor : mcpTools) {
                toolkit.registerAgentTool(new McpAgentTool(descriptor, globalMcpManager));
            }
            builder.toolkit(toolkit);
        }

        // 始终注册调试中间件：输出 thinking/输出/轮次/工具全量轨迹日志（hooks 可为 null）
        {
            MiddlewareBase middleware = new AgentDebugMiddleware(
                    agentId, effectiveDebugHook, effectivePlanHook, effectivePlanMaxTask, feedback);
            builder.middleware(middleware);
            if (effectivePlan && effectivePlanHook != null && effectivePlanMaxTask > 0) {
                effectivePlanHook.onPlan(AgentHookEvent.builder()
                        .type("PLAN_CONFIG")
                        .agentId(agentId)
                        .message("planEnabled=true, planMaxTask=" + effectivePlanMaxTask)
                        .timestamp(System.currentTimeMillis())
                        .attributes(Map.of("planMaxTask", effectivePlanMaxTask, "planEnabled", true))
                        .build());
            }
        }

        return builder.build();
    }

    /**
     * compute延迟
     *
     * @param attempt 尝试
     * @param maxRetries 最大重试
     * @return compute延迟的结果
     */
    private long computeDelay(int attempt, int maxRetries) {
        AgentRetryConfig cfg = AgentRetryConfig.builder()
                .backoffStrategy(retryStrategy)
                .baseDelayMillis(retryBaseDelay)
                .maxDelayMillis(30000)
                .build();
        return cfg.calculateDelayMillis(attempt);
    }


    /**
     * 解析最大值Iters
     *
     * @param chainValue chain值
     * @param defValue def值
     * @return resolve最大iters的结果
     */
    private static int resolveMaxIters(int chainValue, int defValue) {
        int effective = chainValue != 0 ? chainValue : (defValue != 0 ? defValue : 5);
        return effective > 0 ? effective : Integer.MAX_VALUE;
    }

    /**
     * 解析模型id
     *
     * @param prefix 前缀
     * @param chatClient 对话客户端
     * @return resolve模型id的结果
     */
    private static String resolveModelId(String prefix, ChatClient chatClient) {
        String suffix = "default";
        try {
            suffix = Integer.toHexString(chatClient.hashCode());
        } catch (Exception ig) {
        }
        return "agentscope:" + prefix + ":" + suffix;
    }

    /**
     * 解析Workspace
     *
     * @return resolveWorkspace的结果
     */
    private String resolveWorkspace() {
        if (memoryConfig != null && memoryConfig.getWorkspace() != null && !memoryConfig.getWorkspace().isBlank()) {
            return memoryConfig.getWorkspace();
        }
        return System.getProperty("java.io.tmpdir", "/tmp") + "agentscope-compression";
    }

    /**
     * 记录日志Architecture
    */
    private static final String DIAGRAM_HEADER = "===== Agent Architecture Diagram =====";
    private static final String PROMPTS_TREE_HEADER = "===== System Prompts Tree ====="; // 提示符树头部
    private static final String ROUTER_NODE_LABEL = "\u53ef\u7528\u5b50 Agent"; // router节点标签

    /**
     * 打印代理架构图。
     * 当 print 配置为 true 时，随运行输出到宿主控制台。
     * 依次输出主代理标识、各子代理节点、路由节点及系统提示词树。
     */
    private void printArchitectureDiagram() {
        boolean leader = definition != null && definition.isLeader();
        String mainLabel = leader ? "Main Agent"
                : (definition != null && definition.getName() != null ? definition.getName() : "Agent");
        StringBuilder sb = new StringBuilder();
        sb.append(DIAGRAM_HEADER).append('\n');
        sb.append("[Leader] ").append(mainLabel);
        if (definition != null && definition.getId() != null) {
            sb.append(" (id=").append(definition.getId()).append(')');
        }
        sb.append('\n');
        for (ChatClient cc : chatClients.values()) {
            sb.append("  ChatClient: ").append(cc).append('\n');
        }
        sb.append("SubAgents (").append(subAgentDefinitions.size()).append("):\n");
        int i = 1;
        for (AgentDefinition sub : subAgentDefinitions) {
            String sid = sub.getId() != null ? sub.getId() : ("sub-" + i);
            String sname = sub.getName() != null ? sub.getName() : "Unnamed";
            if (sub instanceof com.chua.common.support.ai.agent.ImageDefinition img) {
                sb.append("  [").append(i).append("] ImageGenerationModel(")
                  .append(img.getImageModel()).append(") | ").append(sname).append('\n');
            } else {
                sb.append("  [").append(i).append("] ").append(sid).append(" | ").append(sname).append('\n');
            }
            i++;
        }
        log.info("\n{}", sb);
    }

    /**
     * printSystemPromptsTree。
     */
    private void printSystemPromptsTree() {
        boolean leader = definition != null && definition.isLeader();
        String mainName = definition != null && definition.getName() != null ? definition.getName() : "?";
        String mainLabel = leader ? "Main Agent" : mainName;
        boolean routerPrefix = leader && mode == AgentMode.ROUTER;
        StringBuilder sb = new StringBuilder();
        sb.append(PROMPTS_TREE_HEADER).append('\n');
        sb.append("[Main Agent] ").append(mainName).append('\n');
        if (routerPrefix) {
            sb.append("  [").append(ROUTER_NODE_LABEL)
              .append("] dispatch requests to sub agents\n");
        }
        sb.append("  - prompt: ")
          .append(definition != null && definition.getInstruction() != null ? definition.getInstruction() : "")
          .append('\n');
        for (AgentDefinition sub : subAgentDefinitions) {
            sb.append("  - ").append(sub.getName() != null ? sub.getName() : sub.getId()).append('\n');
            sb.append("      prompt: ")
              .append(sub.getInstruction() != null ? sub.getInstruction() : "").append('\n');
        }
        log.info("[AgentTree] begin\n{}\n[AgentTree] end", sb);
    }
    /**
     * logArchitecture。
     *
     * @param effectiveMaxIters effective最大值Iters，不允许为 null
     */
    private void logArchitecture(int effectiveMaxIters) {
        String mainAgentId = definition != null && definition.getId() != null ? definition.getId() : "agent";
        String mainAgentName = definition != null && definition.getName() != null ? definition.getName() : "Agent";
        log.info("[Agent] 主Agent: id={}, name={}, maxIters={}, mode={}",
                mainAgentId, mainAgentName, effectiveMaxIters, mode != null ? mode.name() : "AUTO");
        log.info("[Agent] 子Agent数量: {}", subAgentDefinitions.size());
        for (int i = 0; i < subAgentDefinitions.size(); i++) {
            AgentDefinition subDef = subAgentDefinitions.get(i);
            String subId = StringUtils.defaultString(subDef.getId(), "sub-" + i);
            int subIter = resolveMaxIters(subDef.getMaxToolIterations(), effectiveMaxIters);
            log.info("[Agent]   [{}] id={}, name={}, maxIters={}", i + 1, subId,
                    StringUtils.defaultString(subDef.getName(), "Unnamed"), subIter);
        }
        log.info("[Agent] ChatClient映射: {} 个", chatClients.size());
        log.info("[Agent] compression: {}, plan: {}, mcp: {}",
                compressionConfig != null && compressionConfig.isEnabled() ? "ON" : "OFF",
                planEnabled, mcpEnabled);
    }

    /**
     * 将 skill处理器 桥接为 Agentscope Agenttool。
     * @author CH
     * @since 4.0.0
     */
    private static class SkillAgentTool implements io.agentscope.core.tool.AgentTool {

        private final String name; // 名称
        private final String description; // description
          private final com.chua.common.support.ai.skill.SkillHandler handler; // 处理器

        SkillAgentTool(String name, String description, com.chua.common.support.ai.skill.SkillHandler handler) {
            this.name = name;
            this.description = description;
              this.handler = handler;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getDescription() { return description; }

        @Override
        public java.util.Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }

        @Override
        public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(
                io.agentscope.core.tool.ToolCallParam param) {
            try {
                Map<String, Object> input = param.getInput() != null
                        ? new HashMap<>(param.getInput()) : Map.of();
                com.chua.common.support.ai.skill.SkillResult result = handler.handle(input);
                String text = result.isSuccess()
                        ? String.valueOf(result.getContent())
                        : "ERROR: " + result.getErrorMessage();
                return reactor.core.publisher.Mono.just(
                        io.agentscope.core.message.ToolResultBlock.text(text));
            } catch (Exception e) {
                return reactor.core.publisher.Mono.just(
                        io.agentscope.core.message.ToolResultBlock.error(e.getMessage()));
            }
        }
    }

    /**
     * 将 MCP 工具描述符桥接为 Agentscope AgentTool，调用路由到 {@link McpManager}。
     */
    private static class McpAgentTool implements io.agentscope.core.tool.AgentTool {

        /**
         * 用于把非字符串 MCP 返回内容序列化为文本
         */
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        /**
         * MCP 工具描述符
         */
        private final McpToolDescriptor descriptor;
        /**
         * MCP 管理器
         */
        private final McpManager manager;

        McpAgentTool(McpToolDescriptor descriptor, McpManager manager) {
            this.descriptor = descriptor;
            this.manager = manager;
        }

        @Override
        public String getName() {
            return descriptor.getName();
        }

        @Override
        public String getDescription() {
            return descriptor.getDescription() != null
                    ? descriptor.getDescription()
                    : "MCP tool: " + descriptor.getName();
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> schema = descriptor.getInputSchema();
            if (schema != null && !schema.isEmpty()) {
                return schema;
            }
            return new HashMap<>(Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(
                io.agentscope.core.tool.ToolCallParam param) {
            try {
                Map<String, Object> input = param.getInput() != null
                        ? new HashMap<>(param.getInput()) : new HashMap<>();
                McpToolResult result = manager.callTool(
                        descriptor.getServerName(),
                        new McpToolCall(descriptor.getName(), input));
                if (result.isSuccess()) {
                    Object content = result.getContent();
                    String text;
                    if (content == null) {
                        text = "";
                    } else if (content instanceof String s) {
                        text = s;
                    } else {
                        text = MAPPER.writeValueAsString(content);
                    }
                    return reactor.core.publisher.Mono.just(
                            io.agentscope.core.message.ToolResultBlock.text(text));
                }
                return reactor.core.publisher.Mono.just(
                        io.agentscope.core.message.ToolResultBlock.error(
                                result.getErrorMessage() != null
                                        ? result.getErrorMessage()
                                        : "MCP 工具调用失败"));
            } catch (Exception e) {
                return reactor.core.publisher.Mono.just(
                        io.agentscope.core.message.ToolResultBlock.error(e.getMessage()));
            }
        }
    }
}





