package com.chua.common.support.ai.agent;

import org.jspecify.annotations.NullUnmarked;

/**
 * Agent 执行模式
 *
 * <p>定义智能体的多种执行编排策略。不同模式对子 Agent 的使用方式不同：
 *
 * <ul>
 *   <li><b>SINGLE</b> — 忽略子 Agent，主 Agent 直接处理</li>
 *   <li><b>ROUTER</b> — 主 Agent LLM 选择一个子 Agent 执行（智能路由）</li>
 *   <li><b>AUTO</b> — 同 ROUTER，LLM 自主决定是否委派</li>
 *   <li><b>PIPELINE</b> — 所有子 Agent 按注册顺序依次执行（任务链）</li>
 *   <li><b>FAN_OUT</b> — 所有子 Agent 并行执行，结果合并</li>
 *   <li><b>PLAN_AND_EXECUTE</b> — 主 Agent 先规划再执行，不委派子 Agent</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/15
 */
@NullUnmarked
public enum AgentMode {

    /**
     * 单 Agent 直连执行
     *
     * <p>忽略所有已注册的子 Agent，主 Agent 直接处理用户输入。
     * 适用于简单场景或不需要多 Agent 协作的任务。
     */
    SINGLE,

    /**
     * 管道模式：多个 Agent 按配置顺序依次执行
     *
     * <p>所有子 Agent 按注册顺序依次执行，前一个的输出作为后一个的输入。
     * 每个子 Agent 使用自己的 ChatClient、MCP 和 Skill。
     *
     * <p>示例流程：需求分析 → 架构设计 → 代码实现 → 测试
     */
    PIPELINE,

    /**
     * 扇出模式：所有 Agent 接收相同输入，结果合并
     *
     * <p>所有子 Agent 并行接收相同输入，各自独立执行，结果合并返回。
     * 适用于多角度分析、对比评估等场景。
     *
     * <p>示例：Java 专家 + Python 专家 + SQL 专家同时分析同一问题
     */
    FAN_OUT,

    /**
     * 路由器模式：主 Agent 智能选择一个子 Agent
     *
     * <p>主 Agent 的 system prompt 包含路由表，LLM 根据用户意图选择最匹配的子 Agent。
     * 框架解析 LLM 输出中的 Agent ID 后，用该子 Agent 的专属配置（ChatClient/MCP/Skill）执行。
     *
     * <p>适用场景：多 Agent 智能分派，如代码任务 → dev-agent，搜索任务 → search-agent
     */
    ROUTER,

    /**
     * 规划执行模式：先规划再执行
     *
     * <p>主 Agent 先制定执行计划，再按计划逐步执行。不委派子 Agent。
     * 适用于复杂任务分解场景。
     */
    PLAN_AND_EXECUTE,

    /**
     * 自动模式：由 Agent 自行选择最佳执行策略
     *
     * <p>与 ROUTER 类似，但 LLM 有更大自由度。可自主决定：
     * 直接回答、调用 MCP 工具、执行 Skill、或委派给子 Agent。
     * 框架同样会解析 LLM 输出中的 Agent ID 并委派执行。
     */
    AUTO
}
