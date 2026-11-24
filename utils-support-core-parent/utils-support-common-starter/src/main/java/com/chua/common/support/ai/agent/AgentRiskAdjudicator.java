package com.chua.common.support.ai.agent;

/**
 * 工具调用风险裁决器契约。
 *
 * <p>在工具真正执行前回答「这个调用能不能放行」。实现方决定裁决策略：</p>
 * <ul>
 *   <li>纯本地策略：只按 {@link ToolRiskPolicy} 分级映射结论（低风险放行、
 *       其余需确认）；</li>
 *   <li>AI 辅助策略：把调用上下文交给 {@code DecisionsClient} 做是/否裁决，
 *       详见 {@link DecisionsAgentRiskAdjudicator}。</li>
 * </ul>
 *
 * <p>裁决必须「快速且不抛异常」：抛异常等同于拒绝链路，调用方应把异常按
 * {@link AgentRiskDecision#confirmRequired(String)} 兜底处理。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@FunctionalInterface
public interface AgentRiskAdjudicator {

    /**
     * 裁决一次工具调用。
     *
     * @param request 裁决请求，不应为 null
     * @return 裁决结论，不应为 null
     */
    AgentRiskDecision adjudicate(AgentRiskRequest request);

    /**
     * 按最小参数裁决（风险等级现场按 {@link ToolRiskPolicy#classify} 分级）。
     *
     * @param agentId   Agent 标识，可为 null
     * @param toolName  工具名称
     * @param toolInput 工具入参，可为 null
     * @return 裁决结论
     */
    default AgentRiskDecision adjudicate(String agentId, String toolName, String toolInput) {
        return adjudicate(AgentRiskRequest.of(agentId, toolName, toolInput));
    }

    /**
     * 本地默认裁决器：低风险放行，其余一律需人工确认。
     *
     * <p>不做任何外部调用，适合作为未接入决策模型时的兜底策略；
     * 危险操作仍走强确认链路（由风险等级决定）。</p>
     *
     * @return 本地默认裁决器
     */
    static AgentRiskAdjudicator localDefault() {
        return request -> {
            String level = request.effectiveRiskLevel();
            if (ToolRiskPolicy.RISK_LOW.equals(level)) {
                return new AgentRiskDecision(AgentRiskDecision.Verdict.ALLOW, level,
                        "低风险只读操作，自动放行", null);
            }
            return new AgentRiskDecision(AgentRiskDecision.Verdict.CONFIRM_REQUIRED, level,
                    "风险等级 " + level + "，需人工确认", null);
        };
    }
}
