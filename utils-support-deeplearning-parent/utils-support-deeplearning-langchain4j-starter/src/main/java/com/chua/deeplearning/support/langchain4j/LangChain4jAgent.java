package com.chua.deeplearning.support.langchain4j;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentCompressionConfig;
import com.chua.common.support.ai.agent.AgentContextCompressionService;
import com.chua.common.support.ai.agent.AgentDebugHook;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentHookEvent;
import com.chua.common.support.ai.agent.AgentLoopGuard;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentPlanHook;
import com.chua.common.support.ai.agent.AgentQuestionAnswer;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.agent.AgentRetryConfig;
import com.chua.common.support.ai.agent.AgentStreamEvent;
import com.chua.common.support.ai.agent.AgentSystemPromptBuilder;
import com.chua.common.support.ai.agent.AgentTool;
import com.chua.common.support.ai.agent.AgentTrace;
import com.chua.common.support.ai.agent.AgentBudget;
import com.chua.common.support.ai.agent.AgentBudgetTracker;
import com.chua.common.support.ai.agent.AgentCancelledException;
import com.chua.common.support.ai.agent.AgentCancellationToken;
import com.chua.common.support.ai.agent.AgentContentGuard;
import com.chua.common.support.ai.agent.AgentContentKind;
import com.chua.common.support.ai.agent.AgentContentVerdict;
import com.chua.common.support.ai.agent.AgentEvalContext;
import com.chua.common.support.ai.agent.AgentEvalHook;
import com.chua.common.support.ai.agent.AgentEvalRecord;
import com.chua.common.support.ai.agent.AgentOrchestrator;
import com.chua.common.support.ai.agent.AgentParallelToolExecutor;
import com.chua.common.support.ai.agent.AgentPlan;
import com.chua.common.support.ai.agent.AgentPlanConfirmation;
import com.chua.common.support.ai.agent.AgentRiskAdjudicator;
import com.chua.common.support.ai.agent.AgentRiskDecision;
import com.chua.common.support.ai.agent.AgentRiskRequest;
import com.chua.common.support.ai.agent.AgentSuspendState;
import com.chua.common.support.ai.agent.AgentSuspendStore;
import com.chua.common.support.ai.agent.AgentToolSafetyPolicy;
import com.chua.common.support.ai.agent.DecisionsAgentRiskAdjudicator;
import com.chua.common.support.ai.agent.InMemoryAgentSuspendStore;
import com.chua.common.support.ai.agent.SimpleAgentBudgetTracker;
import com.chua.common.support.ai.agent.SimpleAgentOrchestrator;
import com.chua.common.support.ai.agent.ConfirmationDecision;
import com.chua.common.support.ai.agent.ConfirmationItem;
import com.chua.common.support.ai.agent.goal.AgentGoalSupport;
import com.chua.common.support.ai.agent.goal.GoalLoop;
import com.chua.common.support.ai.agent.goal.GoalStatus;
import com.chua.common.support.ai.agent.goal.GoalTracker;
import com.chua.common.support.ai.agent.goal.SessionGoal;
import com.chua.common.support.ai.agent.ToolRiskPolicy;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.DecisionsClient;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.memory.MemoryEntry;
import com.chua.common.support.ai.memory.MemoryManager;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.ai.skill.SkillHandler;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillResult;
import com.chua.common.support.collection.wrap.Wraps;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import com.chua.common.support.utils.StringUtils;

/**
 * 基于 langchain4j 实现的 ReAct Agent。
 *
 * <p>能力边界（与 {@link Agent} 接口口径一致）：
 * <ul>
 *   <li>支持：MCP 工具、Skill 工具、多轮会话历史、记忆体、上下文压缩、规划、
 *       重试、目标循环、工具结果截断、真实流式输出（模型实现
 *       {@link StreamingChatModel} 时）</li>
 *   <li>不支持：内置 shell 工具（本实现的工具装配不含 shell/命令执行类工具，
 *       {@link #shellTool(boolean)} 开关仅保留以兼容契约，不再抛异常）</li>
 *   <li>人工确认恢复：流式执行（{@link #runStream}）中对中高风险工具暂停并下发
 *       {@link AgentStreamEvent.Type#CONFIRMATION_REQUEST}，调用方经
 *       {@link #resumeStream(String, List, String)} 回传决策后恢复执行；
 *       同步 {@link #run(String)} 无事件通道，保持自动执行。
 *       结构化提问恢复 {@link #answerQuestion(String, List)} 机制可用，
 *       但本实现不产生 {@link AgentStreamEvent.Type#QUESTION_REQUEST}
 *       （无提问类工具），误用时按 ERROR 事件返回</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class LangChain4jAgent implements Agent {

    /**
     * 架构图标题
     */
    private static final String DIAGRAM_HEADER = "==================== Agent Architecture ====================";

    /**
     * 提示词树标题
     */
    private static final String PROMPTS_TREE_HEADER = "==================== System Prompts Tree ====================";

    /**
     * 硬性安全上限：{@code maxToolIterations} 为 0/负数时（语义上的“不限制”）
     * 由本上限兜底，避免真的跑成死循环
     */
    private static final int SAFETY_MAX_ITERS = 100;

    /**
     * 流式单次模型调用等待上限（秒）
     */
    private static final long STREAM_TIMEOUT_SECONDS = 600L;

    /**
     * 会话历史保留的最大消息数（仅用户/助手文本）
     */
    private static final int SESSION_HISTORY_LIMIT = 64;

    /**
     * 记忆检索返回条数
     */
    private static final int MEMORY_SEARCH_LIMIT = 5;

    /**
     * 默认规划子任务数
     */
    private static final int DEFAULT_PLAN_TASK = 5;

    /**
     * Agent 定义，包含系统提示词、最大工具调用次数等配置
     */
    private AgentDefinition definition;

    /**
     * Agent 运行模式：AUTO / TOOL / DIRECT 等
     */
    private AgentMode mode = AgentMode.AUTO;

    /**
     * 工具调用最大迭代次数，防止无限循环
     */
    private int maxToolIterations = 5;

    /**
     * 会话护栏：死循环判定 + 中断信号
     */
    private volatile AgentLoopGuard loopGuard = new AgentLoopGuard();

    /**
     * 聊天客户端集合，键 为 Agent 标识
     */
    private final Map<String, ChatClient> chatClients = Wraps.map();

    /**
     * 子 Agent 定义列表
     */
    private final List<AgentDefinition> subAgentDefinitions = Wraps.list();

    /**
     * 全局 MCP 管理器，用于动态注入 MCP 工具
     */
    private McpManager globalMcpManager;

    /**
     * 全局 Skill 管理器，用于动态注入 Skill 工具
     */
    private SkillManager globalSkillManager;

    /**
     * 是否启用 MCP 工具（关闭后退化为纯模型调用）
     */
    private boolean mcpEnabled = true;

    /**
     * langchain4j 对话模型，Agent 实际与 LLM 交互的底层模型
     */
    private ChatModel chatModel;

    /**
     * 通过 {@link #skill(String, String, SkillHandler)} 注册的本地技能
     */
    private final Map<String, SkillDefinition> localSkills = new LinkedHashMap<>();

    /**
     * 通过 {@link #tool(AgentTool)} 注册的进程内工具（名称 → 定义）
     */
    private final Map<String, AgentTool> localTools = new LinkedHashMap<>();

    /**
     * 按名称关闭的工具，下发模型前统一裁剪
     */
    private final Set<String> disabledTools = new LinkedHashSet<>();

    /**
     * 决策客户端（会话配置的决策模型）。
     *
     * <p>本实现当前没有消费决策模型的内置工具，仅保存引用以兼容
     * {@link Agent#decisionsClient(DecisionsClient)} 契约。</p>
     */
    private DecisionsClient decisionsClient;

    /**
     * 是否允许人工确认恢复：false 时 {@link #resumeStream} 直接返回 ERROR 事件
     */
    private boolean pendingToolRecoveryEnabled = true;

    /**
     * shell 工具开关：null 表示沿用默认开启语义。
     *
     * <p>本实现不装配 shell/命令执行类工具，该开关仅保留以兼容
     * {@link Agent#shellTool(boolean)} 契约。</p>
     */
    private volatile Boolean shellEnabled;

    /**
     * 暂停状态存储（外置化）：人工确认 / 提问挂起时把 {@link PendingPause} 包裹为
     * {@link AgentSuspendState} 写入该存储并携带 TTL，恢复时按 replyId 取回。
     *
     * <p>跨请求保留不再依赖 {@code static} 缓存：未显式注入时 {@link #suspendStore()}
     * 回落到进程级共享的 {@link InMemoryAgentSuspendStore#globalDefault()}（带 TTL 与
     * 条目上限），需要跨实例共享时可注入 Redis / 数据库实现。</p>
     */
    private volatile AgentSuspendStore suspendStore;

    /**
     * 预算限制：本次运行的 Token / 工具调用 / 费用 / 时长上限，null 表示不设限
     */
    private volatile AgentBudget budget;

    /**
     * 取消令牌：外部对一次运行的生命周期控制，null 按 {@link AgentCancellationToken#none()} 处理
     */
    private volatile AgentCancellationToken cancellationToken;

    /**
     * 工具并行安全策略：判定哪些工具调用可并行执行，null 按 {@link AgentToolSafetyPolicy#readOnlyDefault()} 处理
     */
    private volatile AgentToolSafetyPolicy toolSafetyPolicy;

    /**
     * 风险裁决器（显式注入）；null 时若配置了 {@link #decisionsClient} 则用决策模型兜底裁决
     */
    private volatile AgentRiskAdjudicator riskAdjudicator;

    /**
     * 内容护栏；null 时回落默认启发式护栏（{@link #contentGuard(AgentContentGuard)} 传 noop 显式关闭）
     */
    private volatile AgentContentGuard contentGuard;

    /**
     * 决策模型兜底裁决器缓存（懒创建，避免每次裁决都重建）
     */
    private volatile AgentRiskAdjudicator decisionsRiskAdjudicator;

    /**
     * 计划确认器：计划产出后先送审再执行，null 表示不送审
     */
    private volatile AgentPlanConfirmation planConfirmation;

    /**
     * 评测 / 回放钩子：run / runStream 边界回调，null 表示不启用
     */
    private volatile AgentEvalHook evalHook;

    /**
     * 多 Agent 编排器：null 按 {@link SimpleAgentOrchestrator#INSTANCE} 处理
     */
    private volatile AgentOrchestrator orchestrator;

    /**
     * 共享并行工具执行器：安全组工具并发执行用。
     *
     * <p>{@code static final}：执行器线程安全、可跨运行共享，避免每次运行重建线程池；
     * 并行度取 CPU 核数（至少 2）。</p>
     */
    private static final AgentParallelToolExecutor PARALLEL_TOOL_EXECUTOR =
            AgentParallelToolExecutor.of(Math.max(2, Runtime.getRuntime().availableProcessors()));

    /**
     * 记忆体配置
     */
    private MemoryConfig memoryConfig;

    /**
     * 懒创建的记忆管理器
     */
    private volatile MemoryManager memoryManager;

    /**
     * 上下文压缩配置
     */
    private AgentCompressionConfig compressionConfig;

    /**
     * 懒创建的上下文压缩服务
     */
    private AgentContextCompressionService compressionService;

    /**
     * 是否启用规划
     */
    private boolean planEnabled;

    /**
     * 是否首轮只规划、不执行
     */
    private boolean planStartActive;

    /**
     * 规划最大子任务数
     */
    private int planMaxTask;

    /**
     * 调试 Hook
     */
    private AgentDebugHook debugHook;

    /**
     * 规划 Hook
     */
    private AgentPlanHook planHook;

    /**
     * 最大重试次数（-1 表示无限）
     */
    private int maxRetries;

    /**
     * 重试退避策略
     */
    private AgentRetryConfig.BackoffStrategy retryStrategy = AgentRetryConfig.BackoffStrategy.EXPONENTIAL;

    /**
     * 重试基础延迟（毫秒）
     */
    // [P3C A20 豁免]
    private long retryBaseDelay = 1000L;

    /**
     * 是否已通过链式方法显式配置过重试
     */
    private boolean retryConfigured;

    /**
     * 工具结果截断阈值（0 表示不截断）
     */
    private int toolResultMaxChars;

    /**
     * 截断后保留的预览字符数
     */
    private int toolResultHeadChars;

    /**
     * 是否打印架构图
     */
    private boolean printConfigEnabled;

    /**
     * 是否打印系统提示词树
     */
    private boolean debugLogging;

    /**
     * 默认会话标识
     */
    private String defaultSessionId;

    /**
     * 工作目录
     */
    private String workspacePath;

    /**
     * 目标能力共享支撑
     */
    private final AgentGoalSupport goalSupport = new AgentGoalSupport();

    /**
     * 跨轮会话历史（仅用户/助手文本，避免工具消息结构跨轮失效）
     */
    private final List<ChatMessage> sessionHistory = Collections.synchronizedList(new ArrayList<>());

    /**
     * 设置 Agent 运行模式
     *
     * @param mode 运行模式，空 时默认使用 AUTO
     * @return 当前 Agent 实例，支持链式调用
     */
    @Override
    public Agent mode(AgentMode mode) {
        this.mode = mode != null ? mode : AgentMode.AUTO;
        return this;
    }

    /**
     * 设置默认聊天客户端
     *
     * <p>langchain4j 的主循环使用 {@link #chatModel(ChatModel)}；此处的 ChatClient
     * 作为记忆总结与上下文压缩的回退客户端。
     *
     * @param chatClient 聊天客户端实例
     * @return 当前 Agent 实例
     */
    @Override
    public Agent chatClient(ChatClient chatClient) {
        this.chatClients.put(null, chatClient);
        return this;
    }

    /**
     * 按 Agent 标识 设置聊天客户端
     *
     * @param agentId    Agent 标识
     * @param chatClient 聊天客户端实例
     * @return 当前 Agent 实例
     */
    @Override
    public Agent chatClient(String agentId, ChatClient chatClient) {
        this.chatClients.put(agentId, chatClient);
        return this;
    }

    /**
     * 设置全局 MCP 管理器
     *
     * @param mcpManager MCP 管理器实例
     * @return 当前 Agent 实例
     */
    @Override
    public Agent mcpManager(McpManager mcpManager) {
        this.globalMcpManager = mcpManager;
        return this;
    }

    /**
     * 按 Agent 标识 设置 MCP 管理器（仅首次设置生效）
     *
     * @param agentId    Agent 标识
     * @param mcpManager MCP 管理器实例
     * @return 当前 Agent 实例
     */
    @Override
    public Agent mcpManager(String agentId, McpManager mcpManager) {
        if (this.globalMcpManager == null) {
            this.globalMcpManager = mcpManager;
        }
        return this;
    }

    /**
     * 设置全局 Skill 管理器
     *
     * @param skillManager Skill 管理器实例
     * @return 当前 Agent 实例
     */
    @Override
    public Agent skillManager(SkillManager skillManager) {
        this.globalSkillManager = skillManager;
        return this;
    }

    /**
     * 按 Agent 标识 设置 Skill 管理器（仅首次设置生效）
     *
     * @param agentId      Agent 标识
     * @param skillManager Skill 管理器实例
     * @return 当前 Agent 实例
     */
    @Override
    public Agent skillManager(String agentId, SkillManager skillManager) {
        if (this.globalSkillManager == null) {
            this.globalSkillManager = skillManager;
        }
        return this;
    }

    /**
     * 注册子 Agent 定义
     *
     * @param subAgent 子 Agent 定义
     * @return 当前 Agent 实例
     */
    @Override
    public Agent subAgent(AgentDefinition subAgent) {
        if (subAgent != null) {
            this.subAgentDefinitions.add(subAgent);
        }
        return this;
    }

    /**
     * 注册自定义 Skill 工具
     *
     * @param name        Skill 名称
     * @param description Skill 描述
     * @param handler     处理器
     * @return 当前 Agent 实例
     */
    @Override
    public Agent skill(String name, String description, SkillHandler handler) {
        if (StringUtils.isBlank(name) || handler == null) {
            return this;
        }
        localSkills.put(name, new SkillDefinition(name,
                description != null ? description : name, List.of(), handler));
        return this;
    }

    /**
     * 注册进程内工具（function calling）
     *
     * <p>工具会与 MCP 工具、Skill 工具一起进入工具循环；同名时先注册者优先
     * （MCP → 进程内工具 → Skill），并可通过 {@link #disableTool(String)} 关闭。</p>
     *
     * @param tool 工具定义
     * @return 当前 Agent 实例
     */
    @Override
    public Agent tool(AgentTool tool) {
        if (tool == null) {
            return this;
        }
        localTools.put(tool.getName(), tool);
        return this;
    }

    /**
     * 已注册的进程内工具
     *
     * @return 工具定义列表
     */
    @Override
    public List<AgentTool> tools() {
        return new ArrayList<>(localTools.values());
    }

    /**
     * 按名称关闭工具
     *
     * @param toolName 工具名称
     * @return 当前 Agent 实例
     */
    @Override
    public Agent disableTool(String toolName) {
        if (StringUtils.isNotBlank(toolName)) {
            disabledTools.add(toolName.trim());
        }
        return this;
    }

    /**
     * 开启或关闭 MCP 工具
     *
     * @param mcp 是否开启
     * @return 当前 Agent 实例
     */
    @Override
    public Agent mcp(boolean mcp) {
        this.mcpEnabled = mcp;
        return this;
    }

    /**
     * 设置工具调用最大迭代次数
     *
     * @param maxIterations 最大迭代次数
     * @return 当前 Agent 实例
     */
    @Override
    public Agent maxToolIterations(int maxIterations) {
        this.maxToolIterations = maxIterations;
        return this;
    }

    /**
     * 注入会话护栏
     *
     * @param guard 会话护栏，null 时回退为新建默认护栏
     * @return 当前 Agent 实例
     */
    @Override
    public Agent loopGuard(AgentLoopGuard guard) {
        this.loopGuard = guard != null ? guard : new AgentLoopGuard();
        return this;
    }

    /**
     * 获取会话护栏
     *
     * @return 会话护栏，恒不为 null
     */
    @Override
    public AgentLoopGuard loopGuard() {
        return loopGuard;
    }

    /**
     * 要求中断本轮执行
     *
     * <p>本实现没有外部资源可释放（模型调用走 langchain4j，工具执行是方法调用），
     * 因此只需置位护栏：{@link #run(String)} 与 {@link #runStream(String)} 在循环点
     * 与每次工具调用前检测，置位后立即跳出循环并返回已产生的部分结果。
     *
     * @return 当前 Agent 实例
     */
    @Override
    public Agent interrupt() {
        loopGuard.interrupt();
        return this;
    }

    /**
     * 设置记忆配置
     *
     * @param memoryConfig 记忆配置
     * @return 当前 Agent 实例
     */
    @Override
    public Agent memoryConfig(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
        closeMemoryManager();
        return this;
    }

    /**
     * 获取记忆管理器
     *
     * @return 记忆管理器，未启用记忆时返回 null
     */
    @Override
    public MemoryManager getMemoryManager() {
        return resolveMemoryManager();
    }

    /**
     * 设置上下文压缩配置
     *
     * @param compressionConfig 压缩配置
     * @return 当前 Agent 实例
     */
    @Override
    public Agent compressionConfig(AgentCompressionConfig compressionConfig) {
        this.compressionConfig = compressionConfig;
        this.compressionService = null;
        return this;
    }

    /**
     * 获取上下文压缩配置
     *
     * @return 压缩配置，未设置时返回 null
     */
    @Override
    public AgentCompressionConfig compressionConfig() {
        if (compressionConfig != null) {
            return compressionConfig;
        }
        return definition != null ? definition.getCompressionConfig() : null;
    }

    /**
     * 开启或关闭规划模式（先规划、再执行两段式）
     *
     * @param plan 是否开启
     * @return 当前 Agent 实例
     */
    @Override
    public Agent plan(boolean plan) {
        this.planEnabled = plan;
        return this;
    }

    /**
     * 首轮只做规划、不执行工具
     *
     * @param active 是否首轮只规划
     * @return 当前 Agent 实例
     */
    @Override
    public Agent startInPlanMode(boolean active) {
        this.planStartActive = active;
        if (active) {
            this.planEnabled = true;
        }
        return this;
    }

    /**
     * 设置规划最大任务数
     *
     * @param planMaxTask 最大任务数（0 或负数使用默认值）
     * @return 当前 Agent 实例
     */
    @Override
    public Agent planMaxTask(int planMaxTask) {
        this.planMaxTask = planMaxTask;
        return this;
    }

    /**
     * 设置调试钩子
     *
     * @param debugHook 调试钩子
     * @return 当前 Agent 实例
     */
    @Override
    public Agent debugHook(AgentDebugHook debugHook) {
        this.debugHook = debugHook;
        return this;
    }

    /**
     * 设置规划钩子
     *
     * @param planHook 规划钩子
     * @return 当前 Agent 实例
     */
    @Override
    public Agent planHook(AgentPlanHook planHook) {
        this.planHook = planHook;
        return this;
    }

    /**
     * 设置最大重试次数
     *
     * @param maxRetries 最大重试次数（-1 表示无限）
     * @return 当前 Agent 实例
     */
    @Override
    public Agent maxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        this.retryConfigured = true;
        return this;
    }

    /**
     * 设置重试退避策略
     *
     * @param strategy 退避策略
     * @return 当前 Agent 实例
     */
    @Override
    public Agent retryBackoff(AgentRetryConfig.BackoffStrategy strategy) {
        if (strategy != null) {
            this.retryStrategy = strategy;
            this.retryConfigured = true;
        }
        return this;
    }

    /**
     * 设置重试基础延迟
     *
     * @param baseDelayMillis 基础延迟（毫秒）
     * @return 当前 Agent 实例
     */
    @Override
    public Agent retryBaseDelay(long baseDelayMillis) {
        this.retryBaseDelay = baseDelayMillis;
        this.retryConfigured = true;
        return this;
    }

    /**
     * 设置完整重试配置
     *
     * @param retryConfig 重试配置
     * @return 当前 Agent 实例
     */
    @Override
    public Agent retryConfig(AgentRetryConfig retryConfig) {
        if (retryConfig != null) {
            this.maxRetries = retryConfig.getMaxRetries();
            this.retryStrategy = retryConfig.getBackoffStrategy() != null
                    ? retryConfig.getBackoffStrategy()
                    : AgentRetryConfig.BackoffStrategy.EXPONENTIAL;
            this.retryBaseDelay = retryConfig.getBaseDelayMillis();
            this.retryConfigured = true;
        }
        return this;
    }

    /**
     * 设置 Agent 定义
     *
     * @param definition Agent 定义
     * @return 当前 Agent 实例
     */
    @Override
    public Agent definition(AgentDefinition definition) {
        this.definition = definition;
        return this;
    }

    /**
     * 设置单条工具结果的截断阈值
     *
     * @param maxChars  触发截断的字符数上限
     * @param headChars 截断后保留的预览字符数
     * @return 当前 Agent 实例
     */
    @Override
    public Agent toolResultEviction(int maxChars, int headChars) {
        if (maxChars < 0 || headChars < 0) {
            throw new IllegalArgumentException("maxChars/headChars 不能为负: "
                    + maxChars + "/" + headChars);
        }
        this.toolResultMaxChars = maxChars;
        this.toolResultHeadChars = headChars;
        return this;
    }

    /**
     * 默认会话标识
     *
     * @param sessionId 会话标识
     * @return 当前 Agent 实例
     */
    @Override
    public Agent sessionId(String sessionId) {
        this.defaultSessionId = sessionId;
        return this;
    }

    /**
     * 工作目录（记忆体与压缩基线落盘根目录）
     *
     * @param workspace 工作目录绝对路径
     * @return 当前 Agent 实例
     */
    @Override
    public Agent workspace(String workspace) {
        this.workspacePath = workspace;
        closeMemoryManager();
        this.compressionService = null;
        return this;
    }

    /**
     * 设置决策客户端（会话配置的决策模型）
     *
     * <p>本实现当前没有消费决策模型的内置工具，仅保存引用以兼容
     * {@link Agent#decisionsClient(DecisionsClient)} 契约；后续若引入决策类工具，
     * 可通过 {@link #getDecisionsClient()} 取用。</p>
     *
     * @param decisionsClient 决策客户端
     * @return 当前 Agent 实例
     */
    @Override
    public Agent decisionsClient(DecisionsClient decisionsClient) {
        this.decisionsClient = decisionsClient;
        return this;
    }

    /**
     * 获取决策客户端
     *
     * @return 决策客户端，未配置时为 null
     */
    public DecisionsClient getDecisionsClient() {
        return decisionsClient;
    }

    /**
     * 是否允许人工确认恢复
     *
     * <p>关闭（false）后 {@link #resumeStream} 直接返回 ERROR 事件，
     * 恢复通道整体停用。</p>
     *
     * @param enabled false 表示关闭恢复通道
     * @return 当前 Agent 实例
     */
    @Override
    public Agent pendingToolRecovery(boolean enabled) {
        this.pendingToolRecoveryEnabled = enabled;
        return this;
    }

    /**
     * 是否允许 shell 工具
     *
     * <p>本实现没有内置 shell/命令执行类工具（工具装配只含 MCP、本地与 Skill 工具），
     * 不存在可兑现的 shell 通道；本开关仅保留以兼容 {@link Agent#shellTool(boolean)} 契约，
     * 不再抛出异常。这里只保存取值（{@code null} 表示未显式设置，按默认开启语义处理），
     * 供后续如引入 shell 类工具时在装配阶段判断是否跳过。</p>
     *
     * @param enabled 是否允许 shell 工具
     * @return 当前 Agent 实例
     */
    @Override
    public Agent shellTool(boolean enabled) {
        this.shellEnabled = enabled;
        return this;
    }

    // ==================== 生产化扩展：挂起存储 / 预算 / 取消 / 并行策略 / 风险裁决 / 计划确认 / 评测 / 编排 ====================

    /**
     * 设置暂停状态存储
     *
     * @param suspendStore 暂停状态存储，null 表示移除（退回进程级默认存储）
     * @return 当前 Agent 实例
     */
    @Override
    public Agent suspendStore(AgentSuspendStore suspendStore) {
        this.suspendStore = suspendStore;
        return this;
    }

    /**
     * 获取暂停状态存储
     *
     * @return 暂停状态存储；未注入时回落进程级共享的内存默认实现（带 TTL 与条目上限）
     */
    @Override
    public AgentSuspendStore suspendStore() {
        AgentSuspendStore store = this.suspendStore;
        return store != null ? store : AgentSuspendStore.globalDefault();
    }

    /**
     * 设置预算限制
     *
     * @param budget 预算限制，null 表示不设限
     * @return 当前 Agent 实例
     */
    @Override
    public Agent budget(AgentBudget budget) {
        this.budget = budget;
        return this;
    }

    /**
     * 获取预算限制
     *
     * @return 预算限制，未设置时为 null
     */
    @Override
    public AgentBudget budget() {
        return this.budget;
    }

    /**
     * 设置取消令牌
     *
     * @param cancellationToken 取消令牌，null 等价于永不取消
     * @return 当前 Agent 实例
     */
    @Override
    public Agent cancellationToken(AgentCancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
        return this;
    }

    /**
     * 获取取消令牌
     *
     * @return 取消令牌，未设置时返回永不取消的空实现
     */
    @Override
    public AgentCancellationToken cancellationToken() {
        AgentCancellationToken token = this.cancellationToken;
        return token != null ? token : AgentCancellationToken.none();
    }

    /**
     * 设置工具安全策略
     *
     * @param toolSafetyPolicy 工具安全策略，null 表示退回只读安全默认策略
     * @return 当前 Agent 实例
     */
    @Override
    public Agent toolSafetyPolicy(AgentToolSafetyPolicy toolSafetyPolicy) {
        this.toolSafetyPolicy = toolSafetyPolicy;
        return this;
    }

    /**
     * 获取工具安全策略
     *
     * @return 工具安全策略，未设置时返回只读安全默认策略
     */
    @Override
    public AgentToolSafetyPolicy toolSafetyPolicy() {
        AgentToolSafetyPolicy policy = this.toolSafetyPolicy;
        return policy != null ? policy : AgentToolSafetyPolicy.readOnlyDefault();
    }

    /**
     * 设置风险裁决器
     *
     * @param riskAdjudicator 风险裁决器，null 表示清除显式配置
     * @return 当前 Agent 实例
     */
    @Override
    public Agent riskAdjudicator(AgentRiskAdjudicator riskAdjudicator) {
        this.riskAdjudicator = riskAdjudicator;
        return this;
    }

    /**
     * 获取风险裁决器
     *
     * <p>优先返回显式注入的裁决器；未注入且配置了 {@link DecisionsClient} 时，
     * 懒创建并复用 {@link DecisionsAgentRiskAdjudicator} 作为兜底（低风险放行、
     * 中风险交决策模型、高/危险级转人工确认）。</p>
     *
     * @return 风险裁决器；两者皆未配置时返回 null（不做风险裁决）
     */
    @Override
    public AgentRiskAdjudicator riskAdjudicator() {
        AgentRiskAdjudicator explicit = this.riskAdjudicator;
        if (explicit != null) {
            return explicit;
        }
        DecisionsClient client = this.decisionsClient;
        if (client == null) {
            return null;
        }
        AgentRiskAdjudicator fallback = this.decisionsRiskAdjudicator;
        if (fallback == null) {
            fallback = new DecisionsAgentRiskAdjudicator(client);
            this.decisionsRiskAdjudicator = fallback;
        }
        return fallback;
    }

    /**
     * 设置内容护栏；null 时回落默认启发式护栏，传 {@link AgentContentGuard#noop()} 显式关闭。
     */
    @Override
    public Agent contentGuard(AgentContentGuard contentGuard) {
        this.contentGuard = contentGuard;
        return this;
    }

    /**
     * 获取内容护栏；未设置时返回 null（运行时经 {@link #effectiveContentGuard()} 回落默认启发式护栏）。
     */
    @Override
    public AgentContentGuard contentGuard() {
        return this.contentGuard;
    }

    /**
     * 生效内容护栏：未显式设置时回落默认启发式护栏（注入检测 + 隐私脱敏 + 凭据拦截）。
     */
    private AgentContentGuard effectiveContentGuard() {
        AgentContentGuard guard = this.contentGuard;
        return guard != null ? guard : AgentContentGuard.heuristic();
    }

    /**
     * 用户输入边界检查：空白输入直接放行，其余按 USER_INPUT 检查。
     */
    private AgentContentVerdict inspectUserInput(String input) {
        if (input == null || input.isBlank()) {
            return AgentContentVerdict.allow();
        }
        return effectiveContentGuard().inspect(AgentContentKind.USER_INPUT, input);
    }

    /**
     * 模型输出边界检查：脱敏命中返回替换文本；被拒返回拦截通知文本。
     */
    private String guardModelOutput(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        AgentContentVerdict verdict = effectiveContentGuard().inspect(AgentContentKind.MODEL_OUTPUT, output);
        if (verdict.action() == AgentContentVerdict.Action.BLOCK) {
            log.warn("[LangChain4j] 模型输出被内容护栏拦截: agentId={}, findings={}",
                    agentId(), describeFindings(verdict));
            return AgentContentGuard.BLOCKED_NOTICE;
        }
        return verdict.content() != null ? verdict.content() : output;
    }

    /**
     * 工具返回边界检查（单点收口于 {@link #executeTool}）：注入命中摘除为通知文本，
     * 隐私 / 凭据命中替换为脱敏文本，其余原样返回。
     */
    private String guardToolResult(String toolName, String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        AgentContentVerdict verdict = effectiveContentGuard().inspect(AgentContentKind.TOOL_RESULT, output);
        if (verdict == null) {
            return output;
        }
        switch (verdict.action()) {
            case BLOCK:
                log.warn("[LangChain4j] 工具返回被内容护栏摘除: agentId={}, tool={}, findings={}",
                        agentId(), toolName, describeFindings(verdict));
                return AgentContentGuard.BLOCKED_NOTICE;
            case REDACT:
                return verdict.content() != null ? verdict.content() : output;
            case ALLOW:
            default:
                return output;
        }
    }

    /**
     * 汇总命中项为简短描述（类型/规则，去重限 5 条）。
     */
    private static String describeFindings(AgentContentVerdict verdict) {
        if (verdict == null || verdict.findings().isEmpty()) {
            return "unspecified";
        }
        return verdict.findings().stream()
                .map(finding -> finding.type()
                        + (finding.rule() == null || finding.rule().isBlank() ? "" : "/" + finding.rule()))
                .distinct()
                .limit(5)
                .collect(Collectors.joining(", "));
    }

    /**
     * 设置计划确认器
     *
     * @param planConfirmation 计划确认器，null 表示不送审
     * @return 当前 Agent 实例
     */
    @Override
    public Agent planConfirmation(AgentPlanConfirmation planConfirmation) {
        this.planConfirmation = planConfirmation;
        return this;
    }

    /**
     * 获取计划确认器
     *
     * @return 计划确认器，未设置时为 null
     */
    @Override
    public AgentPlanConfirmation planConfirmation() {
        return this.planConfirmation;
    }

    /**
     * 设置评测 / 回放钩子
     *
     * @param evalHook 评测钩子，null 表示不启用
     * @return 当前 Agent 实例
     */
    @Override
    public Agent evalHook(AgentEvalHook evalHook) {
        this.evalHook = evalHook;
        return this;
    }

    /**
     * 获取评测 / 回放钩子
     *
     * @return 评测钩子，未设置时为 null
     */
    @Override
    public AgentEvalHook evalHook() {
        return this.evalHook;
    }

    /**
     * 设置多 Agent 编排器
     *
     * @param orchestrator 编排器，null 表示退回默认实现
     * @return 当前 Agent 实例
     */
    @Override
    public Agent orchestrator(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
        return this;
    }

    /**
     * 获取多 Agent 编排器
     *
     * @return 编排器，未设置时返回默认的 {@link SimpleAgentOrchestrator#INSTANCE}
     */
    @Override
    public AgentOrchestrator orchestrator() {
        AgentOrchestrator orchestrator = this.orchestrator;
        return orchestrator != null ? orchestrator : SimpleAgentOrchestrator.INSTANCE;
    }

    /**
     * 设置是否打印架构配置
     *
     * @param printConfig 是否启用
     * @return 当前 Agent 实例
     */
    @Override
    public Agent printConfig(boolean printConfig) {
        this.printConfigEnabled = printConfig;
        return this;
    }

    /**
     * 设置是否开启系统提示词树形打印
     *
     * @param debug 是否启用
     * @return 当前 Agent 实例
     */
    @Override
    public Agent debug(boolean debug) {
        this.debugLogging = debug;
        return this;
    }

    /**
     * 绑定一个会话目标
     *
     * @param goal 目标快照，不能为 null
     * @return 当前 Agent 实例
     */
    @Override
    public Agent goal(SessionGoal goal) {
        goalSupport.goal(goal);
        return this;
    }

    /**
     * 挂接一个已存在的目标状态机
     *
     * @param tracker 目标状态机，不能为 null
     * @return 当前 Agent 实例
     */
    @Override
    public Agent attachGoal(GoalTracker tracker) {
        goalSupport.attach(tracker);
        return this;
    }

    /**
     * 设置目标循环的轮次硬上限
     *
     * @param maxRounds 轮次上限
     * @return 当前 Agent 实例
     */
    @Override
    public Agent goalMaxRounds(int maxRounds) {
        goalSupport.maxRounds(maxRounds);
        return this;
    }

    /**
     * 设置单次暂停等待上限
     *
     * @param pauseTimeoutMillis 毫秒
     * @return 当前 Agent 实例
     */
    @Override
    public Agent goalPauseTimeout(long pauseTimeoutMillis) {
        goalSupport.pauseTimeout(pauseTimeoutMillis);
        return this;
    }

    /**
     * 是否已绑定目标
     *
     * @return 已绑定返回 true
     */
    @Override
    public boolean hasGoal() {
        return goalSupport.hasGoal();
    }

    /**
     * 当前目标状态
     *
     * @return 展示投影；未绑定返回 null
     */
    @Override
    public GoalStatus goalStatus() {
        return goalSupport.status();
    }

    /**
     * 暂停当前目标
     *
     * @return 暂停后的展示投影
     */
    @Override
    public GoalStatus pauseGoal() {
        return goalSupport.pause();
    }

    /**
     * 恢复当前目标
     *
     * @return 恢复后的展示投影
     */
    @Override
    public GoalStatus resumeGoal() {
        return goalSupport.resume();
    }

    /**
     * 取消当前目标
     *
     * @param reason 取消原因
     * @return 取消后的展示投影
     */
    @Override
    public GoalStatus cancelGoal(String reason) {
        return goalSupport.cancel(reason);
    }

    /**
     * 围绕已绑定的目标反复推进
     *
     * @return 结束时的展示投影
     */
    @Override
    public GoalStatus runGoal() {
        return goalSupport.run(prompt -> {
            AgentResponse response = run(prompt);
            String output = response != null && response.getOutput() != null
                    ? response.getOutput() : "";
            return new GoalLoop.TurnResult(output,
                    AgentGoalSupport.tokensOf(response));
        });
    }

    /**
     * 获取 Agent 定义
     *
     * @return Agent 定义
     */
    @Override
    public AgentDefinition getDefinition() {
        return definition;
    }

    /**
     * 获取实际下发给模型系统提示词。
     *
     * <p>不能回落到接口默认的 {@code definition.getSystemPrompt()}：leader 场景下
     * 真正下发的提示词由 {@link AgentSystemPromptBuilder} 拼上子 Agent 路由表，
     * 默认实现返回的是未拼路由表的原始值。</p>
     *
     * @return 完整系统提示词
     */
    @Override
    public String getSystemPrompt() {
        return buildSystemPrompt();
    }

    /**
     * 获取子 Agent 定义列表（返回不可变副本）
     *
     * @return 子 Agent 定义列表
     */
    @Override
    public List<AgentDefinition> getSubAgents() {
        return List.copyOf(subAgentDefinitions);
    }

    /**
     * 设置 langchain4j 对话模型
     *
     * @param chatModel 对话模型 实例
     * @return 当前 Agent 实例
     */
    public LangChain4jAgent chatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
        return this;
    }

    /**
     * 执行 Agent 推理。
     *
     * <p>流程：准备运行上下文（定义解析、记忆检索、压缩、工具装配）→
     * 需要时先规划 → ReAct 循环（模型调用 → 工具调用 → 结果回填）→
     * 写回会话历史与记忆。失败按重试配置退避重试。
     *
     * @param input 用户输入
     * @return Agent 响应结果
     */
    @Override
    public AgentResponse run(String input) {
        AgentEvalContext evalContext = newEvalContext(input, "sync");
        fireEvalStart(evalContext);
        try {
            AgentResponse response = doRun(input);
            fireEvalEnd(evalContext, response, null);
            return response;
        } catch (AgentCancelledException e) {
            log.info("[LangChain4j] 运行被取消: {}", e.getMessage());
            fireEvalEnd(evalContext, null, e);
            return cancelledResponse(1);
        } catch (Exception e) {
            log.error("[LangChain4j] 运行失败: {}", e.getMessage(), e);
            fireEvalEnd(evalContext, null, e);
            return errorResponse(e.getMessage());
        }
    }

    /**
     * 同步执行的完整实现
     *
     * @param input 用户输入
     * @return Agent 响应
     */
    private AgentResponse doRun(String input) {
        // 内容护栏：用户输入边界（注入默认阻断；脱敏命中替换后放行）
        AgentContentVerdict inputVerdict = inspectUserInput(input);
        if (inputVerdict.action() == AgentContentVerdict.Action.BLOCK) {
            log.warn("[LangChain4j] 用户输入被内容护栏拦截: agentId={}, findings={}",
                    agentId(), describeFindings(inputVerdict));
            return errorResponse("用户输入被内容护栏拦截: " + describeFindings(inputVerdict));
        }
        input = inputVerdict.content() != null ? inputVerdict.content() : input;
        AgentBudgetTracker budgetTracker = createBudgetTracker();
        RunPlan plan = prepareRun(input);
        if (printConfigEnabled) {
            printArchitectureDiagram(plan);
        }
        if (debugLogging) {
            printSystemPromptsTree(plan);
            logArchitecture(plan);
        }
        loopGuard.reset();

        AgentRetryConfig retry = plan.retryConfig;
        int attempt = 0;
        while (true) {
            if (cancellationToken().isCancelled()) {
                return cancelledResponse(attempt);
            }
            if (isBudgetExceeded(budgetTracker)) {
                return budgetExceededResponse(budgetTracker, attempt);
            }
            if (loopGuard.isInterrupted()) {
                return interruptedResponse(attempt);
            }
            attempt++;
            try {
                UsageAcc acc = new UsageAcc();
                String planText = null;
                if (plan.planEnabled) {
                    planText = producePlan(plan, input, acc);
                    if (plan.planStartActive) {
                        recordSessionTurn(input, planText);
                        autoSaveMemory(input, planText);
                        AgentResponse resp = AgentResponse.builder()
                                .output(planText)
                                .mode(modeName())
                                .usage(acc.build())
                                .build();
                        resp.getMetadata().put("planOnly", Boolean.TRUE);
                        resp.getMetadata().put("attempts", attempt);
                        return resp;
                    }
                }
                // 计划送审：配置了计划确认器时，正式执行前先审批
                AgentPlanConfirmation.Outcome planOutcome = reviewPlan(plan, input, planText);
                if (planOutcome != null
                        && planOutcome.decision() != AgentPlanConfirmation.Decision.PROCEED) {
                    return planDecisionResponse(planOutcome, acc, attempt);
                }
                LoopResult result = executeReAct(plan, input, planText, acc, budgetTracker);
                if (cancellationToken().isCancelled()) {
                    return cancelledResponse(attempt);
                }
                if (result.aborted && isBudgetExceeded(budgetTracker)) {
                    return budgetExceededResponse(budgetTracker, attempt);
                }
                recordSessionTurn(input, result.output);
                autoSaveMemory(input, result.output);
                AgentResponse resp = AgentResponse.builder()
                        .output(result.output)
                        .mode(modeName())
                        .usage(acc.build())
                        .build();
                resp.getMetadata().put("attempts", attempt);
                if (result.aborted) {
                    resp.getMetadata().put("aborted", Boolean.TRUE);
                    resp.getMetadata().put("reason", result.abortReason);
                    resp.getMetadata().put("repeats", loopGuard.consecutiveRepeats());
                    resp.getMetadata().put("lastTool", loopGuard.lastToolName());
                }
                return resp;
            } catch (Exception e) {
                if (e instanceof AgentCancelledException || cancellationToken().isCancelled()) {
                    // 取消按取消语义收尾，不按错误告警
                    log.info("[LangChain4j] 运行被取消 attempt={} reason={}", attempt, cancelReasonText());
                    return cancelledResponse(attempt);
                }
                if (loopGuard.isInterrupted()) {
                    log.info("[LangChain4j] 中断令执行提前结束 attempt={}", attempt);
                    return interruptedResponse(attempt);
                }
                fireDebug(plan.debugHook, "ERROR", plan.agentId,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), attempt);
                log.error("[LangChain4j] run 执行失败 attempt={}/{}", attempt,
                        retry.getMaxRetries() >= 0 ? retry.getMaxRetries() + 1 : -1, e);
                if (!shouldRetry(retry, e, attempt)) {
                    return errorResponse(e.getMessage(), attempt);
                }
                long delay = retry.calculateDelayMillis(attempt - 1);
                if (!sleepQuietly(delay)) {
                    return interruptedResponse(attempt);
                }
            }
        }
    }

    /**
     * 流式执行。
     *
     * <p>模型实现 {@link StreamingChatModel} 时逐 delta 推送正文/思考，
     * 并在工具调用处推送 {@code TOOL_START}/{@code TOOL_RESULT}；
     * 否则退化为一次性推送 {@link #run(String)} 的结果。
     *
     * @param input 用户输入
     * @return 流式事件 Flux
     */
    @Override
    public Flux<AgentStreamEvent> runStream(String input) {
        if (chatModel == null) {
            return Flux.just(errorEvent("ChatModel 未配置，请先调用 chatModel(...)"));
        }
        // 内容护栏：用户输入边界（注入默认阻断；脱敏命中替换后放行）
        AgentContentVerdict inputVerdict = inspectUserInput(input);
        if (inputVerdict.action() == AgentContentVerdict.Action.BLOCK) {
            log.warn("[LangChain4j] 用户输入被内容护栏拦截(流式): agentId={}, findings={}",
                    agentId(), describeFindings(inputVerdict));
            return Flux.just(errorEvent("用户输入被内容护栏拦截: " + describeFindings(inputVerdict)));
        }
        final String guardedInput = inputVerdict.content() != null ? inputVerdict.content() : input;
        if (!(chatModel instanceof StreamingChatModel)) {
            return runStreamFallback(guardedInput);
        }
        AgentEvalContext evalContext = newEvalContext(guardedInput, "stream");
        fireEvalStart(evalContext);
        return Flux.create(sink -> {
            try {
                streamRun(guardedInput, sink);
                fireEvalEnd(evalContext, null, null);
            } catch (AgentCancelledException e) {
                log.info("[LangChain4j] 流式运行被取消: {}", e.getMessage());
                fireEvalEnd(evalContext, null, e);
                if (!sink.isCancelled()) {
                    sink.next(errorEvent("运行已被取消: " + cancelReasonText()));
                }
            } catch (Exception e) {
                log.error("[LangChain4j] runStream 失败: {}", e.getMessage(), e);
                fireEvalEnd(evalContext, null, e);
                if (!sink.isCancelled()) {
                    sink.next(errorEvent(e.getMessage()));
                }
            } finally {
                sink.complete();
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 非流式模型的一次性回退
     *
     * @param input 用户输入
     * @return 流式事件 Flux
     */
    private Flux<AgentStreamEvent> runStreamFallback(String input) {
        return Flux.defer(() -> {
            AgentResponse response;
            try {
                response = run(input);
            } catch (Exception e) {
                return Flux.just(errorEvent(e.getMessage()));
            }
            List<AgentStreamEvent> events = new ArrayList<>();
            String output = response != null && response.getOutput() != null
                    ? response.getOutput() : "";
            if (!output.isEmpty()) {
                events.add(AgentStreamEvent.reasoningDelta(agentId(), output));
            }
            events.add(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.COMPLETE)
                    .agentId(agentId())
                    .usage(response != null ? response.getUsage() : null)
                    .timestamp(System.currentTimeMillis())
                    .build());
            return Flux.fromIterable(events);
        });
    }

    /**
     * 流式执行主体
     *
     * @param input 用户输入
     * @param sink  事件接收器
     */
    private void streamRun(String input, FluxSink<AgentStreamEvent> sink) {
        AgentBudgetTracker budgetTracker = createBudgetTracker();
        RunPlan plan = prepareRun(input);
        if (printConfigEnabled) {
            printArchitectureDiagram(plan);
        }
        if (debugLogging) {
            printSystemPromptsTree(plan);
            logArchitecture(plan);
        }
        loopGuard.reset();
        String agentId = plan.agentId;
        sink.next(AgentStreamEvent.trace(
                AgentTrace.of(AgentTrace.KIND_AGENT_START, agentId, null, input)));

        UsageAcc planAcc = new UsageAcc();
        String planText = null;
        if (plan.planEnabled) {
            planText = producePlan(plan, input, planAcc);
            sink.next(AgentStreamEvent.trace(
                    AgentTrace.of("plan", agentId, null, planText)));
            if (plan.planStartActive) {
                sink.next(AgentStreamEvent.reasoningDelta(agentId, planText));
                sink.next(AgentStreamEvent.builder()
                        .type(AgentStreamEvent.Type.COMPLETE)
                        .agentId(agentId)
                        .usage(planAcc.build())
                        .timestamp(System.currentTimeMillis())
                        .build());
                sink.next(AgentStreamEvent.trace(
                        AgentTrace.of(AgentTrace.KIND_AGENT_END, agentId, null, "plan_only")));
                recordSessionTurn(input, planText);
                autoSaveMemory(input, planText);
                return;
            }
        }

        // 计划送审：配置了计划确认器时，正式执行前先审批
        AgentPlanConfirmation.Outcome planOutcome = reviewPlan(plan, input, planText);
        if (planOutcome != null
                && planOutcome.decision() != AgentPlanConfirmation.Decision.PROCEED) {
            String decisionText = planDecisionText(planOutcome);
            sink.next(AgentStreamEvent.reasoningDelta(agentId, decisionText));
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.COMPLETE)
                    .agentId(agentId)
                    .usage(planAcc.build())
                    .timestamp(System.currentTimeMillis())
                    .build());
            sink.next(AgentStreamEvent.trace(AgentTrace.of(
                    AgentTrace.KIND_AGENT_END, agentId, null, planOutcome.decision().name())));
            return;
        }

        AgentRetryConfig retry = plan.retryConfig;
        int attempt = 0;
        while (true) {
            if (sink.isCancelled() || cancellationToken().isCancelled()) {
                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                        AgentTrace.KIND_EDGE, agentId, null, "cancelled")));
                return;
            }
            if (loopGuard.isInterrupted()) {
                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                        AgentTrace.KIND_EDGE, agentId, null, "interrupted")));
                return;
            }
            if (isBudgetExceeded(budgetTracker)) {
                sink.next(errorEvent(budgetExceededText(budgetTracker)));
                return;
            }
            attempt++;
            boolean[] emitted = {false};
            try {
                UsageAcc acc = new UsageAcc();
                acc.add(planAcc);
                LoopResult result = streamReAct(plan, input, planText, acc, sink, emitted, budgetTracker);
                if (result.pausedReplyId != null) {
                    // 人工确认暂停：本轮正常收尾，不记录会话轮次，等待 resumeStream 恢复
                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                            AgentTrace.KIND_EDGE, agentId, null, "await_confirmation")));
                } else {
                    recordSessionTurn(input, result.output);
                    autoSaveMemory(input, result.output);
                    if (result.aborted) {
                        sink.next(AgentStreamEvent.trace(AgentTrace.of(
                                AgentTrace.KIND_EDGE, agentId, null, result.abortReason)));
                    }
                }
                sink.next(AgentStreamEvent.builder()
                        .type(AgentStreamEvent.Type.COMPLETE)
                        .agentId(agentId)
                        .usage(acc.build())
                        .timestamp(System.currentTimeMillis())
                        .build());
                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                        AgentTrace.KIND_AGENT_END, agentId, null, null)));
                return;
            } catch (Exception e) {
                if (e instanceof AgentCancelledException || cancellationToken().isCancelled()) {
                    // 取消按取消语义收尾，不按错误重试
                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                            AgentTrace.KIND_EDGE, agentId, null, "cancelled")));
                    return;
                }
                if (loopGuard.isInterrupted() || sink.isCancelled()) {
                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                            AgentTrace.KIND_EDGE, agentId, null, "interrupted")));
                    return;
                }
                fireDebug(plan.debugHook, "ERROR", agentId,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), attempt);
                // 已经推送过增量时不能重放，避免前端出现重复段落
                if (emitted[0] || !shouldRetry(retry, e, attempt)) {
                    sink.next(errorEvent(e.getMessage()));
                    return;
                }
                long delay = retry.calculateDelayMillis(attempt - 1);
                if (!sleepQuietly(delay)) {
                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                            AgentTrace.KIND_EDGE, agentId, null, "interrupted")));
                    return;
                }
            }
        }
    }

    /**
     * 恢复执行：回传用户对人工确认的决策
     *
     * @param replyId   待确认请求回复 ID（CONFIRMATION_REQUEST 原样带回）
     * @param decisions 各待确认工具的决策
     * @return 恢复后的流式事件 Flux
     */
    @Override
    public Flux<AgentStreamEvent> resumeStream(String replyId, List<ConfirmationDecision> decisions) {
        return resumeStream(replyId, decisions, null);
    }

    /**
     * 携带用户修改意见恢复人工确认
     *
     * <p>从暂停态缓存取出对应轮次：批准的请求真实执行，拒绝（或未决策）的请求把
     * 拒绝文案作为工具结果回填给模型；{@code feedback} 非空时作为用户补充消息注入
     * 上下文，模型据此调整后续动作。恢复轮复用流式 ReAct 循环，从暂停点继续。</p>
     *
     * @param replyId   待确认请求回复 ID（CONFIRMATION_REQUEST 原样带回）
     * @param decisions 各待确认工具的决策
     * @param feedback  用户修改意见（可空）
     * @return 恢复后的流式事件 Flux
     */
    @Override
    public Flux<AgentStreamEvent> resumeStream(String replyId, List<ConfirmationDecision> decisions,
                                               String feedback) {
        if (!pendingToolRecoveryEnabled) {
            // 恢复通道被显式关闭：逐出缓存并返回错误事件，绝不静默丢弃决策
            takePendingPause(replyId);
            return Flux.just(errorEvent(
                    "已通过 pendingToolRecovery(false) 关闭待执行工具恢复，请重新发起运行"));
        }
        if (chatModel == null) {
            return Flux.just(errorEvent("ChatModel 未配置，请先调用 chatModel(...)"));
        }
        if (!(chatModel instanceof StreamingChatModel)) {
            return Flux.just(errorEvent("人工确认恢复需要流式模型（StreamingChatModel）"));
        }
        PendingPause pause = takePendingPause(replyId);
        if (pause == null || pause.question) {
            if (pause != null) {
                // 类型不匹配（确认轮次被误用提问恢复）：放回缓存避免状态丢失
                cachePendingPause(replyId, pause);
            }
            return Flux.just(errorEvent("未找到待确认的暂停轮次: " + replyId));
        }
        return Flux.create(sink -> {
            try {
                resumeRun(pause, decisions, feedback, sink);
            } catch (Exception e) {
                log.error("[LangChain4j] resumeStream 失败: {}", e.getMessage(), e);
                if (!sink.isCancelled()) {
                    sink.next(errorEvent(e.getMessage()));
                }
            } finally {
                sink.complete();
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 恢复轮执行主体
     *
     * @param pause     暂停态快照
     * @param decisions 用户决策
     * @param feedback  用户修改意见（可空）
     * @param sink      事件接收器
     */
    private void resumeRun(PendingPause pause, List<ConfirmationDecision> decisions,
                           String feedback, FluxSink<AgentStreamEvent> sink) {
        RunPlan plan;
        try {
            // 复用 prepareRun 完成工具装配/系统提示词/规划配置；消息序列沿用暂停态缓存
            plan = prepareRun(null);
        } catch (Exception e) {
            sink.next(errorEvent(e.getMessage()));
            return;
        }
        // 从挂起点延续护栏与预算状态：重复判定不清零（挂起前后同参调用仍累计），
        // 预算累计与熔断闩锁跨挂起延续——挂起前已超限的，恢复轮立即收尾不再烧钱
        loopGuard.restore(pause.loopGuardSnapshot);
        AgentBudgetTracker budgetTracker = createBudgetTracker();
        if (budgetTracker != null && pause.budgetSnapshot != null) {
            replayBudget(budgetTracker, pause.budgetSnapshot);
        }
        sink.next(AgentStreamEvent.trace(AgentTrace.of(
                AgentTrace.KIND_AGENT_START, plan.agentId, null, "resume:" + pause.replyId)));

        Map<String, ConfirmationDecision> decisionMap = new LinkedHashMap<>();
        if (decisions != null) {
            for (ConfirmationDecision decision : decisions) {
                if (decision != null && decision.getToolCallId() != null) {
                    decisionMap.put(decision.getToolCallId(), decision);
                }
            }
        }
        List<ChatMessage> history = new ArrayList<>(pause.history);
        List<ToolExecutionResultMessage> results = new ArrayList<>();
        for (ToolExecutionRequest request : pause.pendingRequests) {
            ConfirmationDecision decision = decisionMap.get(request.id());
            String resultText;
            if (cancellationToken().isCancelled()) {
                // 恢复轮被取消：待确认工具一律不执行，按取消语义回填
                resultText = "运行已被取消，跳过执行该工具调用";
                sink.next(AgentStreamEvent.builder()
                        .type(AgentStreamEvent.Type.TOOL_RESULT)
                        .agentId(plan.agentId)
                        .toolName(request.name())
                        .toolOutput(resultText)
                        .timestamp(System.currentTimeMillis())
                        .build());
            } else if (decision != null && decision.isConfirmed()) {
                long toolStartAt = System.currentTimeMillis();
                sink.next(AgentStreamEvent.builder()
                        .type(AgentStreamEvent.Type.TOOL_START)
                        .agentId(plan.agentId)
                        .toolName(request.name())
                        .toolInput(request.arguments())
                        .timestamp(toolStartAt)
                        .build());
                resultText = executeTool(request, plan.executors);
                if (budgetTracker != null) {
                    budgetTracker.recordToolCall();
                }
                sink.next(AgentStreamEvent.builder()
                        .type(AgentStreamEvent.Type.TOOL_RESULT)
                        .agentId(plan.agentId)
                        .toolName(request.name())
                        .toolOutput(resultText)
                        .timestamp(System.currentTimeMillis())
                        .build());
                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                        AgentTrace.KIND_TOOL_RESULT, plan.agentId, request.name(), resultText)));
            } else {
                // 未批准（拒绝或未决策）一律不执行，回填拒绝文案作为工具结果
                resultText = decision != null
                        ? "用户拒绝执行该工具调用"
                        : "用户未对该工具调用作出决策，按拒绝处理";
                sink.next(AgentStreamEvent.builder()
                        .type(AgentStreamEvent.Type.TOOL_RESULT)
                        .agentId(plan.agentId)
                        .toolName(request.name())
                        .toolOutput(resultText)
                        .timestamp(System.currentTimeMillis())
                        .build());
            }
            results.add(ToolExecutionResultMessage.from(request, resultText));
        }
        history.addAll(results);
        if (StringUtils.isNotBlank(feedback)) {
            // 内容护栏：恢复轮用户反馈视作用户输入边界检查（注入默认阻断）
            AgentContentVerdict feedbackVerdict = inspectUserInput(feedback);
            if (feedbackVerdict.action() == AgentContentVerdict.Action.BLOCK) {
                log.warn("[LangChain4j] 恢复轮用户反馈被内容护栏拦截: agentId={}, findings={}",
                        agentId(), describeFindings(feedbackVerdict));
                sink.next(errorEvent("用户反馈被内容护栏拦截: " + describeFindings(feedbackVerdict)));
                return;
            }
            String guardedFeedback = feedbackVerdict.content() != null ? feedbackVerdict.content() : feedback;
            // 用户修改意见注入模型上下文与会话历史，模型据此调整后续动作
            history.add(UserMessage.from(guardedFeedback));
            appendSessionFeedback(guardedFeedback);
        }

        UsageAcc acc = new UsageAcc();
        boolean[] emitted = {false};
        try {
            LoopResult result = streamReActLoop(plan, history, pause.originalInput, acc, sink, emitted,
                    budgetTracker);
            if (result.pausedReplyId != null) {
                // 恢复轮再次遇到高风险工具：再次暂停，等待下一次 resumeStream
                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                        AgentTrace.KIND_EDGE, plan.agentId, null, "await_confirmation")));
            } else {
                recordSessionTurn(pause.originalInput, result.output);
                autoSaveMemory(pause.originalInput, result.output);
                if (result.aborted) {
                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                            AgentTrace.KIND_EDGE, plan.agentId, null, result.abortReason)));
                }
            }
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.COMPLETE)
                    .agentId(plan.agentId)
                    .usage(acc.build())
                    .timestamp(System.currentTimeMillis())
                    .build());
            sink.next(AgentStreamEvent.trace(AgentTrace.of(
                    AgentTrace.KIND_AGENT_END, plan.agentId, null, null)));
        } catch (Exception e) {
            log.error("[LangChain4j] resumeStream 执行失败: {}", e.getMessage(), e);
            if (!sink.isCancelled()) {
                sink.next(errorEvent(e.getMessage()));
            }
        }
    }

    /**
     * 回传结构化提问的答案并恢复执行
     *
     * <p>本实现没有提问类工具（不会产生 {@code QUESTION_REQUEST}），正常流程不存在
     * 待回答的提问；这里保留与 {@link #resumeStream} 一致的守卫与缓存查询口径，
     * 误用时返回明确的 ERROR 事件而不是静默忽略。</p>
     *
     * @param replyId 提问请求 ID（QUESTION_REQUEST 原样带回）
     * @param answers 用户答案，顺序与提问顺序一致
     * @return 恢复后的流式事件 Flux
     */
    @Override
    public Flux<AgentStreamEvent> answerQuestion(String replyId, List<AgentQuestionAnswer> answers) {
        if (!pendingToolRecoveryEnabled) {
            // 恢复通道被显式关闭：逐出缓存并返回错误事件
            takePendingPause(replyId);
            return Flux.just(errorEvent(
                    "已通过 pendingToolRecovery(false) 关闭待执行工具恢复，请重新发起运行"));
        }
        PendingPause pause = takePendingPause(replyId);
        if (pause != null) {
            // 命中的是确认类挂起（本实现不产生提问挂起）：放回缓存避免状态丢失
            cachePendingPause(replyId, pause);
            return Flux.just(errorEvent("该回复 ID 对应的是待确认工具调用，请使用 resumeStream 恢复"));
        }
        return Flux.just(errorEvent("未找到待回答的提问: " + replyId));
    }

    /**
     * 同步 ReAct 循环
     *
     * @param plan          运行计划
     * @param input         用户输入
     * @param planText      规划文本（可空）
     * @param acc           用量累加器
     * @param budgetTracker 预算记账器（可空，空表示不限额）
     * @return 循环结果
     */
    private LoopResult executeReAct(RunPlan plan, String input, String planText, UsageAcc acc,
                                    AgentBudgetTracker budgetTracker) {
        List<ChatMessage> history = buildHistory(plan, input, planText);
        String finalOutput = "";
        String abortReason = null;
        int iter = 0;
        while (iter < plan.maxIters) {
            if (cancellationToken().isCancelled()) {
                abortReason = "cancelled";
                break;
            }
            if (loopGuard.isInterrupted()) {
                abortReason = "interrupted";
                break;
            }
            if (isBudgetExceeded(budgetTracker)) {
                abortReason = budgetExceededText(budgetTracker);
                break;
            }
            fireDebug(plan.debugHook, "PRE_REASONING", plan.agentId,
                    "调用模型", iter + 1);
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(history)
                    .toolSpecifications(plan.toolSpecs)
                    .build());
            acc.add(response.tokenUsage());
            recordBudgetUsage(budgetTracker, response.tokenUsage());
            AiMessage aiMessage = response.aiMessage();
            if (aiMessage == null) {
                break;
            }
            history.add(aiMessage);
            fireDebug(plan.debugHook, "POST_REASONING", plan.agentId,
                    aiMessage.text() != null ? aiMessage.text() : "", iter + 1);

            if (!aiMessage.hasToolExecutionRequests()) {
                // 内容护栏：模型输出边界（隐私 / 凭据脱敏；命中拦截替换为通知文本）
                finalOutput = guardModelOutput(aiMessage.text() != null ? aiMessage.text() : "");
                break;
            }

            fireDebug(plan.debugHook, "PRE_ACTING", plan.agentId,
                    "执行工具 " + aiMessage.toolExecutionRequests().size() + " 个", iter + 1);
            List<ToolExecutionResultMessage> results = new ArrayList<>();
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                if (cancellationToken().isCancelled() || loopGuard.isInterrupted()) {
                    abortReason = cancellationToken().isCancelled() ? "cancelled" : "interrupted";
                    break;
                }
                if (isBudgetExceeded(budgetTracker)) {
                    abortReason = budgetExceededText(budgetTracker);
                    break;
                }
                // 连续相同（工具名 + 入参）调用：模型在原地打转，不再执行
                if (loopGuard.recordToolCall(request.name(), request.arguments())) {
                    abortReason = loopGuard.describe();
                    break;
                }
                // 风险门禁：拒绝 / 需确认（含危险级硬底线）一律不执行，结论作为工具结果回给模型
                String resultText = executeToolWithGate(plan, request, budgetTracker);
                results.add(ToolExecutionResultMessage.from(request, resultText));
            }
            fireDebug(plan.debugHook, "POST_ACTING", plan.agentId,
                    "工具执行完成 " + results.size() + " 个", iter + 1);
            history.addAll(results);
            if (abortReason != null) {
                break;
            }
            iter++;
        }
        return new LoopResult(finalOutput, abortReason != null, abortReason);
    }

    /**
     * 流式 ReAct 循环（初始轮）
     *
     * @param plan          运行计划
     * @param input         用户输入
     * @param planText      规划文本（可空）
     * @param acc           用量累加器
     * @param sink          事件接收器
     * @param emitted       是否已推送正文/思考增量（长度 1 的标记数组）
     * @param budgetTracker 预算记账器（可空，空表示不限额）
     * @return 循环结果
     * @throws Exception 模型调用失败
     */
    private LoopResult streamReAct(RunPlan plan, String input, String planText,
                                   UsageAcc acc, FluxSink<AgentStreamEvent> sink,
                                   boolean[] emitted, AgentBudgetTracker budgetTracker) throws Exception {
        return streamReActLoop(plan, buildHistory(plan, input, planText), input, acc, sink, emitted,
                budgetTracker);
    }

    /**
     * 流式 ReAct 循环主体（初始轮与人工确认恢复轮共用）
     *
     * <p>区别只在起始消息序列：初始轮由 {@link #buildHistory} 现建；
     * 恢复轮沿用暂停时缓存的消息序列（已含带工具请求的 AiMessage 与已执行工具的结果）。
     * 循环内遇到中高风险工具时暂停并下发 {@code CONFIRMATION_REQUEST}，正常结束本轮，
     * 由调用方经 {@link #resumeStream} 恢复。</p>
     *
     * @param plan          运行计划
     * @param history       起始消息序列
     * @param originalInput 触发本轮的原始用户输入（暂停缓存与恢复轮记录用）
     * @param acc           用量累加器
     * @param sink          事件接收器
     * @param emitted       是否已推送正文/思考增量（长度 1 的标记数组）
     * @param budgetTracker 预算记账器（可空，空表示不限额）
     * @return 循环结果
     * @throws Exception 模型调用失败
     */
    private LoopResult streamReActLoop(RunPlan plan, List<ChatMessage> history, String originalInput,
                                       UsageAcc acc, FluxSink<AgentStreamEvent> sink,
                                       boolean[] emitted, AgentBudgetTracker budgetTracker) throws Exception {
        StreamingChatModel streamingModel = (StreamingChatModel) chatModel;
        String finalOutput = "";
        String abortReason = null;
        int iter = 0;
        while (iter < plan.maxIters) {
            if (sink.isCancelled() || cancellationToken().isCancelled()) {
                abortReason = "cancelled";
                break;
            }
            if (loopGuard.isInterrupted()) {
                abortReason = "interrupted";
                break;
            }
            if (isBudgetExceeded(budgetTracker)) {
                abortReason = budgetExceededText(budgetTracker);
                break;
            }
            fireDebug(plan.debugHook, "PRE_REASONING", plan.agentId,
                    "调用模型（流式）", iter + 1);
            String spanId = AgentTrace.newSpanId(plan.agentId, "model", iter);
            long modelStartAt = System.currentTimeMillis();
            sink.next(AgentStreamEvent.trace(AgentTrace.spanStart(
                    "model", plan.agentId, spanId, null, "model call")));

            ChatRequest request = ChatRequest.builder()
                    .messages(history)
                    .toolSpecifications(plan.toolSpecs)
                    .build();
            ChatResponse response = streamOnce(streamingModel, request, plan.agentId, sink, emitted);
            acc.add(response.tokenUsage());
            recordBudgetUsage(budgetTracker, response.tokenUsage());

            sink.next(AgentStreamEvent.trace(AgentTrace.spanEnd(
                    "model", plan.agentId, spanId, null, null, modelStartAt)));
            AiMessage aiMessage = response.aiMessage();
            if (aiMessage == null) {
                break;
            }
            history.add(aiMessage);
            fireDebug(plan.debugHook, "POST_REASONING", plan.agentId,
                    aiMessage.text() != null ? aiMessage.text() : "", iter + 1);

            if (!aiMessage.hasToolExecutionRequests()) {
                // 内容护栏：模型输出边界（隐私 / 凭据脱敏；命中拦截替换为通知文本）
                finalOutput = guardModelOutput(aiMessage.text() != null ? aiMessage.text() : "");
                break;
            }

            fireDebug(plan.debugHook, "PRE_ACTING", plan.agentId,
                    "执行工具 " + aiMessage.toolExecutionRequests().size() + " 个", iter + 1);
            List<ToolExecutionResultMessage> results = new ArrayList<>();
            List<ToolExecutionRequest> batchRequests = aiMessage.toolExecutionRequests();
            String batchAbort = null;
            if (isParallelBatch(plan, batchRequests)) {
                // 本批全部放行且安全策略允许并行：并行执行，结果按提交顺序回填
                batchAbort = runParallelBatch(plan, batchRequests, results, iter, sink, budgetTracker);
            } else {
                for (int requestIndex = 0; requestIndex < batchRequests.size(); requestIndex++) {
                    ToolExecutionRequest toolRequest = batchRequests.get(requestIndex);
                    if (sink.isCancelled() || cancellationToken().isCancelled()) {
                        batchAbort = "cancelled";
                        break;
                    }
                    if (loopGuard.isInterrupted()) {
                        batchAbort = "interrupted";
                        break;
                    }
                    if (isBudgetExceeded(budgetTracker)) {
                        batchAbort = budgetExceededText(budgetTracker);
                        break;
                    }
                    if (loopGuard.recordToolCall(toolRequest.name(), toolRequest.arguments())) {
                        batchAbort = loopGuard.describe();
                        break;
                    }
                    // 风险门禁：拒绝 → 回填拒绝文案；需确认（含危险级硬底线）→ 挂起等待人工确认
                    AgentRiskDecision decision = adjudicateTool(plan, toolRequest);
                    if (decision != null && decision.verdict() == AgentRiskDecision.Verdict.DENY) {
                        String denyText = "风险裁决拒绝执行该工具调用: "
                                + (decision.reason() != null ? decision.reason() : "未说明原因");
                        log.info("[LangChain4j] 风险裁决拒绝执行工具 {}: {}", toolRequest.name(), denyText);
                        emitToolResultEvent(sink, plan.agentId, toolRequest, denyText, iter);
                        results.add(ToolExecutionResultMessage.from(toolRequest, denyText));
                        continue;
                    }
                    if (decision != null
                            && decision.verdict() == AgentRiskDecision.Verdict.CONFIRM_REQUIRED) {
                        return pauseForConfirmation(plan, history, results, batchRequests,
                                requestIndex, originalInput, sink, budgetTracker);
                    }
                    long toolStartAt = System.currentTimeMillis();
                    String toolSpanId = AgentTrace.newSpanId(plan.agentId, "tool", iter);
                    sink.next(AgentStreamEvent.builder()
                            .type(AgentStreamEvent.Type.TOOL_START)
                            .agentId(plan.agentId)
                            .toolName(toolRequest.name())
                            .toolInput(toolRequest.arguments())
                            .timestamp(toolStartAt)
                            .build());
                    sink.next(AgentStreamEvent.trace(AgentTrace.builder()
                            .kind(AgentTrace.KIND_TOOL_CALL)
                            .agentId(plan.agentId)
                            .spanId(toolSpanId)
                            .toolName(toolRequest.name())
                            .text("调用工具: " + toolRequest.name())
                            .timestamp(toolStartAt)
                            .build()));
                    String resultText = executeTool(toolRequest, plan.executors);
                    if (budgetTracker != null) {
                        budgetTracker.recordToolCall();
                    }
                    sink.next(AgentStreamEvent.builder()
                            .type(AgentStreamEvent.Type.TOOL_RESULT)
                            .agentId(plan.agentId)
                            .toolName(toolRequest.name())
                            .toolOutput(resultText)
                            .timestamp(System.currentTimeMillis())
                            .build());
                    // 结束行带开始时刻与工具名：回放按工具名聚合时这一行才不会被跳过，
                    // 也才能算出单次工具耗时（原先只发点事件，工具行永远没有用时）。
                    sink.next(AgentStreamEvent.trace(AgentTrace.spanEnd(
                            AgentTrace.KIND_TOOL_RESULT, plan.agentId, toolSpanId,
                            toolRequest.name(), resultText, toolStartAt)));
                    results.add(ToolExecutionResultMessage.from(toolRequest, resultText));
                }
            }
            if (batchAbort != null) {
                abortReason = batchAbort;
            }
            fireDebug(plan.debugHook, "POST_ACTING", plan.agentId,
                    "工具执行完成 " + results.size() + " 个", iter + 1);
            history.addAll(results);
            if (abortReason != null) {
                break;
            }
            iter++;
        }
        return new LoopResult(finalOutput, abortReason != null, abortReason);
    }

    /**
     * 中高风险工具暂停：缓存待执行请求与循环状态，下发确认请求事件后正常结束本轮
     *
     * <p>挂起时同步采集护栏与预算快照：恢复轮从挂起点延续重复判定与预算累计
     * （含熔断闩锁），避免「挂起前后各调一次相同工具」绕过死循环判定、
     * 或「预算在挂起前已熔断，恢复轮从零重算」继续烧钱。</p>
     *
     * @param plan          运行计划
     * @param history       当前消息序列（已含带工具请求的 AiMessage）
     * @param executed      本批已执行工具的结果
     * @param batch         本批全部工具请求
     * @param fromIndex     首个未执行请求的下标
     * @param originalInput 触发本轮的原始用户输入
     * @param sink          事件接收器
     * @param budgetTracker 预算记账器（可空，空表示不限额）
     * @return 暂停态循环结果（pausedReplyId 非空）
     */
    private LoopResult pauseForConfirmation(RunPlan plan, List<ChatMessage> history,
                                            List<ToolExecutionResultMessage> executed,
                                            List<ToolExecutionRequest> batch,
                                            int fromIndex, String originalInput,
                                            FluxSink<AgentStreamEvent> sink,
                                            AgentBudgetTracker budgetTracker) {
        List<ToolExecutionRequest> pending = new ArrayList<>(batch.subList(fromIndex, batch.size()));
        List<ChatMessage> snapshot = new ArrayList<>(history);
        snapshot.addAll(executed);
        String replyId = UUID.randomUUID().toString();
        PendingPause.BudgetSnapshot budgetSnap = budgetTracker != null
                ? new PendingPause.BudgetSnapshot(budgetTracker.usedInputTokens(), budgetTracker.usedOutputTokens(),
                budgetTracker.usedToolCalls(), budgetTracker.usedCostMicros()) : null;
        cachePendingPause(replyId, new PendingPause(replyId, plan.agentId, originalInput,
                pending, snapshot, false, loopGuard.snapshot(), budgetSnap));
        List<ConfirmationItem> items = new ArrayList<>();
        for (ToolExecutionRequest request : pending) {
            items.add(ConfirmationItem.builder()
                    .toolCallId(request.id())
                    .toolName(request.name())
                    .title(request.name())
                    .description(StringUtils.isNotBlank(request.arguments())
                            ? request.arguments() : request.name())
                    .riskLevel(ToolRiskPolicy.classify(request.name(), request.arguments()))
                    .build());
        }
        sink.next(AgentStreamEvent.confirmationRequest(plan.agentId, replyId, items));
        return new LoopResult("", false, null, replyId);
    }

    /**
     * 调用一次流式模型并等待完整响应
     *
     * @param model   流式模型
     * @param request 请求
     * @param agentId Agent 标识
     * @param sink    事件接收器
     * @param emitted 增量标记
     * @return 完整响应
     * @throws Exception 调用失败
     */
    private ChatResponse streamOnce(StreamingChatModel model, ChatRequest request,
                                    String agentId, FluxSink<AgentStreamEvent> sink,
                                    boolean[] emitted) throws Exception {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (StringUtils.isNotEmpty(partialResponse)) {
                    emitted[0] = true;
                    sink.next(AgentStreamEvent.reasoningDelta(agentId, partialResponse));
                }
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                String text = partialThinking != null ? partialThinking.text() : null;
                if (StringUtils.isNotEmpty(text)) {
                    emitted[0] = true;
                    sink.next(AgentStreamEvent.thinkingDelta(agentId, text));
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                future.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            return future.get(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("流式调用被中断", e);
        }
    }

    /**
     * 组装本轮消息：系统提示词 + 历史会话 + 用户输入
     *
     * @param plan     运行计划
     * @param input    用户输入
     * @param planText 规划文本（可空）
     * @return 消息列表
     */
    private List<ChatMessage> buildHistory(RunPlan plan, String input, String planText) {
        String systemPrompt = plan.systemPrompt;
        if (StringUtils.isNotBlank(planText)) {
            systemPrompt = systemPrompt + "\n\n【执行计划】\n" + planText;
        }
        List<ChatMessage> history = new ArrayList<>();
        history.add(SystemMessage.from(systemPrompt));
        history.addAll(plan.history);
        history.add(UserMessage.from(input));
        return history;
    }

    /**
     * 生成执行计划（不携带任何工具，模型只能思考）
     *
     * @param plan  运行计划
     * @param input 用户输入
     * @param acc   用量累加器
     * @return 计划文本
     */
    private String producePlan(RunPlan plan, String input, UsageAcc acc) {
        int maxTask = plan.planMaxTask > 0 ? plan.planMaxTask : DEFAULT_PLAN_TASK;
        firePlan(plan.planHook, "PLAN_ENTER", plan.agentId,
                "进入规划阶段，最多 " + maxTask + " 个子任务");
        String planSystem = "你是任务规划器。请把用户任务拆解为不超过 " + maxTask
                + " 个可执行子任务，只输出编号列表，不要调用任何工具，也不要执行任务。";
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(List.of(SystemMessage.from(planSystem), UserMessage.from(input)))
                .build());
        acc.add(response.tokenUsage());
        AiMessage message = response.aiMessage();
        String text = message != null && message.text() != null ? message.text() : "";
        firePlan(plan.planHook, "PLAN_WRITE", plan.agentId, text);
        firePlan(plan.planHook, "PLAN_EXIT", plan.agentId, "规划完成，进入执行阶段");
        return text;
    }

    /**
     * 准备运行上下文：解析定义、装配工具、检索记忆、压缩历史
     *
     * @param input 用户输入
     * @return 运行计划
     */
    private RunPlan prepareRun(String input) {
        if (chatModel == null) {
            throw new IllegalStateException("ChatModel 未配置，请先调用 chatModel(...)");
        }
        RunPlan plan = new RunPlan();
        plan.agentId = agentId();
        plan.maxIters = resolveMaxIters(this.maxToolIterations,
                definition != null ? definition.getMaxToolIterations() : 0);
        plan.planEnabled = planEnabled || planStartActive
                || (definition != null && definition.isPlanning());
        plan.planStartActive = planStartActive;
        plan.planMaxTask = planMaxTask > 0
                ? planMaxTask
                : (definition != null ? definition.getPlanMaxTask() : 0);
        plan.debugHook = debugHook != null
                ? debugHook
                : (definition != null ? definition.getDebugHook() : null);
        plan.planHook = planHook != null
                ? planHook
                : (definition != null ? definition.getPlanHook() : null);
        plan.retryConfig = resolveRetryConfig();
        plan.compressionConfig = compressionConfig != null
                ? compressionConfig
                : (definition != null ? definition.getCompressionConfig() : null);
        plan.mcpManager = effectiveMcpManager();
        plan.skillManager = effectiveSkillManager();
        plan.toolSpecs = buildToolSpecifications(plan);
        plan.executors = buildToolExecutors(plan);
        plan.systemPrompt = injectMemory(buildSystemPrompt(), input);
        plan.history = compressHistory(snapshotSessionHistory(), plan);
        plan.sessionId = agentId();
        return plan;
    }

    /**
     * 解析有效最大工具迭代次数
     *
     * @param chainValue 链式设置的值
     * @param defValue   定义中的值
     * @return 有效最大迭代次数
     */
    private static int resolveMaxIters(int chainValue, int defValue) {
        int effective = chainValue != 0 ? chainValue : (defValue != 0 ? defValue : 5);
        return effective > 0 ? effective : SAFETY_MAX_ITERS;
    }

    /**
     * 解析重试配置：链式配置优先，其次 definition，最后默认
     *
     * @return 重试配置
     */
    private AgentRetryConfig resolveRetryConfig() {
        if (!retryConfigured && definition != null && definition.getRetryConfig() != null) {
            return definition.getRetryConfig();
        }
        return AgentRetryConfig.builder()
                .maxRetries(maxRetries)
                .backoffStrategy(retryStrategy)
                .baseDelayMillis(retryBaseDelay)
                .build();
    }

    /**
     * 是否允许继续重试
     *
     * @param retry   重试配置
     * @param error   异常
     * @param attempt 已尝试次数（从 1 起）
     * @return true 表示应重试
     */
    private static boolean shouldRetry(AgentRetryConfig retry, Throwable error, int attempt) {
        if (retry.getRetryPredicate() != null && !retry.getRetryPredicate().shouldRetry(error)) {
            return false;
        }
        int max = retry.getMaxRetries();
        return max < 0 || attempt <= max;
    }

    /**
     * 静默睡眠，被中断时恢复中断标记
     *
     * @param millis 毫秒
     * @return false 表示被中断
     */
    private static boolean sleepQuietly(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 解析系统提示词
     *
     * <p>先取 {@link AgentDefinition} 已经生成的提示词（显式 systemPrompt 或按 definition
     * 内声明拼好的路由表），再把运行期通过 {@link #subAgent(AgentDefinition)} 追加、
     * definition 里没有声明的子 Agent 补进路由表，保证「读到的」与「发出去的」一致。</p>
     *
     * @return 系统提示词
     */
    private String buildSystemPrompt() {
        if (definition == null) {
            return "You are a helpful assistant.";
        }
        String prompt = definition.getSystemPrompt();
        List<AgentDefinition> runtimeSubAgents = undeclaredSubAgents();
        if (definition.isLeader() && !runtimeSubAgents.isEmpty()) {
            prompt = AgentSystemPromptBuilder.build(prompt, runtimeSubAgents);
        }
        if (StringUtils.isEmpty(prompt)) {
            return "You are a helpful assistant.";
        }
        return prompt;
    }

    /**
     * 收集运行期追加、但 definition 中没有同 id 声明的子 Agent。
     *
     * <p>definition 已声明的子 Agent 已经体现在 {@code definition.getSystemPrompt()} 里，
     * 再拼一次会出现重复路由表。</p>
     *
     * @return 需要补进路由表的子 Agent
     */
    private List<AgentDefinition> undeclaredSubAgents() {
        if (subAgentDefinitions.isEmpty()) {
            return List.of();
        }
        List<AgentDefinition> declared = definition.getAgents();
        List<AgentDefinition> extra = new ArrayList<>();
        for (AgentDefinition sub : subAgentDefinitions) {
            boolean exists = false;
            for (AgentDefinition agent : declared) {
                if (agent.getId() != null && agent.getId().equals(sub.getId())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                extra.add(sub);
            }
        }
        return extra;
    }

    /**
     * 检索长期记忆并注入系统提示词
     *
     * @param systemPrompt 原系统提示词
     * @param input        用户输入
     * @return 注入后的系统提示词
     */
    private String injectMemory(String systemPrompt, String input) {
        MemoryManager manager = resolveMemoryManager();
        if (manager == null || StringUtils.isBlank(input)) {
            return systemPrompt;
        }
        try {
            List<MemoryEntry> entries = manager.search(input, MEMORY_SEARCH_LIMIT);
            if (entries == null || entries.isEmpty()) {
                return systemPrompt;
            }
            StringBuilder sb = new StringBuilder(systemPrompt);
            sb.append("\n\n【相关长期记忆】\n");
            for (MemoryEntry entry : entries) {
                if (entry != null && entry.getContent() != null && !entry.getContent().isBlank()) {
                    sb.append("- ").append(entry.getContent()).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[LangChain4j] 记忆检索失败，跳过注入: {}", e.getMessage());
            return systemPrompt;
        }
    }

    /**
     * 保存本轮对话到记忆体
     *
     * @param input  用户输入
     * @param output 助手输出
     */
    private void autoSaveMemory(String input, String output) {
        MemoryManager manager = resolveMemoryManager();
        if (manager == null || StringUtils.isBlank(output)) {
            return;
        }
        try {
            manager.saveFromConversation("用户: " + input + "\n助手: " + output,
                    sessionKey(), agentId());
        } catch (Exception e) {
            log.warn("[LangChain4j] 记忆保存失败: {}", e.getMessage());
        }
    }

    /**
     * 解析记忆管理器（懒创建）
     *
     * @return 记忆管理器；未启用记忆时返回 null
     */
    private MemoryManager resolveMemoryManager() {
        MemoryConfig config = resolveMemoryConfig();
        if (config == null) {
            return null;
        }
        MemoryManager current = this.memoryManager;
        if (current == null) {
            synchronized (this) {
                current = this.memoryManager;
                if (current == null) {
                    current = new MemoryManager(config);
                    this.memoryManager = current;
                }
            }
        }
        return current;
    }

    /**
     * 解析记忆配置，并把工作目录落到自定义 workspace 下
     *
     * @return 记忆配置；未启用记忆时返回 null
     */
    private MemoryConfig resolveMemoryConfig() {
        MemoryConfig config = memoryConfig;
        if (config == null && definition != null) {
            config = definition.getMemoryConfig();
        }
        if (config == null) {
            if (definition == null || !definition.isMemory()) {
                return null;
            }
            config = MemoryConfig.builder().build();
        }
        String workspace = config.getWorkspace();
        if (workspacePath != null && workspacePath.isBlank() == false
                && (StringUtils.isBlank(workspace) || ".agent/memory".equals(workspace))) {
            String resolved = workspacePath + "/.agent/memory";
            return MemoryConfig.builder()
                    .storeType(config.getStoreType())
                    .engine(config.getEngine())
                    .workspace(resolved)
                    .maxContentLength(config.getMaxContentLength())
                    .maxEntries(config.getMaxEntries())
                    .summarizerClient(config.getSummarizerClient())
                    .summarizerModel(config.getSummarizerModel())
                    .summarizerPrompt(config.getSummarizerPrompt())
                    .autoSummarize(config.isAutoSummarize())
                    .build();
        }
        return config;
    }

    /**
     * 关闭并清空记忆管理器
     */
    private void closeMemoryManager() {
        MemoryManager current = this.memoryManager;
        this.memoryManager = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception e) {
                log.debug("[LangChain4j] 关闭记忆管理器失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 压缩会话历史（阈值压缩 + 偏差矫正）
     *
     * @param history 原始历史
     * @param plan    运行计划
     * @return 压缩后的历史
     */
    private List<ChatMessage> compressHistory(List<ChatMessage> history, RunPlan plan) {
        AgentCompressionConfig config = plan.compressionConfig;
        if (config == null || !config.isEnabled() || history.isEmpty()) {
            return history;
        }
        AgentContextCompressionService service = resolveCompressionService(config);
        if (service == null) {
            return history;
        }
        try {
            List<com.chua.common.support.ai.chat.ChatMessage> common = new ArrayList<>(history.size());
            for (ChatMessage message : history) {
                common.add(toCommonMessage(message));
            }
            if (!service.isBaselineSaved()
                    && common.size() >= config.getContextCompressionThreshold()) {
                service.onFirstCompression(common);
            }
            List<com.chua.common.support.ai.chat.ChatMessage> compressed = service.compressContext(common);
            if (compressed != null && compressed.size() < common.size()) {
                common = compressed;
            }
            if (service.shouldTriggerDeviationCorrection()) {
                List<com.chua.common.support.ai.chat.ChatMessage> corrected =
                        service.onDeviationCompression(service.loadBaseline(), common);
                if (corrected != null && !corrected.isEmpty()) {
                    common = corrected;
                }
            }
            List<ChatMessage> result = new ArrayList<>(common.size());
            for (com.chua.common.support.ai.chat.ChatMessage message : common) {
                result.add(fromCommonMessage(message));
            }
            return result;
        } catch (Exception e) {
            log.warn("[LangChain4j] 上下文压缩失败，使用原始历史: {}", e.getMessage());
            return history;
        }
    }

    /**
     * 解析压缩服务（懒创建）
     *
     * @param config 压缩配置
     * @return 压缩服务
     */
    private AgentContextCompressionService resolveCompressionService(AgentCompressionConfig config) {
        AgentContextCompressionService current = this.compressionService;
        if (current == null) {
            synchronized (this) {
                current = this.compressionService;
                if (current == null) {
                    current = new AgentContextCompressionService(config,
                            chatClients.get(null), resolveWorkspace());
                    this.compressionService = current;
                }
            }
        }
        return current;
    }

    /**
     * 解析工作目录
     *
     * @return 工作目录
     */
    private String resolveWorkspace() {
        if (StringUtils.isNotBlank(workspacePath)) {
            return workspacePath;
        }
        return System.getProperty("user.dir");
    }

    /**
     * 会话历史快照
     *
     * @return 快照副本
     */
    private List<ChatMessage> snapshotSessionHistory() {
        synchronized (sessionHistory) {
            return new ArrayList<>(sessionHistory);
        }
    }

    /**
     * 记录一轮对话到会话历史
     *
     * @param input  用户输入
     * @param output 助手输出
     */
    private void recordSessionTurn(String input, String output) {
        if (StringUtils.isBlank(output)) {
            return;
        }
        synchronized (sessionHistory) {
            sessionHistory.add(UserMessage.from(input));
            sessionHistory.add(AiMessage.from(output));
            while (sessionHistory.size() > SESSION_HISTORY_LIMIT) {
                sessionHistory.remove(0);
            }
        }
    }

    /**
     * 追加用户反馈（人工确认时的修改意见）到会话历史
     *
     * @param feedback 用户修改意见
     */
    private void appendSessionFeedback(String feedback) {
        synchronized (sessionHistory) {
            sessionHistory.add(UserMessage.from(feedback));
            while (sessionHistory.size() > SESSION_HISTORY_LIMIT) {
                sessionHistory.remove(0);
            }
        }
    }

    /**
     * langchain4j 消息转 common 消息
     *
     * @param message langchain4j 消息
     * @return common 消息
     */
    private static com.chua.common.support.ai.chat.ChatMessage toCommonMessage(ChatMessage message) {
        String role;
        String content;
        if (message instanceof SystemMessage systemMessage) {
            role = "system";
            content = systemMessage.text();
        } else if (message instanceof UserMessage userMessage) {
            role = "user";
            content = userMessage.hasSingleText() ? userMessage.singleText() : userMessage.toString();
        } else if (message instanceof AiMessage aiMessage) {
            role = "assistant";
            content = aiMessage.text();
        } else {
            role = "tool";
            content = message.toString();
        }
        return com.chua.common.support.ai.chat.ChatMessage.builder()
                .role(role)
                .content(content)
                .build();
    }

    /**
     * common 消息转 langchain4j 消息
     *
     * @param message common 消息
     * @return langchain4j 消息
     */
    private static ChatMessage fromCommonMessage(com.chua.common.support.ai.chat.ChatMessage message) {
        String role = message.getRole() != null ? message.getRole() : "user";
        String content = message.getContent() != null ? message.getContent() : "";
        return switch (role) {
            case "system" -> SystemMessage.from(content);
            case "assistant" -> AiMessage.from(content);
            default -> UserMessage.from(content);
        };
    }

    /**
     * 构建工具规格列表
     *
     * @param plan 运行计划
     * @return 工具规格列表
     */
    private List<ToolSpecification> buildToolSpecifications(RunPlan plan) {
        List<ToolSpecification> specs = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();

        if (plan.mcpManager != null) {
            for (McpToolDescriptor descriptor : plan.mcpManager.listAllTools()) {
                if (descriptor == null || descriptor.getName() == null
                        || isToolDisabled(descriptor.getName())
                        || !names.add(descriptor.getName())) {
                    continue;
                }
                JsonObjectSchema parameters = buildParametersFromSchema(descriptor.getInputSchema());
                specs.add(ToolSpecification.builder()
                        .name(descriptor.getName())
                        .description(descriptor.getDescription())
                        .parameters(parameters)
                        .build());
            }
        }

        for (AgentTool tool : localTools.values()) {
            if (!isToolDisabled(tool.getName()) && names.add(tool.getName())) {
                specs.add(ToolSpecification.builder()
                        .name(tool.getName())
                        .description(tool.getDescription() != null
                                ? tool.getDescription() : "Tool: " + tool.getName())
                        .parameters(buildParametersFromSchema(tool.getParameters()))
                        .build());
            }
        }

        for (SkillDefinition skill : collectSkills(plan)) {
            if (skill == null || skill.getName() == null || isToolDisabled(skill.getName())
                    || !names.add(skill.getName())) {
                continue;
            }
            JsonObjectSchema parameters = buildParametersFromSkill(skill);
            specs.add(ToolSpecification.builder()
                    .name(skill.getName())
                    .description(skill.getDescription())
                    .parameters(parameters)
                    .build());
        }
        return specs;
    }

    /**
     * 构建工具执行器映射
     *
     * @param plan 运行计划
     * @return 工具名称到执行器的映射
     */
    private Map<String, ToolExecutor> buildToolExecutors(RunPlan plan) {
        Map<String, ToolExecutor> executors = new LinkedHashMap<>();

        if (plan.mcpManager != null) {
            for (McpToolDescriptor descriptor : plan.mcpManager.listAllTools()) {
                if (descriptor == null || descriptor.getName() == null
                        || isToolDisabled(descriptor.getName())) {
                    continue;
                }
                executors.putIfAbsent(descriptor.getName(), args -> {
                    String serverName = descriptor.getServerName();
                    if (serverName == null) {
                        serverName = "";
                    }
                    McpToolResult result = plan.mcpManager.callTool(
                            serverName, new McpToolCall(descriptor.getName(), args));
                    return result.isSuccess()
                            ? String.valueOf(result.getContent())
                            : result.getErrorMessage();
                });
            }
        }

        for (AgentTool tool : localTools.values()) {
            if (isToolDisabled(tool.getName())) {
                continue;
            }
            executors.putIfAbsent(tool.getName(), args -> String.valueOf(tool.getExecutor().execute(args)));
        }

        for (SkillDefinition skill : collectSkills(plan)) {
            if (skill == null || skill.getName() == null || isToolDisabled(skill.getName())) {
                continue;
            }
            executors.putIfAbsent(skill.getName(), args -> {
                SkillResult result = skill.execute(args);
                return result.isSuccess()
                        ? String.valueOf(result.getContent())
                        : result.getErrorMessage();
            });
        }
        return executors;
    }

    /**
     * 判断工具是否已被 {@link #disableTool(String)} 关闭
     *
     * @param name 工具名称
     * @return 已关闭返回 true
     */
    private boolean isToolDisabled(String name) {
        return name != null && disabledTools.contains(name);
    }

    /**
     * 汇总技能定义：管理器中的技能 + 本地注册的技能（本地优先）
     *
     * @param plan 运行计划
     * @return 技能定义列表
     */
    private List<SkillDefinition> collectSkills(RunPlan plan) {
        Map<String, SkillDefinition> all = new LinkedHashMap<>();
        if (plan.skillManager != null && plan.skillManager.getAll() != null) {
            all.putAll(plan.skillManager.getAll());
        }
        all.putAll(localSkills);
        return new ArrayList<>(all.values());
    }

    /**
     * 有效 MCP 管理器
     *
     * @return MCP 管理器；关闭或无配置时返回 null
     */
    private McpManager effectiveMcpManager() {
        if (!mcpEnabled || (definition != null && !definition.isMcp())) {
            return null;
        }
        if (globalMcpManager != null) {
            return globalMcpManager;
        }
        return definition != null ? definition.getMcpManager() : null;
    }

    /**
     * 有效 Skill 管理器
     *
     * @return Skill 管理器；无配置时返回 null
     */
    private SkillManager effectiveSkillManager() {
        if (globalSkillManager != null) {
            return globalSkillManager;
        }
        return definition != null ? definition.getSkillManager() : null;
    }

    /**
     * 执行单个工具调用
     *
     * @param request   工具执行请求
     * @param executors 工具执行器映射
     * @return 工具执行结果文本
     */
    private String executeTool(ToolExecutionRequest request, Map<String, ToolExecutor> executors) {
        ToolExecutor executor = executors.get(request.name());
        if (executor == null) {
            return guardToolResult(request.name(), "Error: unknown tool '" + request.name() + "'");
        }
        try {
            Map<String, Object> args = parseArguments(request.arguments());
            return guardToolResult(request.name(), truncate(executor.execute(args)));
        } catch (Exception e) {
            return guardToolResult(request.name(),
                    "Error executing tool '" + request.name() + "': " + e.getMessage());
        }
    }

    /**
     * 按配置截断工具结果
     *
     * @param text 原始结果
     * @return 截断后的结果
     */
    private String truncate(String text) {
        if (text == null || toolResultMaxChars <= 0 || text.length() <= toolResultMaxChars) {
            return text;
        }
        int head = Math.min(Math.max(toolResultHeadChars, 0), text.length());
        return text.substring(0, head)
                + "\n...[truncated " + (text.length() - head) + " chars]";
    }

    /**
     * 解析工具调用参数 JSON 为 映射
     *
     * @param json JSON 字符串
     * @return 参数映射
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.trim().isEmpty() || "{}".equals(json.trim())) {
            return Map.of();
        }
        return (Map<String, Object>) Json.fromJson(json, Map.class);
    }

    /**
     * 从 MCP 工具的 JSON 模式 构建 langchain4j 参数描述
     *
     * @param schema MCP 工具的输入 模式
     * @return 参数描述对象
     */
    @SuppressWarnings("unchecked")
    private static JsonObjectSchema buildParametersFromSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return JsonObjectSchema.builder().additionalProperties(true).build();
        }

        JsonObjectSchema.Builder builder = JsonObjectSchema.builder()
                .description("Parameters for the tool");

        Object properties = schema.get("properties");
        if (properties instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) properties).entrySet()) {
                builder.addProperty(entry.getKey(), JsonStringSchema.builder().build());
            }
        }

        Object required = schema.get("required");
        if (required instanceof List) {
            List<String> reqList = new ArrayList<>();
            for (Object item : (List<?>) required) {
                if (item instanceof String) {
                    reqList.add((String) item);
                }
            }
            if (!reqList.isEmpty()) {
                builder.required(reqList);
            }
        }
        return builder.build();
    }

    /**
     * 从 Skill 定义构建 langchain4j 参数描述
     *
     * @param skill Skill 定义
     * @return 参数描述对象
     */
    private static JsonObjectSchema buildParametersFromSkill(SkillDefinition skill) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder()
                .description(skill.getDescription());
        List<String> requiredArgs = new ArrayList<>();

        for (com.chua.common.support.ai.skill.SkillArgumentSchema arg : skill.getArguments()) {
            JsonSchemaElement property = switch (arg.getType().toLowerCase()) {
                case "number" -> JsonNumberSchema.builder()
                        .description(arg.getDescription()).build();
                case "integer" -> JsonIntegerSchema.builder()
                        .description(arg.getDescription()).build();
                case "boolean" -> JsonBooleanSchema.builder()
                        .description(arg.getDescription()).build();
                case "enum" -> JsonEnumSchema.builder()
                        .description(arg.getDescription())
                        .enumValues(arg.getEnumValues() != null ? arg.getEnumValues() : List.of())
                        .build();
                default -> JsonStringSchema.builder()
                        .description(arg.getDescription()).build();
            };
            builder.addProperty(arg.getName(), property);
            if (arg.isRequired()) {
                requiredArgs.add(arg.getName());
            }
        }

        if (!requiredArgs.isEmpty()) {
            builder.required(requiredArgs);
        }
        return builder.build();
    }

    /**
     * 打印架构图
     *
     * @param plan 运行计划
     */
    private void printArchitectureDiagram(RunPlan plan) {
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
        for (ChatClient client : chatClients.values()) {
            sb.append("  ChatClient: ").append(client).append('\n');
        }
        sb.append("SubAgents (").append(subAgentDefinitions.size()).append("):\n");
        int index = 1;
        for (AgentDefinition sub : subAgentDefinitions) {
            String subId = sub.getId() != null ? sub.getId() : ("sub-" + index);
            String subName = sub.getName() != null ? sub.getName() : "Unnamed";
            sb.append("  [").append(index).append("] ").append(subId)
                    .append(" | ").append(subName).append('\n');
            index++;
        }
        log.info("\n{}", sb);
    }

    /**
     * 打印系统提示词树
     *
     * @param plan 运行计划
     */
    private void printSystemPromptsTree(RunPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(PROMPTS_TREE_HEADER).append('\n');
        String mainName = definition != null && definition.getName() != null
                ? definition.getName() : "?";
        sb.append("[Main Agent] ").append(mainName).append('\n');
        sb.append("  - prompt: ")
                .append(definition != null && definition.getInstruction() != null
                        ? definition.getInstruction() : "")
                .append('\n');
        for (AgentDefinition sub : subAgentDefinitions) {
            sb.append("  - ").append(sub.getName() != null ? sub.getName() : sub.getId()).append('\n');
            sb.append("      prompt: ")
                    .append(sub.getInstruction() != null ? sub.getInstruction() : "").append('\n');
        }
        log.info("[AgentTree] begin\n{}\n[AgentTree] end", sb);
    }

    /**
     * 打印架构概览
     *
     * @param plan 运行计划
     */
    private void logArchitecture(RunPlan plan) {
        log.info("[Agent] 主Agent: id={}, name={}, maxIters={}, mode={}",
                plan.agentId,
                definition != null && definition.getName() != null ? definition.getName() : "Agent",
                plan.maxIters, modeName());
        log.info("[Agent] 子Agent数量: {}", subAgentDefinitions.size());
        for (int i = 0; i < subAgentDefinitions.size(); i++) {
            AgentDefinition sub = subAgentDefinitions.get(i);
            log.info("[Agent] 子 Agent [{}] id={}, name={}, maxIters={}", i + 1,
                    sub.getId() != null ? sub.getId() : ("sub-" + (i + 1)),
                    sub.getName() != null ? sub.getName() : "Unnamed",
                    resolveMaxIters(sub.getMaxToolIterations(), plan.maxIters));
        }
        log.info("[Agent] ChatClient映射: {} 个, memory: {}, compression: {}, plan: {}, mcp: {}",
                chatClients.size(),
                resolveMemoryConfig() != null ? "ON" : "OFF",
                plan.compressionConfig != null && plan.compressionConfig.isEnabled() ? "ON" : "OFF",
                plan.planEnabled, plan.mcpManager != null);
    }

    /**
     * 触发调试 Hook（Hook 异常不影响主流程）
     *
     * @param hook      调试 Hook
     * @param type      事件类型
     * @param agentId   Agent 标识
     * @param message   消息
     * @param iteration 轮次
     */
    private static void fireDebug(AgentDebugHook hook, String type, String agentId,
                                  String message, int iteration) {
        if (hook == null) {
            return;
        }
        try {
            hook.onDebug(AgentHookEvent.of(type, agentId, message, iteration));
        } catch (Exception e) {
            log.warn("[LangChain4j] debugHook 回调失败: {}", e.getMessage());
        }
    }

    /**
     * 触发规划 Hook（Hook 异常不影响主流程）
     *
     * @param hook    规划 Hook
     * @param type    事件类型
     * @param agentId Agent 标识
     * @param message 消息
     */
    private static void firePlan(AgentPlanHook hook, String type, String agentId, String message) {
        if (hook == null) {
            return;
        }
        try {
            hook.onPlan(AgentHookEvent.of(type, agentId, message));
        } catch (Exception e) {
            log.warn("[LangChain4j] planHook 回调失败: {}", e.getMessage());
        }
    }

    /**
     * Agent 标识
     *
     * @return 标识
     */
    private String agentId() {
        return definition != null && definition.getId() != null ? definition.getId() : "agent";
    }

    /**
     * 会话键
     *
     * @return 会话标识
     */
    private String sessionKey() {
        return defaultSessionId != null ? defaultSessionId : agentId();
    }

    /**
     * 运行模式名称
     *
     * @return 模式名称
     */
    private String modeName() {
        return mode != null ? mode.name() : AgentMode.AUTO.name();
    }

    /**
     * 带风险门禁的工具执行（同步链路）。
     *
     * <p>拒绝与需确认的工具调用一律不真实执行：拒绝把理由作为工具结果回给模型改道；
     * 需确认（含危险级硬底线）在同步链路没有确认事件通道，fail-safe 跳过执行，
     * 把「需人工确认」作为工具结果回给模型。</p>
     *
     * @param plan          运行计划
     * @param request       工具执行请求
     * @param budgetTracker 预算记账器（可空）
     * @return 工具结果文本（拒绝 / 跳过时为说明文案）
     */
    private String executeToolWithGate(RunPlan plan, ToolExecutionRequest request,
                                       AgentBudgetTracker budgetTracker) {
        AgentRiskDecision decision = adjudicateTool(plan, request);
        if (decision != null && decision.verdict() == AgentRiskDecision.Verdict.DENY) {
            log.info("[LangChain4j] 风险裁决拒绝执行工具 {}: {}",
                    request.name(), decision.reason() != null ? decision.reason() : "未说明原因");
            return "风险裁决拒绝执行该工具调用: "
                    + (decision.reason() != null ? decision.reason() : "未说明原因");
        }
        if (decision != null && decision.verdict() == AgentRiskDecision.Verdict.CONFIRM_REQUIRED) {
            log.info("[LangChain4j] 工具 {} 需人工确认，同步链路跳过执行: {}",
                    request.name(), decision.reason() != null ? decision.reason() : "风险控制");
            return "该工具调用需人工确认（" + (decision.reason() != null ? decision.reason() : "风险控制")
                    + "），同步执行链路已跳过；如需执行请改用流式链路并完成人工确认。";
        }
        String resultText = executeTool(request, plan.executors);
        if (budgetTracker != null) {
            budgetTracker.recordToolCall();
        }
        return resultText;
    }

    /**
     * 风险裁决：显式裁决器优先；裁决器异常按需确认处理（fail-safe）；
     * 危险级是硬底线——即使裁决器放行也强制需人工确认。
     *
     * @param plan    运行计划
     * @param request 工具执行请求
     * @return 裁决结论；无裁决器且非危险级时为 null（放行）
     */
    private AgentRiskDecision adjudicateTool(RunPlan plan, ToolExecutionRequest request) {
        String riskLevel = ToolRiskPolicy.classify(request.name(), request.arguments());
        AgentRiskAdjudicator adjudicator = riskAdjudicator();
        AgentRiskDecision decision = null;
        if (adjudicator != null) {
            try {
                decision = adjudicator.adjudicate(AgentRiskRequest.of(
                        plan.agentId, request.name(), request.arguments(), riskLevel));
            } catch (Exception e) {
                log.warn("[LangChain4j] 风险裁决器执行失败，按需确认处理: {}", e.getMessage());
                decision = AgentRiskDecision.confirmRequired("风险裁决器异常: " + e.getMessage());
            }
        }
        if (decision != null && decision.verdict() != AgentRiskDecision.Verdict.ALLOW) {
            return decision;
        }
        if (ToolRiskPolicy.isDangerous(riskLevel)) {
            return AgentRiskDecision.confirmRequired("危险操作（风险等级: " + riskLevel + "），必须人工确认");
        }
        return decision;
    }

    /**
     * 判断本批工具是否可并行执行：批内多于一个工具、全部裁决放行且安全策略允许并行。
     *
     * <p>只要有一个工具被拒绝或需确认，整批走串行路径，保证风险处置语义一致。</p>
     *
     * @param plan  运行计划
     * @param batch 本批工具请求
     * @return true 表示可并行
     */
    private boolean isParallelBatch(RunPlan plan, List<ToolExecutionRequest> batch) {
        if (batch == null || batch.size() < 2) {
            return false;
        }
        AgentToolSafetyPolicy policy = toolSafetyPolicy();
        for (ToolExecutionRequest request : batch) {
            AgentRiskDecision decision = adjudicateTool(plan, request);
            if (decision != null && decision.verdict() != AgentRiskDecision.Verdict.ALLOW) {
                return false;
            }
            if (!policy.isParallelSafe(request.name(), request.arguments())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 并行执行一批已放行且并行安全的工具，结果按提交顺序回填。
     *
     * <p>先按序下发 TOOL_START，再交 {@link #PARALLEL_TOOL_EXECUTOR} 并行执行，
     * 最后按提交顺序回填 TOOL_RESULT 与轨迹结束行，保证事件流有序可回放。</p>
     *
     * @param plan          运行计划
     * @param batch         本批工具请求（全部放行且并行安全）
     * @param results       结果回填列表
     * @param iter          当前迭代轮次（轨迹 span 用）
     * @param sink          事件接收器
     * @param budgetTracker 预算记账器（可空）
     * @return 中止原因；正常完成为 null
     */
    private String runParallelBatch(RunPlan plan, List<ToolExecutionRequest> batch,
                                    List<ToolExecutionResultMessage> results, int iter,
                                    FluxSink<AgentStreamEvent> sink, AgentBudgetTracker budgetTracker) {
        List<String> spanIds = new ArrayList<>(batch.size());
        List<Long> startTimes = new ArrayList<>(batch.size());
        for (ToolExecutionRequest request : batch) {
            long startAt = System.currentTimeMillis();
            String spanId = AgentTrace.newSpanId(plan.agentId, "tool", iter);
            spanIds.add(spanId);
            startTimes.add(startAt);
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.TOOL_START)
                    .agentId(plan.agentId)
                    .toolName(request.name())
                    .toolInput(request.arguments())
                    .timestamp(startAt)
                    .build());
            sink.next(AgentStreamEvent.trace(AgentTrace.builder()
                    .kind(AgentTrace.KIND_TOOL_CALL)
                    .agentId(plan.agentId)
                    .spanId(spanId)
                    .toolName(request.name())
                    .text("调用工具: " + request.name())
                    .timestamp(startAt)
                    .build()));
        }
        List<Callable<String>> tasks = new ArrayList<>(batch.size());
        for (ToolExecutionRequest request : batch) {
            tasks.add(() -> executeTool(request, plan.executors));
        }
        List<String> outputs;
        try {
            outputs = PARALLEL_TOOL_EXECUTOR.execute(tasks);
        } catch (Exception e) {
            // 并行执行异常：回退串行执行，保证行为与串行链路一致
            log.warn("[LangChain4j] 并行工具执行异常，回退串行: {}", e.getMessage());
            outputs = new ArrayList<>(batch.size());
            for (ToolExecutionRequest request : batch) {
                outputs.add(executeTool(request, plan.executors));
            }
        }
        for (int i = 0; i < batch.size(); i++) {
            ToolExecutionRequest request = batch.get(i);
            String resultText = i < outputs.size() && outputs.get(i) != null
                    ? outputs.get(i) : "Error: tool '" + request.name() + "' produced no result";
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.TOOL_RESULT)
                    .agentId(plan.agentId)
                    .toolName(request.name())
                    .toolOutput(resultText)
                    .timestamp(System.currentTimeMillis())
                    .build());
            sink.next(AgentStreamEvent.trace(AgentTrace.spanEnd(
                    AgentTrace.KIND_TOOL_RESULT, plan.agentId, spanIds.get(i),
                    request.name(), resultText, startTimes.get(i))));
            results.add(ToolExecutionResultMessage.from(request, resultText));
            if (budgetTracker != null) {
                budgetTracker.recordToolCall();
            }
        }
        return null;
    }

    /**
     * 推送一个未真实执行的工具结果事件（如风险拒绝），保持前端事件流完整可回放
     *
     * @param sink       事件接收器
     * @param agentId    Agent 标识
     * @param request    工具执行请求
     * @param resultText 结果文案
     * @param iter       当前迭代轮次
     */
    private void emitToolResultEvent(FluxSink<AgentStreamEvent> sink, String agentId,
                                     ToolExecutionRequest request, String resultText, int iter) {
        long now = System.currentTimeMillis();
        String spanId = AgentTrace.newSpanId(agentId, "tool", iter);
        sink.next(AgentStreamEvent.builder()
                .type(AgentStreamEvent.Type.TOOL_START)
                .agentId(agentId)
                .toolName(request.name())
                .toolInput(request.arguments())
                .timestamp(now)
                .build());
        sink.next(AgentStreamEvent.trace(AgentTrace.builder()
                .kind(AgentTrace.KIND_TOOL_CALL)
                .agentId(agentId)
                .spanId(spanId)
                .toolName(request.name())
                .text("调用工具: " + request.name())
                .timestamp(now)
                .build()));
        sink.next(AgentStreamEvent.builder()
                .type(AgentStreamEvent.Type.TOOL_RESULT)
                .agentId(agentId)
                .toolName(request.name())
                .toolOutput(resultText)
                .timestamp(now)
                .build());
        sink.next(AgentStreamEvent.trace(AgentTrace.spanEnd(
                AgentTrace.KIND_TOOL_RESULT, agentId, spanId, request.name(), resultText, now)));
    }

    /**
     * 记录一次模型调用的令牌用量到预算记账器
     *
     * @param tracker 记账器（可空）
     * @param usage   本次调用用量（可空）
     */
    private void recordBudgetUsage(AgentBudgetTracker tracker, TokenUsage usage) {
        if (tracker == null || usage == null) {
            return;
        }
        int input = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        int output = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
        if (input > 0 || output > 0) {
            tracker.recordTokens(input, output);
        }
    }

    /**
     * 构造评测上下文（会话取 sessionKey，模式区分 sync / stream）
     *
     * @param input 输入文本
     * @param mode  执行模式
     * @return 评测上下文
     */
    private AgentEvalContext newEvalContext(String input, String mode) {
        return AgentEvalContext.of(agentId(), input).withMode(mode, sessionKey());
    }

    /**
     * 触发评测开始钩子；钩子异常只记日志，绝不影响主流程
     *
     * @param context 运行上下文
     */
    private void fireEvalStart(AgentEvalContext context) {
        AgentEvalHook hook = evalHook();
        if (hook == null) {
            return;
        }
        try {
            hook.onRunStart(context);
        } catch (Exception e) {
            log.warn("[LangChain4j] 评测 onRunStart 钩子异常: {}", e.getMessage());
        }
    }

    /**
     * 触发评测结束钩子；钩子异常只记日志，绝不影响主流程
     *
     * @param context  运行上下文
     * @param response 运行结果（失败时可为 null）
     * @param error    失败异常（成功时为 null）
     */
    private void fireEvalEnd(AgentEvalContext context, AgentResponse response, Exception error) {
        AgentEvalHook hook = evalHook();
        if (hook == null) {
            return;
        }
        long duration = Math.max(0L, System.currentTimeMillis() - context.startMillis());
        try {
            if (error != null) {
                hook.onRunEnd(AgentEvalRecord.failure(context,
                        error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName(),
                        duration));
                return;
            }
            hook.onRunEnd(AgentEvalRecord.success(context,
                    response != null ? response.getOutput() : null,
                    duration,
                    response != null ? response.getUsage() : null));
        } catch (Exception e) {
            log.warn("[LangChain4j] 评测 onRunEnd 钩子异常: {}", e.getMessage());
        }
    }

    /**
     * 按预算配置创建记账器；未配置或无界预算返回 null（不记账）
     *
     * @return 记账器；不限额时为 null
     */
    private AgentBudgetTracker createBudgetTracker() {
        AgentBudget budget = budget();
        return budget != null && budget.isBounded() ? new SimpleAgentBudgetTracker(budget) : null;
    }

    /**
     * 把挂起前的预算消耗回放到新记账器：经记账 API 重放（而非直接改内部状态），
     * 熔断闩锁按与正常运行完全相同的判定规则触发——挂起前已超限的维度在重放时即置位。
     *
     * <p>时间预算无法精确延续（记账器起始时间从恢复点重新计），属已知限制：
     * 时长上限按恢复轮自身耗时独立判定。</p>
     *
     * @param tracker 目标记账器
     * @param snap    挂起前的预算消耗快照
     */
    private static void replayBudget(AgentBudgetTracker tracker, PendingPause.BudgetSnapshot snap) {
        tracker.recordTokens(snap.inputTokens(), snap.outputTokens());
        long calls = Math.max(0, snap.toolCalls());
        for (long i = 0; i < calls; i++) {
            tracker.recordToolCall();
        }
        if (snap.costMicros() > 0) {
            tracker.recordCost(snap.costMicros());
        }
    }

    /**
     * 预算是否已超限
     *
     * @param tracker 记账器（可空）
     * @return true 表示已超限
     */
    private boolean isBudgetExceeded(AgentBudgetTracker tracker) {
        return tracker != null && tracker.isExceeded();
    }

    /**
     * 预算超限文案
     *
     * @param tracker 记账器
     * @return 文案
     */
    private String budgetExceededText(AgentBudgetTracker tracker) {
        return "预算已超限，运行中止: "
                + (tracker != null && tracker.violationReason() != null
                ? tracker.violationReason() : "未知预算项");
    }

    /**
     * 构造预算超限错误响应
     *
     * @param tracker  记账器
     * @param attempts 已尝试次数
     * @return 响应
     */
    private AgentResponse budgetExceededResponse(AgentBudgetTracker tracker, int attempts) {
        AgentResponse resp = errorResponse(budgetExceededText(tracker), attempts);
        resp.getMetadata().put("budgetExceeded", Boolean.TRUE);
        return resp;
    }

    /**
     * 取消原因文案
     *
     * @return 文案
     */
    private String cancelReasonText() {
        String reason = cancellationToken().reason();
        return reason != null ? reason : "用户取消";
    }

    /**
     * 构造取消收尾响应（按取消语义，不带错误标记）
     *
     * @param attempts 已尝试次数
     * @return 响应
     */
    private AgentResponse cancelledResponse(int attempts) {
        AgentResponse resp = AgentResponse.builder()
                .output("")
                .mode(modeName())
                .build();
        resp.getMetadata().put("cancelled", Boolean.TRUE);
        resp.getMetadata().put("reason", cancelReasonText());
        resp.getMetadata().put("attempts", attempts);
        return resp;
    }

    /**
     * 计划送审：配置了计划确认器且本轮生成了计划时，正式执行前送审
     *
     * @param plan     运行计划
     * @param input    用户输入
     * @param planText 规划文本（可空）
     * @return 审批结论；无需送审（未配置确认器 / 无计划文本）时为 null
     */
    private AgentPlanConfirmation.Outcome reviewPlan(RunPlan plan, String input, String planText) {
        AgentPlanConfirmation confirmation = planConfirmation();
        if (confirmation == null || StringUtils.isBlank(planText)) {
            return null;
        }
        AgentPlan agentPlan = AgentPlan.of(abbreviateText(planText.trim(), 512),
                parsePlanSteps(planText));
        try {
            return confirmation.confirm(agentPlan);
        } catch (Exception e) {
            // 确认器异常按拒绝处理：宁可终止也不绕过人工审批
            log.warn("[LangChain4j] 计划确认器异常，按拒绝处理: {}", e.getMessage());
            return AgentPlanConfirmation.Outcome.reject("计划确认器异常: " + e.getMessage());
        }
    }

    /**
     * 从计划文本解析步骤列表（编号 / 短横线行），解析不出时整段作为单一步骤
     *
     * @param planText 规划文本
     * @return 步骤列表
     */
    private List<String> parsePlanSteps(String planText) {
        List<String> steps = new ArrayList<>();
        for (String line : planText.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.matches("^\\d+[.、)．]\\s*\\S.*")
                    || trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                steps.add(trimmed);
            }
        }
        if (steps.isEmpty()) {
            steps.add(abbreviateText(planText.trim(), 512));
        }
        return steps;
    }

    /**
     * 截断文本（超出上限时以省略号收尾）
     *
     * @param text 原文本（可空）
     * @param max  最大长度
     * @return 截断后的文本
     */
    private String abbreviateText(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

    /**
     * 计划审批结论文案（终止时回给调用方 / 模型上下文）
     *
     * @param outcome 审批结论
     * @return 文案；放行为 null
     */
    private String planDecisionText(AgentPlanConfirmation.Outcome outcome) {
        if (outcome == null) {
            return null;
        }
        if (outcome.decision() == AgentPlanConfirmation.Decision.REJECT) {
            return "计划被拒绝，本次执行终止: "
                    + (outcome.feedback() != null ? outcome.feedback() : "未说明原因");
        }
        if (outcome.decision() == AgentPlanConfirmation.Decision.REVISE) {
            return "计划需修订，本次执行终止: "
                    + (outcome.feedback() != null ? outcome.feedback() : "未提供修改意见");
        }
        return null;
    }

    /**
     * 构造计划被拒 / 待修订的终止响应
     *
     * @param outcome  审批结论
     * @param acc      用量累加器
     * @param attempts 已尝试次数
     * @return 响应
     */
    private AgentResponse planDecisionResponse(AgentPlanConfirmation.Outcome outcome,
                                               UsageAcc acc, int attempts) {
        AgentResponse resp = AgentResponse.builder()
                .output(planDecisionText(outcome))
                .mode(modeName())
                .usage(acc.build())
                .build();
        resp.getMetadata().put("attempts", attempts);
        resp.getMetadata().put("planDecision", outcome != null ? outcome.decision().name() : null);
        return resp;
    }

    /**
     * 构造错误响应
     *
     * @param message 错误信息
     * @return 响应
     */
    private AgentResponse errorResponse(String message) {
        return errorResponse(message, 0);
    }

    /**
     * 构造错误响应
     *
     * @param message  错误信息
     * @param attempts 已尝试次数
     * @return 响应
     */
    private AgentResponse errorResponse(String message, int attempts) {
        AgentResponse resp = AgentResponse.builder()
                .output("")
                .mode(modeName())
                .build();
        resp.getMetadata().put("error", message);
        resp.getMetadata().put("attempts", attempts);
        return resp;
    }

    /**
     * 构造中断收尾响应
     *
     * @param attempts 已尝试次数
     * @return 响应
     */
    private AgentResponse interruptedResponse(int attempts) {
        AgentResponse resp = AgentResponse.builder()
                .output("")
                .mode(modeName())
                .build();
        resp.getMetadata().put("aborted", Boolean.TRUE);
        resp.getMetadata().put("reason", "interrupted");
        resp.getMetadata().put("attempts", attempts);
        resp.getMetadata().put("agentId", agentId());
        return resp;
    }

    /**
     * 构造错误流式事件
     *
     * @param message 错误信息
     * @return 事件
     */
    private AgentStreamEvent errorEvent(String message) {
        return AgentStreamEvent.builder()
                .type(AgentStreamEvent.Type.ERROR)
                .agentId(agentId())
                .toolOutput(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 关闭 Agent，释放资源
     *
     * <p>关闭即等于中断：正在跑的一轮会在下一个循环点退出，
     * 不会留下一个已经没人要却还在调用工具的循环。
     */
    @Override
    public void close() {
        loopGuard.interrupt();
        closeMemoryManager();
        this.compressionService = null;
    }

    /**
     * 写入一条待确认暂停态（委托 {@link #suspendStore()}，带 TTL 与容量逐出）。
     *
     * <p>原静态有界缓存已外置为 {@link AgentSuspendState} + {@link AgentSuspendStore}：
     * 未显式注入存储时回落到进程级 {@link InMemoryAgentSuspendStore#globalDefault()}，
     * 条目带 TTL，用户中途放弃或决策未送达时自动过期清除，不再无限残留。</p>
     *
     * @param replyId 回复标识，为空不缓存
     * @param pause   暂停态
     */
    private void cachePendingPause(String replyId, PendingPause pause) {
        if (replyId == null || pause == null) {
            return;
        }
        try {
            suspendStore().put(AgentSuspendState.of(replyId, pause.agentId, pause,
                    InMemoryAgentSuspendStore.DEFAULT_TTL_MILLIS));
        } catch (Exception e) {
            log.warn("[LangChain4j] 写入挂起状态失败 replyId={}: {}", replyId, e.getMessage());
        }
    }

    /**
     * 取走并逐出一条待确认暂停态（委托 {@link #suspendStore()}，一次性消费语义）
     *
     * @param replyId 回复标识，可为空
     * @return 暂停态；无对应缓存、已过期或载荷类型不符时为 null
     */
    private PendingPause takePendingPause(String replyId) {
        if (replyId == null) {
            return null;
        }
        try {
            AgentSuspendState state = suspendStore().remove(replyId);
            if (state == null || state.isExpired()) {
                return null;
            }
            Object payload = state.payload();
            return payload instanceof PendingPause ? (PendingPause) payload : null;
        } catch (Exception e) {
            log.warn("[LangChain4j] 读取挂起状态失败 replyId={}: {}", replyId, e.getMessage());
            return null;
        }
    }

    /**
     * 人工确认/提问暂停态快照
     *
     * @author CH
     * @since 4.0.0
     */
    private static final class PendingPause {

        /**
         * 暂停批次标识（恢复时原样带回）
         */
        private final String replyId;

        /**
         * Agent 标识
         */
        private final String agentId;

        /**
         * 触发本轮的原始用户输入
         */
        private final String originalInput;

        /**
         * 尚未执行的工具请求（确认类挂起）；提问类挂起为空
         */
        private final List<ToolExecutionRequest> pendingRequests;

        /**
         * 暂停时的消息序列（已含带工具请求的 AiMessage 与已执行工具的结果）
         */
        private final List<ChatMessage> history;

        /**
         * 是否提问类挂起（本实现不产生提问，恒为 false）
         */
        private final boolean question;

        /**
         * 暂停时的护栏状态快照：恢复时回放，重复判定与中断信号跨挂起延续
         */
        private final AgentLoopGuard.Snapshot loopGuardSnapshot;

        /**
         * 暂停时的预算消耗快照：恢复时回放记账，熔断闩锁跨挂起延续；未记账为 null
         */
        private final BudgetSnapshot budgetSnapshot;

        private PendingPause(String replyId, String agentId, String originalInput,
                             List<ToolExecutionRequest> pendingRequests,
                             List<ChatMessage> history, boolean question,
                             AgentLoopGuard.Snapshot loopGuardSnapshot,
                             BudgetSnapshot budgetSnapshot) {
            this.replyId = replyId;
            this.agentId = agentId;
            this.originalInput = originalInput;
            this.pendingRequests = pendingRequests;
            this.history = history;
            this.question = question;
            this.loopGuardSnapshot = loopGuardSnapshot;
            this.budgetSnapshot = budgetSnapshot;
        }

        /**
         * 预算消耗快照（跨挂起延续记账）
         *
         * @param inputTokens  已用输入 Token
         * @param outputTokens 已用输出 Token
         * @param toolCalls    已用工具调用次数
         * @param costMicros   已用费用（微单位）
         */
        private record BudgetSnapshot(long inputTokens, long outputTokens,
                                      long toolCalls, long costMicros) {
        }
    }

    /**
     * 运行计划：一次运行所需的全部装配结果
     *
     * @author CH
     * @since 4.0.0
     */
    private static final class RunPlan {

        /**
         * Agent 标识
         */
        private String agentId;

        /**
         * 系统提示词（已注入记忆）
         */
        private String systemPrompt;

        /**
         * 历史会话快照（已压缩）
         */
        private List<ChatMessage> history = List.of();

        /**
         * 工具规格
         */
        private List<ToolSpecification> toolSpecs = List.of();

        /**
         * 工具执行器
         */
        private Map<String, ToolExecutor> executors = Map.of();

        /**
         * 最大迭代次数
         */
        private int maxIters = 5;

        /**
         * 是否启用规划
         */
        private boolean planEnabled;

        /**
         * 是否首轮只规划
         */
        private boolean planStartActive;

        /**
         * 最大规划子任务数
         */
        private int planMaxTask;

        /**
         * 调试 Hook
         */
        private AgentDebugHook debugHook;

        /**
         * 规划 Hook
         */
        private AgentPlanHook planHook;

        /**
         * 重试配置
         */
        private AgentRetryConfig retryConfig = AgentRetryConfig.builder().build();

        /**
         * 压缩配置
         */
        private AgentCompressionConfig compressionConfig;

        /**
         * 有效 MCP 管理器
         */
        private McpManager mcpManager;

        /**
         * 有效 Skill 管理器
         */
        private SkillManager skillManager;

        /**
         * 会话标识
         */
        private String sessionId;
    }

    /**
     * ReAct 循环结果
     *
     * @param output        最终输出
     * @param aborted       是否被中断/护栏中止
     * @param abortReason   中止原因
     * @param pausedReplyId 非空表示本轮因等待人工确认而正常暂停，值为确认回复 ID
     */
    private record LoopResult(String output, boolean aborted, String abortReason, String pausedReplyId) {

        /**
         * 常规结果（未暂停）
         *
         * @param output      最终输出
         * @param aborted     是否被中断/护栏中止
         * @param abortReason 中止原因
         */
        private LoopResult(String output, boolean aborted, String abortReason) {
            this(output, aborted, abortReason, null);
        }
    }

    /**
     * 用量累加器
     *
     * @author CH
     * @since 4.0.0
     */
    private static final class UsageAcc {

        /**
         * 输入令牌
         */
        private int inputTokens;

        /**
         * 输出令牌
         */
        private int outputTokens;

        /**
         * 开始时间
         */
        private final long startTime = System.currentTimeMillis();

        /**
         * 累加一次调用的令牌
         *
         * @param usage 令牌用量
         */
        private void add(TokenUsage usage) {
            if (usage == null) {
                return;
            }
            if (usage.inputTokenCount() != null) {
                inputTokens += usage.inputTokenCount();
            }
            if (usage.outputTokenCount() != null) {
                outputTokens += usage.outputTokenCount();
            }
        }

        /**
         * 合并另一份用量
         *
         * @param other 另一份用量
         */
        private void add(UsageAcc other) {
            if (other == null) {
                return;
            }
            inputTokens += other.inputTokens;
            outputTokens += other.outputTokens;
        }

        /**
         * 构建用量视图
         *
         * @return 用量
         */
        private AiUsage build() {
            return AiUsage.builder()
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .totalTokens(inputTokens + outputTokens)
                    .startTime(startTime)
                    .durationMillis(Math.max(0L, System.currentTimeMillis() - startTime))
                    .build();
        }
    }

    /**
     * 工具执行器函数式接口。
     * 接收参数映射，返回执行结果文本。
     *
     * @author CH
     * @since 4.0.0
     */
    @FunctionalInterface
    private interface ToolExecutor {
        /**
         * 执行工具
         *
         * @param args 参数
         * @return 结果文本
         * @throws Exception 执行失败
         */
        String execute(Map<String, Object> args) throws Exception;
    }
}
