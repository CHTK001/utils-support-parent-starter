// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.agent.opencode;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentCompressionConfig;
import com.chua.common.support.ai.agent.AgentDebugHook;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentLoopGuard;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentPlanHook;
import com.chua.common.support.ai.agent.AgentQuestion;
import com.chua.common.support.ai.agent.AgentQuestionAnswer;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.agent.AgentRetryConfig;
import com.chua.common.support.ai.agent.AgentStreamEvent;
import com.chua.common.support.ai.agent.AgentTool;
import com.chua.common.support.ai.agent.goal.AgentGoalSupport;
import com.chua.common.support.ai.agent.goal.GoalLoop;
import com.chua.common.support.ai.agent.goal.GoalStatus;
import com.chua.common.support.ai.agent.goal.GoalTracker;
import com.chua.common.support.ai.agent.goal.SessionGoal;
import com.chua.common.support.ai.agent.ConfirmationDecision;
import com.chua.common.support.ai.agent.ConfirmationItem;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.memory.MemoryManager;
import com.chua.common.support.ai.skill.SkillHandler;
import com.chua.common.support.ai.skill.SkillManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于单个 {@code opencode serve} + HTTP/SSE 的 Agent 实现。
 *
 * <p>与已移除的旧实现（每次运行拉起一次 {@code opencode run} 子进程）相比，
 * 本实现把 opencode 当作<b>常驻 agent 运行时</b>使用：JVM 内只托管一个 server 进程，
 * 每轮对话对应 server 上的一个会话，正文/思考/工具/权限事件通过 SSE 持久事件流实时取回。
 * 这样做保留了 opencode 的全部 agent 能力（多步工具循环、子 agent、LSP、MCP、技能），
 * 同时做到跨请求续话与人工审批回放。</p>
 *
 * <h3>会话映射</h3>
 * <p>opencode 会话 ID 由业务会话 ID + 工作目录确定性派生（{@code ses_} + SHA-256 前 128 位）：
 * 同一个业务会话在同一目录下始终命中同一个 opencode 会话，多轮上下文自然延续；
 * 目录变化则视为新会话，避免把 A 目录的上下文带到 B 目录。</p>
 *
 * <h3>每轮执行</h3>
 * <ol>
 *   <li>建立/复用会话（{@code POST /api/session}，携带 {@code location.directory}）</li>
 *   <li>按用户配置切换模型（{@code POST /api/session/{id}/model}）与 agent
 *       （{@code POST /api/session/{id}/agent}）</li>
 *   <li>先从历史最大 {@code durable.seq} 之后订阅
 *       {@code GET /api/session/{id}/event?after=<seq>}，再
 *       {@code POST /api/session/{id}/prompt}，确保不丢事件</li>
 *   <li>把事件翻译为 {@link AgentStreamEvent}：正文/思考增量、工具开始/结果、
 *       审批请求、错误、完成</li>
 * </ol>
 *
 * <h3>人工审批</h3>
 * <p>{@code permission.v2.asked} 事件映射为 {@code CONFIRMATION_REQUEST}（{@code replyId}
 * 为权限请求 ID），本轮 Flux 正常结束；{@link #resumeStream} 通过
 * {@code POST /api/session/{id}/permission/{requestID}/reply} 回传 {@code once|reject}
 * 后，从当前游标继续订阅。</p>
 *
 * <h3>安全约束（fail-closed）</h3>
 * <ul>
 *   <li>{@code shellTool(false)}：shell 类权限请求（{@code bash}/{@code shell}）直接回
 *       {@code reject}，不透传给前端，也不允许“用户点批准”把它放行</li>
 *   <li>工作目录一律由服务端设置（{@code workspace(..)}），本实现不接受前端传入路径</li>
 *   <li>中断：{@link #interrupt()} 调用 {@code POST /api/session/{id}/interrupt} 终止当前轮</li>
 * </ul>
 *
 * <h3>能力映射</h3>
 * <p>opencode 自管工具循环、技能、子 agent、记忆与重试，因此这些能力不是“本层转发”，
 * 而是在 {@link #ensureSession()} 前把上层配置翻译成 opencode 自己的项目配置：
 * {@code opencode.json}（agent / permission / mcp / compaction / skills）与
 * {@code AGENTS.md}。生成逻辑见 {@link OpencodeProjectConfig}。</p>
 *
 * <p>确实无法跨进程桥接的 Java 回调（{@code McpManager}/{@code SkillManager}/
 * {@code SkillHandler}/{@code MemoryConfig}/{@code AgentPlanHook}/{@code AgentDebugHook}）
 * 一律抛 {@link UnsupportedOperationException} 并给出替代做法，不做静默降级。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public class OpencodeServerAgent implements Agent {

    /**
     * 会话路径前缀
     */
    private static final String SESSION_PATH = "/api/session";

    /**
     * 事件名称：正文增量
     */
    private static final String EVENT_TEXT_DELTA = "session.next.text.delta";

    /**
     * 事件名称：思考增量
     */
    private static final String EVENT_REASONING_DELTA = "session.next.reasoning.delta";

    /**
     * 事件名称：工具调用开始
     */
    private static final String EVENT_TOOL_CALLED = "session.next.tool.called";

    /**
     * 事件名称：工具调用成功
     */
    private static final String EVENT_TOOL_SUCCESS = "session.next.tool.success";

    /**
     * 事件名称：工具调用失败
     */
    private static final String EVENT_TOOL_FAILED = "session.next.tool.failed";

    /**
     * 事件名称：shell 开始
     */
    private static final String EVENT_SHELL_STARTED = "session.next.shell.started";

    /**
     * 事件名称：shell 结束
     */
    private static final String EVENT_SHELL_ENDED = "session.next.shell.ended";

    /**
     * 事件名称：单步开始
     */
    private static final String EVENT_STEP_STARTED = "session.next.step.started";

    /**
     * 事件名称：单步结束（含 token 与费用）
     */
    private static final String EVENT_STEP_ENDED = "session.next.step.ended";

    /**
     * 事件名称：单步失败
     */
    private static final String EVENT_STEP_FAILED = "session.next.step.failed";

    /**
     * 事件名称：会话错误
     */
    private static final String EVENT_SESSION_ERROR = "session.error";

    /**
     * 事件名称：会话空闲（一轮结束）
     */
    private static final String EVENT_SESSION_IDLE = "session.idle";

    /**
     * 事件名称：权限询问
     */
    private static final String EVENT_PERMISSION_ASKED = "permission.v2.asked";

    /**
     * 事件名称：提问（v2 question）
     */
    private static final String EVENT_QUESTION_ASKED = "question.v2.asked";

    /**
     * 权限应答：单次允许
     */
    private static final String PERMISSION_ONCE = "once";

    /**
     * 权限应答：拒绝
     */
    private static final String PERMISSION_REJECT = "reject";

    /**
     * 派生 opencode 会话 ID 前缀（满足 OpenAPI 的 {@code ^ses} 约束）
     */
    private static final String SESSION_ID_PREFIX = "ses_";

    /**
     * Session ID 摘要截取字节数（16 字节 = 32 位十六进制）
     */
    private static final int SESSION_HASH_BYTES = 16;

    /**
     * 默认 agent 标识（用于事件归属）
     */
    private static final String DEFAULT_AGENT_ID = "opencode";

    /**
     * Plan 模式对应的 opencode 内置 agent 名
     */
    private static final String PLAN_AGENT_NAME = "plan";

    /**
     * 本层生成主 agent 时使用的默认名称
     */
    private static final String DEFAULT_MANAGED_AGENT = "chua";

    /**
     * 单轮默认超时秒数
     */
    private static final long DEFAULT_TURN_TIMEOUT_SECONDS = 1800L;

    /**
     * 权限资源摘要最多展示条目数
     */
    private static final int PERMISSION_RESOURCE_LIMIT = 5;

    /**
     * 系统属性：单轮超时秒数
     */
    private static final String PROP_TURN_TIMEOUT = "opencode.agent.timeout-seconds";

    /**
     * 跨实例共享的事件游标：opencode 会话 ID -> 已消费的最大 durable.seq
     */
    private static final Map<String, Long> SESSION_CURSORS = new ConcurrentHashMap<>();

    /**
     * server 进程管理器
     */
    private final OpencodeServerProcess server = OpencodeServerProcess.getInstance();

    /**
     * 业务会话 ID（由上层会话编排传入，用于派生 opencode 会话）
     */
    private volatile String sessionId;

    /**
     * 工作目录绝对路径（由服务端推导，不接受前端直接传入）
     */
    private volatile String workspace;

    /**
     * opencode agent 名称（如 build / plan）
     */
    private volatile String agentName;

    /**
     * 模型标识，形如 {@code provider/model}
     */
    private volatile String model;

    /**
     * 主 ChatClient（仅用于从上层配置派生 provider/model）
     */
    private volatile ChatClient chatClient;

    /**
     * 是否允许 shell；null 表示上层未声明，沿用 opencode 默认（可能询问）
     */
    private volatile Boolean shellEnabled;

    /**
     * 是否请求启用 Plan 能力
     */
    private volatile boolean planEnabled;

    /**
     * 是否首轮即进入 Plan 模式
     */
    private volatile boolean planMode;

    /**
     * 工具调用最大轮数；≤0 表示不限制
     */
    private volatile int maxToolIterations;

    /**
     * 会话护栏（死循环判定 + 中断信号）
     */
    private volatile AgentLoopGuard loopGuard = new AgentLoopGuard();

    /**
     * 执行模式（opencode 自管编排，非 SINGLE 仅记录）
     */
    private volatile AgentMode mode = AgentMode.SINGLE;

    /**
     * 主 Agent 定义
     */
    private volatile AgentDefinition definition;

    /**
     * 上下文压缩配置（映射到 opencode compaction.auto）
     */
    private volatile AgentCompressionConfig compressionConfig;

    /**
     * 是否打印生效配置
     */
    private volatile boolean printConfig;

    /**
     * 是否输出调试日志
     */
    private volatile boolean debug;

    /**
     * 是否启用 MCP（false 时把配置中的 MCP 服务统一置为 disabled）
     */
    private volatile boolean mcpEnabled = true;

    /**
     * 由本层写入 opencode.json 的 MCP 服务（服务名 -> opencode 原生条目）
     */
    private final Map<String, Map<String, Object>> mcpServerSpecs = new LinkedHashMap<>();

    /**
     * 追加的技能发现目录
     */
    private final List<String> skillPaths = new CopyOnWriteArrayList<>();

    /**
     * 子 agent 专属模型：agent 标识 -> ChatClient
     */
    private final Map<String, ChatClient> agentChatClients = new ConcurrentHashMap<>();

    /**
     * 子 Agent 定义（写入 opencode.json 的 agent 段）
     */
    private final List<AgentDefinition> subAgents = new CopyOnWriteArrayList<>();

    /**
     * 按名称关闭的工具（写入各 agent 的 tools 段，值为 false）
     */
    private final Map<String, Boolean> disabledTools = new ConcurrentHashMap<>();

    /**
     * 中断标记
     */
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    /**
     * 当前会话上下文（跨轮复用）
     */
    private volatile SessionContext currentSession;

    /**
     * 当前进行中的一轮（用于 close 时释放事件流）
     */
    private volatile Turn activeTurn;

    /**
     * Agent 是否已关闭
     */
    private volatile boolean closed;

    /**
     * 目标支撑：状态机 + 循环 + 计账（与其它实现共用同一份逻辑）
     */
    private final AgentGoalSupport goalSupport = new AgentGoalSupport();

    /**
     * 待回答的提问：提问请求 ID -> 各问题的 questionId（顺序与提问顺序一致）
     */
    private final Map<String, List<String>> pendingQuestionIds = new ConcurrentHashMap<>();

    /**
     * 单条工具结果触发截断的字符数上限
     */
    private volatile int toolResultMaxChars = 8000;

    /**
     * 截断后保留的预览字符数
     */
    private volatile int toolResultHeadChars = 1500;

    /**
     * 是否允许恢复被暂停的权限/提问请求
     */
    private volatile boolean pendingToolRecoveryEnabled = true;

    /**
     * 模型缺少 provider 的告警是否已发出
     */
    private final AtomicBoolean modelProviderWarned = new AtomicBoolean(false);

    /**
     * 无参构造（SPI {@code create("opencode-server")} 入口）。
     */
    public OpencodeServerAgent() {
    }

    /**
     * 设置模型。
     *
     * <p>入参形如 {@code provider/model}（如 {@code anthropic/claude-sonnet-4-5}）。
     * 只有模型名而没有 provider 时，会尝试从 {@link ChatClient} 的 provider 补齐。</p>
     *
     * @param model 模型标识
     * @return 当前实例
     */
    public OpencodeServerAgent model(String model) {
        this.model = (model == null || model.isBlank()) ? null : model.trim();
        return this;
    }

    /**
     * 设置 opencode agent 名称。
     *
     * @param agentName agent 名称（须在 opencode 配置中存在），null 使用默认
     * @return 当前实例
     */
    public OpencodeServerAgent agentName(String agentName) {
        this.agentName = (agentName == null || agentName.isBlank()) ? null : agentName.trim();
        return this;
    }

    /**
     * 设置业务会话 ID（便捷入口，与 {@link #sessionId(String)} 同一字段）。
     *
     * @param session 业务会话 ID
     * @return 当前实例
     */
    public OpencodeServerAgent session(String session) {
        return sessionId(session);
    }

    @Override
    public OpencodeServerAgent sessionId(String sessionId) {
        this.sessionId = (sessionId == null || sessionId.isBlank()) ? null : sessionId.trim();
        this.currentSession = null;
        return this;
    }

    @Override
    public OpencodeServerAgent workspace(String workspace) {
        this.workspace = (workspace == null || workspace.isBlank()) ? null : workspace.trim();
        this.currentSession = null;
        return this;
    }

    @Override
    public OpencodeServerAgent shellTool(boolean enabled) {
        this.shellEnabled = enabled;
        return this;
    }

    @Override
    public OpencodeServerAgent mode(AgentMode mode) {
        AgentMode value = mode == null ? AgentMode.SINGLE : mode;
        switch (value) {
            case PIPELINE, FAN_OUT -> throw new UnsupportedOperationException(
                    "opencode 原生没有顺序/并行多 agent 编排原语，无法实现 " + value
                            + "；请改用 goal 循环或调用方自行编排");
            case PLAN_AND_EXECUTE -> {
                this.planEnabled = true;
                this.mode = value;
            }
            default -> this.mode = value;
        }
        return this;
    }

    @Override
    public OpencodeServerAgent chatClient(ChatClient chatClient) {
        if (chatClient == null) {
            return this;
        }
        this.chatClient = chatClient;
        if (this.model == null || this.model.isBlank()) {
            this.model = deriveModel(chatClient);
        }
        return this;
    }

    @Override
    public OpencodeServerAgent chatClient(String agentId, ChatClient chatClient) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        if (chatClient == null) {
            throw new IllegalArgumentException("chatClient 不能为空（agentId=" + agentId + "）");
        }
        agentChatClients.put(agentId.trim(), chatClient);
        return this;
    }

    @Override
    public OpencodeServerAgent mcp(boolean mcp) {
        this.mcpEnabled = mcp;
        return this;
    }

    /**
     * 登记一个 MCP 服务（直接使用 opencode 原生条目）。
     *
     * <p>opencode 是独立进程，无法回调本进程内的 {@code McpClient}，因此 MCP 只能以
     * 「服务启动命令 / 远端地址」的形式写进 {@code opencode.json}。本方法就是这条通道：
     * 生成配置时把条目原样写入 {@code mcp} 段。</p>
     *
     * <p>条目键：{@code type}（{@code local}/{@code remote}，必填）、
     * {@code command}（local 必填）、{@code url}（remote 必填）、
     * {@code environment}、{@code headers}、{@code cwd}、{@code timeout}。</p>
     *
     * @param name 服务名
     * @param spec opencode 原生 MCP 条目
     * @return 当前实例
     */
    public OpencodeServerAgent mcpServer(String name, Map<String, Object> spec) {
        if (name == null || name.isBlank() || spec == null || spec.isEmpty()) {
            throw new IllegalArgumentException("MCP 服务名与配置不能为空");
        }
        Map<String, Object> copy = new LinkedHashMap<>(spec);
        mcpServerSpecs.put(name.trim(), copy);
        return this;
    }

    @Override
    public OpencodeServerAgent mcpManager(McpManager mcpManager) {
        if (mcpManager == null) {
            return this;
        }
        throw new UnsupportedOperationException(
                "opencode 是独立进程，无法使用进程内的 McpManager；"
                        + "请改调 mcpServer(name, spec) 或在 workspace 的 opencode.json 中声明 mcp 服务");
    }

    @Override
    public OpencodeServerAgent mcpManager(String agentId, McpManager mcpManager) {
        if (mcpManager == null) {
            return this;
        }
        throw new UnsupportedOperationException(
                "opencode 是独立进程，无法给 agent " + agentId + " 注入进程内的 McpManager；"
                        + "请改调 mcpServer(name, spec) 或在 workspace 的 opencode.json 中声明 mcp 服务");
    }

    @Override
    public OpencodeServerAgent skillManager(SkillManager skillManager) {
        if (skillManager == null) {
            return this;
        }
        throw new UnsupportedOperationException(
                "opencode 是独立进程，无法使用进程内的 SkillManager；"
                        + "请把 SKILL.md 放进 workspace 的 .opencode/skill/<name>/，或调用 skillPath(path) 追加技能目录");
    }

    @Override
    public OpencodeServerAgent skillManager(String agentId, SkillManager skillManager) {
        if (skillManager == null) {
            return this;
        }
        throw new UnsupportedOperationException(
                "opencode 是独立进程，无法给 agent " + agentId + " 注入进程内的 SkillManager；"
                        + "请把 SKILL.md 放进 workspace 的 .opencode/skill/<name>/，或调用 skillPath(path) 追加技能目录");
    }

    /**
     * 追加一个 opencode 技能发现目录（含 {@code SKILL.md} 的目录）。
     *
     * @param path 技能目录，相对路径按 workspace 解析
     * @return 当前实例
     */
    public OpencodeServerAgent skillPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("技能目录不能为空");
        }
        String value = path.trim();
        if (!skillPaths.contains(value)) {
            skillPaths.add(value);
        }
        return this;
    }

    @Override
    public OpencodeServerAgent skill(String name, String description, SkillHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("技能 handler 不能为空（skill=" + name + "）");
        }
        throw new UnsupportedOperationException(
                "Java 技能 handler 只在当前进程内可调用，opencode 无法访问；技能 " + name
                        + " 请改写为 workspace/.opencode/skill/" + name + "/SKILL.md，再调用 skillPath(..)");
    }

    @Override
    public OpencodeServerAgent tool(AgentTool tool) {
        String name = tool == null ? "null" : tool.getName();
        throw new UnsupportedOperationException(
                "进程内工具执行器无法跨进程回调：opencode 是独立进程，工具 " + name
                        + " 的 Java executor 不会被调用。请改用 mcpServer(name, spec) 暴露 MCP 服务，"
                        + "或把能力写成 workspace/.opencode/skill/<name>/SKILL.md 后 skillPath(..)");
    }

    @Override
    public OpencodeServerAgent disableTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return this;
        }
        disabledTools.put(toolName.trim(), Boolean.FALSE);
        return this;
    }

    @Override
    public OpencodeServerAgent subAgent(AgentDefinition subAgent) {
        if (subAgent == null) {
            return this;
        }
        validateDefinition(subAgent);
        subAgents.add(subAgent);
        return this;
    }

    @Override
    public OpencodeServerAgent maxToolIterations(int maxIterations) {
        this.maxToolIterations = maxIterations;
        return this;
    }

    @Override
    public OpencodeServerAgent loopGuard(AgentLoopGuard guard) {
        this.loopGuard = guard == null ? new AgentLoopGuard() : guard;
        return this;
    }

    @Override
    public AgentLoopGuard loopGuard() {
        return loopGuard;
    }

    @Override
    public OpencodeServerAgent memoryConfig(MemoryConfig memoryConfig) {
        if (memoryConfig == null) {
            return this;
        }
        throw new UnsupportedOperationException(
                "opencode 自管会话记忆，不接受进程内 MemoryConfig；"
                        + "如需项目级长期记忆，请写入 AGENTS.md 或改为 opencode 技能");
    }

    /**
     * 重试次数：opencode 在独立进程内自管重试，适配层无法插入重试钩子。
     *
     * <p>本方法只接受「不启用重试」的取值（{@code 0}）。一旦要求真正重试
     * （{@code > 0} 或 {@code -1} 无限重试）就显式失败，避免出现
     * 「存下来但从不生效」的静默失配。</p>
     *
     * @param maxRetries 最大重试次数，只有 {@code 0} 被接受
     * @return 当前 agent（链式）
     */
    @Override
    public OpencodeServerAgent maxRetries(int maxRetries) {
        if (maxRetries != 0) {
            throw new UnsupportedOperationException(
                    "opencode 在独立进程内自管重试，进程内 maxRetries 不会生效；"
                            + "请在 opencode 侧配置 provider 重试，或用 RetryFlow 包裹 run()/chat() 在外部重试");
        }
        return this;
    }

    /**
     * 重试退避策略：opencode 自管重试间隔，进程内策略无从生效。
     *
     * @param strategy 退避策略，只有 {@code null} 被接受
     * @return 当前 agent（链式）
     */
    @Override
    public OpencodeServerAgent retryBackoff(AgentRetryConfig.BackoffStrategy strategy) {
        if (strategy != null) {
            throw new UnsupportedOperationException(
                    "opencode 在独立进程内自管重试退避，retryBackoff 不会生效；"
                            + "请在 opencode 侧配置 provider 重试，或用 RetryFlow 配置退避策略");
        }
        return this;
    }

    /**
     * 重试基础延迟：opencode 自管重试间隔，进程内延迟无从生效。
     *
     * @param baseDelayMillis 基础延迟（毫秒），只有 {@code <= 0} 被接受
     * @return 当前 agent（链式）
     */
    @Override
    public OpencodeServerAgent retryBaseDelay(long baseDelayMillis) {
        if (baseDelayMillis > 0) {
            throw new UnsupportedOperationException(
                    "opencode 在独立进程内自管重试延迟，retryBaseDelay 不会生效；"
                            + "请在 opencode 侧配置 provider 重试，或用 RetryFlow 设置基础延迟");
        }
        return this;
    }

    /**
     * 完整重试配置：与 {@link #maxRetries(int)} 同口径，只接受不启用重试的配置。
     *
     * @param retryConfig 重试配置，{@code null} 或 {@code maxRetries == 0} 被接受
     * @return 当前 agent（链式）
     */
    @Override
    public OpencodeServerAgent retryConfig(AgentRetryConfig retryConfig) {
        if (retryConfig != null && retryConfig.getMaxRetries() != 0) {
            throw new UnsupportedOperationException(
                    "opencode 在独立进程内自管重试，retryConfig.maxRetries="
                            + retryConfig.getMaxRetries() + " 不会生效；"
                            + "请在 opencode 侧配置 provider 重试，或用 RetryFlow 包裹 run()/chat()");
        }
        return this;
    }

    @Override
    public OpencodeServerAgent compressionConfig(AgentCompressionConfig compressionConfig) {
        this.compressionConfig = compressionConfig;
        return this;
    }

    @Override
    public AgentCompressionConfig compressionConfig() {
        return compressionConfig;
    }

    @Override
    public OpencodeServerAgent plan(boolean plan) {
        this.planEnabled = plan;
        return this;
    }

    @Override
    public OpencodeServerAgent startInPlanMode(boolean active) {
        this.planMode = active;
        return this;
    }

    @Override
    public OpencodeServerAgent planMaxTask(int planMaxTask) {
        if (planMaxTask > 0) {
            throw new UnsupportedOperationException(
                    "opencode 没有计划子任务上限配置；请改用 maxToolIterations(steps) 或把任务拆分写进 prompt");
        }
        return this;
    }

    @Override
    public OpencodeServerAgent planHook(AgentPlanHook planHook) {
        if (planHook == null) {
            return this;
        }
        throw new UnsupportedOperationException(
                "Java 规划 Hook 无法在 opencode 进程内执行；请改用 plan(true)/startInPlanMode(true) 走 opencode 原生 plan agent");
    }

    @Override
    public OpencodeServerAgent printConfig(boolean printConfig) {
        this.printConfig = printConfig;
        return this;
    }

    @Override
    public OpencodeServerAgent debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    @Override
    public OpencodeServerAgent debugHook(AgentDebugHook debugHook) {
        if (debugHook == null) {
            return this;
        }
        throw new UnsupportedOperationException(
                "Java 调试 Hook 无法在 opencode 进程内执行；请改用 debug(true) 输出适配层日志");
    }

    /**
     * 绑定一个会话目标（Issue），供 {@link #runGoal()} 反复推进。
     *
     * @param goal 目标快照，不能为 null
     * @return 当前 agent（链式）
     */
    @Override
    public OpencodeServerAgent goal(SessionGoal goal) {
        goalSupport.goal(goal);
        return this;
    }

    /**
     * 挂接一个已存在的目标状态机（会话级目标接力推进）。
     *
     * @param tracker 目标状态机，不能为 null
     * @return 当前 agent（链式）
     */
    @Override
    public OpencodeServerAgent attachGoal(GoalTracker tracker) {
        goalSupport.attach(tracker);
        return this;
    }

    /**
     * 设置目标循环的轮次硬上限（至少 1）。
     *
     * @param maxRounds 轮次上限
     * @return 当前 agent（链式）
     */
    @Override
    public OpencodeServerAgent goalMaxRounds(int maxRounds) {
        goalSupport.maxRounds(maxRounds);
        return this;
    }

    /**
     * 设置单次暂停等待上限。
     *
     * @param pauseTimeoutMillis 毫秒；小于等于 0 表示一直等待
     * @return 当前 agent（链式）
     */
    @Override
    public OpencodeServerAgent goalPauseTimeout(long pauseTimeoutMillis) {
        goalSupport.pauseTimeout(pauseTimeoutMillis);
        return this;
    }

    @Override
    public boolean hasGoal() {
        return goalSupport.hasGoal();
    }

    @Override
    public GoalStatus goalStatus() {
        return goalSupport.status();
    }

    @Override
    public GoalStatus pauseGoal() {
        return goalSupport.pause();
    }

    @Override
    public GoalStatus resumeGoal() {
        return goalSupport.resume();
    }

    @Override
    public GoalStatus cancelGoal(String reason) {
        return goalSupport.cancel(reason);
    }

    /**
     * 围绕已绑定的目标反复推进。
     *
     * <p>每一轮都通过 {@link #run(String)} 真实驱动一次 opencode 会话；轮次之间由
     * {@link AgentGoalSupport} 检查暂停/取消，不会在暂停期间继续发消息。</p>
     *
     * @return 结束时的展示投影
     */
    @Override
    public GoalStatus runGoal() {
        return goalSupport.run(prompt -> {
            AgentResponse response = run(prompt);
            String reply = response != null && response.getOutput() != null ? response.getOutput() : "";
            return new GoalLoop.TurnResult(reply, AgentGoalSupport.tokensOf(response));
        });
    }

    /**
     * 设置单条工具结果的截断阈值。
     *
     * @param maxChars  触发截断的字符数上限
     * @param headChars 截断后保留的预览字符数
     * @return 当前 agent（链式）
     */
    @Override
    public OpencodeServerAgent toolResultEviction(int maxChars, int headChars) {
        if (maxChars <= 0 || headChars < 0 || headChars > maxChars) {
            throw new IllegalArgumentException("截断阈值不合法: maxChars=" + maxChars + ", headChars=" + headChars);
        }
        this.toolResultMaxChars = maxChars;
        this.toolResultHeadChars = headChars;
        return this;
    }

    /**
     * 是否允许恢复被暂停的权限/提问请求。
     *
     * <p>关闭后 {@link #resumeStream} 与 {@link #answerQuestion} 会直接返回错误事件，
     * 调用方必须重新发起 {@link #runStream(String)}。</p>
     *
     * @param enabled 是否启用
     * @return 当前 agent（链式）
     */
    @Override
    public OpencodeServerAgent pendingToolRecovery(boolean enabled) {
        this.pendingToolRecoveryEnabled = enabled;
        return this;
    }

    /**
     * 回传结构化提问的答案并恢复执行。
     *
     * <p>opencode 的答复体是 {@code {"answers": [[...], ...]}}，外层顺序必须与提问顺序一致；
     * 这里按 {@code questionId} 还原顺序，缺失的问题回传空数组。</p>
     *
     * @param replyId 提问请求 ID（QUESTION_REQUEST 原样带回）
     * @param answers 用户答案
     * @return 恢复后的流式事件
     */
    @Override
    public Flux<AgentStreamEvent> answerQuestion(String replyId, List<AgentQuestionAnswer> answers) {
        return Flux.defer(() -> {
            if (closed) {
                return Flux.just(errorEvent("Agent 已关闭"));
            }
            if (!pendingToolRecoveryEnabled) {
                return Flux.just(errorEvent("已禁用待执行工具恢复，请重新发起运行"));
            }
            if (replyId == null || replyId.isBlank()) {
                return Flux.just(errorEvent("提问请求 ID 为空"));
            }
            if (!pendingQuestionIds.containsKey(replyId)) {
                return Flux.just(errorEvent("未找到待回答的提问: " + replyId));
            }
            try {
                SessionContext context = currentSession;
                if (context == null) {
                    context = ensureSession();
                }
                SessionContext session = context;
                List<List<String>> ordered = orderAnswers(replyId, answers);
                return streamTurn(session, () -> replyQuestion(session, replyId, ordered))
                        .timeout(Duration.ofSeconds(resolveTurnTimeoutSeconds()));
            } catch (Throwable t) {
                return Flux.just(errorEvent(messageOf(t)));
            }
        });
    }

    @Override
    public OpencodeServerAgent definition(AgentDefinition definition) {
        validateDefinition(definition);
        this.definition = definition;
        return this;
    }

    @Override
    public AgentDefinition getDefinition() {
        return definition;
    }

    /**
     * opencode 自管会话记忆，进程内没有 {@code MemoryManager}。
     *
     * <p>显式返回 null 而不是沿用接口默认值，避免调用方误以为可以拿到进程内记忆管理器
     * 去做读写或 {@code saveFromConversation}。需要项目级长期记忆请写 {@code AGENTS.md}。</p>
     *
     * @return 恒为 null
     */
    @Override
    public MemoryManager getMemoryManager() {
        return null;
    }

    @Override
    public List<AgentDefinition> getSubAgents() {
        return List.copyOf(subAgents);
    }

    @Override
    public OpencodeServerAgent interrupt() {
        interrupted.set(true);
        AgentLoopGuard guard = loopGuard;
        if (guard != null) {
            guard.interrupt();
        }
        SessionContext context = currentSession;
        if (context != null) {
            requestInterrupt(context);
        }
        return this;
    }

    @Override
    public boolean isInterrupted() {
        AgentLoopGuard guard = loopGuard;
        return interrupted.get() || (guard != null && guard.isInterrupted());
    }

    @Override
    public void close() {
        closed = true;
        pendingQuestionIds.clear();
        Turn turn = activeTurn;
        if (turn != null) {
            turn.closeQuietly();
            if (!turn.isFinished()) {
                requestInterrupt(turn.context);
            }
        }
        currentSession = null;
    }

    @Override
    public AgentResponse run(String input) {
        StringBuilder output = new StringBuilder();
        AtomicReference<AiUsage> usage = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        try {
            runStream(input).doOnNext(event -> {
                if (event == null || event.getType() == null) {
                    return;
                }
                switch (event.getType()) {
                    case REASONING_DELTA -> {
                        if (event.getDelta() != null) {
                            output.append(event.getDelta());
                        }
                    }
                    case COMPLETE -> usage.set(event.getUsage());
                    case ERROR -> error.set(event.getToolOutput());
                    default -> {
                        // 其余事件仅用于流式展示，同步执行时无需聚合
                    }
                }
            }).blockLast();
        } catch (RuntimeException e) {
            error.set(messageOf(e));
        }
        if (error.get() != null) {
            log.warn("[opencode-server] 同步执行失败: {}", error.get());
        }
        return AgentResponse.builder()
                .output(output.toString())
                .mode(mode.name())
                .selectedAgent(agentId())
                .usage(usage.get())
                .build();
    }

    @Override
    public Flux<AgentStreamEvent> runStream(String input) {
        return Flux.defer(() -> {
            if (closed) {
                return Flux.just(errorEvent("Agent 已关闭"));
            }
            if (input == null || input.isBlank()) {
                return Flux.just(errorEvent("输入为空"));
            }
            AgentLoopGuard guard = loopGuard;
            if (guard != null) {
                guard.reset();
            }
            interrupted.set(false);
            try {
                if (printConfig) {
                    log.info("[opencode-server] 生效配置: workspace={}, session={}, agent={}, model={}, shell={}, mode={}",
                            resolveWorkspace(resolveWorkspacePath()), sessionId, effectiveAgentName(),
                            resolveModelRef(), shellEnabled, mode);
                }
                SessionContext context = ensureSession();
                return streamTurn(context, () -> sendPrompt(context, input))
                        .timeout(Duration.ofSeconds(resolveTurnTimeoutSeconds()));
            } catch (Throwable t) {
                return Flux.just(errorEvent(messageOf(t)));
            }
        });
    }

    @Override
    public Flux<AgentStreamEvent> resumeStream(String replyId, List<ConfirmationDecision> decisions,
                                               String feedback) {
        return Flux.defer(() -> {
            if (closed) {
                return Flux.just(errorEvent("Agent 已关闭"));
            }
            if (!pendingToolRecoveryEnabled) {
                return Flux.just(errorEvent("已禁用待执行工具恢复，请重新发起运行"));
            }
            try {
                SessionContext context = currentSession;
                if (context == null) {
                    context = ensureSession();
                }
                SessionContext session = context;
                return streamTurn(session, () -> replyPermissions(session, replyId, decisions, feedback))
                        .timeout(Duration.ofSeconds(resolveTurnTimeoutSeconds()));
            } catch (Throwable t) {
                return Flux.just(errorEvent(messageOf(t)));
            }
        });
    }

    /**
     * 建立或复用一个 opencode 会话，并应用模型 / agent 设置。
     *
     * @return 会话上下文
     */
    private SessionContext ensureSession() {
        Path workspacePath = resolveWorkspacePath();
        applyProjectConfig(workspacePath);
        OpencodeServerProcess.ServerInfo serverInfo = server.ensureStarted();
        String directory = resolveWorkspace(workspacePath);
        String opencodeSessionId = deriveSessionId(directory);
        OpencodeHttpClient client = serverInfo.newClient(directory);
        String sessionPath = SESSION_PATH + "/" + opencodeSessionId;
        if (!exists(client, sessionPath)) {
            createSession(client, opencodeSessionId, directory);
        }
        applyModel(client, sessionPath);
        applyAgent(client, sessionPath);
        SessionContext context = new SessionContext(client, opencodeSessionId, directory);
        currentSession = context;
        return context;
    }

    /**
     * 发送一条用户消息。
     *
     * @param context 会话上下文
     * @param input   用户输入
     */
    private void sendPrompt(SessionContext context, String input) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("text", input);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        context.client().postJson(SESSION_PATH + "/" + context.sessionId() + "/prompt", body);
    }

    /**
     * 回传人工审批决策。
     *
     * @param context   会话上下文
     * @param replyId   待审批请求 ID
     * @param decisions 决策列表；为空时视为一次性批准
     * @param feedback  用户修改意见
     */
    private void replyPermissions(SessionContext context, String replyId,
                                  List<ConfirmationDecision> decisions, String feedback) {
        List<ConfirmationDecision> effective;
        if (decisions == null || decisions.isEmpty()) {
            effective = List.of(ConfirmationDecision.builder()
                    .toolCallId(replyId)
                    .confirmed(true)
                    .build());
        } else {
            effective = decisions;
        }
        for (ConfirmationDecision decision : effective) {
            String requestId = decision.getToolCallId();
            if (requestId == null || requestId.isBlank()) {
                requestId = replyId;
            }
            String reply = decision.isConfirmed() ? PERMISSION_ONCE : PERMISSION_REJECT;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reply", reply);
            if (feedback != null && !feedback.isBlank()) {
                body.put("message", feedback);
            }
            context.client().postNoContent(SESSION_PATH + "/" + context.sessionId()
                    + "/permission/" + requestId + "/reply", body);
        }
    }

    /**
     * 回传结构化提问的答案。
     *
     * @param context 会话上下文
     * @param replyId 提问请求 ID
     * @param answers 已按提问顺序排好的答案
     */
    private void replyQuestion(SessionContext context, String replyId, List<List<String>> answers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answers", answers);
        context.client().postNoContent(SESSION_PATH + "/" + context.sessionId()
                + "/question/" + replyId + "/reply", body);
        pendingQuestionIds.remove(replyId);
    }

    /**
     * 按 {@code questionId} 还原答案顺序；未提供的问题回传空数组。
     *
     * @param replyId 提问请求 ID
     * @param answers 用户答案
     * @return 与提问顺序一致的答案
     */
    private List<List<String>> orderAnswers(String replyId, List<AgentQuestionAnswer> answers) {
        List<String> questionIds = pendingQuestionIds.get(replyId);
        Map<String, List<String>> byId = new LinkedHashMap<>();
        if (answers != null) {
            for (AgentQuestionAnswer answer : answers) {
                if (answer == null) {
                    continue;
                }
                byId.put(answer.getQuestionId(),
                        answer.getAnswers() == null ? List.of() : answer.getAnswers());
            }
        }
        if (questionIds == null || questionIds.isEmpty()) {
            return answers == null ? List.of()
                    : answers.stream()
                    .map(item -> item == null || item.getAnswers() == null ? List.<String>of() : item.getAnswers())
                    .toList();
        }
        return questionIds.stream()
                .map(id -> byId.getOrDefault(id, List.of()))
                .toList();
    }

    /**
     * 判断会话是否存在。
     *
     * @param client      客户端
     * @param sessionPath 会话路径
     * @return 是否存在
     */
    private boolean exists(OpencodeHttpClient client, String sessionPath) {
        try {
            client.getJson(sessionPath);
            return true;
        } catch (OpencodeApiException e) {
            if (e.getStatusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 创建 opencode 会话。
     *
     * @param client      客户端
     * @param sessionKey  会话 ID
     * @param directory   工作目录
     */
    private void createSession(OpencodeHttpClient client, String sessionKey, String directory) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", sessionKey);
        String agent = effectiveAgentName();
        if (agent != null) {
            body.put("agent", agent);
        }
        ModelRef ref = resolveModelRef();
        if (ref != null) {
            body.put("model", modelBody(ref));
        }
        body.put("location", Map.of("directory", directory));
        try {
            client.postJson(SESSION_PATH, body);
            log.info("[opencode-server] 创建会话 {}（directory={}, agent={}, model={}）",
                    sessionKey, directory, agent, ref == null ? null : ref.providerID() + "/" + ref.id());
        } catch (OpencodeApiException e) {
            if (exists(client, SESSION_PATH + "/" + sessionKey)) {
                return;
            }
            if (e.getStatusCode() == 404) {
                throw new IllegalStateException(
                        "当前 opencode 版本不支持 v2 agent API（缺少 " + SESSION_PATH
                                + "），请升级 opencode 或改用 opencode 子命令模式", e);
            }
            if (agent != null && e.getStatusCode() == 400) {
                body.remove("agent");
                client.postJson(SESSION_PATH, body);
                log.warn("[opencode-server] agent {} 不可用，已回退到 opencode 默认 agent", agent);
                return;
            }
            throw e;
        }
    }

    /**
     * 切换会话模型。
     *
     * @param client      客户端
     * @param sessionPath 会话路径
     */
    private void applyModel(OpencodeHttpClient client, String sessionPath) {
        ModelRef ref = resolveModelRef();
        if (ref == null) {
            return;
        }
        try {
            client.postNoContent(sessionPath + "/model", Map.of("model", modelBody(ref)));
        } catch (OpencodeApiException e) {
            log.warn("[opencode-server] 切换模型 {}/{} 失败，将沿用会话既有模型: {}",
                    ref.providerID(), ref.id(), e.getMessage());
        }
    }

    /**
     * 切换会话 agent。
     *
     * @param client      客户端
     * @param sessionPath 会话路径
     */
    private void applyAgent(OpencodeHttpClient client, String sessionPath) {
        String agent = effectiveAgentName();
        if (agent == null) {
            return;
        }
        try {
            client.postNoContent(sessionPath + "/agent", Map.of("agent", agent));
        } catch (OpencodeApiException e) {
            log.warn("[opencode-server] 切换 agent {} 失败，将沿用默认 agent: {}", agent, e.getMessage());
        }
    }

    /**
     * 构造一轮流式执行：先订阅事件，再执行启动动作（发消息 / 回传审批）。
     *
     * @param context 会话上下文
     * @param starter 启动动作
     * @return 事件流
     */
    private Flux<AgentStreamEvent> streamTurn(SessionContext context, Runnable starter) {
        return Flux.<AgentStreamEvent>create(sink -> {
            Turn turn = new Turn(context, sink);
            activeTurn = turn;
            sink.onDispose(() -> {
                if (activeTurn == turn) {
                    activeTurn = null;
                }
                boolean finished = turn.isFinished();
                turn.closeQuietly();
                if (!finished) {
                    requestInterrupt(context);
                }
            });
            turn.subscribe();
            try {
                starter.run();
            } catch (Throwable t) {
                turn.fail(t);
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 请求中断当前轮。
     *
     * @param context 会话上下文
     */
    private void requestInterrupt(SessionContext context) {
        interrupted.set(true);
        AgentLoopGuard guard = loopGuard;
        if (guard != null) {
            guard.interrupt();
        }
        Thread.ofVirtual().name("opencode-interrupt").start(() -> {
            try {
                context.client().postNoContent(SESSION_PATH + "/" + context.sessionId() + "/interrupt", null);
            } catch (Exception e) {
                log.debug("[opencode-server] 中断会话 {} 失败: {}", context.sessionId(), e.getMessage());
            }
        });
    }

    /**
     * 解析工作目录（缺省为进程工作目录，并确保存在）。
     *
     * @return 工作目录
     */
    private Path resolveWorkspacePath() {
        String value = workspace;
        if (value == null || value.isBlank()) {
            value = System.getProperty("user.dir");
        }
        Path path = Paths.get(value).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new IllegalStateException("创建工作目录失败: " + path, e);
        }
        return path;
    }

    /**
     * 解析工作目录字符串。
     *
     * @param path 目录
     * @return 绝对路径字符串
     */
    private static String resolveWorkspace(Path path) {
        return path.toString();
    }

    /**
     * 派生 opencode 会话 ID。
     *
     * @param directory 工作目录
     * @return 会话 ID
     */
    private String deriveSessionId(String directory) {
        String businessId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        String seed = businessId + '|' + directory;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            return SESSION_ID_PREFIX + HexFormat.of().formatHex(hash, 0, SESSION_HASH_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 解析生效的 opencode agent 名称。
     *
     * @return agent 名称；null 表示使用服务端默认
     */
    private String effectiveAgentName() {
        if (agentName != null && !agentName.isBlank()) {
            return agentName;
        }
        if (planMode) {
            return PLAN_AGENT_NAME;
        }
        return managedPrimaryAgentName();
    }

    /**
     * 解析模型引用。
     *
     * @return 模型引用；无法解析时返回 null
     */
    private ModelRef resolveModelRef() {
        String value = model;
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.trim();
        int slash = value.indexOf('/');
        if (slash > 0 && slash < value.length() - 1) {
            return new ModelRef(value.substring(0, slash), value.substring(slash + 1));
        }
        String provider = resolveProvider();
        if (provider == null || provider.isBlank()) {
            if (modelProviderWarned.compareAndSet(false, true)) {
                log.warn("[opencode-server] 模型 {} 缺少 provider 前缀且无 ChatClient provider 可补齐，"
                        + "会话将沿用 opencode 默认模型；请使用 provider/model 或先设置 chatClient(..)", value);
            }
            return null;
        }
        return new ModelRef(provider.trim(), value);
    }

    /**
     * 从 ChatClient 设置中取 provider。
     *
     * @return provider；未知返回 null
     */
    private String resolveProvider() {
        ChatClient client = this.chatClient;
        if (client == null) {
            return null;
        }
        ChatClientSetting setting = client.getSetting();
        return setting != null ? setting.getProvider() : null;
    }

    /**
     * 由 ChatClient 派生 {@code provider/model}。
     *
     * @param client ChatClient
     * @return 模型标识；无法派生返回 null
     */
    private static String deriveModel(ChatClient client) {
        ChatClientSetting setting = client.getSetting();
        String modelValue = client.getModel();
        if (modelValue == null || modelValue.isBlank()) {
            modelValue = setting != null ? setting.getModel() : null;
        }
        if (modelValue == null || modelValue.isBlank()) {
            return null;
        }
        modelValue = modelValue.trim();
        if (modelValue.contains("/")) {
            return modelValue;
        }
        String provider = setting != null ? setting.getProvider() : null;
        return (provider != null && !provider.isBlank()) ? provider.trim() + "/" + modelValue : modelValue;
    }

    /**
     * 构造模型请求体。
     *
     * @param ref 模型引用
     * @return 请求体 Map
     */
    private static Map<String, Object> modelBody(ModelRef ref) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerID", ref.providerID());
        body.put("id", ref.id());
        return body;
    }

    /**
     * 读取事件流起始游标。
     *
     * @param context 会话上下文
     * @return 起始游标
     */
    private long resolveCursor(SessionContext context) {
        Long cached = SESSION_CURSORS.get(context.sessionId());
        if (cached != null) {
            return cached;
        }
        long latest = fetchLatestSeq(context);
        SESSION_CURSORS.put(context.sessionId(), latest);
        return latest;
    }

    /**
     * 从历史事件中取最大 {@code durable.seq}。
     *
     * @param context 会话上下文
     * @return 最大序号；空历史或会话不存在返回 0
     */
    private long fetchLatestSeq(SessionContext context) {
        try {
            JsonNode history = context.client().getJson(
                    SESSION_PATH + "/" + context.sessionId() + "/history");
            long max = 0L;
            JsonNode data = history.path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    max = Math.max(max, item.path("durable").path("seq").asLong(-1L));
                }
            }
            return max;
        } catch (OpencodeApiException e) {
            if (e.getStatusCode() == 404) {
                return 0L;
            }
            throw e;
        }
    }

    /**
     * 解析单轮超时秒数。
     *
     * @return 秒数
     */
    private static long resolveTurnTimeoutSeconds() {
        String prop = System.getProperty(PROP_TURN_TIMEOUT);
        if (prop == null || prop.isBlank()) {
            return DEFAULT_TURN_TIMEOUT_SECONDS;
        }
        try {
            long value = Long.parseLong(prop.trim());
            return value > 0 ? value : DEFAULT_TURN_TIMEOUT_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_TURN_TIMEOUT_SECONDS;
        }
    }

    /**
     * 把上层配置翻译成 opencode 项目配置并落盘。
     *
     * <p>opencode 自管工具循环、子 agent、MCP、技能与压缩，进程外无法注入 Java 回调，
     * 因此这里生成它自己会读取的 {@code opencode.json} 与 {@code AGENTS.md}。
     * 没有任何可写内容时不触碰用户文件。</p>
     *
     * @param workspacePath 工作目录
     */
    private void applyProjectConfig(Path workspacePath) {
        Map<String, OpencodeProjectConfig.AgentSpec> specs = collectAgentSpecs();
        String globalModel = specs.isEmpty() ? resolvedModelId(null) : null;
        String instructions = definition == null ? null : definition.getInstruction();
        AgentCompressionConfig compression = compressionConfig != null
                ? compressionConfig
                : (definition == null ? null : definition.getCompressionConfig());
        boolean disableMcp = !mcpEnabled || (definition != null && !definition.isMcp());
        boolean manageMcp = disableMcp || !mcpServerSpecs.isEmpty();
        boolean managePlan = planRequested();
        boolean hasManaged = !specs.isEmpty() || globalModel != null || hasText(instructions)
                || manageMcp || managePlan || shellEnabled != null
                || compression != null || !skillPaths.isEmpty() || !disabledTools.isEmpty();
        if (!hasManaged) {
            return;
        }
        OpencodeProjectConfig config = OpencodeProjectConfig.in(workspacePath);
        specs.values().forEach(config::agent);
        if (globalModel != null) {
            config.model(globalModel);
        }
        if (shellEnabled != null) {
            config.permission("bash", shellEnabled ? OpencodeProjectConfig.PERMISSION_ALLOW
                    : OpencodeProjectConfig.PERMISSION_DENY);
        }
        if (managePlan) {
            planPermissions().forEach(config::permission);
        }
        if (manageMcp) {
            config.mcpEnabled(!disableMcp);
            mcpServerSpecs.forEach(config::mcpServer);
        }
        if (compression != null) {
            config.compaction(compression.isEnabled());
        }
        skillPaths.forEach(config::skillPath);
        if (hasText(instructions)) {
            config.instructions(instructions);
        }
        OpencodeProjectConfig.Result result = config.write();
        if (result.changed()) {
            log.info("[opencode-server] 已生成 opencode 配置: {}{}", result.configFile(),
                    result.instructionsFile() == null ? "" : "，" + result.instructionsFile());
        } else if (debug) {
            log.info("[opencode-server] opencode 配置无变化: {}", result.configFile());
        }
    }

    /**
     * 汇总需要写入 opencode 的 agent 定义。
     *
     * @return agent 名称 -> 定义
     */
    private Map<String, OpencodeProjectConfig.AgentSpec> collectAgentSpecs() {
        Map<String, OpencodeProjectConfig.AgentSpec> specs = new LinkedHashMap<>();
        String primaryName = managedPrimaryAgentName();
        if (primaryName != null) {
            specs.put(primaryName, primarySpec(primaryName));
        }
        int index = 0;
        for (AgentDefinition subAgent : subAgents) {
            index = addSubAgentSpecs(specs, subAgent, index);
        }
        if (definition != null) {
            for (AgentDefinition child : definition.getAgents()) {
                index = addSubAgentSpecs(specs, child, index);
            }
        }
        for (Map.Entry<String, ChatClient> entry : agentChatClients.entrySet()) {
            String name = sanitizeAgentName(entry.getKey());
            if (name.isEmpty() || specs.containsKey(name)) {
                continue;
            }
            specs.put(name, OpencodeProjectConfig.AgentSpec.modelOnly(name,
                    resolvedModelId(entry.getValue()), toolsOverride()));
        }
        return specs;
    }

    /**
     * 构造主 agent 定义。
     *
     * @param name agent 名称
     * @return 定义
     */
    private OpencodeProjectConfig.AgentSpec primarySpec(String name) {
        AgentDefinition def = definition;
        String prompt = def == null ? null : def.getSystemPrompt();
        String description = def == null ? null : def.getDescription();
        int steps = maxToolIterations > 0 ? maxToolIterations
                : (def == null ? 0 : def.getMaxToolIterations());
        ChatClient client = findAgentChatClient(name);
        if (client == null && def != null) {
            client = findAgentChatClient(def.getId());
        }
        Map<String, String> permissions = def != null && def.isPlanning() ? planPermissions() : null;
        return new OpencodeProjectConfig.AgentSpec(name, OpencodeProjectConfig.MODE_PRIMARY,
                description, prompt, steps > 0 ? steps : null, resolvedModelId(client), permissions,
                toolsOverride());
    }

    /**
     * 展开一个子 agent 定义（含其嵌套子 agent）。
     *
     * @param specs    已登记的 agent
     * @param subAgent 子 agent 定义
     * @param index    已处理数量
     * @return 新的已处理数量
     */
    private int addSubAgentSpecs(Map<String, OpencodeProjectConfig.AgentSpec> specs,
                                 AgentDefinition subAgent, int index) {
        if (subAgent == null) {
            return index;
        }
        int next = index + 1;
        String name = sanitizeAgentName(subAgent.getId());
        if (name.isEmpty()) {
            name = sanitizeAgentName(subAgent.getName());
        }
        if (name.isEmpty()) {
            name = "subagent-" + next;
        }
        if (specs.containsKey(name)) {
            throw new IllegalStateException("子 agent 名称重复: " + name);
        }
        String prompt = hasText(subAgent.getSystemPrompt())
                ? subAgent.getSystemPrompt() : subAgent.getInstruction();
        int steps = subAgent.getMaxToolIterations();
        ChatClient client = findAgentChatClient(subAgent.getId());
        if (client == null) {
            client = findAgentChatClient(subAgent.getName());
        }
        Map<String, String> permissions = subAgent.isPlanning() ? planPermissions() : null;
        specs.put(name, new OpencodeProjectConfig.AgentSpec(name, OpencodeProjectConfig.MODE_SUBAGENT,
                subAgent.getDescription(), prompt, steps > 0 ? steps : null,
                resolvedModelId(client), permissions, toolsOverride()));
        for (AgentDefinition child : subAgent.getAgents()) {
            next = addSubAgentSpecs(specs, child, next);
        }
        return next;
    }

    /**
     * 解析生效的主 agent 名称。
     *
     * @return 名称；null 表示使用 opencode 默认 agent
     */
    private String managedPrimaryAgentName() {
        if (agentName != null) {
            return agentName;
        }
        if (!shouldManagePrimary()) {
            // 仅按名称裁剪工具时也必须有一个可写的 agent 承载 tools 覆盖
            return disabledTools.isEmpty() ? null : DEFAULT_MANAGED_AGENT;
        }
        if (definition != null && hasText(definition.getId())) {
            String name = sanitizeAgentName(definition.getId());
            if (!name.isEmpty()) {
                return name;
            }
        }
        return DEFAULT_MANAGED_AGENT;
    }

    /**
     * 写入 agent 的工具开关覆盖。
     *
     * @return 工具名 -> false；没有关闭任何工具时返回 null
     */
    private Map<String, Boolean> toolsOverride() {
        return disabledTools.isEmpty() ? null : disabledTools;
    }

    /**
     * 是否需要生成自定义主 agent。
     *
     * @return true 表示需要
     */
    private boolean shouldManagePrimary() {
        if (maxToolIterations > 0) {
            return true;
        }
        if (definition == null) {
            return false;
        }
        return hasText(definition.getSystemPrompt()) || hasText(definition.getDescription())
                || definition.isPlanning() || definition.getMaxToolIterations() > 0;
    }

    /**
     * 是否请求了规划能力。
     *
     * @return true 表示请求
     */
    private boolean planRequested() {
        return planEnabled || planMode || (definition != null && definition.isPlanning());
    }

    /**
     * 规划相关权限（允许进入/退出 plan 模式）。
     *
     * @return 权限表
     */
    private static Map<String, String> planPermissions() {
        Map<String, String> permissions = new LinkedHashMap<>();
        permissions.put("plan_enter", OpencodeProjectConfig.PERMISSION_ALLOW);
        permissions.put("plan_exit", OpencodeProjectConfig.PERMISSION_ALLOW);
        return permissions;
    }

    /**
     * 在已登记的 agent 模型中查找 ChatClient。
     *
     * @param agentId agent 标识
     * @return ChatClient；未登记返回 null
     */
    private ChatClient findAgentChatClient(String agentId) {
        if (!hasText(agentId)) {
            return null;
        }
        ChatClient client = agentChatClients.get(agentId);
        if (client == null) {
            client = agentChatClients.get(sanitizeAgentName(agentId));
        }
        return client;
    }

    /**
     * 解析模型标识（provider/model）。
     *
     * @param preferred 优先使用的 ChatClient；null 表示使用本实例的 model
     * @return 模型标识；无法解析返回 null
     * @throws IllegalStateException 显式指定了 ChatClient 却无法解析出 provider/model
     */
    private String resolvedModelId(ChatClient preferred) {
        if (preferred != null) {
            String derived = deriveModel(preferred);
            if (derived != null && derived.indexOf('/') > 0) {
                return derived;
            }
            throw new IllegalStateException("ChatClient 无法解析出 provider/model，"
                    + "请在 ChatClientSetting 中设置 provider 与 model");
        }
        ModelRef ref = resolveModelRef();
        return ref == null ? null : ref.providerID() + "/" + ref.id();
    }

    /**
     * 校验无法跨进程桥接的 AgentDefinition 配置，避免静默降级。
     *
     * @param def 定义
     */
    private static void validateDefinition(AgentDefinition def) {
        if (def == null) {
            return;
        }
        if (def.getMcpManager() != null) {
            throw new UnsupportedOperationException(
                    "AgentDefinition 携带进程内 McpManager，opencode 无法使用；请用 mcpServer(name, spec) 声明 MCP");
        }
        if (def.getSkillManager() != null) {
            throw new UnsupportedOperationException(
                    "AgentDefinition 携带进程内 SkillManager，opencode 无法使用；请把 SKILL.md 放进 .opencode/skill/");
        }
        if (def.getMemoryConfig() != null) {
            throw new UnsupportedOperationException(
                    "opencode 自管会话记忆，不接受 AgentDefinition 中的 MemoryConfig");
        }
        if (def.getPlanHook() != null) {
            throw new UnsupportedOperationException(
                    "Java 规划 Hook 无法在 opencode 进程内执行；请改用 plan(true)");
        }
        if (def.getDebugHook() != null) {
            throw new UnsupportedOperationException(
                    "Java 调试 Hook 无法在 opencode 进程内执行；请改用 debug(true)");
        }
        if (def.getPlanMaxTask() > 0) {
            throw new UnsupportedOperationException(
                    "opencode 没有计划子任务上限配置: planMaxTask=" + def.getPlanMaxTask());
        }
        if (def.getRetryConfig() != null && def.getRetryConfig().getMaxRetries() != 0) {
            throw new UnsupportedOperationException(
                    "opencode 自管重试策略，不接受 AgentDefinition 中的 retryConfig");
        }
        if (def.isLeader()) {
            throw new UnsupportedOperationException(
                    "opencode 子 agent 不能作为 leader 编排其他 agent；请改用 mode(ROUTER/AUTO) 委派");
        }
        AgentCompressionConfig compression = def.getCompressionConfig();
        if (compression != null && compression.getCompressionChatClient() != null) {
            throw new UnsupportedOperationException(
                    "压缩用的 ChatClient 无法跨进程传入 opencode；请只设置 compressionConfig.enabled");
        }
        for (AgentDefinition child : def.getAgents()) {
            validateDefinition(child);
        }
    }

    /**
     * 把名称归一化为 opencode agent 标识（ASCII 字母/数字/-/_/.）。
     *
     * @param value 原始名称
     * @return 归一化名称；全部非法时返回空串
     */
    private static String sanitizeAgentName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            builder.append(valid ? c : '-');
        }
        String name = builder.toString();
        while (name.startsWith("-") || name.startsWith(".")) {
            name = name.substring(1);
        }
        while (name.endsWith("-") || name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    /**
     * 判断文本非空白。
     *
     * @param value 文本
     * @return true 表示有内容
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Agent 标识（事件归属）。
     *
     * @return 标识
     */
    private String agentId() {
        if (definition != null && definition.getId() != null && !definition.getId().isBlank()) {
            return definition.getId();
        }
        return DEFAULT_AGENT_ID;
    }

    /**
     * 构造错误事件。
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
     * 提取异常信息。
     *
     * @param throwable 异常
     * @return 可读信息
     */
    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        return (message == null || message.isBlank()) ? throwable.getClass().getSimpleName() : message;
    }

    /**
     * 按配置阈值截断文本。
     *
     * @param value 文本
     * @return 截断结果
     */
    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        int max = toolResultMaxChars;
        if (value.length() <= max) {
            return value;
        }
        int head = Math.min(toolResultHeadChars, value.length());
        return value.substring(0, head) + "...[truncated " + (value.length() - head) + " chars]";
    }

    /**
     * 会话上下文。
     *
     * @param client    HTTP 客户端（已绑定工作目录）
     * @param sessionId opencode 会话 ID
     * @param directory 工作目录
     */
    private record SessionContext(OpencodeHttpClient client, String sessionId, String directory) {
    }

    /**
     * 模型引用（对应 OpenAPI {@code ModelRef}）。
     *
     * @param providerID provider 标识
     * @param id         模型标识
     */
    private record ModelRef(String providerID, String id) {
    }

    /**
     * 单轮流式执行上下文：负责事件翻译、用量聚合与结束判定。
     */
    private final class Turn {

        /**
         * 会话上下文
         */
        private final SessionContext context;

        /**
         * 事件接收端
         */
        private final FluxSink<AgentStreamEvent> sink;

        /**
         * 本轮是否已结束（完成 / 失败 / 暂停审批）
         */
        private final AtomicBoolean finished = new AtomicBoolean(false);

        /**
         * 轮次开始时间
         */
        private final long startTime = System.currentTimeMillis();

        /**
         * 本次允许的最大步数；≤0 不限制
         */
        private final int maxSteps = maxToolIterations;

        /**
         * 是否允许 shell（仅有显式 {@code shellTool(false)} 时拒绝）
         */
        private final boolean shellAllowed = shellEnabled == null || shellEnabled;

        /**
         * 已开始的步数
         */
        private final AtomicInteger steps = new AtomicInteger(0);

        /**
         * 事件流
         */
        private volatile OpencodeEventStream stream;

        /**
         * 累计输入 token
         */
        private long inputTokens;

        /**
         * 累计输出 token
         */
        private long outputTokens;

        /**
         * 累计推理 token
         */
        private long reasoningTokens;

        /**
         * 累计缓存读 token
         */
        private long cacheTokens;

        /**
         * 累计费用
         */
        private BigDecimal cost = BigDecimal.ZERO;

        /**
         * 最后一次 finish 原因
         */
        private String finishReason;

        /**
         * 构造一轮。
         *
         * @param context 会话上下文
         * @param sink    事件接收端
         */
        private Turn(SessionContext context, FluxSink<AgentStreamEvent> sink) {
            this.context = context;
            this.sink = sink;
        }

        /**
         * 建立事件订阅（不阻塞等待握手）。
         *
         * <p>opencode 的 SSE 在首条事件产生前不会下发响应头，若在此阻塞等待“已连接”会白白
         * 拖慢每一轮；而事件本身带 {@code durable.seq}，稍后建立也只会从游标处补齐，不会丢。
         * 因此这里发起订阅后立即返回，由调用方紧接着发送 prompt。</p>
         */
        private void subscribe() {
            long after = resolveCursor(context);
            stream = new OpencodeEventStream(context.client(), context.sessionId(), after,
                    this::onEvent, this::onStreamError);
            stream.start();
        }

        /**
         * 本轮是否已结束。
         *
         * @return 是否结束
         */
        private boolean isFinished() {
            return finished.get();
        }

        /**
         * 关闭事件流。
         */
        private void closeQuietly() {
            OpencodeEventStream current = stream;
            if (current != null) {
                current.close();
            }
        }

        /**
         * 正常完成本轮。
         */
        private void complete() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            closeQuietly();
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.COMPLETE)
                    .agentId(agentId())
                    .usage(buildUsage())
                    .timestamp(System.currentTimeMillis())
                    .build());
            sink.complete();
        }

        /**
         * 暂停本轮（等待人工审批）。
         */
        private void pause() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            closeQuietly();
            sink.complete();
        }

        /**
         * 失败结束本轮。
         *
         * @param throwable 异常
         */
        private void fail(Throwable throwable) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            closeQuietly();
            sink.next(errorEvent(messageOf(throwable)));
            sink.complete();
        }

        /**
         * 流读取连续失败。
         *
         * @param throwable 异常
         */
        private void onStreamError(Throwable throwable) {
            fail(throwable);
        }

        /**
         * 翻译一条 durable 事件。
         *
         * @param node 事件 JSON
         */
        private void onEvent(JsonNode node) {
            String type = node.path("type").asText("");
            JsonNode data = node.path("data");
            long seq = node.path("durable").path("seq").asLong(-1L);
            if (seq > 0) {
                SESSION_CURSORS.merge(context.sessionId(), seq, Math::max);
            }
            switch (type) {
                case EVENT_TEXT_DELTA -> emitDelta(AgentStreamEvent.Type.REASONING_DELTA,
                        data.path("delta").asText(""));
                case EVENT_REASONING_DELTA -> emitDelta(AgentStreamEvent.Type.THINKING_DELTA,
                        data.path("delta").asText(""));
                case EVENT_TOOL_CALLED -> onToolCalled(data);
                case EVENT_TOOL_SUCCESS -> onToolResult(data, null);
                case EVENT_TOOL_FAILED -> onToolResult(data, errorText(data.path("error")));
                case EVENT_SHELL_STARTED -> onShellStarted(data);
                case EVENT_SHELL_ENDED -> onShellEnded(data);
                case EVENT_STEP_STARTED -> onStepStarted();
                case EVENT_STEP_ENDED -> onStepEnded(data);
                case EVENT_STEP_FAILED -> onStepFailed(data);
                case EVENT_SESSION_ERROR -> fail(new IllegalStateException(errorText(data.path("error"))));
                case EVENT_SESSION_IDLE -> complete();
                case EVENT_PERMISSION_ASKED -> onPermissionAsked(data);
                case EVENT_QUESTION_ASKED -> onQuestionAsked(data);
                default -> log.trace("[opencode-server] 未处理事件: {}", type);
            }
        }

        /**
         * 发送文本增量。
         *
         * @param type  事件类型
         * @param delta 片段
         */
        private void emitDelta(AgentStreamEvent.Type type, String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            sink.next(AgentStreamEvent.builder()
                    .type(type)
                    .agentId(agentId())
                    .delta(delta)
                    .timestamp(System.currentTimeMillis())
                    .build());
        }

        /**
         * 工具调用开始。
         *
         * @param data 事件数据
         */
        private void onToolCalled(JsonNode data) {
            String tool = data.path("tool").asText("tool");
            String input = truncate(text(data.path("input")));
            AgentLoopGuard guard = loopGuard;
            if (guard != null && guard.recordToolCall(tool, input)) {
                log.warn("[opencode-server] {}，已请求中断", guard.describe());
                requestInterrupt(context);
            }
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.TOOL_START)
                    .agentId(agentId())
                    .toolName(tool)
                    .toolInput(input)
                    .timestamp(System.currentTimeMillis())
                    .build());
        }

        /**
         * 工具调用结果。
         *
         * @param data  事件数据
         * @param error 错误信息；非空表示失败
         */
        private void onToolResult(JsonNode data, String error) {
            String output = error != null ? truncate(error) : truncate(toolContent(data));
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.TOOL_RESULT)
                    .agentId(agentId())
                    .toolName(data.path("tool").asText("tool"))
                    .toolOutput(output)
                    .timestamp(System.currentTimeMillis())
                    .build());
        }

        /**
         * shell 命令开始（映射为工具开始）。
         *
         * @param data 事件数据
         */
        private void onShellStarted(JsonNode data) {
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.TOOL_START)
                    .agentId(agentId())
                    .toolName("bash")
                    .toolInput(truncate(data.path("command").asText("")))
                    .timestamp(System.currentTimeMillis())
                    .build());
        }

        /**
         * shell 命令结束（映射为工具结果）。
         *
         * @param data 事件数据
         */
        private void onShellEnded(JsonNode data) {
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.TOOL_RESULT)
                    .agentId(agentId())
                    .toolName("bash")
                    .toolOutput(truncate(data.path("output").asText("")))
                    .timestamp(System.currentTimeMillis())
                    .build());
        }

        /**
         * 单步开始：按 {@code maxToolIterations} 做看门狗。
         */
        private void onStepStarted() {
            int count = steps.incrementAndGet();
            if (maxSteps > 0 && count > maxSteps) {
                log.info("[opencode-server] 达到 maxToolIterations={}，请求中断会话 {}", maxSteps,
                        context.sessionId());
                requestInterrupt(context);
            }
        }

        /**
         * 单步结束：聚合 token 与费用。
         *
         * @param data 事件数据
         */
        private void onStepEnded(JsonNode data) {
            JsonNode tokens = data.path("tokens");
            inputTokens += tokens.path("input").asLong(0L);
            outputTokens += tokens.path("output").asLong(0L);
            reasoningTokens += tokens.path("reasoning").asLong(0L);
            cacheTokens += tokens.path("cache").path("read").asLong(0L);
            cost = cost.add(BigDecimal.valueOf(data.path("cost").asDouble(0D)));
            finishReason = data.path("finish").asText(finishReason);
        }

        /**
         * 单步失败。
         *
         * @param data 事件数据
         */
        private void onStepFailed(JsonNode data) {
            fail(new IllegalStateException(errorText(data.path("error"))));
        }

        /**
         * 提问询问：翻译为 QUESTION_REQUEST 并暂停本轮，等待 {@link #answerQuestion} 回传答案。
         *
         * @param data 事件数据
         */
        private void onQuestionAsked(JsonNode data) {
            String requestId = data.path("id").asText("");
            if (requestId.isBlank()) {
                log.warn("[opencode-server] 收到缺少请求 ID 的 question.v2.asked，已忽略");
                return;
            }
            List<AgentQuestion> questions = new ArrayList<>();
            List<String> questionIds = new ArrayList<>();
            JsonNode items = data.path("questions");
            int index = 0;
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String questionId = requestId + "#" + index;
                    questionIds.add(questionId);
                    List<String> options = new ArrayList<>();
                    JsonNode optionNodes = item.path("options");
                    if (optionNodes.isArray()) {
                        for (JsonNode option : optionNodes) {
                            String label = option.path("label").asText("");
                            if (!label.isBlank()) {
                                options.add(label);
                            }
                        }
                    }
                    questions.add(AgentQuestion.builder()
                            .id(questionId)
                            .header(item.path("header").asText(""))
                            .question(item.path("question").asText(""))
                            .options(options)
                            .multiple(item.path("multiple").asBoolean(false))
                            .custom(item.path("custom").asBoolean(true))
                            .build());
                    index++;
                }
            }
            pendingQuestionIds.put(requestId, questionIds);
            sink.next(AgentStreamEvent.questionRequest(agentId(), requestId, questions));
            pause();
        }

        /**
         * 权限询问：shell 禁令直接拒绝，其余转人工审批。
         *
         * @param data 事件数据
         */
        private void onPermissionAsked(JsonNode data) {
            String requestId = data.path("id").asText("");
            String action = data.path("action").asText("");
            if (!shellAllowed && isShellAction(action)) {
                log.info("[opencode-server] 非管理员账号禁止 shell，自动拒绝权限请求 {}（action={}）",
                        requestId, action);
                rejectPermission(requestId, "非管理员账号禁止执行 shell 命令");
                return;
            }
            List<ConfirmationItem> items = List.of(ConfirmationItem.builder()
                    .toolCallId(requestId)
                    .toolName(action)
                    .title("需要确认：" + action)
                    .description(describeResources(data.path("resources")))
                    .build());
            sink.next(AgentStreamEvent.confirmationRequest(agentId(), requestId, items));
            pause();
        }

        /**
         * 自动拒绝权限请求。
         *
         * @param requestId 权限请求 ID
         * @param message   拒绝原因
         */
        private void rejectPermission(String requestId, String message) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reply", PERMISSION_REJECT);
            body.put("message", message);
            try {
                context.client().postNoContent(SESSION_PATH + "/" + context.sessionId()
                        + "/permission/" + requestId + "/reply", body);
            } catch (Exception e) {
                log.warn("[opencode-server] 自动拒绝权限请求 {} 失败: {}", requestId, e.getMessage());
            }
        }

        /**
         * 判断权限动作是否为 shell。
         *
         * @param action 权限动作
         * @return 是否 shell
         */
        private boolean isShellAction(String action) {
            String value = action == null ? "" : action.toLowerCase();
            return value.contains("bash") || value.contains("shell") || value.contains("command");
        }

        /**
         * 权限资源摘要。
         *
         * @param resources 资源数组节点
         * @return 摘要文本
         */
        private String describeResources(JsonNode resources) {
            if (!resources.isArray()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int index = 0;
            for (JsonNode item : resources) {
                if (index > 0) {
                    sb.append(", ");
                }
                sb.append(item.asText(""));
                if (++index >= PERMISSION_RESOURCE_LIMIT) {
                    sb.append(" ...");
                    break;
                }
            }
            return truncate(sb.toString());
        }

        /**
         * 汇总工具结果文本。
         *
         * @param data 事件数据
         * @return 结果文本
         */
        private String toolContent(JsonNode data) {
            JsonNode structured = data.path("structured");
            if (!structured.isMissingNode() && !structured.isNull()) {
                return text(structured);
            }
            JsonNode result = data.path("result");
            if (!result.isMissingNode() && !result.isNull()) {
                return text(result);
            }
            StringBuilder sb = new StringBuilder();
            JsonNode content = data.path("content");
            if (content.isArray()) {
                for (JsonNode item : content) {
                    String part = item.path("text").asText("");
                    if (!part.isBlank()) {
                        sb.append(part);
                    }
                }
            }
            return sb.toString();
        }

        /**
         * 节点转文本。
         *
         * @param node 节点
         * @return 文本
         */
        private String text(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return "";
            }
            return node.isTextual() ? node.asText() : node.toString();
        }

        /**
         * 提取错误文本。
         *
         * @param error 错误节点
         * @return 错误文本
         */
        private String errorText(JsonNode error) {
            if (error == null || error.isMissingNode() || error.isNull()) {
                return "未知错误";
            }
            if (error.isTextual()) {
                return error.asText();
            }
            JsonNode message = error.path("message");
            if (message.isTextual()) {
                return message.asText();
            }
            return error.toString();
        }

        /**
         * 构造聚合用量。
         *
         * @return 用量
         */
        private AiUsage buildUsage() {
            long total = inputTokens + outputTokens;
            ModelRef ref = resolveModelRef();
            return AiUsage.builder()
                    .inputTokens((int) inputTokens)
                    .outputTokens((int) outputTokens)
                    .totalTokens((int) total)
                    .reasoningTokens((int) reasoningTokens)
                    .cacheTokens((int) cacheTokens)
                    .totalCost(cost.signum() > 0 ? cost : null)
                    .model(ref == null ? model : ref.id())
                    .provider(ref == null ? null : ref.providerID())
                    .finishReason(finishReason)
                    .startTime(startTime)
                    .durationMillis(System.currentTimeMillis() - startTime)
                    .build();
        }
    }
}