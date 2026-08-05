package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.memory.MemoryManager;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.ai.skill.SkillHandler;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.spi.ServiceProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * AI Agent 接口
 *
 * <p>提供统一的智能体执行抽象，支持多 Agent 编排、MCP 工具调用、技能系统和大模型对话。
 * 实现类通过 SPI 机制按 provider 名称注册，调用方通过工厂方法获取实例。
 *
 * <h3>系统架构</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                        Agent 接口                           │
 * │  run() / runAsync()  ← 同步/异步执行入口                     │
 * ├─────────────────────────────────────────────────────────────┤
 * │  配置层（链式调用）                                           │
 * │  .chatClient(client)         ← 主 Agent 的 ChatClient       │
 * │  .chatClient("id", client)   ← 子 Agent 专属 ChatClient     │
 * │  .mcpManager(mcp)            ← 全局 MCP 工具                │
 * │  .mcpManager("id", mcp)      ← 子 Agent 专属 MCP            │
 * │  .skillManager(skill)        ← 全局技能                      │
 * │  .skillManager("id", skill)  ← 子 Agent 专属技能             │
 * │  .subAgent(definition)       ← 注册子 Agent                  │
 * │  .memoryConfig(config)       ← 记忆体配置                    │
 * ├─────────────────────────────────────────────────────────────┤
 * │  执行层                                                      │
 * │  SINGLE        → 主 Agent 直连，忽略子 Agent                  │
 * │  ROUTER        → 主 Agent LLM 选择子 Agent → 框架委派执行     │
 * │  AUTO          → 同 ROUTER，LLM 自由度更大                    │
 * │  PIPELINE      → 所有子 Agent 按序执行（任务链）               │
 * │  FAN_OUT       → 所有子 Agent 并行执行（结果合并）             │
 * │  PLAN_AND_EXECUTE → 主 Agent 先规划再执行                     │
 * ├─────────────────────────────────────────────────────────────┤
 * │  基础设施                                                    │
 * │  ChatClient     ← 大模型通信（OpenAI/Claude/智谱/文心等 15+） │
 * │  McpManager     ← MCP 工具管理（代码分析/搜索/数据库等）      │
 * │  SkillManager   ← 技能管理（天气/翻译/SQL 等自定义能力）      │
 * │  MemoryManager  ← 长期记忆（对话总结/检索/备份）              │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>主从路由流程（ROUTER/AUTO 模式）</h3>
 * <pre>
 *   用户输入
 *     → 主 Agent ChatClient(system=路由表)
 *     → LLM 输出: "使用 dev-agent 执行"
 *     → 框架 extractAgentId() 解析出 "dev-agent"
 *     → 取出 dev-agent 的 ChatClient + MCP + Skill
 *     → 用 dev-agent 的 system prompt 重新执行
 *     → 返回结果
 * </pre>
 *
 * <h3>记忆体流程</h3>
 * <pre>
 *   Agent.run(input)
 *     → initMemoryIfNeeded()           首次创建 MemoryManager + 注册 MCP 插件
 *     → execute(input)                 正常执行
 *     → autoSaveMemory(input, output)  对话结束后 AI 总结 → 持久化存储
 *     → 下次对话 search(keyword)       检索相关记忆 → 注入上下文
 * </pre>
 *
 * <p>多 Agent 编排示例：
     * <pre>{@code
     *   AgentResponse response = Agent.create("agentscope")
     *       .chatClient(mainChatClient)
     *       .subAgent(AgentDefinition.builder()
     *           .id("dev-agent").name("开发助手").description("代码开发专家")
     *           .instruction("你是一名资深开发工程师").build())
     *       .subAgent(AgentDefinition.builder()
     *           .id("search-agent").name("搜索助手").description("联网搜索专家")
     *           .instruction("你是一名信息检索专家").build())
     *       .run("帮我查一下今天北京的天气");
     * }</pre>
     *
     * <p>多模型编排示例（不同子 Agent 使用不同大模型）：
     * <pre>{@code
     *   AgentResponse response = Agent.create("agentscope")
     *       .chatClient(mainChatClient)                    // 主 Agent 使用 gpt-4o
     *       .chatClient("dev-agent", devChatClient)        // 开发 Agent 使用 deepseek-coder
     *       .chatClient("search-agent", searchChatClient)  // 搜索 Agent 使用 gpt-4o-mini
     *       .subAgent(AgentDefinition.builder()
     *           .id("dev-agent").name("开发助手").description("代码开发专家").build())
     *       .subAgent(AgentDefinition.builder()
     *           .id("search-agent").name("搜索助手").description("联网搜索专家").build())
     *       .run("帮我写一个快速排序");
     * }</pre>
 *
 * @author CH
 * @since 2026/07/15
 */
public interface Agent extends AutoCloseable {

    /**
     * 创建指定 provider 的 Agent
     *
     * @param provider 实现提供者名称，如 "agentscope"
     * @return Agent 实例
     */
    static Agent create(String provider) {
        return ServiceProvider.of(Agent.class).getNewExtension(provider);
    }

    /**
     * 创建指定 provider 的 Agent（带配置参数）
     *
     * @param provider 实现提供者名称
     * @param args     创建参数
     * @return Agent 实例
     */
    static Agent create(String provider, Object args) {
        return ServiceProvider.of(Agent.class).getNewExtension(provider, args);
    }

    /**
     * 同步执行 Agent
     *
     * <p>阻塞等待 Agent 执行完成并返回结果。
     *
     * @param input 用户输入
     * @return Agent 执行结果
     */
    AgentResponse run(String input);

    /**
     * 异步执行 Agent
     *
     * <p>非阻塞，立即返回 CompletableFuture。适用于 Web 场景、批量调用等。
     * 默认实现基于 {@link #run(String)} 包装为异步任务。
     * 实现类可覆写以提供真正的异步执行（如使用 ChatClient.chatAsync）。
     *
     * @param input 用户输入
     * @return 异步任务，完成时返回 Agent 执行结果
     */
    default CompletableFuture<AgentResponse> runAsync(String input) {
        return CompletableFuture.supplyAsync(() -> run(input));
    }

    /**
     * 设置执行模式
     *
     * <p>决定 Agent 如何处理用户输入以及如何使用已注册的子 Agent。
     * 不同模式对子 Agent 的使用方式差异很大，选择合适的模式至关重要。
     *
     * <h3>各模式对子 Agent 的处理方式</h3>
     * <pre>
     * ┌──────────────────┬──────────┬──────────────────────────────────────────┐
     * │ 模式              │ 使用子Agent │ 主从处理方式                               │
     * ├──────────────────┼──────────┼──────────────────────────────────────────┤
     * │ SINGLE           │ 否        │ 忽略所有子 Agent，主 Agent 直接处理        │
     * │ ROUTER           │ 是        │ 主 Agent LLM 选择子 Agent → 框架委派执行   │
     * │ AUTO             │ 是        │ 同 ROUTER，LLM 自主决定是否委派            │
     * │ PIPELINE         │ 是        │ 所有子 Agent 按序执行，前输出=后输入        │
     * │ FAN_OUT          │ 是        │ 所有子 Agent 并行执行，结果合并             │
     * │ PLAN_AND_EXECUTE │ 否        │ 主 Agent 先规划再执行，不委派子 Agent       │
     * └──────────────────┴──────────┴──────────────────────────────────────────┘
     * </pre>
     *
     * <h3>执行流程</h3>
     * <pre>
     *   SINGLE:       用户 → [主 Agent] → 输出
     *   ROUTER:       用户 → [主 Agent LLM(路由表)] → 选中子 Agent → [子 Agent] → 输出
     *   AUTO:         用户 → [主 Agent LLM(路由表+工具+技能)] → 自主决策 → 输出
     *   PIPELINE:     用户 → [Agent1] → [Agent2] → [Agent3] → 输出
     *   FAN_OUT:      用户 → [Agent1] ─┐
     *                            [Agent2] ─┼→ 合并 → 输出
     *                            [Agent3] ─┘
     *   PLAN_EXECUTE: 用户 → [规划] → 计划 → [执行] → 输出
     * </pre>
     *
     * <h3>适用场景</h3>
     * <pre>
     *   SINGLE           → 简单问答，无需多 Agent 协作
     *   ROUTER           → 多 Agent 智能分派（代码→dev-agent，搜索→search-agent）
     *   AUTO             → 通用场景，LLM 灵活决策
     *   PIPELINE         → 多步骤任务链（需求→架构→编码→测试）
     *   FAN_OUT          → 多角度分析（Java专家 + Python专家 同时分析）
     *   PLAN_AND_EXECUTE → 复杂任务分解，先拆解再逐步处理
     * </pre>
     *
     * @param mode 执行模式，默认 {@link AgentMode#AUTO}
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent mode(AgentMode mode) {
        return this;
    }

    /**
     * 设置大模型对话客户端
     *
     * <p>Agent 内部使用该客户端与大模型通信。
     *
     * @param chatClient 大模型对话客户端
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent chatClient(ChatClient chatClient) {
        return this;
    }

    /**
     * 设置大模型对话客户端（按 Agent 标识）
     *
     * <p>不同子 Agent 可使用不同的大模型。
     *
     * @param agentId    Agent 标识
     * @param chatClient 大模型对话客户端
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent chatClient(String agentId, ChatClient chatClient) {
        return this;
    }

    /**
     * 设置 MCP 管理器（全局）
     *
     * <p>为所有 Agent 设置共享的 MCP 管理器。
     * 子 Agent 若通过 {@link #mcpManager(String, McpManager)} 配置了专属管理器，则优先使用专属的。
     *
     * @param mcpManager MCP 管理器
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent mcpManager(McpManager mcpManager) {
        return this;
    }

    /**
     * 设置 MCP 管理器（按 Agent 标识）
     *
     * <p>为指定子 Agent 配置独立的 MCP 管理器，该子 Agent 仅能调用此管理器中的工具。
     * 未配置专属管理器的子 Agent 将回退到全局 MCP 管理器。
     *
     * @param agentId    子 Agent 标识
     * @param mcpManager 该子 Agent 专属的 MCP 管理器
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent mcpManager(String agentId, McpManager mcpManager) {
        return this;
    }

    /**
     * 设置技能管理器（全局）
     *
     * <p>为所有 Agent 设置共享的技能管理器。
     * 子 Agent 若通过 {@link #skillManager(String, SkillManager)} 配置了专属管理器，则优先使用专属的。
     *
     * @param skillManager 技能管理器
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent skillManager(SkillManager skillManager) {
        return this;
    }

    /**
     * 设置技能管理器（按 Agent 标识）
     *
     * <p>为指定子 Agent 配置独立的技能管理器，该子 Agent 仅能执行此管理器中的技能。
     * 未配置专属管理器的子 Agent 将回退到全局技能管理器。
     *
     * @param agentId      子 Agent 标识
     * @param skillManager 该子 Agent 专属的技能管理器
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent skillManager(String agentId, SkillManager skillManager) {
        return this;
    }

    /**
     * 注册子 Agent 定义
     *
     * <p>主 Agent 通过此方法注册子 Agent，运行时将自动在 system prompt 中
     * 注入子 Agent 的路由说明，使大模型能够智能分派任务。
     *
     * @param subAgent 子 Agent 定义
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent subAgent(AgentDefinition subAgent) {
        return this;
    }

    /**
     * 注册技能定义
     *
     * @param name        技能名称
     * @param description 技能描述
     * @param handler     技能处理器
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent skill(String name, String description, SkillHandler handler) {
        return this;
    }

    /**
     * 设置是否启用 MCP 工具
     *
     * <p>关闭后 Agent 退化为纯路由模型，不加载任何 MCP 工具和技能工具。
     *
     * @param mcp 是否启用（默认 true）
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent mcp(boolean mcp) {
        return this;
    }

    /**
     * 设置工具调用最大迭代次数
     *
     * <p>控制 LLM → 工具调用 → LLM 循环的最大轮数。
     * 达到上限后即使仍有工具调用请求也直接返回最终结果。
     * 默认 5 轮，设为 0 或负数表示不限制（请谨慎使用）。
     *
     * <p>也可在 {@link AgentDefinition} 中为每个 Agent 独立配置：
     * <pre>{@code
     *   AgentDefinition.builder()
     *       .id("dev-agent")
     *       .maxToolIterations(8)
     *       .build();
     * }</pre>
     *
     * @param maxIterations 最大迭代次数，0 或负数表示不限制
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent maxToolIterations(int maxIterations) {
        return this;
    }

    /**
     * 设置记忆体配置
     *
     * <p>配置记忆体的存储路径、文本长度限制、总结用 ChatClient 等。
     * 未设置时使用默认配置（工作间文件存储）。
     *
     * @param memoryConfig 记忆体配置
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent memoryConfig(MemoryConfig memoryConfig) {
        return this;
    }

    /**
     * 设置最大重试次数
     *
     * <p>Agent 执行失败时自动重试。
     * <ul>
     *   <li>-1 = 无限重试</li>
     *   <li>0 = 不重试（默认）</li>
     *   <li>N = 重试 N 次</li>
     * </ul>
     *
     * @param maxRetries 最大重试次数
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent maxRetries(int maxRetries) {
        return this;
    }

    /**
     * 设置重试退避策略
     *
     * <p>控制重试间隔的退避方式。
     * <ul>
     *   <li>FIXED     — 固定间隔，每次等待相同时间</li>
     *   <li>LINEAR    — 线性递增，第 N 次等待 N × baseDelay</li>
     *   <li>EXPONENTIAL — 指数退避，第 N 次等待 baseDelay × 2^N</li>
     * </ul>
     *
     * @param strategy 退避策略
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent retryBackoff(AgentRetryConfig.BackoffStrategy strategy) {
        return this;
    }

    /**
     * 设置重试退避基础延迟
     *
     * @param baseDelayMillis 基础延迟（毫秒）
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent retryBaseDelay(long baseDelayMillis) {
        return this;
    }

    /**
     * 设置完整重试配置
     *
     * @param retryConfig 重试配置
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent retryConfig(AgentRetryConfig retryConfig) {
        return this;
    }

    /**
     * 获取记忆管理器
     *
     * <p>返回当前 Agent 的记忆管理器实例。若未启用记忆体则返回 null。
     * Agent 实现类在对话结束后应自动调用 {@link MemoryManager#saveFromConversation}
     * 保存对话记忆。
     *
     * @return 记忆管理器，未启用则返回 null
     */
    default MemoryManager getMemoryManager() {
        return null;
    }

    /**
     * 设置上下文压缩配置
     *
     * <p>配置上下文压缩的阈值、偏差矫正轮次、压缩 ChatClient 等。
     * 未设置时压缩功能不生效（默认）。
     *
     * <p>两阶段压缩策略：
     * <ol>
     *   <li>首次压缩 — 消息数达到阈值时保存完整上下文为基线快照，再执行压缩</li>
     *   <li>偏差矫正 — 基线建立后每 N 轮，读取基线快照总结后与当前上下文做偏差矫正</li>
     * </ol>
     *
     * @param compressionConfig 压缩配置
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent compressionConfig(AgentCompressionConfig compressionConfig) {
        return this;
    }

    /**
     * 获取上下文压缩配置
     *
     * <p>返回当前 Agent 的上下文压缩配置。若未设置则返回 null（压缩不生效）。
     *
     * @return 压缩配置，未设置则返回 null
     */
    default AgentCompressionConfig compressionConfig() {
        return null;
    }

    /**
     * 设置是否启用 Plan 规划模式
     *
     * <p>开启后 Agent 可先规划再执行（对应 AgentScope {@code enablePlanMode}）。
     * 默认为 false。
     *
     * @param plan 是否启用规划
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent plan(boolean plan) {
        return this;
    }

    /**
     * 设置规划最大子任务数
     *
     * <p>限制 Plan 模式下单次计划可拆分的最大子任务数量。
     * 0 或负数表示使用框架默认值。
     *
     * @param planMaxTask 最大子任务数
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent planMaxTask(int planMaxTask) {
        return this;
    }

    /**
     * 设置是否打印架构配置（默认开启）。
     *
     * <p>开启后，Agent 运行前（在 {@link #run(String)} 内部）会在控制台输出完整架构图，
     * 包括主 Agent、子 Agent、ChatClient 映射等，便于确认配置正确。在 {@link AgentScopeAgent}
     * 实现中，此信息在实际调用大模型前打印一次。
     *
     * @param printConfig 是否启用，默认 true
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent printConfig(boolean printConfig) {
        return this;
    }

    /**
     * 设置是否开启系统提示词树形打印（默认关闭）。
     *
     * <p>开启后，Agent 运行前（在 {@link #run(String)} 内部）会输出主 Agent 和所有
     * 子 Agent 的系统提示词（system prompt），并以树形结构展示，方便调试和排查问题。
     * 主 Agent 提示词若包含子 Agent，会在 {@link com.chua.common.support.ai.agent.AgentSystemPromptBuilder}
     * 构建阶段自动追加路由表；在 {@link AgentScopeAgent} 中，调用大模型前会一次性输出整棵树。
     *
     * @param debug 是否启用，默认 false
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent debug(boolean debug) {
        return this;
    }

    /**
     * 设置调试 Hook
     *
     * <p>在调用前后、推理、工具执行、错误等阶段回调，用于日志/监控。
     *
     * @param debugHook 调试回调，null 表示不启用
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent debugHook(AgentDebugHook debugHook) {
        return this;
    }

    /**
     * 设置当前 Agent 的定义（主 Agent 元信息）。
     *
     * <p>设置后，{@link #getDefinition()} 可返回此实例，
     * 并且架构图与系统提示词树（在 {@link AgentScopeAgent} 中）会展示更多细节。
     * 若主 Agent 需要被子 Agent 路由表感知，建议使用 {@link AgentDefinition#leader(boolean)} 设为 true。
     *
     * @param definition Agent 定义
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent definition(AgentDefinition definition) {
        return this;
    }

    /**
     * 设置规划 Hook
     *
     * <p>在进入规划、写出计划、退出规划等阶段回调。
     *
     * @param planHook 规划回调，null 表示不启用
     * @return 当前 Agent 实例，支持链式调用
     */
    default Agent planHook(AgentPlanHook planHook) {
        return this;
    }

    /**
     * 获取已注册的子 Agent 定义列表
     *
     * <p>返回所有通过 {@link #subAgent(AgentDefinition)} 注册的子 Agent。
     * 实现类应维护内部列表并返回不可修改的副本。
     *
     * @return 子 Agent 定义列表，若无注册则返回空列表
     */
    default List<AgentDefinition> getSubAgents() {
        return List.of();
    }

    /**
     * 获取主 Agent 定义
     *
     * <p>返回当前 Agent 的定义信息。实现类应在构造时保存 AgentDefinition 并返回。
     *
     * @return Agent 定义，若未设置则返回 null
     */
    default AgentDefinition getDefinition() {
        return null;
    }

    /**
     * 获取完整的系统提示词
     *
     * <p>返回 AgentDefinition 构造时自动生成的 system prompt。
     * 主 Agent 的 prompt 包含子 Agent 路由表，子 Agent 的 prompt 为其原始 instruction。
     * Agent 实现类在与 ChatClient 通信时应使用此方法获取 system prompt。
     *
     * @return 完整的系统提示词，未设置 definition 时返回 null
     */
    default String getSystemPrompt() {
        AgentDefinition def = getDefinition();
        return def != null ? def.getSystemPrompt() : null;
    }

    /**
     * 关闭 Agent，释放资源
     */
    @Override
    default void close() {
    }
}