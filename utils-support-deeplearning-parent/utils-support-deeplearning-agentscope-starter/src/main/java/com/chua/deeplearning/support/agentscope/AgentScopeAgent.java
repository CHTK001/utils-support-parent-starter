package com.chua.deeplearning.support.agentscope;

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
import com.chua.common.support.ai.agent.AgentSystemPromptBuilder;
import com.chua.common.support.ai.agent.ImageDefinition;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.utils.StringUtils;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 实现：将项目通用 {@link ChatClient} 桥接到 AgentScope Harness，
 * 支持多 Agent 编排、思考上限配置与链式调用。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AgentScopeAgent implements Agent {

    /** 日志记录器 */
    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(AgentScopeAgent.class);

    /**
     * 重试相关
     */
    private int maxRetries = 0;
    /** 重试基础延迟 */
    /** 重试basedelay */
    private long retryBaseDelay = 1000;
    /** 重试退避策略 */
    /** 重试策略 */
    private AgentRetryConfig.BackoffStrategy retryStrategy = AgentRetryConfig.BackoffStrategy.EXPONENTIAL;

    /** 代理定义 */
    /** Definition */
    private AgentDefinition definition;
    /** 模式 */
    private AgentMode mode = AgentMode.AUTO;
    /** 最大工具迭代次数 */
    /** 最大值tooliterations */
    private int maxToolIterations = 5;
    /** 聊天客户端映射 */
    private final Map<String, ChatClient> chatClients = new HashMap<>();
    /** 子代理定义列表 */
    /** SUBagentdefinitions */
    private final List<AgentDefinition> subAgentDefinitions = new ArrayList<>();
    /** 全局 MCP 管理器 */
    /** 全局MCP管理器 */
    private McpManager globalMcpManager;
    /** 全局技能管理器 */
    /** 全局skill管理器 */
    private SkillManager globalSkillManager;
    /** 记忆配置 */
    /** Memory配置 */
    private MemoryConfig memoryConfig;
    /** 压缩配置 */
    /** Compression配置 */
    private AgentCompressionConfig compressionConfig;
    /** 是否启用计划 */
    /** Plan是否启用 */
    private boolean planEnabled;
    /** 是否打印配置 */
    /** Print配置 */
    private boolean printConfig = false;
    /** 是否开启调试日志 */
    /** 调试logging */
    private boolean debugLogging = false;
    /** 计划最大任务数 */
    /** Plan最大值任务 */
    private int planMaxTask;
    /** 调试钩子 */
    private AgentDebugHook debugHook;
    /** 计划钩子 */
    /** Plan钩子 */
    private AgentPlanHook planHook;
    /** 是否启用 MCP */
    /** MCP是否启用 */
    private boolean mcpEnabled = true;

    /**
     * 每次 run 注册的 modelId 追踪，用于清理
     */
    private final List<String> registeredModelIds = new ArrayList<>();

    // ==================== Agent 接口覆写 ====================

    @Override
    /** Mode */
    public Agent mode(AgentMode mode) {
        this.mode = mode != null ? mode : AgentMode.AUTO;
        return this;
    }

    @Override
    /** ChatClient */
    public Agent chatClient(ChatClient chatClient) {
        this.chatClients.put(null, chatClient);
        return this;
    }

    @Override
    /** ChatClient */
    public Agent chatClient(String agentId, ChatClient chatClient) {
        this.chatClients.put(agentId, chatClient);
        return this;
    }

    @Override
    /** McpManager */
    public Agent mcpManager(McpManager mcpManager) {
        this.globalMcpManager = mcpManager;
        return this;
    }

    @Override
    /** McpManager */
    public Agent mcpManager(String agentId, McpManager mcpManager) {
        if (this.globalMcpManager == null) {
            this.globalMcpManager = mcpManager;
        }
        return this;
    }

    @Override
    /** SkillManager */
    public Agent skillManager(SkillManager skillManager) {
        this.globalSkillManager = skillManager;
        return this;
    }

    @Override
    /** SkillManager */
    public Agent skillManager(String agentId, SkillManager skillManager) {
        if (this.globalSkillManager == null) {
            this.globalSkillManager = skillManager;
        }
        return this;
    }

    @Override
    /** SubAgent */
    public Agent subAgent(AgentDefinition subAgent) {
        if (subAgent != null) {
            this.subAgentDefinitions.add(subAgent);
        }
        return this;
    }

    @Override
    /** Skill */
    public Agent skill(String name, String description, com.chua.common.support.ai.skill.SkillHandler handler) {
        return this;
    }

    @Override
    /** Mcp */
    public Agent mcp(boolean mcp) {
        this.mcpEnabled = mcp;
        return this;
    }

    @Override
    /** 最大值ToolIterations */
    public Agent maxToolIterations(int maxIterations) {
        this.maxToolIterations = maxIterations;
        return this;
    }

    @Override
    /** MemoryConfig */
    public Agent memoryConfig(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
        return this;
    }

    @Override
    /** CompressionConfig */
    public Agent compressionConfig(AgentCompressionConfig compressionConfig) {
        this.compressionConfig = compressionConfig;
        return this;
    }

    @Override
    /** CompressionConfig */
    public AgentCompressionConfig compressionConfig() {
        return compressionConfig;
    }

    @Override
    /** Plan */
    public Agent plan(boolean plan) {
        this.planEnabled = plan;
        return this;
    }

    @Override
    /** Plan最大值Task */
    public Agent planMaxTask(int planMaxTask) {
        this.planMaxTask = planMaxTask;
        return this;
    }

    @Override
    /** 调试Hook */
    public Agent debugHook(AgentDebugHook debugHook) {
        this.debugHook = debugHook;
        return this;
    }

    @Override
    /** PrintConfig */
    public Agent printConfig(boolean printConfig) {
        if (printConfig) {
            log.info("[Agent] printConfig已弃用，请使用debug(true)启用日志调试");
        }
        return this;
    }

    @Override
    /** 调试 */
    public Agent debug(boolean debug) {
        this.debugLogging = debug;
        return this;
    }

    @Override
    /** PlanHook */
    public Agent planHook(AgentPlanHook planHook) {
        this.planHook = planHook;
        return this;
    }

    @Override
    /** 最大值Retries */
    public Agent maxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    @Override
    /** RetryBackoff */
    public Agent retryBackoff(AgentRetryConfig.BackoffStrategy strategy) {
        this.retryStrategy = strategy;
        return this;
    }

    @Override
    /** RetryBaseDelay */
    public Agent retryBaseDelay(long baseDelayMillis) {
        this.retryBaseDelay = baseDelayMillis;
        return this;
    }

    @Override
    /** RetryConfig */
    public Agent retryConfig(AgentRetryConfig retryConfig) {
        if (retryConfig != null) {
            this.maxRetries = retryConfig.getMaxRetries();
            this.retryStrategy = retryConfig.getBackoffStrategy();
            this.retryBaseDelay = retryConfig.getBaseDelayMillis();
        }
        return this;
    }

    @Override
    /** 获取Definition */
    public AgentDefinition getDefinition() {
        return definition;
    }

    @Override
    /** 获取SubAgents */
    public List<AgentDefinition> getSubAgents() {
        return List.copyOf(subAgentDefinitions);
    }

    @Override
    /** 运行 */
    public AgentResponse run(String input) {
        ChatClient primaryClient = this.chatClients.get(null);
        if (primaryClient == null) {
            throw new IllegalStateException("未配置主 ChatClient，请先调用 .chatClient(...)");
        }

        int effectiveMaxIters = resolveMaxIters(
                this.maxToolIterations,
                definition != null ? definition.getMaxToolIterations() : 0);

        if (debugLogging) {
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

        boolean effectivePlan = this.planEnabled
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

        AgentResponse result = null;
        HarnessAgent harnessAgent = null;

        int attempt = 0;
        int maxRetriesE = maxRetries;
        while (true) {
            attempt++;
            try {
                harnessAgent = buildHarness(agentId, sysPrompt,
                        primaryModelId, declarations, effectivePlan,
                        effectivePlanMaxTask, effectiveDebugHook, effectivePlanHook,
                        effectiveMaxIters);

                Msg userMsg = Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(input).build())
                        .build();

                Msg resultMsg = harnessAgent.call(userMsg, RuntimeContext.empty()).block();
                String output = resultMsg != null ? resultMsg.getTextContent() : "";
                result = AgentResponse.builder()
                        .output(output)
                        .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                        .metadata(Map.of("attempts", attempt))
                        .build();
                break;
            } catch (Exception e) {
                log.error("[AgentScope] run执行失败 attempt={}/{}, agentId={}",
                        attempt, maxRetriesE + 1, agentId, e);
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
                        log.debug("[AgentScope] HarnessAgent关闭异常: agentId={}", agentId, ignored);
                    }
                }
            }
        }

        cleanupModelRegistrations();
        return result;
    }

    @Override
    /** 关闭 */
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
    /** Definition */
    public Agent definition(AgentDefinition definition) {
        this.definition = definition;
        return this;
    }

    /** CleanupModelRegistrations */
    private void cleanupModelRegistrations() {
        registeredModelIds.clear();
    }

    /**
     * 构建Harness
     * @param agentId agentId
     * @param sysPrompt sysPrompt
     * @param primaryModelId primaryModelId
     * @param declarations declarations
     * @param effectivePlan effectivePlan
     * @param effectivePlanMaxTask effectivePlanMaxTask
     * @param effectiveDebugHook effectiveDebugHook
     * @param effectivePlanHook effectivePlanHook
     * @param effectiveMaxIters effectiveMaxIters
     */
    private HarnessAgent buildHarness(String agentId, String sysPrompt,
                                      String primaryModelId, List<SubagentDeclaration> declarations,
                                      boolean effectivePlan, int effectivePlanMaxTask,
                                      AgentDebugHook effectiveDebugHook,
                                      AgentPlanHook effectivePlanHook, int effectiveMaxIters) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentId)
                .description(definition != null ? definition.getName() : "Agent")
                .sysPrompt(sysPrompt)
                .model(ModelRegistry.resolve(primaryModelId))
                .maxIters(effectiveMaxIters)
                .modelResolver(ModelRegistry::resolve);

        for (SubagentDeclaration decl : declarations) {
            builder.subagent(decl);
        }

        if (effectivePlan) {
            builder.enablePlanMode(true);
        }

        if (effectiveDebugHook != null || effectivePlanHook != null || effectivePlanMaxTask > 0) {
            Hook hook = new AgentHookAdapter(
                    agentId, effectiveDebugHook, effectivePlanHook, effectivePlanMaxTask);
            builder.hook(hook);
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

    /** ComputeDelay */
    private long computeDelay(int attempt, int maxRetries) {
        AgentRetryConfig cfg = AgentRetryConfig.builder()
                .backoffStrategy(retryStrategy)
                .baseDelayMillis(retryBaseDelay)
                .maxDelayMillis(30000)
                .build();
        return cfg.calculateDelayMillis(attempt);
    }

    static {
        try {
            Class<?> hookClass = Class.forName("io.agentscope.core.shutdown.AgentScopeJvmShutdownHook");
            java.lang.reflect.Field registeredField = hookClass.getDeclaredField("REGISTERED");
            registeredField.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean registered =
                    (java.util.concurrent.atomic.AtomicBoolean) registeredField.get(null);
            registered.set(true);
        } catch (Throwable ignored) {
        }
    }

    /** 解析最大值Iters */
    private static int resolveMaxIters(int chainValue, int defValue) {
        int effective = chainValue != 0 ? chainValue : (defValue != 0 ? defValue : 5);
        return effective > 0 ? effective : Integer.MAX_VALUE;
    }

    /** 解析ModelId */
    private static String resolveModelId(String prefix, ChatClient chatClient) {
        String suffix = "default";
        try {
            suffix = Integer.toHexString(chatClient.hashCode());
        } catch (Exception ig) {
        }
        return "agentscope:" + prefix + ":" + suffix;
    }

    /** 解析Workspace */
    private String resolveWorkspace() {
        if (memoryConfig != null && memoryConfig.getWorkspace() != null && !memoryConfig.getWorkspace().isBlank()) {
            return memoryConfig.getWorkspace();
        }
        return System.getProperty("java.io.tmpdir", "/tmp") + "agentscope-compression";
    }

    /** 记录日志Architecture */
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
}