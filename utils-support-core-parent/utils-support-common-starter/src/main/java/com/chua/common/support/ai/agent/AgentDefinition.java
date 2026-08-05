package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.skill.SkillManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 定义
 *
 * <p>描述一个 Agent 的元信息，包括标识、角色、指令描述、关联大模型和工具配置。
 * 支持通过 Builder 模式构建，主 Agent 可注册子 Agent 并自动生成路由提示词。
 *
 * <h3>每个 Agent 可独立配置</h3>
 * <pre>
 *   instruction  → system prompt，决定 Agent 的行为和能力边界
 *   chatClient   → 通过 Agent.chatClient("id", client) 注入，决定使用哪个大模型
 *   mcpManager   → 决定可调用哪些 MCP 工具（代码分析/搜索/数据库等）
 *   skillManager → 决定可执行哪些技能（天气/翻译/SQL 等）
 *   memoryConfig → 决定是否启用长期记忆及存储配置
 * </pre>
 *
 * <h3>system prompt 自动生成机制</h3>
 * <p>构造时自动计算：
 * <ul>
 *   <li>主 Agent（leader=true）+ 有子 Agent → instruction + 路由表 + 路由规则</li>
 *   <li>主 Agent 无子 Agent → 仅 instruction</li>
 *   <li>子 Agent → 仅 instruction（子 Agent 的 system prompt）</li>
 * </ul>
 * <p>通过 {@link #getSystemPrompt()} 获取完整 prompt，Agent 实现类直接传给 ChatClient。
 *
 * <h3>使用示例：
 * <pre>{@code
 *   // 构建子 Agent（独立 MCP + 独立 Skill）
 *   AgentDefinition devAgent = AgentDefinition.builder()
 *       .id("dev-agent")
 *       .name("开发助手")
 *       .description("代码开发专家，擅长 Java/Python")
 *       .instruction("你是一名资深开发工程师，可以编写和审查代码")
 *       .mcpManager(devMcpManager)      // 开发专用 MCP（代码分析工具等）
 *       .skillManager(devSkillManager)   // 开发专用技能（编译、测试等）
 *       .build();
 *
 *   // 构建主 Agent，自动注入子 Agent 路由描述
 *   AgentDefinition leader = AgentDefinition.builder()
 *       .id("leader")
 *       .name("主调度")
 *       .leader(true)
 *       .instruction("你是主调度 Agent，负责将任务分配给合适的子 Agent")
 *       .subAgent(devAgent)
 *       .subAgent(searchAgent)
 *       .build();
 * }</pre>
 *
 * @author CH
 * @since 2026/07/15
 */
public class AgentDefinition {

    /** Agent 标识 */
    /**
     * 标识
     */
    private final String id;

    /** Agent 名称 */
    /**
     * 名称
     */
    private final String name;

    /** Agent 描述 */
    /**
     * 描述
     */
    private final String description;

    /** Agent 角色 */
    /**
     * 角色
     */
    private final String role;

    /** 系统指令（即 system prompt） */
    private final String instruction;

    /** 是否允许启用规划 */
    private final boolean planning;

    /** 是否启用 MCP 工具（默认开启，关闭后 Agent 退化为纯路由模型） */
    private final boolean mcp;

    /** 是否为主 Agent */
    private final boolean leader;

    /** 已注册的子 Agent 定义列表（仅主 Agent 使用） */
    private final List<AgentDefinition> agents;

    /** Agent 专属的 MCP 管理器（子 Agent 可独立配置工具集） */
    private final McpManager mcpManager;

    /** Agent 专属的技能管理器（子 Agent 可独立配置技能集） */
    private final SkillManager skillManager;

    /** 自动生成的完整系统提示词（instruction + 子 Agent 路由描述） */
    private final String systemPrompt;

    /** 是否启用记忆体（默认开启） */
    private final boolean memory;

    /** 记忆体配置 */
    private final MemoryConfig memoryConfig;

    /** 重试配置 */
    private final AgentRetryConfig retryConfig;

    /**
     * 工具调用最大迭代次数（思考上限）
     *
     * <p>控制 LLM → 工具调用 → LLM 循环的最大轮数。达到上限后即使仍有工具调用请求
     * 也直接返回最终结果。0 或负数表示不限制（请谨慎使用）。默认 5 轮。
     */
    private final int maxToolIterations;

    /** 上下文压缩配置 */
    private final AgentCompressionConfig compressionConfig;

    /**
     * 规划最大子任务数
     *
     * <p>Plan 模式下单次计划可拆分的最大子任务数。0 或负数表示使用框架默认值。
     */
    private final int planMaxTask;

    /** 调试 Hook */
    private final AgentDebugHook debugHook;

    /** 规划 Hook */
    private final AgentPlanHook planHook;

    /**
     * 全参构造方法
     *
     * @param id                Agent 标识
     * @param name              Agent 名称
     * @param description       Agent 描述
     * @param role              Agent 角色
     * @param instruction       系统指令
     * @param planning          是否允许启用规划
     * @param mcp               是否启用 MCP 工具
     * @param leader            是否为主 Agent
     * @param agents            已注册的子 Agent 定义列表
     * @param mcpManager        Agent 专属的 MCP 管理器
     * @param skillManager      Agent 专属的技能管理器
     * @param memory            是否启用记忆体
     * @param memoryConfig      记忆体配置
     * @param retryConfig       重试配置
     * @param maxToolIterations 工具调用最大迭代次数（思考上限）
     * @param compressionConfig 上下文压缩配置
     * @param planMaxTask       规划最大子任务数
     * @param debugHook         调试 Hook
     * @param planHook          规划 Hook
     */
    public AgentDefinition(String id, String name, String description, String role,
                            String instruction, boolean planning, boolean mcp, boolean leader,
                            List<AgentDefinition> agents,
                            McpManager mcpManager, SkillManager skillManager,
                            boolean memory, MemoryConfig memoryConfig,
                            AgentRetryConfig retryConfig, int maxToolIterations,
                            AgentCompressionConfig compressionConfig,
                            int planMaxTask, AgentDebugHook debugHook, AgentPlanHook planHook,
                            String systemPrompt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.role = role;
        this.instruction = instruction;
        this.planning = planning;
        this.mcp = mcp;
        this.leader = leader;
        this.agents = agents != null ? List.copyOf(agents) : List.of();
        this.mcpManager = mcpManager;
        this.skillManager = skillManager;
        this.memory = memory;
        this.memoryConfig = memoryConfig;
        this.retryConfig = retryConfig;
        this.maxToolIterations = maxToolIterations;
        this.compressionConfig = compressionConfig;
        this.planMaxTask = planMaxTask;
        this.debugHook = debugHook;
        this.planHook = planHook;

        // 若显式设置了 systemPrompt 则直接使用，否则按规则自动生成
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            this.systemPrompt = systemPrompt;
        } else if (leader && !this.agents.isEmpty()) {
            this.systemPrompt = AgentSystemPromptBuilder.build(instruction, this.agents);
        } else {
            this.systemPrompt = instruction;
        }
    }

    /**
     * 创建 Builder 实例
     *
     * @return 新的 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getRole() {
        return role;
    }

    public String getInstruction() {
        return instruction;
    }

    public boolean isPlanning() {
        return planning;
    }

    /**
     * MCP 工具是否启用
     *
     * @return false 表示 Agent 退化为纯路由模型，不加载任何 MCP 工具
     */
    public boolean isMcp() {
        return mcp;
    }

    public boolean isLeader() {
        return leader;
    }

    /**
     * 获取已注册的子 Agent 定义列表
     *
     * @return 子 Agent 列表，不可修改
     */
    public List<AgentDefinition> getAgents() {
        return agents;
    }

    /**
     * 获取 Agent 专属的 MCP 管理器
     *
     * @return MCP 管理器，未配置则返回 null
     */
    public McpManager getMcpManager() {
        return mcpManager;
    }

    /**
     * 获取 Agent 专属的技能管理器
     *
     * @return 技能管理器，未配置则返回 null
     */
    public SkillManager getSkillManager() {
        return skillManager;
    }

    /**
     * 是否启用记忆体
     *
     * <p>默认开启。开启后 Agent 可通过 MCP 插件搜索、保存和管理长期记忆。
     *
     * @return 是否启用
     */
    public boolean isMemory() {
        return memory;
    }

    /**
     * 获取记忆体配置
     *
     * @return 记忆体配置，未设置则返回 null
     */
    public MemoryConfig getMemoryConfig() {
        return memoryConfig;
    }

    /**
     * 获取重试配置
     *
     * @return 重试配置，未设置则返回 null
     */
    public AgentRetryConfig getRetryConfig() {
        return retryConfig;
    }

    /**
     * 获取工具调用最大迭代次数（思考上限）
     *
     * @return 最大迭代次数，0 或负数表示不限制
     */
    public int getMaxToolIterations() {
        return maxToolIterations;
    }

    /**
     * 获取上下文压缩配置
     *
     * @return 压缩配置，未设置则返回 null
     */
    public AgentCompressionConfig getCompressionConfig() {
        return compressionConfig;
    }

    /**
     * 获取规划最大子任务数
     *
     * @return 最大子任务数，0 或负数表示使用框架默认
     */
    public int getPlanMaxTask() {
        return planMaxTask;
    }

    /**
     * 获取调试 Hook
     *
     * @return 调试 Hook，未设置则返回 null
     */
    public AgentDebugHook getDebugHook() {
        return debugHook;
    }

    /**
     * 获取规划 Hook
     *
     * @return 规划 Hook，未设置则返回 null
     */
    public AgentPlanHook getPlanHook() {
        return planHook;
    }

    /**
     * 获取完整的系统提示词
     *
     * <p>构造时自动生成，包含：
     * <ul>
     *   <li>主 Agent 的原始 instruction</li>
     *   <li>子 Agent 路由表（含 Agent ID、名称、描述）</li>
     *   <li>路由规则说明</li>
     * </ul>
     *
     * <p>非主 Agent 或无子 Agent 时，返回值等于 {@link #getInstruction()}。
     * Agent 实现类应直接调用此方法获取 system prompt 传给 ChatClient，
     * 无需手动拼接。
     *
     * @return 完整的系统提示词
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Agent 定义构建器
     *
     * <p>支持链式设置所有属性，并可通过 {@link #subAgent(AgentDefinition)} 注册子 Agent。
     */
    public static class Builder {

        /**
         * 标识
         */
        private String id;
        /**
         * 名称
         */
        private String name;
        /**
         * 描述
         */
        private String description;
        /**
         * 角色
         */
        private String role;
        private String instruction;
        private String systemPrompt;
        private boolean planning;
        private boolean mcp = true;
        private boolean leader;
        private final List<AgentDefinition> agents = new ArrayList<>();
        private McpManager mcpManager;
        private SkillManager skillManager;
        private boolean memory = true;
        private MemoryConfig memoryConfig;
        private AgentRetryConfig retryConfig;
        private int maxToolIterations;
        private AgentCompressionConfig compressionConfig;
        private int planMaxTask;
        private AgentDebugHook debugHook;
        private AgentPlanHook planHook;

        Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        /**
         * 设置系统指令（即 system prompt）
         *
         * <p>主 Agent 的 instruction 应描述路由策略；
         * 子 Agent 的 instruction 应描述其专业能力和行为规范。
         *
         * @param instruction 系统提示词内容
         * @return 当前 Builder
         */
        public Builder instruction(String instruction) {
            this.instruction = instruction;
            return this;
        }

        /**
         * 显式设置 Agent 的 system prompt。
         *
         * <p>若不设置，框架将自动生成：
         * <ul>
         *   <li>主 Agent（leader=true）且有子 Agent → instruction + 路由表</li>
         *   <li>其他情况 → 直接使用 instruction</li>
         * </ul>
         *
         * @param systemPrompt 系统提示词
         * @return 当前 Builder
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder planning(boolean planning) {
            this.planning = planning;
            return this;
        }

        /**
         * 设置是否启用 Plan 规划模式（同 {@link #planning(boolean)}）
         *
         * @param plan 是否启用
         * @return 当前 Builder
         */
        public Builder plan(boolean plan) {
            this.planning = plan;
            return this;
        }

        /**
         * 设置规划最大子任务数
         *
         * @param planMaxTask 最大子任务数，0 或负数表示使用框架默认
         * @return 当前 Builder
         */
        public Builder planMaxTask(int planMaxTask) {
            this.planMaxTask = planMaxTask;
            return this;
        }

        /**
         * 设置调试 Hook
         *
         * @param debugHook 调试回调
         * @return 当前 Builder
         */
        public Builder debugHook(AgentDebugHook debugHook) {
            this.debugHook = debugHook;
            return this;
        }

        /**
         * 设置规划 Hook
         *
         * @param planHook 规划回调
         * @return 当前 Builder
         */
        public Builder planHook(AgentPlanHook planHook) {
            this.planHook = planHook;
            return this;
        }

        /**
         * 设置是否启用 MCP 工具
         *
         * <p>关闭后 Agent 退化为纯路由模型，不加载任何 MCP 工具，
         * system prompt 中也不会出现 MCP 工具描述。
         *
         * @param mcp 是否启用（默认 true）
         * @return 当前 Builder
         */
        public Builder mcp(boolean mcp) {
            this.mcp = mcp;
            return this;
        }

        public Builder leader(boolean leader) {
            this.leader = leader;
            return this;
        }

        /**
         * 设置 Agent 专属的 MCP 管理器
         *
         * <p>子 Agent 配置独立的 MCP 后，仅能调用该管理器注册的工具，
         * 不会受主 Agent 或其他子 Agent 的工具集影响。
         *
         * @param mcpManager MCP 管理器
         * @return 当前 Builder
         */
        public Builder mcpManager(McpManager mcpManager) {
            this.mcpManager = mcpManager;
            return this;
        }

        /**
         * 设置 Agent 专属的技能管理器
         *
         * <p>子 Agent 配置独立的 SkillManager 后，仅能执行该管理器注册的技能。
         *
         * @param skillManager 技能管理器
         * @return 当前 Builder
         */
        public Builder skillManager(SkillManager skillManager) {
            this.skillManager = skillManager;
            return this;
        }

        /**
         * 设置是否启用记忆体
         *
         * <p>默认开启。开启后 Agent 可通过 MCP 插件管理长期记忆。
         *
         * @param memory 是否启用
         * @return 当前 Builder
         */
        public Builder memory(boolean memory) {
            this.memory = memory;
            return this;
        }

        /**
         * 设置记忆体配置
         *
         * <p>可自定义存储路径、文本长度限制、总结用 ChatClient 等。
         * 未设置时使用默认配置（工作间文件存储，最大 2000 字符，500 条上限）。
         *
         * @param memoryConfig 记忆体配置
         * @return 当前 Builder
         */
        public Builder memoryConfig(MemoryConfig memoryConfig) {
            this.memoryConfig = memoryConfig;
            return this;
        }

        /**
         * 设置上下文压缩配置
         *
         * <p>配置上下文压缩的触发阈值、偏差矫正轮次、压缩 ChatClient 等。
         * 未设置时压缩功能不生效（默认）。
         *
         * @param compressionConfig 压缩配置
         * @return 当前 Builder
         */
        public Builder compressionConfig(AgentCompressionConfig compressionConfig) {
            this.compressionConfig = compressionConfig;
            return this;
        }

        /**
         * 设置重试配置
         *
         * @param retryConfig 重试配置
         * @return 当前 Builder
         */
        public Builder retryConfig(AgentRetryConfig retryConfig) {
            this.retryConfig = retryConfig;
            return this;
        }

        /**
         * 设置最大重试次数（便捷方法）
         *
         * @param maxRetries 最大重试次数，-1=无限，0=不重试
         * @return 当前 Builder
         */
        public Builder maxRetries(int maxRetries) {
            if (this.retryConfig == null) {
                this.retryConfig = AgentRetryConfig.builder().build();
            }
            this.retryConfig.setMaxRetries(maxRetries);
            return this;
        }

        /**
         * 设置工具调用最大迭代次数（思考上限）
         *
         * <p>控制 LLM → 工具调用 → LLM 循环的最大轮数。达到上限后即使仍有工具调用请求
         * 也直接返回最终结果。0 或负数表示不限制（请谨慎使用）。默认 5 轮。
         *
         * @param maxToolIterations 最大迭代次数
         * @return 当前 Builder
         */
        public Builder maxToolIterations(int maxToolIterations) {
            this.maxToolIterations = maxToolIterations;
            return this;
        }

        /**
         * 注册子 Agent 定义
         *
         * @param subAgent 子 Agent 定义
         * @return 当前 Builder
         */
        public Builder subAgent(AgentDefinition subAgent) {
            this.agents.add(subAgent);
            return this;
        }

        /**
         * 批量注册子 Agent 定义
         *
         * @param subAgents 子 Agent 定义列表
         * @return 当前 Builder
         */
        public Builder subAgents(List<AgentDefinition> subAgents) {
            this.agents.addAll(subAgents);
            return this;
        }

        /**
         * 构建 AgentDefinition 实例
         *
         * @return AgentDefinition 对象
         */
        public AgentDefinition build() {
            return new AgentDefinition(id, name, description, role,
                    instruction, planning, mcp, leader, agents,
                    mcpManager, skillManager, memory, memoryConfig, retryConfig, maxToolIterations, compressionConfig,
                    planMaxTask, debugHook, planHook, systemPrompt);
        }
    }
}