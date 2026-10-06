package com.chua.common.support.ai.rule;

import com.chua.common.support.ai.decision.DecisionQuery;

/**
 * AI 规则声明的单个决策问题。
 *
 * <p>在 {@link DecisionQuery} 之上补「期望结论」：决策求值后，
 * 答案标签满足期望即视为该问题成立。</p>
 *
 * @param query 决策问题
 * @param expect 期望的结论标签，可为 null（仅要求成功求值即成立）
 * @param threshold 置信度阈值，null 表示不做阈值判断
 * @author CH
 * @since 4.0.0.43
 */
public record AiRuleQuery(DecisionQuery query, String expect, Double threshold) {

    /**
     * 构造 AI 规则决策问题。
     *
     * @param query 决策问题
     * @param expect 期望的结论标签
     * @param threshold 置信度阈值
     * @throws NullPointerException 查询为 null 时
     * @throws IllegalArgumentException 阈值越界时
     */
    public AiRuleQuery {
        if (query == null) {
            throw new NullPointerException("query 不能为 null");
        }
        if (threshold != null && (threshold.isNaN() || threshold < 0.0D || threshold > 1.0D)) {
            throw new IllegalArgumentException("threshold 必须在 0.0~1.0 之间，实际 " + threshold);
        }
        if (expect != null && expect.isBlank()) {
            throw new IllegalArgumentException("expect 不能为空白");
        }
    }

    /**
     * 问题标识。
     *
     * @return 问题标识
     */
    public String id() {
        return query.id();
    }
}
