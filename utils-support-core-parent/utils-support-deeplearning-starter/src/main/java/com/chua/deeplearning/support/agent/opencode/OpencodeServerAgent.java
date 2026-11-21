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
import com.chua.common.support.ai.agent.AgentTrace;
import com.chua.common.support.ai.agent.AgentTool;
import com.chua.common.support.ai.agent.goal.AgentGoalSupport;
import com.chua.common.support.ai.agent.goal.GoalLoop;
import com.chua.common.support.ai.agent.goal.GoalStatus;
import com.chua.common.support.ai.agent.goal.GoalTracker;
import com.chua.common.support.ai.agent.goal.SessionGoal;
import com.chua.common.support.ai.agent.ConfirmationDecision;
import com.chua.common.support.ai.agent.ConfirmationItem;
import com.chua.common.support.ai.agent.ToolRiskPolicy;
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
import com.chua.common.support.utils.StringUtils;

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
 *   <li>按 server 实际版本选择契约（{@link OpencodeApiProfile}，探测 {@code GET /api/info}，
 *       1.19.x 回退 {@code /global/health}）：1.x 用会话级事件流
 *       {@code GET /api/session/{id}/event?after=<seq>}，2.x 用全局
 *       {@code GET /api/event} 并按 {@code data.sessionID} 过滤本会话</li>
 *   <li>建立/复用会话（{@code POST /api/session}，携带 {@code location.directory}）</li>
 *   <li>按用户配置切换模型（{@code POST /api/session/{id}/model}）与 agent
 *       （{@code POST /api/session/{id}/agent}）</li>
 *   <li>订阅事件流后再发消息：1.19.x 的请求体是 {@code {"prompt":{"text":..}}}，
 *       2.x 是 {@code {"text":..}}（发错会被 {@code additionalProperties:false} 直接 400）</li>
 *   <li>把事件翻译为 {@link AgentStreamEvent}：正文/思考增量、工具开始/结果、
 *       审批请求、提问/表单、错误、完成；事件名统一归一化
 *       （{@code session.next.*} → {@code session.*}，{@code permission.v2.asked} →
 *       {@code permission.asked}，{@code question.v2.asked} → {@code question.asked}）</li>
 * </ol>
 *
 * <p>两版的终态判定也不同：1.19.x 没有会话级终态，只能靠 {@code session.step.ended}
 * 的 {@code finish != tool-calls}；2.x 以 {@code session.execution.succeeded/failed/
 * interrupted} 为准，并在断线重连后读一次 {@code GET /api/session/{id}} 按
 * {@code outcome} 与 {@code time.busy/idle} 补判，避免漏掉断线窗口里的终态事件。</p>
 *
 * <h3>人工审批</h3>
 * <p>{@code permission.asked}（1.19.x 的 {@code permission.v2.asked}）映射为
 * {@code CONFIRMATION_REQUEST}（{@code replyId} 为权限请求 ID），本轮 Flux 正常结束；
 * {@link #resumeStream} 通过 {@code POST /api/session/{id}/permission/{requestID}/reply}
 * 回传决策：1.19.x 用 {@code {"reply":"once|reject"}}，2.x 用
 * {@code {"decision":"once|always|reject"}}，随后从当前游标继续订阅。</p>
 *
 * <h3>结构化提问</h3>
 * <p>2.x 把提问改成表单：{@code form.created} 的每个字段映射为一个
 * {@link com.chua.common.support.ai.agent.AgentQuestion}，答案经
 * {@code POST /api/session/{id}/form/{formID}/reply} 的 {@code {"answer":{key:value}}}
 * 回传，并按字段类型还原成布尔/数字/数组。1.19.x 仍走 {@code question.asked} 与
 * {@code {"answers":[[...]]}}。</p>
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
     * 2.x 全局事件流路径（1.19.x 的会话级流是 {@code /api/session/{id}/event}）
     */
    private static final String GLOBAL_EVENT_PATH = "/api/event";

    /**
     * 1.19.x 事件名前缀：2.x 去掉了这一段，解析前统一归一化
     */
    private static final String LEGACY_EVENT_PREFIX = "session.next.";

    /**
     * 归一化后的事件名前缀
     */
    private static final String EVENT_PREFIX = "session.";

    /**
     * 事件名称：正文增量
     */
    private static final String EVENT_TEXT_DELTA = "session.text.delta";

    /**
     * 事件名称：正文开始
     */
    private static final String EVENT_TEXT_STARTED = "session.text.started";

    /**
     * 事件名称：正文结束（{@code data.text} 携带完整正文）
     */
    private static final String EVENT_TEXT_ENDED = "session.text.ended";

    /**
     * 事件名称：思考增量
     */
    private static final String EVENT_REASONING_DELTA = "session.reasoning.delta";

    /**
     * 事件名称：思考结束（{@code data.text} 携带完整思考）
     */
    private static final String EVENT_REASONING_ENDED = "session.reasoning.ended";

    /**
     * 事件名称：工具入参开始（{@code data.id}/{@code data.name}）
     */
    private static final String EVENT_TOOL_INPUT_STARTED = "session.tool.input.started";

    /**
     * 事件名称：工具入参结束（{@code data.text} 为入参 JSON 原文）
     */
    private static final String EVENT_TOOL_INPUT_ENDED = "session.tool.input.ended";

    /**
     * 事件名称：工具调用开始
     */
    private static final String EVENT_TOOL_CALLED = "session.tool.called";

    /**
     * 事件名称：工具调用成功
     */
    private static final String EVENT_TOOL_SUCCESS = "session.tool.success";

    /**
     * 事件名称：工具调用失败
     */
    private static final String EVENT_TOOL_FAILED = "session.tool.failed";

    /**
     * 事件名称：shell 开始
     */
    private static final String EVENT_SHELL_STARTED = "session.shell.started";

    /**
     * 事件名称：shell 结束
     */
    private static final String EVENT_SHELL_ENDED = "session.shell.ended";

    /**
     * 事件名称：单步开始
     */
    private static final String EVENT_STEP_STARTED = "session.step.started";

    /**
     * 事件名称：单步结束（含 token 与费用）
     */
    private static final String EVENT_STEP_ENDED = "session.step.ended";

    /**
     * 事件名称：单步失败
     */
    private static final String EVENT_STEP_FAILED = "session.step.failed";

    /**
     * 事件名称：一轮执行开始
     */
    private static final String EVENT_EXECUTION_STARTED = "session.execution.started";

    /**
     * 事件名称：一轮执行成功（2.x 的权威终态）
     */
    private static final String EVENT_EXECUTION_SUCCEEDED = "session.execution.succeeded";

    /**
     * 事件名称：一轮执行失败
     */
    private static final String EVENT_EXECUTION_FAILED = "session.execution.failed";

    /**
     * 事件名称：一轮执行被中断
     */
    private static final String EVENT_EXECUTION_INTERRUPTED = "session.execution.interrupted";

    /**
     * 事件名称：会话用量更新（{@code cost}/{@code tokens} 为会话累计值）
     */
    private static final String EVENT_USAGE_UPDATED = "session.usage.updated";

    /**
     * 事件名称：重试已排期
     */
    private static final String EVENT_RETRY_SCHEDULED = "session.retry.scheduled";

    /**
     * 事件名称：会话错误（1.19.x）
     */
    private static final String EVENT_SESSION_ERROR = "session.error";

    /**
     * 事件名称：会话空闲（一轮结束）
     */
    private static final String EVENT_SESSION_IDLE = "session.idle";

    /**
     * 事件名称：权限询问
     */
    private static final String EVENT_PERMISSION_ASKED = "permission.asked";

    /**
     * 1.19.x 权限询问的旧事件名
     */
    private static final String LEGACY_EVENT_PERMISSION_ASKED = "permission.v2.asked";

    /**
     * 事件名称：结构化提问（1.19.x）
     */
    private static final String EVENT_QUESTION_ASKED = "question.asked";

    /**
     * 1.19.x 结构化提问的旧事件名
     */
    private static final String LEGACY_EVENT_QUESTION_ASKED = "question.v2.asked";

    /**
     * 事件名称：表单创建（2.x，替代 question）
     */
    private static final String EVENT_FORM_CREATED = "form.created";

    /**
     * 事件名称：表单已取消
     */
    private static final String EVENT_FORM_CANCELLED = "form.cancelled";

    /**
     * 权限应答：单次允许
     */
    private static final String PERMISSION_ONCE = "once";

    /**
     * 权限应答：永久允许
     */
    private static final String PERMISSION_ALWAYS = "always";

    /**
     * 权限应答：拒绝
     */
    private static final String PERMISSION_REJECT = "reject";

    /**
     * 2.x 权限回传字段名
     */
    private static final String FIELD_DECISION = "decision";

    /**
     * 1.19.x 权限回传字段名
     */
    private static final String FIELD_REPLY = "reply";

    /**
     * 单步结束原因：仍需继续执行工具（本轮未结束）
     */
    private static final String FINISH_TOOL_CALLS = "tool-calls";

    /**
     * 会话终态：成功
     */
    private static final String OUTCOME_SUCCEEDED = "succeeded";

    /**
     * 会话终态：失败
     */
    private static final String OUTCOME_FAILED = "failed";

    /**
     * 会话终态：被中断
     */
    private static final String OUTCOME_INTERRUPTED = "interrupted";

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
     * 托管 agent 默认启用的内置工具。
     *
     * <p>opencode 1.19.x 不会把内置工具集继承给配置里自定义的 agent；不显式列出时，
     * write/read/bash 等调用一律返回 {@code provider.executed=false}（提示
     * "Unable to write ..."），表现为「模型能聊天但永远改不了文件」。这里显式启用，
     * 再由 {@code disabledTools} 覆盖关闭项。</p>
     */
    private static final List<String> MANAGED_TOOL_NAMES = List.of(
            "bash", "edit", "write", "read", "glob", "grep", "list", "patch",
            "webfetch", "task", "todowrite", "todoread");

    /**
     * 单轮默认超时秒数
     */
    private static final long DEFAULT_TURN_TIMEOUT_SECONDS = 1800L;

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
     * 待回答的 2.x 表单：表单请求 ID -> 字段定义（顺序与字段顺序一致）
     */
    private final Map<String, List<FormField>> pendingFormFields = new ConcurrentHashMap<>();

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
        this.model = (StringUtils.isBlank(model)) ? null : model.trim();
        return this;
    }

    /**
     * 设置 opencode agent 名称。
     *
     * @param agentName agent 名称（须在 opencode 配置中存在），null 使用默认
     * @return 当前实例
     */
    public OpencodeServerAgent agentName(String agentName) {
        this.agentName = (StringUtils.isBlank(agentName)) ? null : agentName.trim();
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
        this.sessionId = (StringUtils.isBlank(sessionId)) ? null : sessionId.trim();
        this.currentSession = null;
        return this;
    }

    @Override
    public OpencodeServerAgent workspace(String workspace) {
        this.workspace = (StringUtils.isBlank(workspace)) ? null : workspace.trim();
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
        if (StringUtils.isBlank(this.model)) {
            this.model = deriveModel(chatClient);
        }
        return this;
    }

    @Override
    public OpencodeServerAgent chatClient(String agentId, ChatClient chatClient) {
        if (StringUtils.isBlank(agentId)) {
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
        if (StringUtils.isBlank(name) || spec == null || spec.isEmpty()) {
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
        if (StringUtils.isBlank(path)) {
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
        if (StringUtils.isBlank(toolName)) {
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
            if (StringUtils.isBlank(replyId)) {
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
        pendingFormFields.clear();
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
            if (StringUtils.isBlank(input)) {
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
        OpencodeApiProfile profile = OpencodeApiProfile.detect(client);
        String sessionPath = SESSION_PATH + "/" + opencodeSessionId;
        if (!exists(client, sessionPath)) {
            createSession(client, opencodeSessionId, directory);
        }
        applyModel(client, sessionPath);
        applyAgent(client, sessionPath);
        SessionContext context = new SessionContext(client, opencodeSessionId, directory, profile);
        currentSession = context;
        return context;
    }

    /**
     * 发送一条用户消息。
     *
     * <p>两个版本的请求体不同：2.x 直接是 {@code {"text":..}}，1.19.x 包一层
     * {@code {"prompt":{"text":..}}}。发错会被服务端按 {@code additionalProperties:false}
     * 直接 400，因此必须按 {@link OpencodeApiProfile} 构造。</p>
     *
     * @param context 会话上下文
     * @param input   用户输入
     */
    private void sendPrompt(SessionContext context, String input) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("text", input);
        Object body = prompt;
        if (!context.profile().isV2()) {
            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("prompt", prompt);
            body = legacy;
        }
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
            if (StringUtils.isBlank(requestId)) {
                requestId = replyId;
            }
            String reply = permissionReplyOf(decision);
            Map<String, Object> body = permissionReplyBody(context.profile(), reply, feedback);
            context.client().postNoContent(SESSION_PATH + "/" + context.sessionId()
                    + "/permission/" + requestId + "/reply", body);
        }
    }

    /**
     * 构造权限回传请求体。
     *
     * @param profile 契约版本
     * @param reply   {@code once}/{@code always}/{@code reject}
     * @param message 附加说明，可为空
     * @return 请求体
     */
    private static Map<String, Object> permissionReplyBody(OpencodeApiProfile profile, String reply,
                                                           String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(profile.isV2() ? FIELD_DECISION : FIELD_REPLY, reply);
        if (StringUtils.isNotBlank(message)) {
            body.put("message", message);
        }
        return body;
    }

    /**
     * 决策 → opencode 权限应答值。
     *
     * <p>拒绝 → {@code reject}；批准按决策 {@code mode} 映射：{@code always}
     * 表示记住该工具（后续同类不再询问），其余（含缺省）按单次放行 {@code once}。</p>
     *
     * @param decision 用户决策
     * @return {@code once}/{@code always}/{@code reject}
     */
    private static String permissionReplyOf(ConfirmationDecision decision) {
        if (!decision.isConfirmed()) {
            return PERMISSION_REJECT;
        }
        String mode = decision.getMode();
        if (PERMISSION_ALWAYS.equalsIgnoreCase(mode)) {
            return PERMISSION_ALWAYS;
        }
        return PERMISSION_ONCE;
    }

    /**
     * 回传结构化提问/表单的答案。
     *
     * @param context 会话上下文
     * @param replyId 提问请求 ID
     * @param answers 已按提问顺序排好的答案
     */
    private void replyQuestion(SessionContext context, String replyId, List<List<String>> answers) {
        List<FormField> fields = pendingFormFields.remove(replyId);
        if (fields != null) {
            Map<String, Object> answer = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                FormField field = fields.get(i);
                List<String> values = i < answers.size() ? answers.get(i) : List.of();
                Object value = field.toValue(values);
                if (value != null) {
                    answer.put(field.key(), value);
                }
            }
            pendingQuestionIds.remove(replyId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("answer", answer);
            context.client().postNoContent(SESSION_PATH + "/" + context.sessionId()
                    + "/form/" + replyId + "/reply", body);
            return;
        }
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
        var byId = new LinkedHashMap<String, List<String>>();
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
        if (StringUtils.isBlank(value)) {
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
        String businessId = (StringUtils.isBlank(sessionId)) ? "default" : sessionId;
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
        if (StringUtils.isNotBlank(agentName)) {
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
        if (StringUtils.isBlank(value)) {
            return null;
        }
        value = value.trim();
        int slash = value.indexOf('/');
        if (slash > 0 && slash < value.length() - 1) {
            return new ModelRef(value.substring(0, slash), value.substring(slash + 1));
        }
        String provider = resolveProvider();
        if (StringUtils.isBlank(provider)) {
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
        if (StringUtils.isBlank(modelValue)) {
            modelValue = setting != null ? setting.getModel() : null;
        }
        if (StringUtils.isBlank(modelValue)) {
            return null;
        }
        modelValue = modelValue.trim();
        if (modelValue.contains("/")) {
            return modelValue;
        }
        String provider = setting != null ? setting.getProvider() : null;
        return (StringUtils.isNotBlank(provider)) ? provider.trim() + "/" + modelValue : modelValue;
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
        if (context.v2()) {
            // 2.x 没有 /history 接口，全局事件流也不接受 after 游标
            return 0L;
        }
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
        if (StringUtils.isBlank(prop)) {
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
        Map<String, OpencodeProjectConfig.ProviderSpec> providers = collectProviderSpecs();
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
                || compression != null || !skillPaths.isEmpty() || !disabledTools.isEmpty()
                || !providers.isEmpty();
        if (!hasManaged) {
            return;
        }
        OpencodeProjectConfig config = OpencodeProjectConfig.in(workspacePath);
        specs.values().forEach(config::agent);
        providers.values().forEach(config::provider);
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
     * 汇总需要写入 opencode 的 provider 定义。
     *
     * <p>opencode 只认识内置目录里的官方供应商；站点自建的 OpenAI 兼容端点
     * （如 {@code moss}）必须显式声明端点与 {@code models}，否则
     * {@code provider/model} 解析不到，报 {@code UnknownError: Unexpected server error}。
     * 密钥不写进项目文件，而是交给
     * {@link OpencodeServerProcess#providerCredential(String, String)}：
     * 1.x 合并进隔离数据目录的 {@code auth.json}，2.x 注入
     * {@code providers.<id>.env} 声明的环境变量。</p>
     *
     * @return provider 标识 -> 定义
     */
    private Map<String, OpencodeProjectConfig.ProviderSpec> collectProviderSpecs() {
        Map<String, OpencodeProjectConfig.ProviderSpec> specs = new LinkedHashMap<>();
        registerProvider(chatClient, specs);
        agentChatClients.values().forEach(client -> registerProvider(client, specs));
        return specs;
    }

    /**
     * 登记单个 ChatClient 对应的 provider（含凭据）。
     *
     * @param client ChatClient；null 忽略
     * @param specs  已登记的 provider
     */
    private void registerProvider(ChatClient client,
                                  Map<String, OpencodeProjectConfig.ProviderSpec> specs) {
        if (client == null) {
            return;
        }
        ChatClientSetting setting = client.getSetting();
        String baseUrl = setting != null ? setting.getBaseUrl() : null;
        // resolvedModelId 保证返回 provider/model（解析不出时抛异常），
        // 因此直接按斜杠切分；provider 以模型串前缀为准，才能与随后写入的
        // model 字段（agent.model / 顶层 model）保持一致，否则模型会挂到
        // 不存在的 provider 下静默不可用。
        String providerModel = resolvedModelId(client);
        int split = providerModel.indexOf('/');
        String providerId = providerModel.substring(0, split).trim();
        String modelName = providerModel.substring(split + 1).trim();
        if (providerId.isEmpty()) {
            return;
        }
        String apiKey = setting != null ? setting.getAppKey() : null;
        server.providerCredential(providerId, apiKey);
        if (StringUtils.isBlank(baseUrl)) {
            return;
        }
        OpencodeProjectConfig.ProviderSpec existing = specs.get(providerId);
        List<String> models = new ArrayList<>();
        if (existing != null && existing.models() != null) {
            models.addAll(existing.models());
        }
        if (!modelName.isEmpty() && !models.contains(modelName)) {
            models.add(modelName);
        }
        specs.put(providerId, OpencodeProjectConfig.ProviderSpec.openAiCompatible(
                providerId, baseUrl.trim(), models));
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
     * <p>自定义 agent 不会被 opencode 自动赋予内置工具，必须显式列出；未列出的工具
     * 调用会直接失败。默认启用 {@link #MANAGED_TOOL_NAMES}，{@link #disabledTools}
     * 中的关闭项优先。</p>
     *
     * @return 工具名 -> 是否启用
     */
    private Map<String, Boolean> toolsOverride() {
        Map<String, Boolean> tools = new LinkedHashMap<>();
        for (String name : MANAGED_TOOL_NAMES) {
            tools.put(name, Boolean.TRUE);
        }
        if (!disabledTools.isEmpty()) {
            tools.putAll(disabledTools);
        }
        return tools;
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
        if (StringUtils.isBlank(value)) {
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
        return StringUtils.isNotBlank(value);
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
        return (StringUtils.isBlank(message)) ? throwable.getClass().getSimpleName() : message;
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
    private record SessionContext(OpencodeHttpClient client, String sessionId, String directory,
                                  OpencodeApiProfile profile) {

        /**
         * 是否为 2.x 契约。
         *
         * @return true 表示 2.x
         */
        private boolean v2() {
            return profile != null && profile.isV2();
        }
    }

    /**
     * 2.x 表单字段（对应 OpenAPI {@code Form.Field}）。
     *
     * <p>{@code AgentQuestion} 只有「文本 + 选项」的表达力，因此非字符串字段在这里
     * 负责把前端回传的字符串还原成表单要求的 JSON 类型：多选为字符串数组、布尔为
     * {@code true}/{@code false}、数字为 number。</p>
     *
     * @param key   字段键（回传 {@code answer} 的对象键）
     * @param type  字段类型：{@code string}/{@code number}/{@code integer}/{@code boolean}/{@code multiselect}
     * @param title 字段标题
     */
    private record FormField(String key, String type, String title) {

        /**
         * 类型：多选
         */
        private static final String TYPE_MULTISELECT = "multiselect";

        /**
         * 类型：布尔
         */
        private static final String TYPE_BOOLEAN = "boolean";

        /**
         * 类型：整数
         */
        private static final String TYPE_INTEGER = "integer";

        /**
         * 类型：数字
         */
        private static final String TYPE_NUMBER = "number";

        /**
         * 类型：外部字段（由外部页面处理，无法在本轮回答）
         */
        private static final String TYPE_EXTERNAL = "external";

        /**
         * 布尔字段的选项值
         */
        private static final String VALUE_TRUE = "true";

        /**
         * 布尔字段的选项值
         */
        private static final String VALUE_FALSE = "false";

        /**
         * 把用户答案转成表单要求的 JSON 值。
         *
         * @param values 用户答案（按该字段）
         * @return 表单值；无有效答案返回 null（该字段不出现在请求体里）
         */
        private Object toValue(List<String> values) {
            List<String> items = values == null ? List.of() : values;
            switch (type == null ? "" : type) {
                case TYPE_MULTISELECT -> {
                    return items.isEmpty() ? null : items;
                }
                case TYPE_BOOLEAN -> {
                    if (items.isEmpty()) {
                        return null;
                    }
                    return Boolean.parseBoolean(items.get(0));
                }
                case TYPE_INTEGER -> {
                    if (items.isEmpty() || StringUtils.isBlank(items.get(0))) {
                        return null;
                    }
                    try {
                        return Long.parseLong(items.get(0).trim());
                    } catch (NumberFormatException e) {
                        return items.get(0);
                    }
                }
                case TYPE_NUMBER -> {
                    if (items.isEmpty() || StringUtils.isBlank(items.get(0))) {
                        return null;
                    }
                    try {
                        return Double.parseDouble(items.get(0).trim());
                    } catch (NumberFormatException e) {
                        return items.get(0);
                    }
                }
                default -> {
                    return items.isEmpty() ? null : items.get(0);
                }
            }
        }
    }

    /**
     * 会话累计用量的快照（2.x 的 {@code usage.updated} 为会话累计值）。
     *
     * @param inputTokens     累计输入 token
     * @param outputTokens    累计输出 token
     * @param reasoningTokens 累计推理 token
     * @param cacheTokens     累计缓存读 token
     * @param cost            累计费用
     */
    private record SessionUsage(long inputTokens, long outputTokens, long reasoningTokens,
                                long cacheTokens, BigDecimal cost) {
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
         * 各正文片段的已下发长度（用于 {@code text.ended} 补齐未走增量的余量）
         */
        private final Map<String, Integer> textLengths = new ConcurrentHashMap<>();

        /**
         * 各思考片段的已下发长度
         */
        private final Map<String, Integer> reasoningLengths = new ConcurrentHashMap<>();

        /**
         * 已开始但未结束的工具调用：调用键 -> 开始时刻与区间标识。
         *
         * <p>1.19.x 的 {@code tool.called} 与 {@code tool.success/failed} 之间只靠
         * {@code callID} 关联，不记下开始时刻就算不出耗时；而 {@code AgentTrace.end}
         * 自带的 timestamp 与 endTimestamp 同为「此刻」，相减恒为 0，
         * 工具行因此一直显示不出秒数。</p>
         */
        private final Map<String, ToolSpan> toolSpans = new ConcurrentHashMap<>();

        /**
         * 工具区间序号：同一轮里多次同名调用也能得到不重复的 spanId
         */
        private final AtomicInteger toolSeq = new AtomicInteger(0);

        /**
         * 工具调用 ID -> 工具名（2.x 的 {@code tool.input.started} 才带名字）
         */
        private final Map<String, String> toolNames = new ConcurrentHashMap<>();

        /**
         * 本轮是否已经收到 {@code session.execution.started}（断线补判的前提）
         */
        private volatile boolean executionStarted;

        /**
         * 本轮是否已收到 {@code session.usage.updated}
         */
        private volatile boolean usageSeen;

        /**
         * 是否已从 {@code session.step.ended} 拿到按步 token（优先于会话累计值）
         */
        private volatile boolean stepTokensSeen;

        /**
         * 本轮开始前的会话累计用量（2.x 的 {@code usage.updated} 是会话累计值，需减基线）
         */
        private SessionUsage usageAtStart;

        /**
         * 工具调用区间：开始毫秒 + 区间标识（开始/结束两条 trace 共享 spanId）。
         *
         * @param startAt 开始毫秒
         * @param spanId  区间标识
         */
        private record ToolSpan(long startAt, String spanId) {
        }

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
         * 拖慢每一轮；而 1.19.x 的事件带 {@code durable.seq} 游标、2.x 的全局流则靠
         * {@code onReconnect} 补做终态判断，因此这里发起订阅后立即返回，由调用方紧接着
         * 发送 prompt。</p>
         */
        private void subscribe() {
            if (context.v2()) {
                // 2.x 只有全局流：按 data.sessionID 过滤本会话，避免别的会话把本轮带跑
                captureUsageBaseline();
                stream = new OpencodeEventStream(context.client(), context.sessionId(), 0L,
                        GLOBAL_EVENT_PATH, false, this::belongsToSession,
                        this::onEvent, this::onStreamError, this::onReconnect);
            } else {
                long after = resolveCursor(context);
                stream = new OpencodeEventStream(context.client(), context.sessionId(), after,
                        this::onEvent, this::onStreamError);
            }
            stream.start();
        }

        /**
         * 记录本轮开始前的会话累计用量。
         *
         * <p>2.x 的 {@code session.usage.updated} 给的是<b>会话累计</b>值，只有减去本轮
         * 基线才是本轮用量。基线在发 prompt 之前读取，取不到时保持 null，
         * {@link #onUsageUpdated} 就不会拿累计值冒充本轮值。</p>
         */
        private void captureUsageBaseline() {
            try {
                JsonNode data = context.client()
                        .getJson(SESSION_PATH + "/" + context.sessionId()).path("data");
                JsonNode tokens = data.path("tokens");
                usageAtStart = new SessionUsage(
                        tokens.path("input").asLong(0L),
                        tokens.path("output").asLong(0L),
                        tokens.path("reasoning").asLong(0L),
                        tokens.path("cache").path("read").asLong(0L),
                        BigDecimal.valueOf(data.path("cost").asDouble(0D)));
            } catch (Exception e) {
                log.debug("[opencode-server] 读取会话 {} 的用量基线失败: {}",
                        context.sessionId(), e.getMessage());
            }
        }

        /**
         * 2.x 全局事件是否属于本会话。
         *
         * <p>会话类事件的 {@code data.sessionID} 一定存在；{@code server.connected} 等
         * 全局事件没有该字段，对本轮没有意义，一并丢弃。</p>
         *
         * @param node 事件 JSON
         * @return 是否属于本会话
         */
        private boolean belongsToSession(JsonNode node) {
            JsonNode data = node.path("data");
            String sessionID = data.path("sessionID").asText("");
            if (sessionID.isBlank()) {
                // form.created 把会话 ID 放在 form 里，表单事件没有顶层 sessionID
                sessionID = data.path("form").path("sessionID").asText("");
            }
            if (sessionID.isBlank()) {
                sessionID = node.path("durable").path("aggregateID").asText("");
            }
            return context.sessionId().equals(sessionID);
        }

        /**
         * 断线重连后补判终态。
         *
         * <p>2.x 的全局事件流不保证补发断线期间的事件，若 {@code execution.succeeded}
         * 恰好落在断线窗口里，本轮会一直挂到超时。这里在重连后读一次会话状态：
         * 只有本轮确实已开始（收到过 {@code execution.started}）且会话已从忙碌转为空闲
         * （{@code time.idle >= time.busy}）时，才按 {@code outcome} 补做结束判断。</p>
         */
        private void onReconnect() {
            if (!context.v2() || !executionStarted || isFinished()) {
                return;
            }
            try {
                JsonNode data = context.client()
                        .getJson(SESSION_PATH + "/" + context.sessionId()).path("data");
                long busy = data.path("time").path("busy").asLong(0L);
                long idle = data.path("time").path("idle").asLong(0L);
                if (busy <= 0L || idle < busy) {
                    // 仍在执行（或从未执行过），继续等事件
                    return;
                }
                String outcome = data.path("outcome").asText("");
                if (OUTCOME_FAILED.equals(outcome)) {
                    fail(new IllegalStateException(
                            "opencode 会话执行失败（断线重连后按会话状态补判）"));
                } else if (OUTCOME_INTERRUPTED.equals(outcome)) {
                    finishReason = OUTCOME_INTERRUPTED;
                    complete();
                } else {
                    // succeeded 或暂无 outcome：会话已空闲，按成功收流
                    complete();
                }
            } catch (Exception e) {
                log.warn("[opencode-server] 断线重连后补判会话 {} 状态失败: {}",
                        context.sessionId(), e.getMessage());
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
            String type = canonicalType(node.path("type").asText(""));
            JsonNode data = node.path("data");
            long seq = node.path("durable").path("seq").asLong(-1L);
            if (seq > 0) {
                SESSION_CURSORS.merge(context.sessionId(), seq, Math::max);
            }
            switch (type) {
                case EVENT_TEXT_STARTED -> {
                    // 正文开始只用于分段，正文本身仍由增量/结束事件下发
                }
                case EVENT_TEXT_DELTA -> onTextDelta(data, AgentStreamEvent.Type.REASONING_DELTA, textLengths);
                case EVENT_TEXT_ENDED -> onTextEnded(data, AgentStreamEvent.Type.REASONING_DELTA, textLengths);
                case EVENT_REASONING_DELTA -> onTextDelta(data, AgentStreamEvent.Type.THINKING_DELTA, reasoningLengths);
                case EVENT_REASONING_ENDED -> onTextEnded(data, AgentStreamEvent.Type.THINKING_DELTA, reasoningLengths);
                case EVENT_TOOL_INPUT_STARTED -> onToolInputStarted(data);
                case EVENT_TOOL_CALLED -> onToolCalled(data);
                case EVENT_TOOL_SUCCESS -> onToolResult(data, null);
                case EVENT_TOOL_FAILED -> onToolResult(data, errorText(data.path("error")));
                case EVENT_SHELL_STARTED -> onShellStarted(data);
                case EVENT_SHELL_ENDED -> onShellEnded(data);
                case EVENT_STEP_STARTED -> onStepStarted();
                case EVENT_STEP_ENDED -> onStepEnded(data);
                case EVENT_STEP_FAILED -> onStepFailed(data);
                case EVENT_EXECUTION_STARTED -> executionStarted = true;
                case EVENT_EXECUTION_SUCCEEDED -> complete();
                case EVENT_EXECUTION_FAILED -> fail(new IllegalStateException(errorText(data.path("error"))));
                case EVENT_EXECUTION_INTERRUPTED -> {
                    finishReason = data.path("reason").asText("interrupted");
                    complete();
                }
                case EVENT_USAGE_UPDATED -> onUsageUpdated(data);
                case EVENT_RETRY_SCHEDULED -> log.warn("[opencode-server] 会话 {} 第 {} 次重试（{}ms 后）: {}",
                        context.sessionId(), data.path("attempt").asInt(0), data.path("at").asLong(0L),
                        errorText(data.path("error")));
                case EVENT_SESSION_ERROR -> fail(new IllegalStateException(errorText(data.path("error"))));
                case EVENT_SESSION_IDLE -> complete();
                case EVENT_PERMISSION_ASKED -> onPermissionAsked(data);
                case EVENT_QUESTION_ASKED -> onQuestionAsked(data);
                case EVENT_FORM_CREATED -> onFormCreated(data.path("form"));
                case EVENT_FORM_CANCELLED -> {
                    String formId = data.path("id").asText("");
                    log.info("[opencode-server] 表单 {} 已被取消", formId);
                    pendingFormFields.remove(formId);
                    pendingQuestionIds.remove(formId);
                }
                default -> log.trace("[opencode-server] 未处理事件: {}", type);
            }
        }

        /**
         * 把 1.19.x / 2.x 的事件名归一化成同一套常量。
         *
         * @param type 原始事件名
         * @return 归一化事件名
         */
        private String canonicalType(String type) {
            if (type == null || type.isEmpty()) {
                return "";
            }
            if (type.startsWith(LEGACY_EVENT_PREFIX)) {
                return EVENT_PREFIX + type.substring(LEGACY_EVENT_PREFIX.length());
            }
            if (LEGACY_EVENT_PERMISSION_ASKED.equals(type)) {
                return EVENT_PERMISSION_ASKED;
            }
            if (LEGACY_EVENT_QUESTION_ASKED.equals(type)) {
                return EVENT_QUESTION_ASKED;
            }
            return type;
        }

        /**
         * 发送文本增量。
         *
         * @param type  事件类型
         * @param delta 片段
         */
        private void emitDelta(AgentStreamEvent.Type type, String delta) {
            if (StringUtils.isEmpty(delta)) {
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
         * 处理正文/思考增量事件。
         *
         * @param data    事件数据
         * @param type    下发的事件类型
         * @param lengths 已下发长度表
         */
        private void onTextDelta(JsonNode data, AgentStreamEvent.Type type, Map<String, Integer> lengths) {
            String delta = data.path("delta").asText("");
            if (StringUtils.isEmpty(delta)) {
                return;
            }
            lengths.merge(segmentKey(data), delta.length(), Integer::sum);
            emitDelta(type, delta);
        }

        /**
         * 正文/思考分段键。
         *
         * <p>1.19.x 用 {@code textID}；2.x 改用 {@code assistantMessageID + ordinal}。
         * 两者都取不到时退回空键（整轮一段），保证补齐逻辑不会把不同段落串在一起。</p>
         *
         * @param data 事件数据
         * @return 分段键
         */
        private String segmentKey(JsonNode data) {
            String textId = data.path("textID").asText("");
            if (!textId.isBlank()) {
                return textId;
            }
            String messageId = data.path("assistantMessageID").asText("");
            if (messageId.isBlank()) {
                return "";
            }
            return messageId + "#" + data.path("ordinal").asInt(0);
        }

        /**
         * 处理正文/思考结束事件：增量缺失时用完整文本补齐，已下发部分不重复。
         *
         * <p>opencode 的 {@code text.delta} 为非持久事件，重连或非流式上游只会下发
         * {@code text.ended}，仅靠增量会整轮无正文；这里以完整文本兜底。</p>
         *
         * @param data    事件数据
         * @param type    下发的事件类型
         * @param lengths 已下发长度表
         */
        private void onTextEnded(JsonNode data, AgentStreamEvent.Type type, Map<String, Integer> lengths) {
            String key = segmentKey(data);
            String full = data.path("text").asText("");
            int emitted = lengths.getOrDefault(key, 0);
            if (full.length() > emitted) {
                emitDelta(type, full.substring(emitted));
            }
            lengths.put(key, full.length());
        }

        /**
         * 工具入参开始：2.x 只在此时携带工具名，先记下来供后续事件配对。
         *
         * @param data 事件数据
         */
        private void onToolInputStarted(JsonNode data) {
            String callId = callIdOf(data);
            String name = data.path("name").asText("");
            if (!callId.isBlank() && !name.isBlank()) {
                toolNames.put(callId, name);
            }
        }

        /**
         * 工具调用开始。
         *
         * @param data 事件数据
         */
        private void onToolCalled(JsonNode data) {
            String tool = toolNameOf(data);
            String input = truncate(text(data.path("input")));
            AgentLoopGuard guard = loopGuard;
            if (guard != null && guard.recordToolCall(tool, input)) {
                log.warn("[opencode-server] {}，已请求中断", guard.describe());
                requestInterrupt(context);
            }
            String spanId = AgentTrace.newSpanId(agentId(), "tool", toolSeq.incrementAndGet());
            long startAt = System.currentTimeMillis();
            toolSpans.put(toolKey(data, tool), new ToolSpan(startAt, spanId));
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.TOOL_START)
                    .agentId(agentId())
                    .toolName(tool)
                    .toolInput(input)
                    .timestamp(startAt)
                    .build());
            sink.next(AgentStreamEvent.trace(AgentTrace.builder()
                    .kind(AgentTrace.KIND_TOOL_CALL)
                    .agentId(agentId())
                    .spanId(spanId)
                    .toolName(tool)
                    .text("调用工具: " + tool)
                    .timestamp(startAt)
                    .build()));
        }

        /**
         * 工具调用结果。
         *
         * @param data  事件数据
         * @param error 错误信息；非空表示失败
         */
        private void onToolResult(JsonNode data, String error) {
            String toolName = toolNameOf(data);
            String output = error != null ? truncate(error) : truncate(toolContent(data));
            ToolSpan span = toolSpans.remove(toolKey(data, toolName));
            toolNames.remove(callIdOf(data));
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.TOOL_RESULT)
                    .agentId(agentId())
                    .toolName(toolName)
                    .toolOutput(output)
                    .timestamp(System.currentTimeMillis())
                    .build());
            // 结束行同时带上开始时刻：同一行即可算出耗时，落库与刷新回放都不必跨行配对。
            sink.next(AgentStreamEvent.trace(AgentTrace.spanEnd(
                    AgentTrace.KIND_TOOL_RESULT, agentId(),
                    span == null ? null : span.spanId(), toolName, output,
                    span == null ? 0L : span.startAt())));
        }

        /**
         * 解析工具调用 ID。
         *
         * <p>1.19.x 用 {@code callID}/{@code callId}，2.x 用 {@code id}。</p>
         *
         * @param data 事件数据
         * @return 调用 ID；缺失返回空串
         */
        private String callIdOf(JsonNode data) {
            String callId = data.path("callID").asText("");
            if (callId.isBlank()) {
                callId = data.path("callId").asText("");
            }
            if (callId.isBlank()) {
                callId = data.path("id").asText("");
            }
            return callId;
        }

        /**
         * 工具调用键：优先调用 ID，否则退回工具名。
         *
         * @param data     事件数据
         * @param toolName 工具名
         * @return 配对键
         */
        private String toolKey(JsonNode data, String toolName) {
            String callId = callIdOf(data);
            return callId.isBlank() ? toolName : callId;
        }

        /**
         * 解析工具名。
         *
         * <p>1.19.x 的结果事件只带 {@code callID}；2.x 的 {@code tool.called/success}
         * 连名字都没有，只有 {@code tool.input.started} 带 {@code name}，因此先查登记表，
         * 再退回事件自带的 {@code tool}/{@code name}。</p>
         *
         * @param data 事件数据
         * @return 工具名
         */
        private String toolNameOf(JsonNode data) {
            String callId = callIdOf(data);
            if (!callId.isBlank()) {
                String known = toolNames.get(callId);
                if (StringUtils.isNotBlank(known)) {
                    return known;
                }
            }
            String tool = data.path("tool").asText("");
            if (!tool.isBlank()) {
                return tool;
            }
            String name = data.path("name").asText("");
            return name.isBlank() ? "tool" : name;
        }

        /**
         * shell 命令开始（映射为工具开始）。
         *
         * @param data 事件数据
         */
        private void onShellStarted(JsonNode data) {
            JsonNode shell = data.path("shell");
            String command = truncate(shell.path("command").asText(data.path("command").asText("")));
            String spanId = AgentTrace.newSpanId(agentId(), "shell", toolSeq.incrementAndGet());
            long startAt = System.currentTimeMillis();
            toolSpans.put(shellKey(data), new ToolSpan(startAt, spanId));
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.TOOL_START)
                    .agentId(agentId())
                    .toolName("bash")
                    .toolInput(command)
                    .timestamp(startAt)
                    .build());
            sink.next(AgentStreamEvent.trace(AgentTrace.builder()
                    .kind(AgentTrace.KIND_TOOL_CALL)
                    .agentId(agentId())
                    .spanId(spanId)
                    .toolName("bash")
                    .text("调用工具: bash")
                    .timestamp(startAt)
                    .build()));
        }

        /**
         * shell 命令结束（映射为工具结果）。
         *
         * @param data 事件数据
         */
        private void onShellEnded(JsonNode data) {
            JsonNode outputNode = data.path("output");
            String output = truncate(outputNode.isObject()
                    ? outputNode.path("output").asText(outputNode.toString())
                    : outputNode.asText(""));
            ToolSpan span = toolSpans.remove(shellKey(data));
            sink.next(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.TOOL_RESULT)
                    .agentId(agentId())
                    .toolName("bash")
                    .toolOutput(output)
                    .timestamp(System.currentTimeMillis())
                    .build());
            sink.next(AgentStreamEvent.trace(AgentTrace.spanEnd(
                    AgentTrace.KIND_TOOL_RESULT, agentId(),
                    span == null ? null : span.spanId(), "bash", output,
                    span == null ? 0L : span.startAt())));
        }

        /**
         * shell 事件配对键：2.x 的终态事件只带 {@code shell.id}。
         *
         * @param data 事件数据
         * @return 配对键
         */
        private String shellKey(JsonNode data) {
            String shellId = data.path("shell").path("id").asText("");
            if (!shellId.isBlank()) {
                return shellId;
            }
            return toolKey(data, "bash");
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
         * <p>1.19.x 没有会话级终态，只能靠「finish 不是 tool-calls」判定本轮结束；
         * 2.x 有 {@code session.execution.succeeded/failed/interrupted}，终态以它为准，
         * 这里只做聚合，避免早于 usage 事件收流。</p>
         *
         * @param data 事件数据
         */
        private void onStepEnded(JsonNode data) {
            JsonNode tokens = data.path("tokens");
            long stepInput = tokens.path("input").asLong(0L);
            long stepOutput = tokens.path("output").asLong(0L);
            if (stepInput > 0L || stepOutput > 0L) {
                stepTokensSeen = true;
            }
            inputTokens += stepInput;
            outputTokens += stepOutput;
            reasoningTokens += tokens.path("reasoning").asLong(0L);
            cacheTokens += tokens.path("cache").path("read").asLong(0L);
            cost = cost.add(BigDecimal.valueOf(data.path("cost").asDouble(0D)));
            finishReason = data.path("finish").asText(finishReason);
            if (context.v2()) {
                return;
            }
            if (!FINISH_TOOL_CALLS.equals(data.path("finish").asText(""))) {
                complete();
            }
        }

        /**
         * 会话用量更新（2.x 的会话累计值）。
         *
         * <p>{@code session.step.ended} 已经给出按步 token 时以它为准；只有在完全没有
         * 步级 token（个别上游不回报）时才退回会话累计值，并减掉本轮开始前的基线。</p>
         *
         * @param data 事件数据
         */
        private void onUsageUpdated(JsonNode data) {
            usageSeen = true;
            if (stepTokensSeen) {
                return;
            }
            SessionUsage base = usageAtStart;
            if (base == null) {
                // 没有基线就分不清本轮与会话历史，宁可只记 finish 原因，也不虚报用量
                log.debug("[opencode-server] 会话 {} 缺少用量基线，跳过累计用量回填",
                        context.sessionId());
                return;
            }
            JsonNode tokens = data.path("tokens");
            inputTokens = Math.max(0L, tokens.path("input").asLong(0L) - base.inputTokens());
            outputTokens = Math.max(0L, tokens.path("output").asLong(0L) - base.outputTokens());
            reasoningTokens = Math.max(0L, tokens.path("reasoning").asLong(0L) - base.reasoningTokens());
            cacheTokens = Math.max(0L, tokens.path("cache").path("read").asLong(0L) - base.cacheTokens());
            BigDecimal total = BigDecimal.valueOf(data.path("cost").asDouble(0D));
            BigDecimal delta = total.subtract(base.cost());
            cost = delta.signum() > 0 ? delta : BigDecimal.ZERO;
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
         * 表单询问：翻译为 QUESTION_REQUEST 并暂停本轮，等待 {@link #answerQuestion} 回传答案。
         *
         * <p>2.x 把结构化提问改成了表单：一个表单可含多个字段，答案按键值回传。这里把每个
         * 可回答字段映射成一个 {@link AgentQuestion}，并把字段定义按顺序存入
         * {@link #pendingFormFields}，供回传时还原成表单要求的 JSON 类型。</p>
         *
         * @param form {@code form.created} 里的 {@code data.form}
         */
        private void onFormCreated(JsonNode form) {
            if (form == null || form.isMissingNode() || form.isNull()) {
                log.warn("[opencode-server] 收到缺少 form 的 form.created，已忽略");
                return;
            }
            String formId = form.path("id").asText("");
            if (formId.isBlank()) {
                log.warn("[opencode-server] 收到缺少 ID 的 form.created，已忽略");
                return;
            }
            String sessionID = form.path("sessionID").asText("");
            if (!sessionID.isBlank() && !context.sessionId().equals(sessionID)) {
                return;
            }
            String formTitle = form.path("title").asText("");
            List<AgentQuestion> questions = new ArrayList<>();
            List<String> questionIds = new ArrayList<>();
            List<FormField> fields = new ArrayList<>();
            JsonNode items = form.path("fields");
            int index = 0;
            if (items.isArray()) {
                for (JsonNode item : items) {
                    int ordinal = index++;
                    String key = item.path("key").asText("");
                    String type = item.path("type").asText("");
                    if (key.isBlank() || type.isBlank()) {
                        continue;
                    }
                    if (item.path("hidden").asBoolean(false)) {
                        continue;
                    }
                    if (FormField.TYPE_EXTERNAL.equals(type)) {
                        log.warn("[opencode-server] 表单 {} 的字段 {} 是 external 类型（由外部页面处理），"
                                + "本轮无法回答，已跳过", formId, key);
                        continue;
                    }
                    List<String> options = new ArrayList<>();
                    JsonNode optionNodes = item.path("options");
                    if (optionNodes.isArray()) {
                        for (JsonNode option : optionNodes) {
                            // 回传的是 option.value，界面展示 label
                            String value = option.path("value").asText("");
                            String label = option.path("label").asText("");
                            String answer = value.isBlank() ? label : value;
                            if (!answer.isBlank()) {
                                options.add(answer);
                            }
                        }
                    }
                    if (FormField.TYPE_BOOLEAN.equals(type)) {
                        options.add(FormField.VALUE_TRUE);
                        options.add(FormField.VALUE_FALSE);
                    }
                    String title = item.path("title").asText("");
                    String questionId = formId + "#" + ordinal;
                    questionIds.add(questionId);
                    fields.add(new FormField(key, type, title));
                    questions.add(AgentQuestion.builder()
                            .id(questionId)
                            .header(formTitle)
                            .question(title.isBlank() ? key : title)
                            .options(options)
                            .multiple(FormField.TYPE_MULTISELECT.equals(type))
                            .custom(item.path("custom").asBoolean(options.isEmpty()))
                            .build());
                }
            }
            if (questions.isEmpty()) {
                log.warn("[opencode-server] 表单 {} 没有可回答的字段，无法生成提问", formId);
                return;
            }
            pendingFormFields.put(formId, fields);
            pendingQuestionIds.put(formId, questionIds);
            sink.next(AgentStreamEvent.questionRequest(agentId(), formId, questions));
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
            String resources = rawResources(data.path("resources"));
            List<ConfirmationItem> items = List.of(ConfirmationItem.builder()
                    .toolCallId(requestId)
                    .toolName(action)
                    .title("需要确认：" + action)
                    .description(truncate(resources))
                    .riskLevel(ToolRiskPolicy.classify(action, resources))
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
            Map<String, Object> body = permissionReplyBody(context.profile(), PERMISSION_REJECT, message);
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
         * 权限资源摘要（截断版，用于确认卡片描述）。
         *
         * @param resources 资源数组节点
         * @return 摘要文本
         */
        private String describeResources(JsonNode resources) {
            return truncate(rawResources(resources));
        }

        /**
         * 权限资源完整摘要（不截断；风险分级需要看到完整命令行/路径）。
         *
         * @param resources 资源数组节点
         * @return 摘要文本
         */
        private String rawResources(JsonNode resources) {
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
                index++;
            }
            return sb.toString();
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
            if (context.v2() && !usageSeen && !stepTokensSeen) {
                log.warn("[opencode-server] 会话 {} 本轮没有收到任何用量事件，用量按 0 上报",
                        context.sessionId());
            }
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