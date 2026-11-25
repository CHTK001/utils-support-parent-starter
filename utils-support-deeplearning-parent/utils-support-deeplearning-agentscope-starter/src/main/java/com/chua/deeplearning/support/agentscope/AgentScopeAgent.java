// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。 // [P3C CO-循环 豁免]
package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentBudget;
import com.chua.common.support.ai.agent.AgentBudgetTracker;
import com.chua.common.support.ai.agent.AgentCompressionConfig;
import com.chua.common.support.ai.agent.AgentCancellationToken;
import com.chua.common.support.ai.agent.AgentContentGuard;
import com.chua.common.support.ai.agent.AgentContentKind;
import com.chua.common.support.ai.agent.AgentContentVerdict;
import com.chua.common.support.ai.agent.AgentDebugHook;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentEvent;
import com.chua.common.support.ai.agent.AgentEvalContext;
import com.chua.common.support.ai.agent.AgentEvalHook;
import com.chua.common.support.ai.agent.AgentEvalRecord;
import com.chua.common.support.ai.agent.AgentHookEvent;
import com.chua.common.support.ai.agent.AgentLoopGuard;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentOrchestrator;
import com.chua.common.support.ai.agent.AgentPlan;
import com.chua.common.support.ai.agent.AgentPlanConfirmation;
import com.chua.common.support.ai.agent.AgentPlanHook;
import com.chua.common.support.ai.agent.AgentQuestion;
import com.chua.common.support.ai.agent.AgentQuestionAnswer;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.agent.AgentRetryConfig;
import com.chua.common.support.ai.agent.AgentRiskAdjudicator;
import com.chua.common.support.ai.agent.AgentStreamEvent;
import com.chua.common.support.ai.agent.AgentSuspendState;
import com.chua.common.support.ai.agent.AgentSuspendStore;
import com.chua.common.support.ai.agent.AgentSystemPromptBuilder;
import com.chua.common.support.ai.agent.AgentTool;
import com.chua.common.support.ai.agent.AgentToolSafetyPolicy;
import com.chua.common.support.ai.agent.AgentTrace;
import com.chua.common.support.ai.agent.ConfirmationDecision;
import com.chua.common.support.ai.agent.ConfirmationItem;
import com.chua.common.support.ai.agent.DecisionsAgentRiskAdjudicator;
import com.chua.common.support.ai.agent.ImageDefinition;
import com.chua.common.support.ai.agent.SimpleAgentBudgetTracker;
import com.chua.common.support.ai.agent.SimpleAgentOrchestrator;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.DecisionsClient;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.memory.MemoryEntry;
import com.chua.common.support.ai.memory.MemoryManager;
import com.chua.common.support.ai.agent.goal.GoalLoop;
import com.chua.common.support.ai.agent.goal.GoalStatus;
import com.chua.common.support.ai.agent.goal.GoalTracker;
import com.chua.common.support.ai.agent.goal.SessionGoal;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.utils.StringUtils;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
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
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;
import java.util.Iterator;
import lombok.extern.slf4j.Slf4j;

/**
 * Agentscope 实现：将项目通用 {@link ChatClient} 桥接到 Agentscope Harness，
 * 支持多 Agent 编排、思考上限配置与链式调用。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AgentScopeAgent implements Agent {

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
     * 会话护栏：死循环判定 + 中断信号
    */
    private volatile AgentLoopGuard loopGuard = new AgentLoopGuard();
    /**
     * 正在运行的 Harness（中断时关闭它，令阻塞中的调用尽快结束）
    */
    private volatile HarnessAgent runningHarness;
    /**
     * 聊天客户端映射
    */
    private final Map<String, ChatClient> chatClients = Wraps.map();
    /**
     * 会话目标状态机；为 null 表示该 agent 未绑定目标。
     */
    private GoalTracker goalTracker;
    /**
     * 目标循环的轮次硬上限（有界，防止无限推进）。
     */
    private int goalMaxRounds = 20;
    /**
     * 单次暂停等待上限（毫秒）；小于等于 0 表示一直等到恢复或被取消。
     */
    private long goalPauseTimeoutMillis = 0L;
    /**
     * 子代理定义列表
    */
    private final List<AgentDefinition> subAgentDefinitions = Wraps.list();
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
     * 记忆管理器（懒创建，memoryConfig 为空时返回 null）
     */
    private volatile MemoryManager memoryManager;
    /**
     * 记忆检索条数上限：注入系统提示词的长期记忆条目数
     */
    private static final int MEMORY_SEARCH_LIMIT = 5;
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
     * 决策客户端：会话配置的决策模型（System one / decisions 协议），
     * 由门面按会话配置挂载，供决策类工具按选择调用；未配置时为 null
     */
    private DecisionsClient decisionsClient;

    /**
     * 是否允许 shell 工具：默认 true 沿用底层默认（开启）。
     *
     * <p>置 false 会在装配阶段调用 builder.disableShellTool()。用于「不准出工作间」的账号——
     * shell 是一条命令字符串，路径白名单拦不住 {@code cat /etc/passwd} 这类绝对路径读取，
     * 只能整条通道关掉。</p>
     */
    private boolean shellToolEnabled = true;
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
     * 单条工具结果的截断阈值（字符数），超过即只保留预览。
     *
     * <p>默认 8000/1500：超大目录/文件清单会撑爆下一轮模型调用 prompt 并导致卡死。</p>
     */
    private int toolResultMaxChars = 8000;

    /**
     * 截断后保留的预览字符数
     */
    private int toolResultHeadChars = 1500;

    /**
     * 是否允许恢复被暂停的待执行工具（人工确认 plan_exit 后的恢复轮）。
     */
    private boolean pendingToolRecoveryEnabled = true;

    /**
     * 注册的技能列表（名称 → 处理器）
    */
    private final Map<String, com.chua.common.support.ai.skill.SkillHandler> skills = Wraps.linkedMap();
    private final Map<String, String> skillDescriptions = Wraps.linkedMap(); // skilldescriptions

    /**
     * 通过 {@link #tool(AgentTool)} 注册的进程内工具（名称 → 定义）
     */
    private final Map<String, AgentTool> localTools = Wraps.linkedMap();

    /**
     * 按名称关闭的工具，注册进 Toolkit 前统一裁剪
     */
    private final List<String> disabledTools = Wraps.list();

    /**
     * 每次 运行 注册的 模型id 追踪，用于清理
     */
    private final List<String> registeredModelIds = Wraps.list();

    /**
     * 暂停状态存储；null 时回落到进程级默认存储（带 TTL 与条目上限）
     */
    private volatile AgentSuspendStore suspendStore;

    /**
     * 本次运行的资源预算；null 表示不设限
     */
    private volatile AgentBudget budget;

    /**
     * 外部取消令牌；null 视为永不取消
     */
    private volatile AgentCancellationToken cancellationToken;

    /**
     * 工具并行安全策略；null 时回落到只读默认策略
     */
    private volatile AgentToolSafetyPolicy toolSafetyPolicy;

    /**
     * 工具调用风险裁决器；null 时若有决策客户端则兜底为决策模型裁决
     */
    private volatile AgentRiskAdjudicator riskAdjudicator;

    /**
     * 内容护栏；null 时回落默认启发式护栏（{@link #contentGuard(AgentContentGuard)} 传 noop 显式关闭）
     */
    private volatile AgentContentGuard contentGuard;

    /**
     * 计划确认器；null 表示不送审直接执行
     */
    private volatile AgentPlanConfirmation planConfirmation;

    /**
     * 评测 / 回放钩子；null 表示不做运行边界埋点
     */
    private volatile AgentEvalHook evalHook;

    /**
     * 多 Agent 编排器；null 时回落到默认编排器
     */
    private volatile AgentOrchestrator orchestrator;

    // ==================== Agent 接口覆写 ====================

    // ---------- 生产化扩展：挂起存储 / 预算 / 取消 / 并行安全 / 风险裁决 / 计划确认 / 评测 / 编排 ----------

    /**
     * 设置暂停状态存储。
     *
     * <p>人工确认 / 结构化提问挂起时，把待恢复上下文写入该存储并携带 TTL；
     * 恢复轮按 replyId 取回。未设置时 {@link #suspendStore()} 回落进程级默认存储。</p>
     */
    @Override
    public Agent suspendStore(AgentSuspendStore suspendStore) {
        this.suspendStore = suspendStore;
        return this;
    }

    /**
     * 获取暂停状态存储；未设置时回落进程级默认存储。
     */
    @Override
    public AgentSuspendStore suspendStore() {
        return this.suspendStore != null ? this.suspendStore : AgentSuspendStore.globalDefault();
    }

    /**
     * 设置本次运行的资源预算；null 表示不设限。
     *
     * <p>预算在运行期由 {@link SimpleAgentBudgetTracker} 记账，任一维度超限即熔断收尾。</p>
     */
    @Override
    public Agent budget(AgentBudget budget) {
        this.budget = budget;
        return this;
    }

    /**
     * 获取本次运行的资源预算；未设置时返回 null（不熔断）。
     */
    @Override
    public AgentBudget budget() {
        return this.budget;
    }

    /**
     * 设置外部取消令牌；null 视为永不取消。
     *
     * <p>在每轮模型调用前与流式事件边界检查，被取消后按取消语义收尾（不按异常告警）。</p>
     */
    @Override
    public Agent cancellationToken(AgentCancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
        return this;
    }

    /**
     * 获取取消令牌；未设置时返回永不取消的空令牌。
     */
    @Override
    public AgentCancellationToken cancellationToken() {
        return this.cancellationToken != null ? this.cancellationToken : AgentCancellationToken.none();
    }

    /**
     * 设置工具并行安全策略；null 表示移除（退回只读默认策略）。
     */
    @Override
    public Agent toolSafetyPolicy(AgentToolSafetyPolicy toolSafetyPolicy) {
        this.toolSafetyPolicy = toolSafetyPolicy;
        return this;
    }

    /**
     * 获取工具并行安全策略；未设置时回落「仅低风险只读可并行」默认策略。
     */
    @Override
    public AgentToolSafetyPolicy toolSafetyPolicy() {
        return this.toolSafetyPolicy != null
                ? this.toolSafetyPolicy : AgentToolSafetyPolicy.readOnlyDefault();
    }

    /**
     * 设置工具调用风险裁决器；null 表示不启用主动裁决。
     *
     * <p>工具执行前裁决放行 / 拒绝 / 转人工；未显式设置时，
     * 若配置了决策客户端则兜底为 {@link DecisionsAgentRiskAdjudicator}。</p>
     */
    @Override
    public Agent riskAdjudicator(AgentRiskAdjudicator riskAdjudicator) {
        this.riskAdjudicator = riskAdjudicator;
        return this;
    }

    /**
     * 获取风险裁决器；未显式设置时若有决策客户端则兜底为决策模型裁决，否则返回 null。
     */
    @Override
    public AgentRiskAdjudicator riskAdjudicator() {
        if (this.riskAdjudicator != null) {
            return this.riskAdjudicator;
        }
        return this.decisionsClient != null ? new DecisionsAgentRiskAdjudicator(this.decisionsClient) : null;
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
            log.warn("[AgentScope] 模型输出被内容护栏拦截: agentId={}, findings={}",
                    agentId(), describeFindings(verdict));
            return AgentContentGuardMiddleware.BLOCKED_TEXT;
        }
        return verdict.content() != null ? verdict.content() : output;
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
     * 设置计划确认器；null 表示不启用（计划直接执行）。
     */
    @Override
    public Agent planConfirmation(AgentPlanConfirmation planConfirmation) {
        this.planConfirmation = planConfirmation;
        return this;
    }

    /**
     * 获取计划确认器；未设置时返回 null（不送审）。
     */
    @Override
    public AgentPlanConfirmation planConfirmation() {
        return this.planConfirmation;
    }

    /**
     * 设置评测 / 回放钩子；null 表示不启用。
     *
     * <p>在 {@link #run(String)} 与 {@link #runStream(String)} 边界埋点；
     * 钩子异常只记日志，不影响主流程。</p>
     */
    @Override
    public Agent evalHook(AgentEvalHook evalHook) {
        this.evalHook = evalHook;
        return this;
    }

    /**
     * 获取评测 / 回放钩子；未设置时返回 null。
     */
    @Override
    public AgentEvalHook evalHook() {
        return this.evalHook;
    }

    /**
     * 设置多 Agent 编排器；null 表示移除（退回默认编排器）。
     */
    @Override
    public Agent orchestrator(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
        return this;
    }

    /**
     * 获取多 Agent 编排器；未设置时回落默认编排器。
     *
     * <p>{@link #orchestrate(AgentOrchestrationRequest)} 与
     * {@link #orchestrateStream(AgentOrchestrationRequest)} 保持接口默认实现，
     * 经本 getter 取编排器执行。</p>
     */
    @Override
    public AgentOrchestrator orchestrator() {
        return this.orchestrator != null ? this.orchestrator : SimpleAgentOrchestrator.INSTANCE;
    }

    // ---------- 基础配置覆写 ----------

    /**
     * Mode
    */
    @Override
    public Agent mode(AgentMode mode) {
        this.mode = mode != null ? mode : AgentMode.AUTO;
        return this;
    }

    /**
     * 对话客户端
    */
    @Override
    public Agent chatClient(ChatClient chatClient) {
        this.chatClients.put(null, chatClient);
        return this;
    }

    /**
     * 对话客户端
    */
    @Override
    public Agent chatClient(String agentId, ChatClient chatClient) {
        this.chatClients.put(agentId, chatClient);
        return this;
    }

    /**
     * mcp管理器
    */
    @Override
    public Agent mcpManager(McpManager mcpManager) {
        this.globalMcpManager = mcpManager;
        return this;
    }

    /**
     * mcp管理器
    */
    @Override
    public Agent mcpManager(String agentId, McpManager mcpManager) {
        if (this.globalMcpManager == null) {
            this.globalMcpManager = mcpManager;
        }
        return this;
    }

    /**
     * skill管理器
    */
    @Override
    public Agent skillManager(SkillManager skillManager) {
        this.globalSkillManager = skillManager;
        return this;
    }

    /**
     * skill管理器
    */
    @Override
    public Agent skillManager(String agentId, SkillManager skillManager) {
        if (this.globalSkillManager == null) {
            this.globalSkillManager = skillManager;
        }
        return this;
    }

    /**
     * subAgent
    */
    @Override
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
            if (description != null) {
                this.skillDescriptions.put(name, description);
            }
        }
        return this;
    }

    /**
     * 注册进程内工具（function calling），注册进 Agentscope Toolkit。
     *
     * @param tool 工具定义
     * @return 当前 Agent 实例
     */
    @Override
    public Agent tool(AgentTool tool) {
        if (tool != null) {
            this.localTools.put(tool.getName(), tool);
        }
        return this;
    }

    /**
     * 已注册的进程内工具
     *
     * @return 工具定义列表
     */
    @Override
    public List<AgentTool> tools() {
        return new java.util.ArrayList<>(localTools.values());
    }

    /**
     * 设置单条工具结果的截断阈值。
     *
     * <p>超大目录清单/文件内容会撑爆下一轮 prompt，这里把阈值交给底层
     * {@code ToolResultEvictionConfig}，超出即只保留前 {@code headChars} 个字符。</p>
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
     * 是否允许恢复被暂停的待执行工具。
     *
     * <p>关闭后 {@link #resumeStream} 直接返回错误事件并逐出缓存块，
     * 要求调用方重新发起运行。</p>
     *
     * @param enabled 是否启用
     * @return 当前 Agent 实例
     */
    @Override
    public Agent pendingToolRecovery(boolean enabled) {
        this.pendingToolRecoveryEnabled = enabled;
        return this;
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
            this.disabledTools.add(toolName.trim());
        }
        return this;
    }

    /**
     * Mcp
    */
    @Override
    public Agent mcp(boolean mcp) {
        this.mcpEnabled = mcp;
        return this;
    }

    /**
     * 最大值tooliterations
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
     * 要求中断本轮执行。
     *
     * <p>Harness 的 ReAct 循环在框架内部，外部没有「停一步」的接口，
     * 能用的中断手段就是<b>关掉当前 Harness</b>：置位中断信号 + {@code close()} 正在运行的
     * Harness，令阻塞中的 {@code call()/streamEvents()} 以异常或结束收场，
     * 上层的 AgentResponse/Flux 随即收尾（中断不是错误，{@code run} 会带着已得的输出返回）。</p>
     *
     * <p>与 opencode 实现的差别：那边能强杀子进程，这边只能关闭内部资源。
     * 因此本实现的中断是「尽力而为」——若 Harness 正卡在 {@code close()} 也解不开的
     * 底层 IO 上，仍需外部超时兜底。</p>
     *
     * @return 当前 Agent 实例
     */
    @Override
    public Agent interrupt() {
        loopGuard.interrupt();
        HarnessAgent harness = runningHarness;
        if (harness != null) {
            try {
                harness.close();
            } catch (Exception e) {
                log.debug("[AgentScope] 中断时关闭 Harness 异常: {}", e.getMessage(), e);
            }
        }
        return this;
    }

    /**
     * 内存配置
    */
    @Override
    public Agent memoryConfig(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
        return this;
    }

    /**
     * 获取记忆管理器
     *
     * <p>与 langchain4j 实现同口径：{@code memoryConfig} 未显式设置时回落到
     * {@link AgentDefinition#getMemoryConfig()}；definition 声明了 {@code memory=true}
     * 但未给配置时使用默认配置。都没有则返回 {@code null}（未启用长期记忆）。</p>
     *
     * @return 记忆管理器；未启用时返回 null
     */
    @Override
    public MemoryManager getMemoryManager() {
        return resolveMemoryManager();
    }

    /**
     * 解析记忆管理器（懒创建）
     *
     * @return 记忆管理器；未启用时返回 null
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
     * 解析记忆配置，并把存储目录落到 workspace 下
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
        if (StringUtils.isNotBlank(workspacePath)
                && (StringUtils.isBlank(workspace) || ".agent/memory".equals(workspace))) {
            return MemoryConfig.builder()
                    .storeType(config.getStoreType())
                    .engine(config.getEngine())
                    .workspace(workspacePath + "/.agent/memory")
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
            log.warn("[AgentScope] 记忆检索失败，跳过注入: {}", e.getMessage());
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
        if (manager == null || StringUtils.isBlank(output) || input == null) {
            return;
        }
        try {
            manager.saveFromConversation("用户: " + input + "\n助手: " + output,
                    sessionKey(), agentId());
        } catch (Exception e) {
            log.warn("[AgentScope] 记忆保存失败: {}", e.getMessage());
        }
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
                log.debug("[AgentScope] 关闭记忆管理器失败: {}", e.getMessage());
            }
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
     * compression配置
    */
    @Override
    public Agent compressionConfig(AgentCompressionConfig compressionConfig) {
        this.compressionConfig = compressionConfig;
        return this;
    }

    /**
     * compression配置
    */
    @Override
    public AgentCompressionConfig compressionConfig() {
        return compressionConfig;
    }

    /**
     * Plan
    */
    @Override
    public Agent plan(boolean plan) {
        this.planEnabled = plan;
        return this;
    }

    /**
     * 首轮即进入 Plan 只读模式
    */
    @Override
    public Agent startInPlanMode(boolean active) {
        this.planStartActive = active;
        // 预进入 Plan 依赖 Plan 工具与中间件，置 true 时一并确保规划能力已注册
        if (active) {
            this.planEnabled = true;
        }
        return this;
    }

    /**
     * 默认会话标识
     */
    @Override
    public Agent sessionId(String sessionId) {
        this.defaultSessionId = sessionId;
        return this;
    }

    /**
     * 工作目录
     */
    @Override
    public Agent workspace(String workspace) {
        this.workspacePath = workspace;
        return this;
    }

    /**
     * 决策客户端（会话配置的决策模型）
     */
    @Override
    public Agent decisionsClient(DecisionsClient decisionsClient) {
        this.decisionsClient = decisionsClient;
        return this;
    }

    /**
     * 取决策客户端；未配置决策模型时为 {@code null}
     */
    public DecisionsClient decisionsClient() {
        return decisionsClient;
    }

    /**
     * 是否允许 shell 工具：false 时装配阶段调用 disableShellTool()
     */
    @Override
    public Agent shellTool(boolean enabled) {
        this.shellToolEnabled = enabled;
        return this;
    }

    /**
     * Plan最大值任务
    */
    @Override
    public Agent planMaxTask(int planMaxTask) {
        this.planMaxTask = planMaxTask;
        return this;
    }

    /**
     * 调试Hook
    */
    @Override
    public Agent debugHook(AgentDebugHook debugHook) {
        this.debugHook = debugHook;
        return this;
    }

    /**
     * print配置
    */
    @Override
    public Agent printConfig(boolean printConfig) {
                this.printConfig = printConfig;
        if (printConfig) {
            log.info("[Agent] printConfig已弃用，请使用debug(true)启用日志调试");
        }
        return this;
    }

    /**
     * 调试
    */
    @Override
    public Agent debug(boolean debug) {
        this.debugLogging = debug;
        return this;
    }

    /**
     * planhook
    */
    @Override
    public Agent planHook(AgentPlanHook planHook) {
        this.planHook = planHook;
        return this;
    }

    /**
     * 最大值Retries
    */
    @Override
    public Agent maxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    /**
     * 重试退避
    */
    @Override
    public Agent retryBackoff(AgentRetryConfig.BackoffStrategy strategy) {
        this.retryStrategy = strategy;
        return this;
    }

    /**
     * 重试base延迟
    */
    @Override
    public Agent retryBaseDelay(long baseDelayMillis) {
        this.retryBaseDelay = baseDelayMillis;
        return this;
    }

    /**
     * 重试配置
    */
    @Override
    public Agent retryConfig(AgentRetryConfig retryConfig) {
        if (retryConfig != null) {
            this.maxRetries = retryConfig.getMaxRetries();
            this.retryStrategy = retryConfig.getBackoffStrategy();
            this.retryBaseDelay = retryConfig.getBaseDelayMillis();
        }
        return this;
    }

    /**
     * 获取Definition
    */
    @Override
    public AgentDefinition getDefinition() {
        return definition;
    }

    /**
     * 解析实际下发给模型的系统提示词。
     *
     * <p>与 {@link #runStream(String)} 使用同一套规则：leader 且声明了子 agent 时，
     * 由 {@link AgentSystemPromptBuilder} 拼上子 Agent 路由表；否则取 definition 的提示词。</p>
     *
     * @return 完整系统提示词
     */
    @Override
    public String getSystemPrompt() {
        return resolveSystemPrompt();
    }

    /**
     * 解析实际下发给模型的系统提示词。
     *
     * <p>先取 {@link AgentDefinition} 已经生成的提示词（显式 systemPrompt 或按
     * definition 内声明拼好的路由表），再把运行期通过 {@link #subAgent(AgentDefinition)}
     * 追加、definition 里没有声明的子 Agent 补进路由表，保证「读到的」与「发出去的」一致。</p>
     *
     * @return 完整系统提示词
     */
    private String resolveSystemPrompt() {
        if (definition == null) {
            return "You are a helpful assistant.";
        }
        String base = definition.getSystemPrompt();
        List<AgentDefinition> runtimeSubAgents = undeclaredSubAgents();
        if (definition.isLeader() && !runtimeSubAgents.isEmpty()) {
            return AgentSystemPromptBuilder.build(base, runtimeSubAgents);
        }
        return base;
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
        List<AgentDefinition> extra = Wraps.list();
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
     * 获取subAgent
    */
    @Override
    public List<AgentDefinition> getSubAgents() {
        return List.copyOf(subAgentDefinitions);
    }

    /**
     * 运行
    */
    @Override
    public AgentResponse run(String input) {
        // 内容护栏：用户输入边界（注入默认阻断；脱敏命中替换后放行）
        AgentContentVerdict inputVerdict = inspectUserInput(input);
        if (inputVerdict.action() == AgentContentVerdict.Action.BLOCK) {
            log.warn("[AgentScope] 用户输入被内容护栏拦截: agentId={}, findings={}",
                    agentId(), describeFindings(inputVerdict));
            return AgentResponse.builder()
                    .output("")
                    .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                    .metadata(Map.of("error", "用户输入被内容护栏拦截: " + describeFindings(inputVerdict)))
                    .build();
        }
        input = inputVerdict.content() != null ? inputVerdict.content() : input;
        RunPlan plan = prepareRun(input);
        // 新的一轮：清掉上一轮的中断信号与重复计数
        loopGuard.reset();

        // 预算记账：未配置预算时用不限记账器（照常累计便于观测，永不熔断）。
        // 口径说明：同步路径拿不到流式用量事件，Token / 费用无法逐轮记账（如实不虚报），
        // 这里只做「每轮尝试前」的熔断与取消检查；工具次数在工具风险闸门统一记账
        // （同步 / 流式同一口径、单点计数），Token 在流式 ModelCallEnd 事件边界记账。
        final AgentBudgetTracker tracker = budget() != null
                ? new SimpleAgentBudgetTracker(budget()) : AgentBudgetTracker.unlimited();
        // 评测埋点上下文：一次同步运行的最小现场
        final AgentEvalContext evalContext = AgentEvalContext.of(plan.agentId, input)
                .withMode("sync", defaultSessionId);
        fireEvalStart(evalContext);
        // 计划确认：不通过则不进入模型循环，直接带原因收尾
        AgentResponse confirmed = confirmPlanForSync(plan);
        if (confirmed != null) {
            fireEvalEnd(evalContext, confirmed, tracker);
            cleanupModelRegistrations();
            return confirmed;
        }
        // 外部取消：进入重试循环前先检查一次
        if (cancellationToken().isCancelled()) {
            log.info("[AgentScope] 运行开始前已被外部取消: agentId={}, reason={}",
                    plan.agentId, cancellationToken().reason());
            AgentResponse cancelled = cancelledResponse(plan.agentId, 0);
            fireEvalEnd(evalContext, cancelled, tracker);
            cleanupModelRegistrations();
            return cancelled;
        }

        AgentResponse result = null;
        HarnessAgent harnessAgent = null;

        int attempt = 0;
        int maxRetriesE = maxRetries;
        while (true) {
            if (loopGuard.isInterrupted()) {
                log.info("[AgentScope] 本轮已被要求中断，停止重试并收尾: agentId={}", plan.agentId);
                result = interruptedResponse(plan.agentId, attempt);
                break;
            }
            if (cancellationToken().isCancelled()) {
                log.info("[AgentScope] 本轮已被外部取消，停止重试并收尾: agentId={}, reason={}",
                        plan.agentId, cancellationToken().reason());
                result = cancelledResponse(plan.agentId, attempt);
                break;
            }
            if (tracker.isExceeded()) {
                log.warn("[AgentScope] 预算熔断，停止重试并收尾: agentId={}, reason={}",
                        plan.agentId, tracker.violationReason());
                result = budgetExceededResponse(plan.agentId, attempt, tracker.violationReason());
                break;
            }
            attempt++;
            try {
                harnessAgent = buildHarness(plan.agentId, plan.sysPrompt,
                        plan.primaryModelId, plan.declarations, plan.effectivePlan,
                        plan.effectivePlanMaxTask, plan.effectiveDebugHook,
                        plan.effectivePlanHook, plan.effectiveMaxIters, null, tracker);
                // 暴露给 interrupt()：外部中断靠关闭它来打断阻塞中的 call()
                runningHarness = harnessAgent;

                // 复用同一个 RuntimeContext：Plan 模式需在调用模型前预先进入，
                // 使首轮即处于只读状态；call 也用同一 ctx 加载该状态
                RuntimeContext ctx = buildRuntimeContext();
                if (plan.planStartActive) {
                    harnessAgent.enterPlanMode(ctx);
                }
                Msg resultMsg = harnessAgent.call(plan.userMsg, ctx).block();
                String output = resultMsg != null ? resultMsg.getTextContent() : "";
                // 内容护栏：模型输出边界（隐私 / 凭据脱敏；命中拦截替换为通知文本）
                output = guardModelOutput(output);
                autoSaveMemory(input, output);
                result = AgentResponse.builder()
                        .output(output)
                        .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                        .metadata(Map.of("attempts", attempt))
                        .build();
                break;
            } catch (Exception e) {
                if (loopGuard.isInterrupted()) {
                    // 中断导致的异常：不重试，按中断收尾
                    log.info("[AgentScope] 中断令执行提前结束 attempt={}, agentId={}, reason={}",
                            attempt, plan.agentId, e.getMessage());
                    result = interruptedResponse(plan.agentId, attempt);
                    break;
                }
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
                runningHarness = null;
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
        // 评测收尾：按 metadata 是否带 error 判定成败（中断 / 取消不算失败，按实际 metadata 口径）
        fireEvalEnd(evalContext, result, tracker);
        return result;
    }

    /**
     * 取消收尾响应：外部取消不是错误，不打 {@code error}，标出 {@code aborted} 与取消原因
     *
     * @param agentId 本轮 Agent 标识
     * @param attempt 已执行到第几次尝试
     * @return 收尾响应（output 为空，metadata 标明 aborted 与取消原因）
     */
    private AgentResponse cancelledResponse(String agentId, int attempt) {
        AgentResponse resp = AgentResponse.builder()
                .output("")
                .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                .build();
        resp.getMetadata().put("aborted", Boolean.TRUE);
        resp.getMetadata().put("reason", "cancelled");
        String reason = cancellationToken().reason();
        if (reason != null && !reason.isBlank()) {
            resp.getMetadata().put("cancelReason", reason);
        }
        resp.getMetadata().put("attempts", attempt);
        resp.getMetadata().put("agentId", agentId);
        return resp;
    }

    /**
     * 预算熔断收尾响应：metadata 标明 {@code budgetExceeded} 与熔断原因
     *
     * @param agentId 本轮 Agent 标识
     * @param attempt 已执行到第几次尝试
     * @param reason  熔断原因（超限维度 + 限额 + 实际值）
     * @return 收尾响应（output 为空，metadata 标明熔断）
     */
    private AgentResponse budgetExceededResponse(String agentId, int attempt, String reason) {
        AgentResponse resp = AgentResponse.builder()
                .output("")
                .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                .build();
        resp.getMetadata().put("aborted", Boolean.TRUE);
        resp.getMetadata().put("budgetExceeded", Boolean.TRUE);
        resp.getMetadata().put("reason", reason != null ? reason : "budget exceeded");
        resp.getMetadata().put("attempts", attempt);
        resp.getMetadata().put("agentId", agentId);
        return resp;
    }

    /**
     * 计划送审（同步 / 流式共用的前置门）。
     *
     * <p>{@code planConfirmation} 未设置时直接放行（返回 null）。本层在进入模型循环前
     * 只持有「本次要做什么」（用户请求原文），尚无逐步计划文本，故送审计划以请求原文为
     * 摘要、步骤清单留空——审批方据此决定放行 / 修订 / 拒绝。确认器异常按「拒绝」兜底，
     * 绝不带着未裁决的计划继续执行。</p>
     *
     * @param plan 运行计划
     * @return 放行时返回 null；否则返回审批结论（REJECT / REVISE 由调用方收尾）
     */
    private AgentPlanConfirmation.Outcome reviewPlan(RunPlan plan) {
        AgentPlanConfirmation confirmation = planConfirmation();
        if (confirmation == null) {
            return null;
        }
        String request = plan.userMsg != null ? plan.userMsg.getTextContent() : "";
        AgentPlan agentPlan = AgentPlan.of(request, Wraps.list());
        try {
            return confirmation.confirm(agentPlan);
        } catch (Exception e) {
            // 确认器异常按「拒绝」兜底：绝不带着未裁决的计划继续执行
            log.warn("[AgentScope] 计划确认器异常，按拒绝收尾: agentId={}", plan.agentId, e);
            return AgentPlanConfirmation.Outcome.reject(
                    "计划确认器异常: " + e.getMessage());
        }
    }

    /**
     * 同步运行前的计划送审收尾。
     *
     * <p>送审结论为拒绝 / 修订时不进入模型循环，直接返回带原因的终止响应
     * （{@code aborted=TRUE} + {@code reason=plan_rejected|plan_revise} + 反馈文本）。</p>
     *
     * @param plan 运行计划
     * @return 放行时返回 null；拒绝 / 修订时返回带原因的终止响应
     */
    private AgentResponse confirmPlanForSync(RunPlan plan) {
        AgentPlanConfirmation.Outcome outcome = reviewPlan(plan);
        if (outcome == null || outcome.decision() == AgentPlanConfirmation.Decision.PROCEED) {
            return null;
        }
        AgentResponse resp = AgentResponse.builder()
                .output("")
                .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                .build();
        resp.getMetadata().put("aborted", Boolean.TRUE);
        boolean rejected = outcome.decision() == AgentPlanConfirmation.Decision.REJECT;
        resp.getMetadata().put("reason", rejected ? "plan_rejected" : "plan_revise");
        if (outcome.feedback() != null && !outcome.feedback().isBlank()) {
            resp.getMetadata().put(rejected ? "rejectReason" : "planFeedback", outcome.feedback());
        }
        resp.getMetadata().put("agentId", plan.agentId);
        log.info("[AgentScope] 计划未获放行，终止本次运行: agentId={}, decision={}",
                plan.agentId, outcome.decision());
        return resp;
    }

    /**
     * 触发评测钩子的运行开始埋点（钩子异常只记日志，不影响主流程）。
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
            log.warn("[AgentScope] 评测钩子 onRunStart 异常（已忽略）: agentId={}",
                    context.agentId(), e);
        }
    }

    /**
     * 触发评测钩子的运行结束埋点（同步链路入口，从响应提取成败）。
     *
     * @param context 运行上下文
     * @param result  运行结果
     * @param tracker 预算记账器（用于回填已记账用量；无记账数据时为 0）
     */
    private void fireEvalEnd(AgentEvalContext context, AgentResponse result,
                             AgentBudgetTracker tracker) {
        Map<String, Object> metadata = result != null ? result.getMetadata() : null;
        boolean success = metadata == null || !metadata.containsKey("error");
        String error = success ? null
                : String.valueOf(metadata != null ? metadata.get("error") : "unknown");
        fireEvalEnd(context, result != null ? result.getOutput() : null, success, error, tracker);
    }

    /**
     * 触发评测钩子的运行结束埋点（核心入口，钩子异常只记日志，不影响主流程）。
     *
     * <p>耗时从 {@code context.startMillis()} 起算；用量只回填已记账部分
     * （同步路径无用量事件时为 0，不虚报）。</p>
     *
     * @param context 运行上下文
     * @param output  输出文本（失败 / 无输出时可为 null）
     * @param success 是否成功结束
     * @param error   失败原因（成功时为 null）
     * @param tracker 预算记账器
     */
    private void fireEvalEnd(AgentEvalContext context, String output, boolean success,
                             String error, AgentBudgetTracker tracker) {
        AgentEvalHook hook = evalHook();
        if (hook == null) {
            return;
        }
        try {
            AiUsage usage = AiUsage.builder()
                    .inputTokens((int) tracker.usedInputTokens())
                    .outputTokens((int) tracker.usedOutputTokens())
                    .totalTokens((int) tracker.usedTotalTokens())
                    .build();
            AgentEvalRecord record = new AgentEvalRecord(context, output, success, error,
                    System.currentTimeMillis() - context.startMillis(), usage);
            hook.onRunEnd(record);
        } catch (Exception e) {
            log.warn("[AgentScope] 评测钩子 onRunEnd 异常（已忽略）: agentId={}",
                    context.agentId(), e);
        }
    }

    /**
     * 中断收尾响应：中断不是错误，故不打 {@code error}，只标出 {@code aborted}
     *
     * @param agentId 本轮 Agent 标识
     * @param attempt 已执行到第几次尝试
     * @return 收尾响应（output 为空，metadata 标明 aborted）
     */
    private AgentResponse interruptedResponse(String agentId, int attempt) {
        AgentResponse resp = AgentResponse.builder()
                .output("")
                .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                .build();
        resp.getMetadata().put("aborted", Boolean.TRUE);
        resp.getMetadata().put("reason", "interrupted");
        resp.getMetadata().put("attempts", attempt);
        resp.getMetadata().put("agentId", agentId);
        return resp;
    }

    /**
     * 流式运行
    */
    @Override
    public Flux<AgentStreamEvent> runStream(String input) {
        // 内容护栏：用户输入边界（注入默认阻断；脱敏命中替换后放行）
        AgentContentVerdict inputVerdict = inspectUserInput(input);
        if (inputVerdict.action() == AgentContentVerdict.Action.BLOCK) {
            log.warn("[AgentScope] 用户输入被内容护栏拦截(流式): agentId={}, findings={}",
                    agentId(), describeFindings(inputVerdict));
            return Flux.just(errorEvent(agentId(),
                    "用户输入被内容护栏拦截: " + describeFindings(inputVerdict)));
        }
        input = inputVerdict.content() != null ? inputVerdict.content() : input;
        final RunPlan plan;
        try {
            plan = prepareRun(input);
        } catch (Exception e) {
            log.error("[AgentScope] runStream准备失败: {}", e.getMessage(), e);
            return Flux.error(e);
        }
        // 计划确认：仅初始轮送审；恢复轮（resumeStream / answerQuestion）不重复送审。
        // REJECT / REVISE 都终止本轮：REJECT 带拒绝理由，REVISE 带修订反馈。
        AgentPlanConfirmation.Outcome outcome = reviewPlan(plan);
        if (outcome != null && outcome.decision() != AgentPlanConfirmation.Decision.PROCEED) {
            boolean rejected = outcome.decision() == AgentPlanConfirmation.Decision.REJECT;
            String feedback = outcome.feedback();
            String detail = feedback != null && !feedback.isBlank() ? "：" + feedback : "";
            log.info("[AgentScope] 计划未获放行，流式运行终止: agentId={}, decision={}",
                    plan.agentId, outcome.decision());
            return Flux.just(errorEvent(plan.agentId,
                    (rejected ? "计划未获批准，终止执行" : "计划需修订后重新提交") + detail));
        }
        return drive(plan, true);
    }

    /**
     * 已写入的待批缓存键登记表：供 {@link #clearPendingBlocks()} 全量清理使用。
     *
     * <p>键在写入时登记、取走时移除，常态下只持有存活批次的键；
     * 被用户中途放弃（TTL 过期、决策未送达）的键会残留到下次清理，
     * 量级与旧「写入顺序队列」相当。</p>
     */
    private static final java.util.Set<String> PENDING_KEYS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 写入一批待批工具块。
     *
     * <p>存储走 {@link AgentSuspendStore}：默认回落进程级共享存储
     * （默认 TTL 30 分钟、条目上限 256，超限逐出最旧批次），过期与逐出由存储实现负责；
     * 上层注入 Redis / 数据库实现后即可跨实例、跨进程恢复。</p>
     *
     * <p>必须跨实例可见：初始轮（runStream）与人工确认恢复轮（resumeStream）由
     * 不同的 AgentScopeAgent 实例承载（facade 每请求新建），实例字段无法跨请求保留。
     * Harness 应用 ConfirmResult 时会用回传的 ToolUseBlock <b>整体替换</b>已存储的待批块，
     * 若只回传 id（name/input 缺失），会导致：① AgentTraceMiddleware 以 null name 写入
     * ConcurrentHashMap 触发 NPE；② 工具入参丢失。故在 RequireUserConfirmEvent 缓存完整块，
     * resume 时原样回传（harness 仅 withState 翻转允许状态，其余字段保留）。</p>
     *
     * @param replyId 回复标识，为空不缓存
     * @param agentId 发起挂起的 Agent 标识，可为 null
     * @param blocks  完整工具块表
     */
    private void cachePendingBlocks(String replyId, String agentId, Map<String, ToolUseBlock> blocks) {
        if (replyId == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        // TTL 传 0：交由存储套用默认 TTL 与条目上限（与旧 PENDING_BLOCKS_LIMIT 口径一致）
        suspendStore().put(AgentSuspendState.of(replyId, agentId, blocks, 0L));
        PENDING_KEYS.add(replyId);
    }

    /**
     * 取走一批待批工具块并立即逐出。
     *
     * <p>逐出是必须的：确认决策只回传一次，缓存若只写不清，每次人工确认都会把
     * 完整工具入参永久留在堆里。取不到时返回 null，让调用方按「无缓存」处理。
     *
     * @param replyId 回复标识，可为空
     * @return 完整工具块表；无对应缓存时为 null
     */
    Map<String, ToolUseBlock> takePendingBlocks(String replyId) {
        if (replyId == null) {
            return null;
        }
        AgentSuspendState state = suspendStore().remove(replyId);
        PENDING_KEYS.remove(replyId);
        if (state == null || !(state.payload() instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, ToolUseBlock> blocks = (Map<String, ToolUseBlock>) state.payload();
        return blocks;
    }

    /**
     * 仅供单测：直接塞入一批待批块（绕过事件来源）。
     *
     * @param replyId 回复标识
     * @param blocks  完整工具块表
     */
    static void cachePendingBlocksForTest(String replyId, Map<String, ToolUseBlock> blocks) {
        if (replyId == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        AgentSuspendStore.globalDefault().put(AgentSuspendState.of(replyId, null, blocks, 0L));
        PENDING_KEYS.add(replyId);
    }

    /**
     * 仅供单测：当前缓存条目数。
     *
     * @return 条目数
     */
    static int pendingBlocksSize() {
        return AgentSuspendStore.globalDefault().size();
    }

    /**
     * 仅供单测：清空待批缓存。
     */
    static void clearPendingBlocks() {
        AgentSuspendStore store = AgentSuspendStore.globalDefault();
        for (String key : PENDING_KEYS) {
            store.remove(key);
        }
        PENDING_KEYS.clear();
    }

    @Override
    public Flux<AgentStreamEvent> resumeStream(String replyId, List<ConfirmationDecision> decisions) {
        return resumeStream(replyId, decisions, null);
    }

    @Override
    public Flux<AgentStreamEvent> resumeStream(String replyId, List<ConfirmationDecision> decisions,
                                              String feedback) {
        if (!this.pendingToolRecoveryEnabled) {
            // 恢复通道被显式关闭：逐出缓存块并返回错误事件，绝不静默丢弃决策
            takePendingBlocks(replyId);
            return Flux.just(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.ERROR)
                    .toolOutput("已通过 pendingToolRecovery(false) 关闭待执行工具恢复，"
                            + "请重新发起运行")
                    .timestamp(System.currentTimeMillis())
                    .build());
        }
        final RunPlan plan;
        try {
            // 复用 prepareRun 完成模型注册 / 系统提示词 / 规划配置；触发消息稍后替换为确认结果消息
            plan = prepareRun(null);
        } catch (Exception e) {
            log.error("[AgentScope] resumeStream准备失败: {}", e.getMessage(), e);
            return Flux.error(e);
        }
        List<ConfirmResult> confirmResults = Wraps.list();
        Map<String, ToolUseBlock> cached = takePendingBlocks(replyId);
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
        Map<String, Object> meta = Wraps.map();
        meta.put(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID, replyId);
        meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
        plan.userMsg = Msg.builder().role(MsgRole.USER).metadata(meta).build();
        // 用户修改意见：drive 在 streamEvents 前注入 Agent 上下文，模型据此重新规划
        plan.feedback = feedback;
        return drive(plan, false);
    }

    /**
     * 回传结构化提问的答案并恢复执行。
     *
     * <p>机制：{@code ask_user} 以 schema-only 外部工具注册，模型调用它时框架挂起当前轮、
     * 发出 {@link RequireExternalExecutionEvent} 并结束本轮流；本方法把用户答案包装成与
     * 挂起块按 id 配对的 {@link ToolResultBlock}，携带
     * {@link Msg#METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID} 元数据开启新一轮
     * {@code streamEvents}，框架配对成功后从暂停点继续执行。</p>
     *
     * <p>与 {@link #resumeStream} 同构：复用 {@code prepareRun(null)} 与静态待批块缓存
     * （门面每个请求新建实例，实例字段跨不过 runStream → answerQuestion）。</p>
     */
    @Override
    public Flux<AgentStreamEvent> answerQuestion(String replyId, List<AgentQuestionAnswer> answers) {
        if (!this.pendingToolRecoveryEnabled) {
            // 恢复通道被显式关闭：逐出缓存块并返回错误事件，绝不静默丢弃答案
            takePendingBlocks(replyId);
            return Flux.just(AgentStreamEvent.builder()
                    .type(AgentStreamEvent.Type.ERROR)
                    .toolOutput("已通过 pendingToolRecovery(false) 关闭待执行工具恢复，"
                            + "请重新发起运行")
                    .timestamp(System.currentTimeMillis())
                    .build());
        }
        final RunPlan plan;
        try {
            // 复用 prepareRun 完成模型注册 / 系统提示词 / 规划配置；触发消息稍后替换为答案结果消息
            plan = prepareRun(null);
        } catch (Exception e) {
            log.error("[AgentScope] answerQuestion准备失败: {}", e.getMessage(), e);
            return Flux.error(e);
        }
        Map<String, ToolUseBlock> cached = takePendingBlocks(replyId);
        List<ContentBlock> results = new java.util.ArrayList<>();
        if (answers != null) {
            for (AgentQuestionAnswer answer : answers) {
                if (answer == null) {
                    continue;
                }
                ToolUseBlock pending = cached != null && answer.getQuestionId() != null
                        ? cached.get(answer.getQuestionId()) : null;
                // 原样回传挂起块的 id/name，框架按 id 与待执行块配对；缓存缺失时退化为裸 id
                String id = pending != null ? pending.getId() : answer.getQuestionId();
                String name = pending != null ? pending.getName() : ASK_USER_TOOL;
                results.add(ToolResultBlock.of(id, name,
                        TextBlock.builder().text(toAnswerJson(answer)).build()));
            }
        }
        Map<String, Object> meta = Wraps.map();
        meta.put(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID, replyId);
        plan.userMsg = Msg.builder().role(MsgRole.USER).content(results).metadata(meta).build();
        // 答案走工具结果通道回传，不再经 feedback 注入上下文
        plan.feedback = null;
        return drive(plan, false);
    }

    /**
     * 答案序列化器：仅用于把用户答案打包成回传给模型的 JSON 文本。
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper ANSWER_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 把单个问题的用户答案序列化为回传给模型的 JSON 文本。
     */
    private static String toAnswerJson(AgentQuestionAnswer answer) {
        Map<String, Object> payload = Wraps.map();
        payload.put("questionId", answer.getQuestionId());
        payload.put("answers", answer.getAnswers());
        try {
            return ANSWER_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            // 序列化失败退化为可读文本，保证答案仍能送达模型
            return String.valueOf(answer.getAnswers());
        }
    }

    /**
     * 结构化提问工具名：以 schema-only 外部工具注册，模型调用即挂起当前轮等待用户回答。
     */
    private static final String ASK_USER_TOOL = "ask_user";

    /**
     * ask_user 工具的参数 schema：问题正文 + 可选项 + 是否多选。
     */
    private static io.agentscope.core.model.ToolSchema askUserSchema() {
        Map<String, Object> question = Wraps.map();
        question.put("type", "string");
        question.put("description", "要问用户的问题正文");
        Map<String, Object> items = Wraps.map();
        items.put("type", "string");
        Map<String, Object> options = Wraps.map();
        options.put("type", "array");
        options.put("items", items);
        options.put("description", "可选项列表；为空表示纯自由输入");
        Map<String, Object> multiple = Wraps.map();
        multiple.put("type", "boolean");
        multiple.put("description", "是否允许多选");
        Map<String, Object> properties = Wraps.map();
        properties.put("question", question);
        properties.put("options", options);
        properties.put("multiple", multiple);
        Map<String, Object> parameters = Wraps.map();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", java.util.Arrays.asList("question"));
        return io.agentscope.core.model.ToolSchema.builder()
                .name(ASK_USER_TOOL)
                .description("向用户提出结构化问题并等待回答。需要用户补充信息、在选项间做选择或确认偏好时调用；"
                        + "调用后当前轮暂停，用户答案会在下一轮以本工具的执行结果形式返回。")
                .parameters(parameters)
                .build();
    }

    /**
     * 把 ask_user 挂起块的入参解析为 {@link AgentQuestion}。
     */
    private static AgentQuestion toAgentQuestion(ToolUseBlock call) {
        Map<String, Object> input = call.getInput();
        String question = input != null && input.get("question") != null
                ? String.valueOf(input.get("question")) : "请补充信息";
        List<String> options = new java.util.ArrayList<>();
        Object rawOptions = input != null ? input.get("options") : null;
        if (rawOptions instanceof List) {
            for (Object item : (List<?>) rawOptions) {
                if (item != null) {
                    options.add(String.valueOf(item));
                }
            }
        }
        Object rawMultiple = input != null ? input.get("multiple") : null;
        boolean multiple = rawMultiple != null && Boolean.parseBoolean(String.valueOf(rawMultiple));
        return AgentQuestion.builder()
                .id(call.getId())
                .question(question)
                .options(options.isEmpty() ? null : options)
                .multiple(multiple)
                .custom(true)
                .build();
    }

    /**
     * 生成区间配对键。
     *
     * <p>必须由「类别 + 唯一标识」拼成。只用类别是不够的：一次运行里会有多次同名
     * 工具调用，只按类别配对会把它们并成一根横条，时长等于整段工具时间。</p>
     *
     * @param scope    区间类别（model/tool/think）
     * @param uniqueId 该次调用的唯一标识；模型调用本身串行，传入 null 即可
     * @return 配对键
     */
    private static String spanKey(String scope, String uniqueId) {
        return scope + "|" + (uniqueId == null ? "-" : uniqueId);
    }

    /**
     * 取出并移除待闭合的区间标识。
     *
     * <p>先按精确键配对；配不上、但该类别下恰好只有一个未闭合区间时，认定它就是
     * 本次结束事件对应的区间（少数框架版本的结果事件不带调用 id）。若该类别有多个
     * 未闭合区间则无法判定，返回 null——宁可少一根横条，也不要把不相干的区间标成
     * 已结束、算出一个假的时长。</p>
     *
     * @param openSpan 未闭合区间表
     * @param scope    区间类别
     * @param uniqueId 该次调用的唯一标识，可空
     * @return 区间标识；无对应区间时为 null
     */
    private static String takeSpan(java.util.Map<String, String> openSpan, String scope, String uniqueId) {
        String exact = openSpan.remove(spanKey(scope, uniqueId));
        if (exact != null) {
            return exact;
        }
        String prefix = scope + "|";
        String only = null;
        for (java.util.Map.Entry<String, String> entry : openSpan.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            if (only != null) {
                return null;
            }
            only = entry.getValue();
        }
        if (only == null) {
            return null;
        }
        for (Iterator<java.util.Map.Entry<String, String>> it = openSpan.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().equals(only)) {
                it.remove();
                break;
            }
        }
        return only;
    }

    /**
     * 取出并移除区间开始时刻。
     *
     * @param starts spanId -&gt; 开始毫秒
     * @param spanId 区间标识（可空）
     * @return 开始毫秒；没有记录时返回 0，由 {@code AgentTrace.spanEnd} 退化为此刻
     */
    private static long spanStart(java.util.Map<String, Long> starts, String spanId) {
        if (spanId == null) {
            return 0L;
        }
        Long value = starts.remove(spanId);
        return value == null ? 0L : value;
    }

    /**
     * 构造流式错误终止事件（取消 / 预算熔断 / 计划未放行等收尾统一出口）。
     *
     * <p>错误文案放 {@code toolOutput}（与 resumeStream 的错误事件同口径），
     * 并带 agentId 便于订阅方归属到当前运行。</p>
     *
     * @param agentId Agent 标识
     * @param message 错误文案
     * @return ERROR 事件
     */
    private AgentStreamEvent errorEvent(String agentId, String message) {
        return AgentStreamEvent.builder()
                .type(AgentStreamEvent.Type.ERROR)
                .agentId(agentId)
                .toolOutput(message)
                .timestamp(System.currentTimeMillis())
                .build();
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
            // 新的一轮：清掉上一轮的中断信号与重复计数
            loopGuard.reset();
            // 预算记账器：未配置预算时用不限记账器（照常累计便于观测，永不熔断）
            final AgentBudgetTracker tracker = budget() != null
                    ? new SimpleAgentBudgetTracker(budget()) : AgentBudgetTracker.unlimited();
            // 评测埋点：一次流式运行的最小现场（resumeStream / answerQuestion 恢复轮同样计一次）
            final AgentEvalContext evalContext = AgentEvalContext.of(plan.agentId,
                    plan.userMsg != null ? plan.userMsg.getTextContent() : null)
                    .withMode("stream", defaultSessionId);
            // 终态标记：ERROR 事件（取消 / 熔断）与上游异常都会置位，doFinally 据此判成败
            final java.util.concurrent.atomic.AtomicBoolean streamFailed =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicReference<String> failReason =
                    new java.util.concurrent.atomic.AtomicReference<>();
            fireEvalStart(evalContext);
            // 订阅即查：已取消 / 已超限时不再发起模型调用
            String preBlock = null;
            if (cancellationToken().isCancelled()) {
                String reason = cancellationToken().reason();
                preBlock = "已被外部取消" + (reason != null && !reason.isBlank() ? ": " + reason : "");
            } else if (tracker.isExceeded()) {
                preBlock = "预算熔断: " + tracker.violationReason();
            }
            if (preBlock != null) {
                streamFailed.set(true);
                failReason.compareAndSet(null, preBlock);
                runningHarness = null;
                cleanupModelRegistrations();
                fireEvalEnd(evalContext, null, false, preBlock, tracker);
                return Flux.just(errorEvent(plan.agentId, preBlock));
            }
            final HarnessAgent harnessAgent;
            try {
                harnessAgent = buildHarness(plan.agentId, plan.sysPrompt,
                        plan.primaryModelId, plan.declarations, plan.effectivePlan,
                        plan.effectivePlanMaxTask, plan.effectiveDebugHook,
                        plan.effectivePlanHook, plan.effectiveMaxIters, plan.feedback, tracker);
            } catch (Exception e) {
                return Flux.error(e);
            }
            // 暴露给 interrupt()：外部中断靠关闭它来打断阻塞中的 streamEvents()
            runningHarness = harnessAgent;
            final long[] inputTokens = {0L};
            final long[] outputTokens = {0L};
            final long[] cachedTokens = {0L};
            // 记录每个 agent 是否已通过 delta 流式输出过正文；
            // 非流式模型适配器不产生 TextBlockDeltaEvent，需在 AgentResultEvent 补正文
            final java.util.Map<String, Boolean> textDeltaEmitted = Wraps.map();
            // 本轮最终正文（用于结束后写入长期记忆）
            final StringBuilder outputText = new StringBuilder();

            // 复用同一个 RuntimeContext：Plan 模式在订阅流前预先进入，首轮即只读
            RuntimeContext ctx = buildRuntimeContext();
            if (initialRun && plan.planStartActive) {
                harnessAgent.enterPlanMode(ctx);
            }

            // 用户「需要修改」的意见由 AgentDebugMiddleware.onReasoning 在状态激活后、
            // 模型调用前注入（activateSlotForContext 会从存储重新加载状态并覆盖此前的预注入，
            // 故不能在 streamEvents 前注入）。

            /*
             * 区间（span）配对状态：只在出现**显式开始事件**时开区间，在对应的
             * 显式结束事件上闭合。绝不按事件先后顺序去推断区间——推断出来的时长
             * 是编的数字，比空着更容易误导人。
             */
            final java.util.Map<String, String> openSpan = Wraps.map();
            // 区间开始时刻与工具名：结束事件上要一并带出，否则耗时与工具行都拼不出来。
            // 键统一用 spanId（结束事件只能取回 spanId，取不回调用方原始键）。
            final java.util.Map<String, Long> spanStartAt = Wraps.map();
            final java.util.Map<String, String> spanToolName = Wraps.map();
            final int[] spanSeq = {0};

            return harnessAgent.streamEvents(plan.userMsg, ctx)
                    /*
                     * 必须用 concatMap 而不是 handle：
                     * handle 的 SynchronousSink 每次调用只允许发射一个信号，第二次
                     * sink.next 会抛 IllegalStateException。而单个源事件本就要产出多条
                     * （工具开始 = TOOL_START + trace；模型结束 = 区间 END + usage），
                     * 用 handle 时后半段会被连带丢弃——表现为工具区间只剩 END、
                     * 每轮 usage 全丢，且不留任何痕迹。
                     */
                    .concatMap(event -> {
                        // 事件边界检查：外部取消 / 预算熔断 → 发 ERROR 事件终止（takeUntil 见即停）
                        if (cancellationToken().isCancelled()) {
                            streamFailed.set(true);
                            String reason = cancellationToken().reason();
                            String message = "已被外部取消"
                                    + (reason != null && !reason.isBlank() ? ": " + reason : "");
                            failReason.compareAndSet(null, message);
                            return Flux.just(errorEvent(plan.agentId, message));
                        }
                        if (tracker.isExceeded()) {
                            streamFailed.set(true);
                            String message = "预算熔断: " + tracker.violationReason();
                            failReason.compareAndSet(null, message);
                            return Flux.just(errorEvent(plan.agentId, message));
                        }
                        final List<AgentStreamEvent> emitted = Wraps.list();
                        try {
                            String aid = plan.agentId;
                            if (event instanceof TextBlockDeltaEvent delta) {
                                if (delta.getDelta() != null && !delta.getDelta().isEmpty()) {
                                    // 正文增量实时下发（真实流式）；输出全文在 AgentResultEvent 统一记一条 trace
                                    emitted.add(AgentStreamEvent.reasoningDelta(aid, delta.getDelta()));
                                    textDeltaEmitted.put(aid, Boolean.TRUE);
                                    outputText.append(delta.getDelta());
                                }
                            } else if (event instanceof ThinkingBlockStartEvent e) {
                                String spanId = AgentTrace.newSpanId(aid, "think", ++spanSeq[0]);
                                openSpan.put(spanKey("think", e.getBlockId()), spanId);
                                spanStartAt.put(spanId, System.currentTimeMillis());
                                emitted.add(AgentStreamEvent.trace(AgentTrace.spanStart(
                                        AgentTrace.KIND_THINKING, aid, spanId, null, "深度思考")));
                            } else if (event instanceof ThinkingBlockEndEvent e) {
                                String spanId = takeSpan(openSpan, "think", e.getBlockId());
                                if (spanId != null) {
                                    emitted.add(AgentStreamEvent.trace(AgentTrace.spanEnd(
                                            AgentTrace.KIND_THINKING, aid, spanId, null, "思考结束",
                                            spanStart(spanStartAt, spanId))));
                                }
                            } else if (event instanceof ThinkingBlockDeltaEvent delta) {
                                if (delta.getDelta() != null && !delta.getDelta().isEmpty()) {
                                    emitted.add(AgentStreamEvent.thinkingDelta(aid, delta.getDelta()));
                                    emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                            AgentTrace.KIND_THINKING, aid, null, delta.getDelta())));
                                }
                            } else if (event instanceof ToolCallStartEvent start) {
                                String toolName = start.getToolCallName();
                                String spanId = AgentTrace.newSpanId(aid, "tool", ++spanSeq[0]);
                                // 以工具调用 id 为键：一次运行里会有多次同名工具调用，
                                // 只用工具名会把它们并成一根横条
                                openSpan.put(spanKey("tool", start.getToolCallId()), spanId);
                                spanStartAt.put(spanId, System.currentTimeMillis());
                                spanToolName.put(spanId, toolName);
                                emitted.add(AgentStreamEvent.builder()
                                        .type(AgentStreamEvent.Type.TOOL_START)
                                        .agentId(aid)
                                        .toolName(toolName)
                                        .timestamp(System.currentTimeMillis())
                                        .build());
                                emitted.add(AgentStreamEvent.trace(AgentTrace.spanStart(
                                        AgentTrace.KIND_TOOL_CALL, aid, spanId, toolName,
                                        "调用工具: " + toolName)));
                            } else if (event instanceof ToolResultEndEvent e) {
                                String spanId = takeSpan(openSpan, "tool", e.getToolCallId());
                                if (spanId != null) {
                                    // 结束行必须带工具名与开始时刻：否则回放按工具名聚合时
                                    // 这一行被跳过，耗时永远算不出来（用户报障：工具没有用时）。
                                    emitted.add(AgentStreamEvent.trace(AgentTrace.spanEnd(
                                            AgentTrace.KIND_TOOL_RESULT, aid, spanId,
                                            spanToolName.remove(spanId), "工具执行完成",
                                            spanStart(spanStartAt, spanId))));
                                }
                            } else if (event instanceof ToolCallDeltaEvent d) {
                                if (d.getDelta() != null && !d.getDelta().isEmpty()) {
                                    emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                            AgentTrace.KIND_TOOL_ARGS, aid, d.getToolCallName(), d.getDelta())));
                                }
                            } else if (event instanceof ToolResultTextDeltaEvent d) {
                                if (d.getDelta() != null && !d.getDelta().isEmpty()) {
                                    emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                            AgentTrace.KIND_TOOL_RESULT, aid, d.getToolCallName(), d.getDelta())));
                                }
                            } else if (event instanceof ModelCallStartEvent) {
                                // 模型调用区间：一次运行里模型调用严格串行，
                                // 故用固定键 "model"，靠 takeSpan 在结束事件上取回
                                String spanId = AgentTrace.newSpanId(aid, "turn", ++spanSeq[0]);
                                openSpan.put(spanKey("model", null), spanId);
                                spanStartAt.put(spanId, System.currentTimeMillis());
                                emitted.add(AgentStreamEvent.trace(AgentTrace.spanStart(
                                        AgentTrace.KIND_TURN, aid, spanId, null, "模型调用")));
                            } else if (event instanceof ModelCallEndEvent end) {
                                String spanId = takeSpan(openSpan, "model", null);
                                if (spanId != null) {
                                    emitted.add(AgentStreamEvent.trace(AgentTrace.spanEnd(
                                            AgentTrace.KIND_TURN, aid, spanId, null, "模型调用完成",
                                            spanStart(spanStartAt, spanId))));
                                }
                                ChatUsage usage = end.getUsage();
                                if (usage != null) {
                                    inputTokens[0] += nonNegative(usage.getInputTokens());
                                    outputTokens[0] += nonNegative(usage.getOutputTokens());
                                    cachedTokens[0] += nonNegative(usage.getCachedTokens());
                                    // 预算记账：模型 Token 用量（事件边界记账）
                                    tracker.recordTokens(nonNegative(usage.getInputTokens()),
                                            nonNegative(usage.getOutputTokens()));
                                    // usage 是点事件而非区间：它是「本轮花了多少」的数字，
                                    // 不是一段时间，塞进区间会让甘特图多出一根 0ms 横条
                                    emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                            AgentTrace.KIND_USAGE, aid, null,
                                            "输入=" + usage.getInputTokens()
                                                    + ", 输出=" + usage.getOutputTokens()
                                                    + ", 缓存=" + usage.getCachedTokens())));
                                }
                            } else if (event instanceof AgentStartEvent e) {
                                emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_AGENT_START, aid, null,
                                        "Agent 启动: " + e.getName())));
                            } else if (event instanceof AgentResultEvent re) {
                                // 非流式模型适配器不产生 TextBlockDeltaEvent，完整正文在结果消息里：
                                // 统一记一条“模型输出”轨迹；若此前未通过 delta 输出正文，则补一次正文供渲染
                                Msg resultMsg = re.getResult();
                                String fullText = resultMsg != null ? resultMsg.getTextContent() : "";
                                // 内容护栏：模型输出边界收口（流式 delta 已发无法召回，这里兜底最终正文）
                                fullText = guardModelOutput(fullText);
                                if (StringUtils.isNotEmpty(fullText)) {
                                    emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                            AgentTrace.KIND_TEXT, aid, null, fullText)));
                                    if (!Boolean.TRUE.equals(textDeltaEmitted.get(aid))) {
                                        emitted.add(AgentStreamEvent.reasoningDelta(aid, fullText));
                                        textDeltaEmitted.put(aid, Boolean.TRUE);
                                        outputText.append(fullText);
                                    }
                                }
                            } else if (event instanceof AgentEndEvent) {
                                emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_AGENT_END, aid, null, "Agent 结束")));
                            } else if (event instanceof HintBlockEvent e) {
                                emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_HINT, aid, null,
                                        "提示[" + e.getHintSource() + "]: " + e.getHint())));
                            } else if (event instanceof RequireUserConfirmEvent e) {
                                List<ConfirmationItem> confirmItems = Wraps.list();
                                List<ToolUseBlock> pending = e.getToolCalls();
                                if (pending != null) {
                                    // 缓存完整待批块（按 replyId 分组），resume 轮原样回传
                                    Map<String, ToolUseBlock> blockById = Wraps.linkedMap();
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
                                        cachePendingBlocks(e.getReplyId(), plan.agentId, blockById);
                                    }
                                }
                                // 下发人工确认请求：前端渲染批准/拒绝卡片，决策回传后恢复
                                emitted.add(AgentStreamEvent.confirmationRequest(
                                        aid, e.getReplyId(), confirmItems));
                                emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_EDGE, aid, null,
                                        "等待【用户确认】(未确认会卡住), 工具数="
                                                + (pending != null ? pending.size() : 0))));
                            } else if (event instanceof RequireExternalExecutionEvent e) {
                                // ask_user 等 schema-only 外部工具挂起当前轮：转成结构化提问下发，
                                // 并缓存完整工具块供 answerQuestion 轮按 id 配对回传
                                List<AgentQuestion> questions = Wraps.list();
                                Map<String, ToolUseBlock> blockById = Wraps.linkedMap();
                                List<ToolUseBlock> externalCalls = e.getToolCalls();
                                if (externalCalls != null) {
                                    for (ToolUseBlock tb : externalCalls) {
                                        if (tb == null) {
                                            continue;
                                        }
                                        if (ASK_USER_TOOL.equals(tb.getName())) {
                                            if (tb.getId() != null) {
                                                blockById.put(tb.getId(), tb);
                                            }
                                            questions.add(toAgentQuestion(tb));
                                        }
                                    }
                                }
                                if (!questions.isEmpty() && e.getReplyId() != null) {
                                    cachePendingBlocks(e.getReplyId(), plan.agentId, blockById);
                                    emitted.add(AgentStreamEvent.questionRequest(
                                            aid, e.getReplyId(), questions));
                                }
                                emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_EDGE, aid, null,
                                        "等待【外部执行】(未执行会卡住), 工具数="
                                                + (externalCalls != null ? externalCalls.size() : 0))));
                            } else if (event instanceof AllToolsDeniedEvent e) {
                                emitted.add(AgentStreamEvent.trace(AgentTrace.of(
                                        AgentTrace.KIND_EDGE, aid, null,
                                        "工具调用全部被拒绝, 数量="
 +
                                                        (e.getDeniedToolCalls() != null
                                                                ? e.getDeniedToolCalls().size()
                                                                : 0))));
                            } else if (event instanceof ExceedMaxItersEvent e) {
                                emitted.add(AgentStreamEvent.trace(AgentTrace.of(
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
                        } catch (Exception e) {
                            /*
                             * 单事件映射异常不中断整个流，但必须留痕。
                             * 这里曾经是 catch (Exception ignored)：第二个 sink.next 抛的
                             * IllegalStateException 被彻底吞掉，既没有日志，也让本轮
                             * 已经产出的信号一起消失——轨迹里就只剩孤儿 END、usage 全丢，
                             * 排查时完全看不出来是哪一步断的。
                             *
                             * 注意 emitted 里已产出的信号不能因为异常丢弃：
                             * 那正是「映射到一半出错也不该把前面的输出抹掉」的意思。
                             */
                            log.warn("[TraceDiag] 事件映射异常 class={}", event.getClass().getName(), e);
                        }
                        return Flux.fromIterable(emitted);
                    })
                    // 取消 / 预算熔断以 ERROR 事件收尾：见到即停（concatMap 本身不产 ERROR，安全）
                    .takeUntil(event -> event.getType() == AgentStreamEvent.Type.ERROR)
                    .concatWith(Flux.defer(() -> {
                        long duration = System.currentTimeMillis() - startTime;
                        // 记忆写在 COMPLETE 之前：宿主收到 COMPLETE 即为本轮结束，
                        // 写晚了会出现「刚结束就查记忆查不到」的竞态
                        autoSaveMemory(plan.userMsg != null ? plan.userMsg.getTextContent() : null,
                                outputText.toString());
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
                    .doOnError(e -> {
                        streamFailed.set(true);
                        failReason.compareAndSet(null,
                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    })
                    .doFinally(signal -> {
                        runningHarness = null;
                        try {
                            harnessAgent.close();
                        } catch (Exception ignored) {
                            log.debug("[AgentScope] 流式HarnessAgent关闭异常: agentId={}", plan.agentId, ignored);
                        }
                        cleanupModelRegistrations();
                        // 评测收尾（恰好一次）：正常 COMPLETE=成功；ERROR 事件（取消 / 熔断）
                        // 与上游异常=失败；订阅方中途 dispose 也在此按失败收尾。
                        boolean success = !streamFailed.get()
                                && signal == reactor.core.publisher.SignalType.ON_COMPLETE;
                        fireEvalEnd(evalContext, outputText.toString(), success,
                                success ? null : failReason.get(), tracker);
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

        List<SubagentDeclaration> declarations = Wraps.list();
        for (int i = 0; i < subAgentDefinitions.size(); i++) {
            AgentDefinition subDef = subAgentDefinitions.get(i);
            String subId = StringUtils.defaultString(subDef.getId(), "sub-" + i);

            ChatClient subChatClient = this.chatClients.get(subId);
            String subModelId;
            if (subDef instanceof ImageDefinition imgDef) {
                subModelId = "agentscope:" + subId + ":image:" + imgDef.getImageModel();
                try {
                    ModelRegistry.register(subModelId,
                                           new ImageGenerationModel(imgDef.getImageClient(), imgDef.getImageModel()));
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
        String sysPrompt = injectMemory(resolveSystemPrompt(), input);

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

    /**
     * 绑定一个会话目标（Issue）。
     *
     * <p>绑定后可用 {@link #runGoal()} 让 agent 围绕该目标反复推进直至完成；
     * 目标状态机由 {@link GoalTracker} 持有，可被其它线程暂停/恢复/取消。
     *
     * @param goal 目标快照，不能为 null
     * @return 当前 agent（链式）
     */
    public Agent goal(SessionGoal goal) {
        this.goalTracker = new GoalTracker(goal);
        return this;
    }

    /**
     * 直接挂接一个已存在的目标状态机（通常来自 {@link GoalRegistry} 的会话级目标）。
     *
     * <p>与 {@link #goal(SessionGoal)} 的区别：后者为本次 agent 新建状态机，
     * 本方法挂接共享状态机，因此多次请求可以围绕同一目标接力推进。
     *
     * @param tracker 目标状态机，不能为 null
     * @return 当前 agent（链式）
     */
    public Agent attachGoal(GoalTracker tracker) {
        if (tracker == null) {
            throw new IllegalArgumentException("tracker 不能为 null");
        }
        this.goalTracker = tracker;
        return this;
    }

    /**
     * 设置目标循环的轮次硬上限（至少 1）。
     *
     * @param maxRounds 轮次上限
     * @return 当前 agent（链式）
     */
    public Agent goalMaxRounds(int maxRounds) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds 至少为 1: " + maxRounds);
        }
        this.goalMaxRounds = maxRounds;
        return this;
    }

    /**
     * 设置单次暂停等待上限。
     *
     * @param pauseTimeoutMillis 毫秒；小于等于 0 表示一直等到恢复或被取消
     * @return 当前 agent（链式）
     */
    public Agent goalPauseTimeout(long pauseTimeoutMillis) {
        this.goalPauseTimeoutMillis = pauseTimeoutMillis;
        return this;
    }

    /**
     * 是否已绑定目标。
     *
     * @return 已绑定返回 true
     */
    public boolean hasGoal() {
        return goalTracker != null;
    }

    /**
     * 当前目标状态；未绑定目标时返回 null。
     *
     * @return 展示投影；未绑定返回 null
     */
    public GoalStatus goalStatus() {
        GoalTracker tracker = this.goalTracker;
        return tracker == null ? null : tracker.status();
    }

    /**
     * 暂停当前目标。
     *
     * @return 暂停后的展示投影
     */
    public GoalStatus pauseGoal() {
        return requireGoal().pause(System.currentTimeMillis());
    }

    /**
     * 恢复当前目标。
     *
     * @return 恢复后的展示投影
     */
    public GoalStatus resumeGoal() {
        return requireGoal().resume(System.currentTimeMillis());
    }

    /**
     * 取消当前目标。
     *
     * @param reason 取消原因
     * @return 取消后的展示投影
     */
    public GoalStatus cancelGoal(String reason) {
        return requireGoal().cancel(reason, System.currentTimeMillis());
    }

    /**
     * 围绕已绑定的目标反复推进，直到完成/失败/取消或到达轮次上限。
     *
     * <p>每一轮都通过 {@link #run(String)} 真实调用一次 agent；轮次之间检查暂停，
     * 暂停期间不发起模型调用。被中断时把目标转为 CANCELED 并重新打上中断标记。
     *
     * @return 结束时的展示投影
     */
    public GoalStatus runGoal() {
        GoalTracker tracker = requireGoal();
        try {
            return GoalLoop.run(tracker, this::goalTurn, new GoalLoop.MarkerGoalEvaluator(),
                    goalMaxRounds, goalPauseTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[AgentScope] 目标执行被中断，转为取消: {}", tracker.status().title());
            try {
                return tracker.cancel("目标执行被中断", System.currentTimeMillis());
            } catch (IllegalStateException terminal) {
                return tracker.status();
            }
        }
    }

    /**
     * 目标循环的单轮执行体：真实跑一次 agent，并折算本轮的令牌消耗。
     *
     * @param prompt 本轮提示词
     * @return 本轮结果
     */
    private GoalLoop.TurnResult goalTurn(String prompt) {
        AgentResponse response = run(prompt);
        String reply = response != null && response.getOutput() != null ? response.getOutput() : "";
        return new GoalLoop.TurnResult(reply, tokenOf(response));
    }

    /**
     * 从响应里取令牌消耗；缺失时返回 0 而不是猜一个数。
     *
     * @param response agent 响应，可为 null
     * @return 令牌数（未知为 0）
     */
    private static long tokenOf(AgentResponse response) {
        if (response == null || response.getUsage() == null) {
            return 0L;
        }
        AiUsage usage = response.getUsage();
        if (usage.getTotalTokens() != null) {
            return usage.getTotalTokens();
        }
        long input = usage.getInputTokens() == null ? 0L : usage.getInputTokens();
        long output = usage.getOutputTokens() == null ? 0L : usage.getOutputTokens();
        return input + output;
    }

    /**
     * 取得已绑定的目标状态机。
     *
     * @return 目标状态机
     */
    private GoalTracker requireGoal() {
        GoalTracker tracker = this.goalTracker;
        if (tracker == null) {
            throw new IllegalStateException("未绑定目标，请先调用 goal(...)");
        }
        return tracker;
    }

    /**
     * 关闭
    */
    @Override
    public void close() {
        // 先中断：不留下一个还在跑的循环（中断会关掉当前 Harness）
        interrupt();
        cleanupModelRegistrations();
        closeMemoryManager();
        for (ChatClient client : chatClients.values()) {
            try {
                client.close();
            } catch (Exception ig) {
                log.debug("[AgentScope] ChatClient关闭异常", ig);
            }
        }
    }

    /**
     * Definition
    */
    @Override
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
     * 构建运行时上下文，并把当前会话 ID 透传给 AgentScope。 注意：{@code enterPlanMode} / {@code call} / {@code streamEvents} 等方法 直接读取 {@link RuntimeContext#getSessionId()}，仅在 Builder 上设置 defaultSessionId 不足以让这些路径工作，因此必须将其写入上下文。
     * @return 返回值
    */
    private RuntimeContext buildRuntimeContext() {
        RuntimeContext.Builder builder = RuntimeContext.builder();
        if (StringUtils.isNotBlank(this.defaultSessionId)) {
            builder.sessionId(this.defaultSessionId);
        }
        return builder.build();
    }

    private HarnessAgent buildHarness(String agentId, String sysPrompt,
                                      String primaryModelId, List<SubagentDeclaration> declarations,
                                      boolean effectivePlan, int effectivePlanMaxTask,
                                      AgentDebugHook effectiveDebugHook,
                                      AgentPlanHook effectivePlanHook, int effectiveMaxIters,
                                      String feedback, AgentBudgetTracker tracker) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentId)
                .description(definition != null ? definition.getName() : "Agent")
                .sysPrompt(sysPrompt)
                .model(ModelRegistry.resolve(primaryModelId))
                .maxIters(effectiveMaxIters)
                .modelResolver(ModelRegistry::resolve);

        // 设置默认会话标识：进入 Plan / 读取 AgentState 时按 sessionId 定位，缺失会抛 NPE
        if (StringUtils.isNotBlank(this.defaultSessionId)) {
            builder.defaultSessionId(this.defaultSessionId);
        }

        // 设置工作目录：文件类工具 / shell 工具以此为根，相对路径在此目录下解析
        if (StringUtils.isNotBlank(this.workspacePath)) {
            Path ws = Path.of(this.workspacePath).toAbsolutePath().normalize();
            builder.workspace(ws.toString());

            /*
             * 【2026-10-23】project 必须与 workspace **同一个值**（账号级根）。
             *
             * 试过把 project 设成"workspace + 会话id"（想让 shell 也进会话目录），
             * 实测不行：harness 会用 project 覆盖 workspace，随后它的会话目录
             * （WorkspaceManager#getSessionDir = workspace.resolve("<sessionId>/")）
             * 就变成 `<root>/agent/147/147/`，双嵌套（实测 acc-check.txt 落在这一层）。
             *
             * harness 的 shellCwd 是**固定值**（LocalFilesystemWithShell#resolveExecuteCwd
             * 里非空即原样返回），没有按会话解析的入口，所以 shell 只能停在账号级根；
             * 会话级隔离由 harness 自己做（文件、memory、事件都落在 `<root>/<会话id>/`）。
             */
            builder.filesystem(new LocalFilesystemSpec()
                    .project(ws)
                    .projectWritable(true));
        }

        // 禁用 shell 通道：文件工具由路径白名单（PathPolicy，默认 ROOTED）挡住越界，
        // 但 shell 是一条命令字符串，绝对路径读取与外部调用都绕得过去，
        // 对「不准出工作间」的账号只能整条关掉。
        if (!this.shellToolEnabled) {
            builder.disableShellTool();
            log.info("[Agent] 已禁用 shell 工具（调用方判定该账号不允许出工作间）");
        }

        // 工具结果截断：超大目录/文件清单（如 list_files 返回数百文件、多 MB）会撑爆
        // 下一轮模型调用的 prompt 并导致卡死。阈值可经 toolResultEviction(..) 覆盖，
        // 完整内容由底层落盘到 large_tool_results，避免无界清单进入下一轮上下文。
        builder.toolResultEviction(ToolResultEvictionConfig.builder()
                .maxResultChars(this.toolResultMaxChars)
                .previewChars(this.toolResultHeadChars)
                .build());

        // 跨轮恢复 pending 工具：人工确认（plan_exit）后再次调用时，agent 据此恢复待执行工具
        builder.enablePendingToolRecovery(this.pendingToolRecoveryEnabled);

        for (SubagentDeclaration decl : declarations) {
            builder.subagent(decl);
        }

        if (effectivePlan) {
            builder.enablePlanMode(true);
        }

        // 汇总工具：技能工具 + MCP 工具，统一注册到 Toolkit
        List<McpToolDescriptor> mcpTools = Wraps.list();
        if (mcpEnabled && globalMcpManager != null) {
            try {
                List<McpToolDescriptor> descriptors = globalMcpManager.listAllTools();
                if (descriptors != null) {
                    mcpTools.addAll(descriptors);
                }
            } catch (Exception e) {
                log.warn("[AgentScope] 列举 MCP 工具失败: {}", e.getMessage(), e);
            }
        }
        io.agentscope.core.tool.Toolkit toolkit = buildToolkit(skills, skillDescriptions, localTools,
                mcpTools, globalMcpManager, disabledTools);
        if (toolkit != null) {
            builder.toolkit(toolkit);
        }

        // 工具风险闸门中间件：acting 阶段对整批工具调用统一记账 + 裁决，
        // 覆盖 harness 内建 shell / 文件工具（它们不经过 Toolkit.callTool，
        // 包装工具级的闸门拦不到）。MiddlewareChain 从注册列表尾向头折叠、
        // 先注册者在外层，必须先于调试中间件注册才能赶在执行链之前裁决。
        builder.middleware(new AgentToolGateMiddleware(riskAdjudicator(), agentId, tracker,
                java.util.Set.of(ASK_USER_TOOL)));

        // 内容护栏中间件：模型调用前对上下文里的工具返回统一脱敏 / 摘除，
        // 覆盖 harness 内建工具与 schema 工具的全部返回（onModelCall 单点收口）。
        // 注册在工具闸门之后、调试中间件之前：先注册者在外层。
        builder.middleware(new AgentContentGuardMiddleware(effectiveContentGuard(), agentId));

        // 始终注册调试中间件：输出 thinking/输出/轮次/工具全量轨迹日志（hooks 可为 null）
        {
            // 护栏一并交给中间件：工具入参在 ToolCallDelta 里是流式片段，
            // 只有在中间件内才能按 callId 拼完再做「工具名 + 入参」的重复判定；
            // 判定命中就回调 interrupt()，关闭当前 Harness。
            MiddlewareBase middleware = new AgentDebugMiddleware(
                    agentId, effectiveDebugHook, effectivePlanHook, effectivePlanMaxTask, feedback,
                    loopGuard, this::interrupt);
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
        } catch (Exception e) {
            log.debug("[AgentScope] 计算模型标识失败, 使用默认值", e);
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
        log.info("[Agent] 子 Agent [{}] id={}, name={}, maxIters={}", i + 1, subId,
                    StringUtils.defaultString(subDef.getName(), "Unnamed"), subIter);
        }
        log.info("[Agent] ChatClient映射: {} 个", chatClients.size());
        log.info("[Agent] compression: {}, plan: {}, mcp: {}",
                compressionConfig != null && compressionConfig.isEnabled() ? "ON" : "OFF",
                planEnabled, mcpEnabled);
    }

    /**
     * 汇总技能、进程内工具与 MCP 工具，注册成 Agentscope Toolkit。
     *
     * <p>{@code disabled} 中的名称不会注册（先注册者优先：Skill → 进程内工具 → MCP）。
     * 工具级风险裁决与预算记账统一上移到 {@link AgentToolGateMiddleware}（acting 阶段
     * 对整批调用裁决，覆盖 harness 内建工具），桥接工具本身不再内嵌闸门。</p>
     *
     * @param skills           技能处理器
     * @param skillDescriptions 技能描述
     * @param localTools       进程内工具
     * @param mcpTools         MCP 工具描述符
     * @param mcpManager       MCP 管理器
     * @param disabled         需要关闭的工具名
     * @return Toolkit；没有任何工具时返回 null
     */
    static io.agentscope.core.tool.Toolkit buildToolkit(
            Map<String, com.chua.common.support.ai.skill.SkillHandler> skills,
            Map<String, String> skillDescriptions,
            Map<String, AgentTool> localTools,
            List<McpToolDescriptor> mcpTools,
            McpManager mcpManager,
            java.util.Collection<String> disabled) {
        java.util.Set<String> disabledNames = disabled == null
                ? java.util.Collections.emptySet() : new java.util.LinkedHashSet<>(disabled);
        // ask_user 未被禁用时始终可用：即使业务工具为空，agent 也应能向用户提问
        boolean askUserEnabled = !disabledNames.contains(ASK_USER_TOOL);
        boolean empty = (skills == null || skills.isEmpty())
                && (localTools == null || localTools.isEmpty())
                && (mcpTools == null || mcpTools.isEmpty())
                && !askUserEnabled;
        if (empty) {
            return null;
        }
        io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
        java.util.Set<String> registered = new java.util.LinkedHashSet<>();
        if (skills != null) {
            for (Map.Entry<String, com.chua.common.support.ai.skill.SkillHandler> entry : skills.entrySet()) {
                String skillName = entry.getKey();
                if (disabledNames.contains(skillName) || !registered.add(skillName)) {
                    continue;
                }
                String desc = skillDescriptions == null ? null : skillDescriptions.get(skillName);
                toolkit.registerAgentTool(new SkillAgentTool(skillName,
                        desc != null ? desc : "Skill: " + skillName, entry.getValue()));
            }
        }
        if (localTools != null) {
            for (AgentTool tool : localTools.values()) {
                if (disabledNames.contains(tool.getName()) || !registered.add(tool.getName())) {
                    continue;
                }
                toolkit.registerAgentTool(new FunctionAgentTool(tool));
            }
        }
        if (mcpTools != null) {
            for (McpToolDescriptor descriptor : mcpTools) {
                if (descriptor == null || descriptor.getName() == null
                        || disabledNames.contains(descriptor.getName())
                        || !registered.add(descriptor.getName())) {
                    continue;
                }
                toolkit.registerAgentTool(new McpAgentTool(descriptor, mcpManager));
            }
        }
        if (askUserEnabled && registered.add(ASK_USER_TOOL)) {
            // schema-only 外部工具：模型调用即挂起等待用户回答，答案经 answerQuestion 回传
            toolkit.registerSchema(askUserSchema());
        }
        return toolkit;
    }

    /**
     * 将进程内工具执行器桥接为 Agentscope AgentTool。
     *
     * @author CH
     * @since 4.0.0.50
     */
    private static class FunctionAgentTool implements io.agentscope.core.tool.AgentTool {

        /**
         * 通用工具定义
         */
        private final AgentTool tool;

        FunctionAgentTool(AgentTool tool) {
            this.tool = tool;
        }

        @Override
        public String getName() {
            return tool.getName();
        }

        @Override
        public String getDescription() {
            return tool.getDescription() != null ? tool.getDescription() : "Tool: " + tool.getName();
        }

        @Override
        public java.util.Map<String, Object> getParameters() {
            if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                return Wraps.map(tool.getParameters());
            }
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }

        @Override
        public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(
                io.agentscope.core.tool.ToolCallParam param) {
            try {
                Map<String, Object> input = param.getInput() != null
                        ? Wraps.map(param.getInput()) : Map.of();
                Object result = tool.getExecutor().execute(input);
                String text = result == null ? "" : String.valueOf(result);
                return reactor.core.publisher.Mono.just(
                        io.agentscope.core.message.ToolResultBlock.text(text));
            } catch (Exception e) {
                return reactor.core.publisher.Mono.just(
                        io.agentscope.core.message.ToolResultBlock.error(
                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
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

        /**
         * Skill Agent Tool
         * @param description 字符串
         * @param handler 参数
         * @param name 字符串
         * @return 返回值
        */
        SkillAgentTool(String name, String description, com.chua.common.support.ai.skill.SkillHandler handler) {
            this.name = name;
            this.description = description;
              this.handler = handler;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public java.util.Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }

        @Override
        public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(
                io.agentscope.core.tool.ToolCallParam param) {
            try {
                Map<String, Object> input = param.getInput() != null
                        ? Wraps.map(param.getInput()) : Map.of();
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

        /**
         * Mcp Agent Tool
         * @param descriptor 参数
         * @param manager 参数
         * @return 返回值
        */
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
            return Wraps.map(Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(
                io.agentscope.core.tool.ToolCallParam param) {
            try {
                Map<String, Object> input = param.getInput() != null
                        ? Wraps.map(param.getInput()) : Wraps.map();
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

