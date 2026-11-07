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
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.agent.AgentRetryConfig;
import com.chua.common.support.ai.agent.AgentStreamEvent;
import com.chua.common.support.ai.agent.AgentSystemPromptBuilder;
import com.chua.common.support.ai.agent.AgentTool;
import com.chua.common.support.ai.agent.AgentTrace;
import com.chua.common.support.ai.agent.goal.AgentGoalSupport;
import com.chua.common.support.ai.agent.goal.GoalLoop;
import com.chua.common.support.ai.agent.goal.GoalStatus;
import com.chua.common.support.ai.agent.goal.GoalTracker;
import com.chua.common.support.ai.agent.goal.SessionGoal;
import com.chua.common.support.ai.chat.ChatClient;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 基于 langchain4j 实现的 ReAct Agent。
 *
 * <p>能力边界（与 {@link Agent} 接口口径一致）：
 * <ul>
 *   <li>支持：MCP 工具、Skill 工具、多轮会话历史、记忆体、上下文压缩、规划、
 *       重试、目标循环、工具结果截断、真实流式输出（模型实现
 *       {@link StreamingChatModel} 时）</li>
 *   <li>不支持：内置 shell 工具（{@link #shellTool(boolean)} 开启时显式抛异常）、
 *       人工确认恢复与结构化提问恢复（{@link Agent#resumeStream} /
 *       {@link Agent#answerQuestion} 走接口默认的 ERROR 事件）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0
 */
public class LangChain4jAgent implements Agent {

    /**
     * 日志
     */
    private static final Logger log = LoggerFactory.getLogger(LangChain4jAgent.class);

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
        if (name == null || name.isBlank() || handler == null) {
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
        if (toolName != null && !toolName.isBlank()) {
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
     * 是否允许 shell 工具
     *
     * <p>langchain4j 实现没有任何内置 shell 工具：不存在可执行的命令通道。
     * 因此关闭（false）天然满足；开启（true）无法兑现，必须显式失败，
     * 不能静默接受一个永远不生效的开关。
     *
     * @param enabled false 表示禁用 shell 工具
     * @return 当前 Agent 实例
     */
    @Override
    public Agent shellTool(boolean enabled) {
        if (enabled) {
            throw new UnsupportedOperationException(
                    "langchain4j Agent 没有内置 shell 工具，无法开启 shellTool");
        }
        return this;
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
        try {
            return doRun(input);
        } catch (Exception e) {
            log.error("[LangChain4j] 运行失败: {}", e.getMessage(), e);
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
                LoopResult result = executeReAct(plan, input, planText, acc);
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
        if (!(chatModel instanceof StreamingChatModel)) {
            return runStreamFallback(input);
        }
        return Flux.create(sink -> {
            try {
                streamRun(input, sink);
            } catch (Exception e) {
                log.error("[LangChain4j] runStream 失败: {}", e.getMessage(), e);
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

        AgentRetryConfig retry = plan.retryConfig;
        int attempt = 0;
        while (true) {
            if (sink.isCancelled() || loopGuard.isInterrupted()) {
                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                        AgentTrace.KIND_EDGE, agentId, null, "interrupted")));
                return;
            }
            attempt++;
            boolean[] emitted = {false};
            try {
                UsageAcc acc = new UsageAcc();
                acc.add(planAcc);
                LoopResult result = streamReAct(plan, input, planText, acc, sink, emitted);
                recordSessionTurn(input, result.output);
                autoSaveMemory(input, result.output);
                if (result.aborted) {
                    sink.next(AgentStreamEvent.trace(AgentTrace.of(
                            AgentTrace.KIND_EDGE, agentId, null, result.abortReason)));
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
     * 同步 ReAct 循环
     *
     * @param plan     运行计划
     * @param input    用户输入
     * @param planText 规划文本（可空）
     * @param acc      用量累加器
     * @return 循环结果
     */
    private LoopResult executeReAct(RunPlan plan, String input, String planText, UsageAcc acc) {
        List<ChatMessage> history = buildHistory(plan, input, planText);
        String finalOutput = "";
        String abortReason = null;
        int iter = 0;
        while (iter < plan.maxIters) {
            if (loopGuard.isInterrupted()) {
                abortReason = "interrupted";
                break;
            }
            fireDebug(plan.debugHook, "PRE_REASONING", plan.agentId,
                    "调用模型", iter + 1);
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(history)
                    .toolSpecifications(plan.toolSpecs)
                    .build());
            acc.add(response.tokenUsage());
            AiMessage aiMessage = response.aiMessage();
            if (aiMessage == null) {
                break;
            }
            history.add(aiMessage);
            fireDebug(plan.debugHook, "POST_REASONING", plan.agentId,
                    aiMessage.text() != null ? aiMessage.text() : "", iter + 1);

            if (!aiMessage.hasToolExecutionRequests()) {
                finalOutput = aiMessage.text() != null ? aiMessage.text() : "";
                break;
            }

            fireDebug(plan.debugHook, "PRE_ACTING", plan.agentId,
                    "执行工具 " + aiMessage.toolExecutionRequests().size() + " 个", iter + 1);
            List<ToolExecutionResultMessage> results = new ArrayList<>();
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                if (loopGuard.isInterrupted()) {
                    abortReason = "interrupted";
                    break;
                }
                // 连续相同（工具名 + 入参）调用：模型在原地打转，不再执行
                if (loopGuard.recordToolCall(request.name(), request.arguments())) {
                    abortReason = loopGuard.describe();
                    break;
                }
                String resultText = executeTool(request, plan.executors);
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
     * 流式 ReAct 循环
     *
     * @param plan     运行计划
     * @param input    用户输入
     * @param planText 规划文本（可空）
     * @param acc      用量累加器
     * @param sink     事件接收器
     * @param emitted  是否已推送正文/思考增量（长度 1 的标记数组）
     * @return 循环结果
     * @throws Exception 模型调用失败
     */
    private LoopResult streamReAct(RunPlan plan, String input, String planText,
                                   UsageAcc acc, FluxSink<AgentStreamEvent> sink,
                                   boolean[] emitted) throws Exception {
        List<ChatMessage> history = buildHistory(plan, input, planText);
        StreamingChatModel streamingModel = (StreamingChatModel) chatModel;
        String finalOutput = "";
        String abortReason = null;
        int iter = 0;
        while (iter < plan.maxIters) {
            if (sink.isCancelled()) {
                abortReason = "cancelled";
                break;
            }
            if (loopGuard.isInterrupted()) {
                abortReason = "interrupted";
                break;
            }
            fireDebug(plan.debugHook, "PRE_REASONING", plan.agentId,
                    "调用模型（流式）", iter + 1);
            String spanId = AgentTrace.newSpanId(plan.agentId, "model", iter);
            sink.next(AgentStreamEvent.trace(AgentTrace.spanStart(
                    "model", plan.agentId, spanId, null, "model call")));

            ChatRequest request = ChatRequest.builder()
                    .messages(history)
                    .toolSpecifications(plan.toolSpecs)
                    .build();
            ChatResponse response = streamOnce(streamingModel, request, plan.agentId, sink, emitted);
            acc.add(response.tokenUsage());

            sink.next(AgentStreamEvent.trace(AgentTrace.end(
                    "model", plan.agentId, spanId, null)));
            AiMessage aiMessage = response.aiMessage();
            if (aiMessage == null) {
                break;
            }
            history.add(aiMessage);
            fireDebug(plan.debugHook, "POST_REASONING", plan.agentId,
                    aiMessage.text() != null ? aiMessage.text() : "", iter + 1);

            if (!aiMessage.hasToolExecutionRequests()) {
                finalOutput = aiMessage.text() != null ? aiMessage.text() : "";
                break;
            }

            fireDebug(plan.debugHook, "PRE_ACTING", plan.agentId,
                    "执行工具 " + aiMessage.toolExecutionRequests().size() + " 个", iter + 1);
            List<ToolExecutionResultMessage> results = new ArrayList<>();
            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                if (sink.isCancelled() || loopGuard.isInterrupted()) {
                    abortReason = sink.isCancelled() ? "cancelled" : "interrupted";
                    break;
                }
                if (loopGuard.recordToolCall(toolRequest.name(), toolRequest.arguments())) {
                    abortReason = loopGuard.describe();
                    break;
                }
                sink.next(AgentStreamEvent.builder()
                        .type(AgentStreamEvent.Type.TOOL_START)
                        .agentId(plan.agentId)
                        .toolName(toolRequest.name())
                        .toolInput(toolRequest.arguments())
                        .timestamp(System.currentTimeMillis())
                        .build());
                String resultText = executeTool(toolRequest, plan.executors);
                sink.next(AgentStreamEvent.builder()
                        .type(AgentStreamEvent.Type.TOOL_RESULT)
                        .agentId(plan.agentId)
                        .toolName(toolRequest.name())
                        .toolOutput(resultText)
                        .timestamp(System.currentTimeMillis())
                        .build());
                sink.next(AgentStreamEvent.trace(AgentTrace.of(
                        AgentTrace.KIND_TOOL_RESULT, plan.agentId, toolRequest.name(), resultText)));
                results.add(ToolExecutionResultMessage.from(toolRequest, resultText));
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
                if (partialResponse != null && !partialResponse.isEmpty()) {
                    emitted[0] = true;
                    sink.next(AgentStreamEvent.reasoningDelta(agentId, partialResponse));
                }
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                String text = partialThinking != null ? partialThinking.text() : null;
                if (text != null && !text.isEmpty()) {
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
        if (planText != null && !planText.isBlank()) {
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
     * @return 系统提示词
     */
    private String buildSystemPrompt() {
        if (definition == null) {
            return "You are a helpful assistant.";
        }
        boolean leader = definition.isLeader() && !subAgentDefinitions.isEmpty();
        String prompt;
        if (leader) {
            prompt = AgentSystemPromptBuilder.build(definition.getInstruction(), subAgentDefinitions);
        } else {
            prompt = definition.getSystemPrompt();
        }
        if (prompt == null || prompt.isEmpty()) {
            return "You are a helpful assistant.";
        }
        return prompt;
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
        if (manager == null || input == null || input.isBlank()) {
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
        if (manager == null || output == null || output.isBlank()) {
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
                && (workspace == null || workspace.isBlank() || ".agent/memory".equals(workspace))) {
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
        if (workspacePath != null && !workspacePath.isBlank()) {
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
        if (output == null || output.isBlank()) {
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
            return "Error: unknown tool '" + request.name() + "'";
        }
        try {
            Map<String, Object> args = parseArguments(request.arguments());
            return truncate(executor.execute(args));
        } catch (Exception e) {
            return "Error executing tool '" + request.name() + "': " + e.getMessage();
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
     * @param output      最终输出
     * @param aborted     是否被中断/护栏中止
     * @param abortReason 中止原因
     */
    private record LoopResult(String output, boolean aborted, String abortReason) {
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
