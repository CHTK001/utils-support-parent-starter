package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.chat.DecisionAnswer;
import com.chua.common.support.ai.chat.DecisionQuestion;
import com.chua.common.support.ai.chat.DecisionsClient;
import com.chua.common.support.ai.chat.DecisionsResult;

import java.util.List;
import com.chua.common.support.utils.StringUtils;

/**
 * 基于决策模型（{@link DecisionsClient}）的 AI 辅助风险裁决器。
 *
 * <p>在 {@link ToolRiskPolicy} 本地分级之上引入 AI 裁决，策略按风险等级分层：</p>
 * <ul>
 *   <li><b>低风险</b>：直接放行，不调用决策模型（零额外开销）；</li>
 *   <li><b>危险级</b>：恒定转人工强确认——破坏性操作不允许任何自动放行；</li>
 *   <li><b>高风险</b>：默认转人工确认；仅当 {@code autoApproveHighRisk=true}
 *       时才交给决策模型裁决；</li>
 *   <li><b>中风险</b>：交给决策模型做「是/否」裁决，允许则放行、
 *       拒绝则把理由回给模型改道。</li>
 * </ul>
 *
 * <p><b>失败兜底</b>：决策模型不可用、返回拒绝型答案、答案不明确或调用抛异常时，
 * 一律按 {@link AgentRiskDecision.Verdict#CONFIRM_REQUIRED} 兜底——宁可多问一次人，
 * 也不静默放行或误拒。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class DecisionsAgentRiskAdjudicator implements AgentRiskAdjudicator {

    /**
     * 送给决策模型的问题名（同一请求内唯一，答案按名回填）
     */
    public static final String QUESTION_NAME = "allow_tool_call";

    /**
     * 送审证据中工具入参的最大保留字符数
     */
    public static final int MAX_EVIDENCE_CHARS = 2000;

    /**
     * 决策客户端
     */
    private final DecisionsClient decisionsClient;

    /**
     * 高风险是否允许决策模型放行（默认 false：高风险仍转人工）
     */
    private final boolean autoApproveHighRisk;

    /**
     * 构造裁决器（高风险默认转人工确认）。
     *
     * @param decisionsClient 决策客户端，不应为 null
     */
    public DecisionsAgentRiskAdjudicator(DecisionsClient decisionsClient) {
        this(decisionsClient, false);
    }

    /**
     * 构造裁决器。
     *
     * @param decisionsClient     决策客户端，不应为 null
     * @param autoApproveHighRisk 高风险是否允许决策模型放行
     */
    public DecisionsAgentRiskAdjudicator(DecisionsClient decisionsClient, boolean autoApproveHighRisk) {
        if (decisionsClient == null) {
            throw new NullPointerException("decisionsClient 不能为 null");
        }
        this.decisionsClient = decisionsClient;
        this.autoApproveHighRisk = autoApproveHighRisk;
    }

    @Override
    public AgentRiskDecision adjudicate(AgentRiskRequest request) {
        if (request == null) {
            throw new NullPointerException("request 不能为 null");
        }
        String level = request.effectiveRiskLevel();
        if (ToolRiskPolicy.RISK_LOW.equals(level)) {
            return new AgentRiskDecision(AgentRiskDecision.Verdict.ALLOW, level,
                    "低风险只读操作，自动放行", null);
        }
        if (ToolRiskPolicy.isDangerous(level)) {
            return new AgentRiskDecision(AgentRiskDecision.Verdict.CONFIRM_REQUIRED, level,
                    "危险操作必须人工强确认", null);
        }
        if (ToolRiskPolicy.RISK_HIGH.equals(level) && !autoApproveHighRisk) {
            return new AgentRiskDecision(AgentRiskDecision.Verdict.CONFIRM_REQUIRED, level,
                    "高风险操作需人工确认", null);
        }
        return adjudicateByDecisionModel(request, level);
    }

    /**
     * 调用决策模型做是/否裁决。
     *
     * @param request 裁决请求
     * @param level   风险等级
     * @return 裁决结论（任何失败均兜底为需人工确认）
     */
    private AgentRiskDecision adjudicateByDecisionModel(AgentRiskRequest request, String level) {
        try {
            String evidence = buildEvidence(request, level);
            DecisionQuestion question = DecisionQuestion.noul(QUESTION_NAME,
                    "该工具调用是否应当被允许执行？回答 true 表示允许，false 表示不允许。");
            DecisionsResult result = decisionsClient.decide(evidence, List.of(question));
            DecisionAnswer answer = result == null || result.answers() == null
                    ? null
                    : result.answers().get(QUESTION_NAME);
            if (answer == null || answer.isRefusal()) {
                return new AgentRiskDecision(AgentRiskDecision.Verdict.CONFIRM_REQUIRED, level,
                        "决策模型未给出结论，转人工确认", null);
            }
            Boolean predicate = answer.predicate();
            Double confidence = answer.probability();
            if (Boolean.TRUE.equals(predicate)) {
                return new AgentRiskDecision(AgentRiskDecision.Verdict.ALLOW, level,
                        "决策模型允许执行（风险等级 " + level + "）", confidence);
            }
            if (Boolean.FALSE.equals(predicate)) {
                return new AgentRiskDecision(AgentRiskDecision.Verdict.DENY, level,
                        "决策模型拒绝执行（风险等级 " + level + "）", confidence);
            }
            return new AgentRiskDecision(AgentRiskDecision.Verdict.CONFIRM_REQUIRED, level,
                    "决策模型答案不明确，转人工确认", confidence);
        } catch (Exception e) {
            return new AgentRiskDecision(AgentRiskDecision.Verdict.CONFIRM_REQUIRED, level,
                    "决策调用失败，转人工确认: " + e.getMessage(), null);
        }
    }

    /**
     * 组装送给决策模型的证据文本。
     *
     * @param request 裁决请求
     * @param level   风险等级
     * @return 证据文本
     */
    private String buildEvidence(AgentRiskRequest request, String level) {
        StringBuilder sb = new StringBuilder();
        sb.append("请裁决一次 AI Agent 工具调用是否放行。\n");
        if (request.agentId() != null) {
            sb.append("Agent: ").append(request.agentId()).append('\n');
        }
        if (request.sessionId() != null) {
            sb.append("会话: ").append(request.sessionId()).append('\n');
        }
        sb.append("工具: ").append(request.toolName()).append('\n');
        sb.append("风险等级: ").append(level).append('\n');
        sb.append("入参: ");
        String input = request.toolInput();
        if (StringUtils.isEmpty(input)) {
            sb.append("(无)");
        } else if (input.length() <= MAX_EVIDENCE_CHARS) {
            sb.append(input);
        } else {
            sb.append(input, 0, MAX_EVIDENCE_CHARS).append("...(已截断)");
        }
        return sb.toString();
    }
}
