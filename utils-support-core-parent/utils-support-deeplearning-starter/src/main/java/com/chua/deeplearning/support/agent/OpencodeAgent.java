package com.chua.deeplearning.support.agent;

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
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.skill.SkillHandler;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.deeplearning.support.engine.CliModelRunner;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 基于外部 {@code opencode} CLI 的 Agent 实现（SINGLE 模式）。
 *
 * <p>opencode 本身就是一个"带模型、带工具、自跑循环"的自治编码 agent，与项目内
 * {@code agentscope} / {@code langchain4j} 那种"框架拿着 ChatClient 驱动 LLM↔工具循环"
 * 方向相反。因此本实现把 opencode 视为<b>不透明的叶子执行器</b>：一次运行即拉起一次
 * {@code opencode run <input> --format json} 进程，逐行解析 NDJSON 事件流。</p>
 *
 * <h3>流式</h3>
 * <p>走 {@code --format json}，stdout 为 NDJSON（每行一个事件：{@code step_start} /
 * {@code text} / {@code step_finish} …）。{@link #runStream(String, Consumer)} 在进程存活期间
 * 逐事件回调；{@link #run(String)} 复用同一条链路、跑完再返回。事件文本增量粒度取决于上游
 * provider 是否流式，本层保证的是<b>事件级</b>流式。同时修复了 default 格式在非交互下不产出
 * /进程挂起的问题。</p>
 *
 * <h3>Agent 参数适配（全部覆写）</h3>
 * <p>本实现覆写 {@link Agent} 的全部配置方法，按 opencode CLI 的真实能力分三档处理：</p>
 * <ul>
 *   <li><b>转发为真实 CLI 参数</b>：{@code chatClient(..)} → 派生 {@code -m provider/model}；
 *       {@link #model(String)} → {@code -m}；{@link #session(String)} → {@code -s}；
 *       {@link #workDir(String)} → {@code --dir}；{@link #agentName(String)} → {@code --agent}；
 *       {@code definition(AgentDefinition)} → 把 system prompt 写进 {@code <workDir>/opencode.json}
 *       的 {@code agent.<id>.prompt} 并以 {@code --agent <id>} 注入（实测 opencode 会从 {@code --dir}
 *       加载项目 {@code opencode.json} 并生效）；{@code mcp(false)} → {@code --pure}
 *       （不加载外部插件）；{@code debug(true)} → {@code --print-logs --log-level DEBUG}。</li>
 *   <li><b>框架侧行为适配</b>：{@code maxRetries/retryBackoff/retryBaseDelay/retryConfig(..)} →
 *       在 {@code run} 外层包裹退避重试；{@code maxToolIterations(n)} → NDJSON 里对 {@code step_finish}
 *       计数的进程级看门狗，达到 n 即强杀 opencode 并返回已采集的部分结果（n≤0 不限制）；
 *       {@code debugHook(..)} → 在 {@code PRE_CALL / POST_CALL / ERROR} 阶段回调；
 *       {@code printConfig(true)} → 运行前打印生效配置；{@code subAgent(..)} → 存入列表并由
 *       {@link #getSubAgents()} 返回；{@code definition(..)} → 既供 {@link #getDefinition()} /
 *       {@link #getSystemPrompt()}，又在 {@link #workDir(String)} 已设且该目录无既有
 *       {@code opencode.json} 时注入 agent 配置；{@code compressionConfig(..)} → 存值供
 *       {@link #compressionConfig()}。</li>
 *   <li><b>不兼容（存不下语义则记录，首次运行 WARN 一次）</b>：
 *       {@code chatClient(id,..)} / {@code mcpManager(..)} / {@code skillManager(..)} /
 *       {@code skill(..)} / {@code memoryConfig(..)} / {@code plan(..)} / {@code planMaxTask(..)} /
 *       {@code planHook(..)} —— opencode 自管工具/技能/记忆，且本实现仅 SINGLE，无法委派这些
 *       框架能力（{@code skill(..)}/{@code mcpManager(..)} 若要生效须在 opencode 侧另起 MCP 回调桥，
 *       不在本层范围）。</li>
 * </ul>
 *
 * <h3>支持范围（最小契约）</h3>
 * <ul>
 *   <li>仅 {@link AgentMode#SINGLE}；其余模式抛 {@link UnsupportedOperationException}</li>
 *   <li>CLI 定位走 {@link CliModelRunner}：{@code deeplearning.cli.opencode.bin}/{@code .dir} /
 *       PATH / 自动下载 三级兜底</li>
 * </ul>
 *
 * <h3>副作用</h3>
 * <p>opencode 会在运行目录内真实编辑文件、执行命令。{@link #workDir(String)} 透传 {@code --dir}
 * 限定其工作目录；未设置时继承当前进程工作目录，调用方须自行确保可控与授权。重试会重复拉起进程，
 * 每次都是对运行目录的真实改动，请谨慎设置 {@code maxRetries}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OpencodeAgent implements Agent {

    /**
     * 默认进程超时（秒）；编码任务耗时较长，放宽到 10 分钟
    */
    private static final long DEFAULT_TIMEOUT_SECONDS = 600L;

    /**
     * CLI 描述
    */
    private final CliModelRunner.CliDescriptor cli;

    /**
     * 模型（provider/model，透传 -m）；null=opencode 默认
    */
    private String model;

    /**
     * 会话 ID（透传 -s，续话）；null=新会话
    */
    private String session;

    /**
     * 工作目录（透传 --dir）；null=继承进程 cwd
    */
    private Path workDir;

    /**
     * 进程超时（秒）
    */
    private long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    /**
     * 执行模式（仅支持 SINGLE）
    */
    private AgentMode mode = AgentMode.SINGLE;

    /**
     * opencode agent 名称（透传 --agent）；null=不指定
    */
    private String agentName;

    /**
     * 不加载外部插件（透传 --pure），由 mcp(false) 派生；null=默认不启用
    */
    private Boolean pure;

    /**
     * 是否打印 opencode 内部日志（透传 --print-logs --log-level DEBUG）
    */
    private boolean debug;

    /**
     * 是否运行前打印生效配置
    */
    private boolean printConfig;

    /**
     * 调试 Hook（PRE_CALL/POST_CALL/ERROR 回调）
    */
    private AgentDebugHook debugHook;

    /**
     * 重试配置；null=不重试
    */
    private AgentRetryConfig retryConfig;

    /**
     * 主 Agent 定义（供 getDefinition/getSystemPrompt）
    */
    private AgentDefinition definition;

    /**
     * 已注册子 Agent（SINGLE 下不委派，仅记录）
    */
    private final List<AgentDefinition> subAgents = new ArrayList<>();

    /**
     * 上下文压缩配置（仅存值供 getter，不转发）
    */
    private AgentCompressionConfig compressionConfig;

    /**
     * 无法映射到 opencode 的配置项，首次运行 WARN 一次
    */
    private final Set<String> ignored = new LinkedHashSet<>();

    /**
     * WARN 是否已发出
    */
    private final AtomicBoolean warned = new AtomicBoolean(false);

    /**
     * 工具调用最大轮数（看门狗计数 step_finish）；≤0 不限制
    */
    private int maxToolIterations;

    /**
     * 无参构造（SPI {@code create("opencode")} 入口）。
     */
    public OpencodeAgent() {
        this(CliModelRunner.opencode());
    }

    /**
     * 指定 CLI 描述构造（便于替换下载源/版本）。
     *
     * @param cli CLI 描述
     */
    public OpencodeAgent(CliModelRunner.CliDescriptor cli) {
        this.cli = cli;
    }

    /**
     * 设置模型（透传 {@code -m}）。
     *
     * @param model provider/model，null 用 opencode 默认
     * @return 当前实例
     */
    public OpencodeAgent model(String model) {
        this.model = model;
        return this;
    }

    /**
     * 设置会话 ID（透传 {@code -s}，续话）。
     *
     * @param session 会话标识，null 新会话
     * @return 当前实例
     */
    public OpencodeAgent session(String session) {
        this.session = session;
        return this;
    }

    /**
     * 设置工作目录（透传 {@code --dir}）。
     *
     * @param workDir 目录路径，null 继承进程 cwd
     * @return 当前实例
     */
    public OpencodeAgent workDir(String workDir) {
        this.workDir = (workDir == null || workDir.isBlank()) ? null : Paths.get(workDir.trim());
        return this;
    }

    /**
     * 设置进程超时（秒）。
     *
     * @param timeoutSeconds 超时秒数，非正回落默认
     * @return 当前实例
     */
    public OpencodeAgent timeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        return this;
    }

    /**
     * 设置 opencode agent 名称（透传 {@code --agent}）。
     *
     * <p>须为 opencode 配置中已注册的 agent 名，否则 CLI 会报错。</p>
     *
     * @param agentName agent 名称，null 不指定
     * @return 当前实例
     */
    public OpencodeAgent agentName(String agentName) {
        this.agentName = (agentName == null || agentName.isBlank()) ? null : agentName.trim();
        return this;
    }

    // ------------------------------------------------------------------
    // Agent 配置方法覆写：可映射 -> CLI 参数 / 框架行为；不可映射 -> 记录并 WARN
    // ------------------------------------------------------------------

    @Override
    public OpencodeAgent mode(AgentMode mode) {
        this.mode = mode != null ? mode : AgentMode.SINGLE;
        return this;
    }

    @Override
    public OpencodeAgent chatClient(ChatClient chatClient) {
        if (chatClient == null) {
            return this;
        }
        String derived = deriveModel(chatClient);
        if (derived != null) {
            if (this.model == null || this.model.isBlank()) {
                this.model = derived;
            }
        } else {
            markIgnored("chatClient(未提供 model/provider)");
        }
        return this;
    }

    @Override
    public OpencodeAgent chatClient(String agentId, ChatClient chatClient) {
        markIgnored("chatClient(agentId,..)[SINGLE 模式无子 Agent 专属模型]");
        return this;
    }

    @Override
    public OpencodeAgent mcpManager(McpManager mcpManager) {
        markIgnored("mcpManager(opencode 自管 MCP 工具)");
        return this;
    }

    @Override
    public OpencodeAgent mcpManager(String agentId, McpManager mcpManager) {
        markIgnored("mcpManager(agentId,..)[SINGLE 模式]");
        return this;
    }

    @Override
    public OpencodeAgent skillManager(SkillManager skillManager) {
        markIgnored("skillManager(opencode 自管插件/技能)");
        return this;
    }

    @Override
    public OpencodeAgent skillManager(String agentId, SkillManager skillManager) {
        markIgnored("skillManager(agentId,..)[SINGLE 模式]");
        return this;
    }

    @Override
    public OpencodeAgent subAgent(AgentDefinition subAgent) {
        if (subAgent != null) {
            this.subAgents.add(subAgent);
        }
        return this;
    }

    @Override
    public OpencodeAgent skill(String name, String description, SkillHandler handler) {
        markIgnored("skill(name,desc,handler)[opencode 不加载框架技能]");
        return this;
    }

    @Override
    public OpencodeAgent mcp(boolean mcp) {
        // opencode 无"仅关工具"开关；关闭 MCP 时以 --pure（不加载外部插件）近似
        this.pure = !mcp;
        return this;
    }

    @Override
    public OpencodeAgent maxToolIterations(int maxIterations) {
        this.maxToolIterations = maxIterations;
        return this;
    }

    @Override
    public OpencodeAgent memoryConfig(MemoryConfig memoryConfig) {
        markIgnored("memoryConfig(opencode 自管会话记忆)");
        return this;
    }

    @Override
    public OpencodeAgent maxRetries(int maxRetries) {
        ensureRetryConfig().setMaxRetries(maxRetries);
        return this;
    }

    @Override
    public OpencodeAgent retryBackoff(AgentRetryConfig.BackoffStrategy strategy) {
        if (strategy != null) {
            ensureRetryConfig().setBackoffStrategy(strategy);
        }
        return this;
    }

    @Override
    public OpencodeAgent retryBaseDelay(long baseDelayMillis) {
        ensureRetryConfig().setBaseDelayMillis(baseDelayMillis);
        return this;
    }

    @Override
    public OpencodeAgent retryConfig(AgentRetryConfig retryConfig) {
        this.retryConfig = retryConfig;
        return this;
    }

    @Override
    public OpencodeAgent compressionConfig(AgentCompressionConfig compressionConfig) {
        // 存值供 getter；opencode 自管上下文窗口，不转发
        this.compressionConfig = compressionConfig;
        markIgnored("compressionConfig(opencode 自管上下文)");
        return this;
    }

    @Override
    public AgentCompressionConfig compressionConfig() {
        return compressionConfig;
    }

    @Override
    public OpencodeAgent plan(boolean plan) {
        markIgnored("plan(opencode 无框架规划阶段)");
        return this;
    }

    @Override
    public OpencodeAgent planMaxTask(int planMaxTask) {
        markIgnored("planMaxTask(opencode 无框架规划阶段)");
        return this;
    }

    @Override
    public OpencodeAgent printConfig(boolean printConfig) {
        this.printConfig = printConfig;
        return this;
    }

    @Override
    public OpencodeAgent debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    @Override
    public OpencodeAgent debugHook(AgentDebugHook debugHook) {
        this.debugHook = debugHook;
        return this;
    }

    @Override
    public OpencodeAgent definition(AgentDefinition definition) {
        this.definition = definition;
        return this;
    }

    @Override
    public OpencodeAgent planHook(AgentPlanHook planHook) {
        markIgnored("planHook(opencode 无框架规划阶段)");
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

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    @Override
    public AgentResponse run(String input) {
        return execute(input, null);
    }

    /**
     * 流式运行：进程存活期间逐事件回调 {@code onEvent}，并返回汇总结果。
     *
     * @param input   用户输入
     * @param onEvent 事件回调（可为 null）
     * @return 执行结果（含 output/events/usage）
     * @throws UnsupportedOperationException 非 SINGLE 模式
     */
    public AgentResponse runStream(String input, Consumer<AgentEvent> onEvent) {
        return execute(input, onEvent);
    }

    /**
     * 执行入口：模式校验、惰性 WARN 不适用配置、按 {@link AgentRetryConfig} 包裹退避重试。
     *
     * @param input   输入
     * @param onEvent 事件回调（可为 null）
     * @return 汇总响应
     */
    private AgentResponse execute(String input, Consumer<AgentEvent> onEvent) {
        if (mode != AgentMode.SINGLE) {
            throw new UnsupportedOperationException(
                    "OpencodeAgent 仅支持 SINGLE 模式，当前为 " + mode
                            + "（opencode 是外部自治 agent，无法委派本框架的 subAgent/ChatClient）");
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("输入为空");
        }
        applyDefinition();
        warnIgnoredOnce();
        if (printConfig) {
            log.info("[opencode] 生效配置: {}", effectiveConfig());
        }
        int attempt = 0;
        while (true) {
            try {
                return runOnce(input, onEvent);
            } catch (RuntimeException e) {
                if (!shouldRetry(e, attempt)) {
                    fireDebug("ERROR", "执行失败: " + e.getMessage(), attempt + 1);
                    throw e;
                }
                long delay = retryConfig.calculateDelayMillis(attempt);
                log.warn("[opencode] 第 {} 次失败，{}ms 后重试: {}", attempt + 1, delay, e.getMessage());
                fireDebug("ERROR", "第 " + (attempt + 1) + " 次失败，重试中: " + e.getMessage(), attempt + 1);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                attempt++;
            }
        }
    }

    /**
     * 单次拉起 {@code opencode run --format json}，逐行解析 NDJSON。
     *
     * @param input   输入
     * @param onEvent 事件回调（可为 null）
     * @return 汇总响应
     */
    private AgentResponse runOnce(String input, Consumer<AgentEvent> onEvent) {
        StreamState st = new StreamState(onEvent);
        boolean capped = false;
        long startMs = System.currentTimeMillis();
        fireDebug("PRE_CALL", preview(input), 1);
        try {
            Path exe = CliModelRunner.locate(cli);
            String[] args = buildArgs(input);
            try {
                CliModelRunner.runStream(exe, args, timeoutSeconds, line -> {
                    // 看门狗：feedLine 在 step_finish 达上限时返回 true，抛异常令 runStream 强杀进程
                    if (feedLine(st, line)) {
                        throw new IterationLimit();
                    }
                });
            } catch (IterationLimit limit) {
                capped = true;
                log.info("[opencode] 达到 maxToolIterations={}，提前终止进程（已采集 {} 事件）",
                        maxToolIterations, st.events.size());
            }
            long duration = System.currentTimeMillis() - startMs;
            AgentResponse resp = AgentResponse.builder()
                    .output(st.output.toString())
                    .mode(mode.name())
                    .events(st.events)
                    .usage(st.acc.toUsage(startMs, duration))
                    .build();
            resp.getMetadata().put("cli", cli.cliId());
            if (model != null) {
                resp.getMetadata().put("model", model);
            }
            if (session != null) {
                resp.getMetadata().put("session", session);
            }
            if (agentName != null) {
                resp.getMetadata().put("agent", agentName);
            }
            if (capped) {
                resp.getMetadata().put("cappedByMaxToolIterations", Boolean.TRUE);
            }
            fireDebug("POST_CALL", preview(st.output.toString()), 1);
            return resp;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("opencode 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式运行的可变累加状态（进程存活期间逐行更新）。
     *
     * <p>独立成类以便单测在不拉起真实进程的情况下驱动逐行解析与看门狗逻辑。</p>
     */
    static final class StreamState {
        /**
         * 文本输出累加
        */
        final StringBuilder output = new StringBuilder();
        /**
         * 已采集事件列表
        */
        final List<AgentEvent> events = new ArrayList<>();
        /**
         * token/费用累加器
        */
        final UsageAccum acc = new UsageAccum();
        /**
         * 逐事件回调，可为 null
        */
        final Consumer<AgentEvent> onEvent;
        /**
         * step_finish 计数（看门狗用）
        */
        int stepCount;

        StreamState(Consumer<AgentEvent> onEvent) {
            this.onEvent = onEvent;
        }
    }

    /**
     * 处理一行 NDJSON：解析为事件、回调 {@code onEvent}、累计文本与用量。
     *
     * @param st   流式累加状态
     * @param line 原始行（可为 null 或非 JSON）
     * @return 当 {@code maxToolIterations>0} 且本次为触发上限的 {@code step_finish} 时返回 true（调用方据此强杀进程）
     */
    boolean feedLine(StreamState st, String line) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty() || t.charAt(0) != '{') {
            return false;
        }
        AgentEvent ev = parseEvent(t, st.output, st.acc);
        if (ev == null) {
            return false;
        }
        st.events.add(ev);
        if (st.onEvent != null) {
            st.onEvent.accept(ev);
        }
        return maxToolIterations > 0 && "step_finish".equals(ev.type()) && ++st.stepCount >= maxToolIterations;
    }

    /**
     * 解析单行 NDJSON 事件：累计文本输出与用量，并构建 {@link AgentEvent}。
     *
     * @param line   一行 JSON
     * @param output 文本输出累加器
     * @param acc    用量累加器
     * @return 事件；解析失败返回 null（跳过该行进事件列表）
     */
    private AgentEvent parseEvent(String line, StringBuilder output, UsageAccum acc) {
        try {
            JsonNode n = Json.parse(line);
            String type = n.get("type").toStringValue("");
            String sid = n.get("sessionID").toStringValue(null);
            long ts = n.get("timestamp").toLongValue(System.currentTimeMillis());
            JsonNode part = n.get("part");
            if ("text".equals(type)) {
                String txt = part.get("text").toStringValue("");
                output.append(txt);
            } else if ("step_finish".equals(type)) {
                acc.addStepFinish(part);
            }
            return AgentEvent.builder()
                    .type(type)
                    .message(summarize(type, part))
                    .agentId(sid)
                    .timestamp(ts)
                    .build();
        } catch (RuntimeException e) {
            log.debug("[opencode] 跳过非事件行: {}", line);
            return null;
        }
    }

    /**
     * 事件摘要（用于 {@link AgentEvent#message()}）。
     *
     * @param type 事件类型
     * @param part part 节点
     * @return 摘要文本
     */
    private static String summarize(String type, JsonNode part) {
        return switch (type) {
            case "text" -> part.get("text").toStringValue("");
            case "step_finish" -> "reason=" + part.get("reason").toStringValue("");
            case "tool" -> part.get("tool").toStringValue("tool");
            default -> type;
        };
    }

    /**
     * 组装 {@code opencode run --format json} 参数。
     *
     * @param input 用户输入
     * @return 参数数组
     */
    private String[] buildArgs(String input) {
        List<String> a = new ArrayList<>();
        a.add("run");
        a.add(input);
        a.add("--format");
        a.add("json");
        if (model != null && !model.isBlank()) {
            a.add("-m");
            a.add(model);
        }
        if (session != null && !session.isBlank()) {
            a.add("-s");
            a.add(session);
        }
        if (workDir != null) {
            a.add("--dir");
            a.add(workDir.toAbsolutePath().toString());
        }
        if (agentName != null && !agentName.isBlank()) {
            a.add("--agent");
            a.add(agentName);
        }
        if (Boolean.TRUE.equals(pure)) {
            a.add("--pure");
        }
        if (debug) {
            a.add("--print-logs");
            a.add("--log-level");
            a.add("DEBUG");
        }
        return a.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // 辅助：模型派生 / 重试 / debugHook / WARN
    // ------------------------------------------------------------------

    /**
     * 从 ChatClient 派生 opencode 需要的 {@code provider/model} 串。
     *
     * @param cc 客户端
     * @return {@code provider/model} 或 model，无法确定返回 null
     */
    private static String deriveModel(ChatClient cc) {
        ChatClientSetting s = cc.getSetting();
        String m = cc.getModel();
        if (m == null || m.isBlank()) {
            m = s != null ? s.getModel() : null;
        }
        if (m == null || m.isBlank()) {
            return null;
        }
        m = m.trim();
        if (m.contains("/")) {
            return m;
        }
        String provider = s != null ? s.getProvider() : null;
        return (provider != null && !provider.isBlank()) ? provider.trim() + "/" + m : m;
    }

    /**
     * 懒创建重试配置（便捷 setter 入口）。
     *
     * @return 重试配置
     */
    private AgentRetryConfig ensureRetryConfig() {
        if (retryConfig == null) {
            retryConfig = AgentRetryConfig.builder().build();
        }
        return retryConfig;
    }

    /**
     * 判断第 attempt（从 0 计）次失败后是否应重试。
     *
     * @param e       异常
     * @param attempt 已重试次数（0=尚未重试过）
     * @return 是否重试
     */
    private boolean shouldRetry(Throwable e, int attempt) {
        if (retryConfig == null || retryConfig.getMaxRetries() == 0) {
            return false;
        }
        int max = retryConfig.getMaxRetries();
        if (max > 0 && attempt >= max) {
            return false;
        }
        AgentRetryConfig.RetryPredicate p = retryConfig.getRetryPredicate();
        return p == null || p.shouldRetry(e);
    }

    /**
     * 触发调试 Hook（若已配置）。
     *
     * @param type      事件类型
     * @param message   摘要
     * @param iteration 执行轮次（从 1 起）
     */
    private void fireDebug(String type, String message, int iteration) {
        if (debugHook == null) {
            return;
        }
        try {
            debugHook.onDebug(AgentHookEvent.of(type, agentId(), message, iteration));
        } catch (RuntimeException e) {
            log.debug("[opencode] debugHook 回调异常已忽略: {}", e.getMessage());
        }
    }

    /**
     * 调试事件用的 Agent 标识。
     *
     * @return definition id，否则 cli id
     */
    private String agentId() {
        if (definition != null && definition.getId() != null && !definition.getId().isBlank()) {
            return definition.getId();
        }
        return cli.cliId();
    }

    /**
     * 记录无法映射到 opencode 的配置项。
     *
     * @param reason 说明
     */
    private void markIgnored(String reason) {
        ignored.add(reason);
    }

    /**
     * 首次运行把不适用配置 WARN 一次，避免刷屏。
     */
    private void warnIgnoredOnce() {
        if (!ignored.isEmpty() && warned.compareAndSet(false, true)) {
            log.warn("[opencode] 以下框架配置对 opencode 不生效，已忽略: {}", ignored);
        }
    }

    /**
     * 生效配置摘要（printConfig 用）。
     *
     * @return 一行摘要
     */
    private String effectiveConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("cli=").append(cli.cliId());
        sb.append(", mode=").append(mode);
        sb.append(", model=").append(model != null ? model : "<opencode-default>");
        sb.append(", session=").append(session != null ? session : "<new>");
        sb.append(", workDir=").append(workDir != null ? workDir : "<cwd>");
        if (agentName != null) {
            sb.append(", agent=").append(agentName);
        }
        if (Boolean.TRUE.equals(pure)) {
            sb.append(", pure");
        }
        sb.append(", timeoutSec=").append(timeoutSeconds);
        if (retryConfig != null && retryConfig.getMaxRetries() != 0) {
            sb.append(", maxRetries=").append(retryConfig.getMaxRetries());
        }
        return sb.toString();
    }

    /**
     * 预览截断（debugHook 消息用）。
     *
     * @param s 原文
     * @return 截断后
     */
    private static String preview(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /**
     * 将 {@link #definition(AgentDefinition)} 的 system prompt 转成 opencode agent 配置：
     * 在 {@link #workDir(String)} 目录下写 {@code opencode.json} 的 {@code agent.<id>.prompt}，
     * 并置 {@link #agentName} 使 {@code --agent} 注入。
     *
     * <p>仅当以下条件全满足才注入：已设 definition、未显式指定 {@code agentName}、prompt 非空、
     * 已设 {@code workDir}、且该目录尚无 {@code opencode.json}（避免覆盖用户真实项目配置）。
     * 任一不满足则记录 {@link #markIgnored} 原因。实测 opencode 会从 {@code --dir} 加载项目
     * {@code opencode.json} 并让 {@code --agent <id>} 生效。</p>
     */
    private void applyDefinition() {
        if (definition == null || agentName != null) {
            return;
        }
        String prompt = definition.getSystemPrompt() != null
                ? definition.getSystemPrompt() : definition.getInstruction();
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        if (workDir == null) {
            markIgnored("definition(系统提示词)[未设 workDir，无处承载 opencode.json]");
            return;
        }
        Path cfg = workDir.resolve("opencode.json");
        if (Files.exists(cfg)) {
            markIgnored("definition(系统提示词)[" + cfg + " 已存在，拒绝覆盖]");
            return;
        }
        String id = (definition.getId() != null && !definition.getId().isBlank())
                ? definition.getId().trim() : "framework-agent";
        try {
            Files.createDirectories(workDir);
            Files.write(cfg, buildAgentConfigJson(id, definition.getDescription(), prompt)
                    .getBytes(StandardCharsets.UTF_8));
            this.agentName = id;
            log.debug("[opencode] definition → {} (agent={})", cfg, id);
        } catch (IOException e) {
            markIgnored("definition(写 opencode.json 失败: " + e.getMessage() + ")");
        }
    }

    /**
     * 生成最小 opencode agent 配置 JSON（{@code agent.<id>} 含 mode=primary 与 prompt）。
     *
     * @param id          agent 标识
     * @param description 描述，可空
     * @param prompt      system prompt
     * @return JSON 文本
     */
    private static String buildAgentConfigJson(String id, String description, String prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n")
                .append("  \"$schema\": \"https://opencode.ai/config.json\",\n")
                .append("  \"agent\": {\n")
                .append("    \"").append(escapeJson(id)).append("\": {\n")
                .append("      \"mode\": \"primary\"");
        if (description != null && !description.isBlank()) {
            sb.append(",\n      \"description\": \"").append(escapeJson(description)).append('"');
        }
        sb.append(",\n      \"prompt\": \"").append(escapeJson(prompt)).append("\"\n")
                .append("    }\n")
                .append("  }\n")
                .append("}\n");
        return sb.toString();
    }

    /**
     * JSON 字符串转义（引号、反斜杠、控制字符）。
     *
     * @param s 原文
     * @return 转义后（不含首尾引号）
     */
    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * maxToolIterations 看门狗信号异常：非错误，仅用于令 {@link CliModelRunner#runStream}
     * 强杀进程后由 {@link #runOnce} 正常返回已采集的部分结果。
     */
    private static final class IterationLimit extends RuntimeException {
        /**
         * 序列化版本号
        */
        private static final long serialVersionUID = 1L;

        IterationLimit() {
            super("maxToolIterations reached", null, false, false);
        }
    }

    /**
     * 多次 step_finish 的 token/费用累加器。
     */
    static final class UsageAccum {
        /**
         * 输入 token 累计
        */
        private int input;
        /**
         * 输出 token 累计
        */
        private int output;
        /**
         * 总 token 累计
        */
        private int total;
        /**
         * 推理 token 累计
        */
        private int reasoning;
        /**
         * 缓存读取 token 累计
        */
        private int cacheRead;
        /**
         * 费用累计
        */
        private BigDecimal cost = BigDecimal.ZERO;
        /**
         * 最后一次停止原因
        */
        private String reason;

        /**
         * 累加一次 step_finish 的用量。
         *
         * @param part step_finish 的 part 节点
         */
        void addStepFinish(JsonNode part) {
            JsonNode tk = part.get("tokens");
            input += tk.get("input").toIntValue(0);
            output += tk.get("output").toIntValue(0);
            total += tk.get("total").toIntValue(0);
            reasoning += tk.get("reasoning").toIntValue(0);
            cacheRead += tk.get("cache").get("read").toIntValue(0);
            cost = cost.add(safeDecimal(part.get("cost")));
            reason = part.get("reason").toStringValue(reason);
        }

        /**
         * 转为 {@link AiUsage}；无 token 数据时返回 null。
         *
         * @param startMs   起始时间戳
         * @param duration  端到端耗时（毫秒）
         * @return 用量，或 null
         */
        AiUsage toUsage(long startMs, long duration) {
            if (total == 0 && input == 0 && output == 0) {
                return null;
            }
            AiUsage.AiUsageBuilder b = AiUsage.builder()
                    .inputTokens(input)
                    .outputTokens(output)
                    .totalTokens(total)
                    .reasoningTokens(reasoning)
                    .cacheTokens(cacheRead)
                    .startTime(startMs)
                    .durationMillis(duration)
                    .finishReason(reason);
            if (cost != null && cost.signum() != 0) {
                b.totalCost(cost);
            }
            return b.build();
        }

        /**
         * 安全读取 BigDecimal（缺失/非法返回 ZERO）。
         *
         * @param node 节点
         * @return 数值
         */
        private static BigDecimal safeDecimal(JsonNode node) {
            if (node == null || node.isMissingValue() || node.isNull()) {
                return BigDecimal.ZERO;
            }
            try {
                BigDecimal v = node.toBigDecimal();
                return v != null ? v : BigDecimal.ZERO;
            } catch (RuntimeException e) {
                return BigDecimal.ZERO;
            }
        }
    }
}
