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
import io.agentscope.core.middleware.MiddlewareBase;
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
 * Agentscope 实现：将项目通用 {@link ChatClient} 桥接到 Agentscope Harness，
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
    /** subagentdefinitions */
    private final List<AgentDefinition> subAgentDefinitions = new ArrayList<>();
    /** 全局 MCP 管理器 */
    /** 全局MCP管理器 */
    private McpManager globalMcpManager;
    /** 全局技能管理器 */
    /** 全局skill管理器 */
    private SkillManager globalSkillManager;
    /** 记忆配置 */
    /** 内存配置 */
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
    /** 调试日志 */
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

    /** 注册的技能列表（名称 → 处理器） */
    private final Map<String, com.chua.common.support.ai.skill.SkillHandler> skills = new java.util.LinkedHashMap<>();
    private final Map<String, String> skillDescriptions = new java.util.LinkedHashMap<>(); // skilldescriptions

    /**
     * 每次 运行 注册的 模型id 追踪，用于清理
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
    /** 对话客户端 */
    public Agent chatClient(ChatClient chatClient) {
        this.chatClients.put(null, chatClient);
        return this;
    }

    @Override
    /** 对话客户端 */
    public Agent chatClient(String agentId, ChatClient chatClient) {
        this.chatClients.put(agentId, chatClient);
        return this;
    }

    @Override
    /** mcp管理器 */
    public Agent mcpManager(McpManager mcpManager) {
        this.globalMcpManager = mcpManager;
        return this;
    }

    @Override
    /** mcp管理器 */
    public Agent mcpManager(String agentId, McpManager mcpManager) {
        if (this.globalMcpManager == null) {
            this.globalMcpManager = mcpManager;
        }
        return this;
    }

    @Override
    /** skill管理器 */
    public Agent skillManager(SkillManager skillManager) {
        this.globalSkillManager = skillManager;
        return this;
    }

    @Override
    /** skill管理器 */
    public Agent skillManager(String agentId, SkillManager skillManager) {
        if (this.globalSkillManager == null) {
            this.globalSkillManager = skillManager;
        }
        return this;
    }

    @Override
    /** subAgent */
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
    /** Mcp */
    public Agent mcp(boolean mcp) {
        this.mcpEnabled = mcp;
        return this;
    }

    @Override
    /** 最大值tooliterations */
    public Agent maxToolIterations(int maxIterations) {
        this.maxToolIterations = maxIterations;
        return this;
    }

    @Override
    /** 内存配置 */
    public Agent memoryConfig(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
        return this;
    }

    @Override
    /** compression配置 */
    public Agent compressionConfig(AgentCompressionConfig compressionConfig) {
        this.compressionConfig = compressionConfig;
        return this;
    }

    @Override
    /** compression配置 */
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
    /** Plan最大值任务 */
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
    /** print配置 */
    public Agent printConfig(boolean printConfig) {
                this.printConfig = printConfig;
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
    /** planhook */
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
    /** 重试退避 */
    public Agent retryBackoff(AgentRetryConfig.BackoffStrategy strategy) {
        this.retryStrategy = strategy;
        return this;
    }

    @Override
    /** 重试base延迟 */
    public Agent retryBaseDelay(long baseDelayMillis) {
        this.retryBaseDelay = baseDelayMillis;
        return this;
    }

    @Override
    /** 重试配置 */
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
    /** 获取subAgent */
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

    /** cleanup模型registrations */
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

        // 注册技能为 Toolkit 工具
        if (!skills.isEmpty()) {
            io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
            for (Map.Entry<String, com.chua.common.support.ai.skill.SkillHandler> entry : skills.entrySet()) {
                final String skillName = entry.getKey();
                final com.chua.common.support.ai.skill.SkillHandler handler = entry.getValue();
                String desc = skillDescriptions.get(skillName);
                toolkit.registerAgentTool(new SkillAgentTool(skillName, desc != null ? desc : "Skill: " + skillName, handler));
            }
            builder.toolkit(toolkit);
        }

        if (effectiveDebugHook != null || effectivePlanHook != null || effectivePlanMaxTask > 0) {
            MiddlewareBase middleware = new AgentDebugMiddleware(
                    agentId, effectiveDebugHook, effectivePlanHook, effectivePlanMaxTask);
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

    /** 记录日志Architecture */
    /**
    * 打印代理架构图（print配置(true) 时随 运行 输出到宿主控制台）。
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
}





