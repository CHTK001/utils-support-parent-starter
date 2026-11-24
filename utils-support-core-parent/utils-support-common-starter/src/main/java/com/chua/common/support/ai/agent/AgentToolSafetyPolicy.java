package com.chua.common.support.ai.agent;

/**
 * 工具并行安全策略契约。
 *
 * <p>判定一个工具调用是否可以与其他调用并行执行。默认口径复用
 * {@link ToolRiskPolicy}：只有「低风险只读」调用才可并行；写入、网络、
 * shell 与未知动作一律串行，避免并发写同一资源、重复下单等副作用。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@FunctionalInterface
public interface AgentToolSafetyPolicy {

    /**
     * 判定工具调用是否可并行。
     *
     * @param toolName  工具名称
     * @param toolInput 工具入参原文（可为 null）
     * @return true 表示可与其他调用并行执行
     */
    boolean isParallelSafe(String toolName, String toolInput);

    /**
     * 默认策略：仅低风险只读调用可并行（复用 {@link ToolRiskPolicy} 分级，
     * 避免两套启发式口径漂移）。
     *
     * @return 默认安全策略
     */
    static AgentToolSafetyPolicy readOnlyDefault() {
        return (toolName, toolInput) ->
                ToolRiskPolicy.RISK_LOW.equals(ToolRiskPolicy.classify(toolName, toolInput));
    }

    /**
     * 全部视为可并行（仅在确认工具面全部无副作用时使用）。
     *
     * @return 全放行策略
     */
    static AgentToolSafetyPolicy allowAll() {
        return (toolName, toolInput) -> true;
    }

    /**
     * 全部串行（等价于不启用并行）。
     *
     * @return 全串行策略
     */
    static AgentToolSafetyPolicy serialOnly() {
        return (toolName, toolInput) -> false;
    }

    /**
     * 按工具名白名单判定：名单内可并行，其余串行。
     *
     * @param safeTools 可并行工具名集合
     * @return 白名单策略
     */
    static AgentToolSafetyPolicy allowNames(java.util.Set<String> safeTools) {
        return (toolName, toolInput) -> toolName != null && safeTools.contains(toolName);
    }
}
