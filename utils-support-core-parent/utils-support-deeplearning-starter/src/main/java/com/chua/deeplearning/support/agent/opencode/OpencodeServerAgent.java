package com.chua.deeplearning.support.agent.opencode;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentCompressionConfig;
import com.chua.common.support.ai.agent.AgentDebugHook;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentLoopGuard;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentPlanHook;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.agent.AgentStreamEvent;
import com.chua.common.support.ai.agent.ConfirmationDecision;
import com.chua.common.support.ai.agent.ConfirmationItem;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.memory.MemoryConfig;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于单个 {@code opencode serve} + HTTP/SSE 的 Agent 实现。
 *
 * <p>与旧的 {@code OpencodeAgent}（每次运行拉起一次 {@code opencode run} 子进程）相比，
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
 * <h3>无法映射的框架能力</h3>
 * <p>opencode 自管工具/技能/子 agent/记忆，本实现会把 {@code mcpManager/skillManager/
 * subAgent/memoryConfig} 等记录为“已忽略”，首次运行 WARN 一次。这些能力的正确接入方式是
 * 在 opencode 侧生成项目配置（后续步骤实现），而不是在本层转发。</p>
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
     * 事件流首次连接等待时间
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5L);

    /**
     * 单轮默认超时秒数
     */
    private static final long DEFAULT_TURN_TIMEOUT_SECONDS = 1800L;

    /**
     * 工具入参/出参摘要截断长度
     */
    private static final int TOOL_PAYLOAD_LIMIT = 2000;

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
     * 上下文压缩配置（仅存值）
     */
    private volatile AgentCompressionConfig compressionConfig;

    /**
     * 规划最大子任务数（仅存值）
     */
    private volatile int planMaxTask;

    /**
     * 规划 Hook（仅存值）
     */
    private volatile AgentPlanHook planHook;

    /**
     * 调试 Hook（仅存值）
     */
    private volatile AgentDebugHook debugHook;

    /**
     * 记忆体配置（仅存值，opencode 自管记忆）
     */
    private volatile MemoryConfig memoryConfig;

    /**
     * 是否打印生效配置
     */
    private volatile boolean printConfig;

    /**
     * 是否输出调试日志
     */
    private volatile boolean debug;

    /**
     * 子 Agent 定义（opencode 自管子 agent，本层仅记录）
     */
    private final List<AgentDefinition> subAgents = new CopyOnWriteArrayList<>();

    /**
     * 无法映射到 opencode 的框架配置项
     */
    private final Set<String> ignored = new LinkedHashSet<>();

    /**
     * 忽略项告警是否已发出
     */
    private final AtomicBoolean ignoredWarned = new AtomicBoolean(false);

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
        this.mode = mode == null ? AgentMode.SINGLE : mode;
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
        markIgnored("chatClient(agentId,..)");
        return this;
    }

    @Override
    public OpencodeServerAgent mcp(boolean mcp) {
        if (!mcp) {
            markIgnored("mcp(false)");
        }
        return this;
    }

    @Override
    public OpencodeServerAgent mcpManager(McpManager mcpManager) {
        markIgnored("mcpManager(..)");
        return this;
    }

    @Override
    public OpencodeServerAgent mcpManager(String agentId, McpManager mcpManager) {
        markIgnored("mcpManager(agentId,..)");
        return this;
    }

    @Override
    public OpencodeServerAgent skillManager(SkillManager skillManager) {
        markIgnored("skillManager(..)");
        return this;
    }

    @Override
    public OpencodeServerAgent skillManager(String agentId, SkillManager skillManager) {
        markIgnored("skillManager(agentId,..)");
        return this;
    }

    @Override
    public OpencodeServerAgent skill(String name, String description, SkillHandler handler) {
        markIgnored("skill(..)");
        return this;
    }

    @Override
    public OpencodeServerAgent subAgent(AgentDefinition subAgent) {
        if (subAgent != null) {
            subAgents.add(subAgent);
        }
        markIgnored("subAgent(..)");
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
        this.memoryConfig = memoryConfig;
        markIgnored("memoryConfig(..)");
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
        this.planMaxTask = planMaxTask;
        return this;
    }

    @Override
    public OpencodeServerAgent planHook(AgentPlanHook planHook) {
        this.planHook = planHook;
        markIgnored("planHook(..)");
        return this;
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
        this.debugHook = debugHook;
        markIgnored("debugHook(..)");
        return this;
    }

    @Override
    public OpencodeServerAgent definition(AgentDefinition definition) {
        this.definition = definition;
        return this;
    }

    @Override
    public AgentDefinition getDefinition() {
        return definition;
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
            warnIgnoredOnce();
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
        OpencodeServerProcess.ServerInfo serverInfo = server.ensureStarted();
        String directory = resolveWorkspace(resolveWorkspacePath());
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
            turn.awaitConnected();
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
        return null;
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
            markIgnored("model(缺少 provider 前缀)");
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
     * 记录无法映射的配置项。
     *
     * @param feature 配置项
     */
    private void markIgnored(String feature) {
        ignored.add(feature);
    }

    /**
     * 首次运行时提示无法映射的配置项。
     */
    private void warnIgnoredOnce() {
        if (ignored.isEmpty() || !ignoredWarned.compareAndSet(false, true)) {
            return;
        }
        log.info("[opencode-server] 以下框架配置由 opencode 自管，未在本层转发: {}", ignored);
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
     * 截断文本。
     *
     * @param value 文本
     * @return 截断结果
     */
    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > TOOL_PAYLOAD_LIMIT ? value.substring(0, TOOL_PAYLOAD_LIMIT) + "..." : value;
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
         * 建立事件订阅并等待首次连接。
         */
        private void awaitConnected() {
            long after = resolveCursor(context);
            stream = new OpencodeEventStream(context.client(), context.sessionId(), after,
                    this::onEvent, this::onStreamError);
            stream.start();
            if (!stream.awaitConnected(CONNECT_TIMEOUT)) {
                log.debug("[opencode-server] 事件流首次连接未在 {} 内建立，继续执行启动动作", CONNECT_TIMEOUT);
            }
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
                case EVENT_QUESTION_ASKED -> log.debug("[opencode-server] 收到 question.v2.asked，当前版本暂不桥接提问: {}",
                        data.path("id").asText(""));
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
