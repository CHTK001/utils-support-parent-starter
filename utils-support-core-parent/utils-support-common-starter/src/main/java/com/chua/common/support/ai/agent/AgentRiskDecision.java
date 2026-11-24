package com.chua.common.support.ai.agent;

/**
 * 风险裁决结论。
 *
 * <p>三种结论语义：</p>
 * <ul>
 *   <li>{@link Verdict#ALLOW} — 放行，无需人工介入；</li>
 *   <li>{@link Verdict#DENY} — 拒绝执行，把原因回给模型改道；</li>
 *   <li>{@link Verdict#CONFIRM_REQUIRED} — 需人工确认（挂起等待
 *       {@code CONFIRMATION_REQUEST} 流程，或由同步回调裁决）。</li>
 * </ul>
 *
 * @param verdict    裁决结论，不应为 null
 * @param riskLevel  风险等级（low / medium / high / dangerous），可为 null
 * @param reason     裁决理由（回显给模型 / 审计日志），可为 null
 * @param confidence 置信度（AI 裁决时携带），可为 null
 * @author CH
 * @since 4.0.0.52
 */
public record AgentRiskDecision(Verdict verdict, String riskLevel, String reason, Double confidence) {

    /**
     * 裁决结论枚举
     */
    public enum Verdict {
        /**
         * 放行
         */
        ALLOW,
        /**
         * 拒绝执行
         */
        DENY,
        /**
         * 需人工确认
         */
        CONFIRM_REQUIRED
    }

    /**
     * 构造裁决结论（负值校验交由调用方）。
     */
    public AgentRiskDecision {
        if (verdict == null) {
            throw new NullPointerException("verdict 不能为 null");
        }
    }

    /**
     * 构造放行结论。
     *
     * @param reason 理由，可为 null
     * @return 放行结论
     */
    public static AgentRiskDecision allow(String reason) {
        return new AgentRiskDecision(Verdict.ALLOW, null, reason, null);
    }

    /**
     * 构造拒绝结论。
     *
     * @param reason 理由，可为 null
     * @return 拒绝结论
     */
    public static AgentRiskDecision deny(String reason) {
        return new AgentRiskDecision(Verdict.DENY, null, reason, null);
    }

    /**
     * 构造需人工确认结论。
     *
     * @param reason 理由，可为 null
     * @return 需确认结论
     */
    public static AgentRiskDecision confirmRequired(String reason) {
        return new AgentRiskDecision(Verdict.CONFIRM_REQUIRED, null, reason, null);
    }
}
