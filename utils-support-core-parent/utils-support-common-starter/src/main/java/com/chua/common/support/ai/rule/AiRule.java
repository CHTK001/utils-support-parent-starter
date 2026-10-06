package com.chua.common.support.ai.rule;

import java.util.List;
import java.util.Map;

/**
 * AI 决策规则。
 *
 * <p>{@link Rule} 的一种实现：条件不再是进程内 lambda / 表达式，
 * 而是一批声明式 {@code DecisionQuery}，执行时由 AI 概率决策自动求值
 * （默认 laya-java 本地推理，在 laya 模块实现本接口的 SPI）。
 * 决策答案展平为 {@code <id>/<id>.confidence/<id>.probabilities} 后
 * 再按阈值判定是否命中，命中后执行 {@code then} 动作。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface AiRule extends Rule {

    /**
     * 本规则声明的决策问题。
     *
     * @return 不可变问题列表
     */
    List<AiRuleQuery> queries();

    /**
     * AI 底座名，缺省为 {@code laya}。
     *
     * @return 底座名
     */
    default String provider() {
        return "laya";
    }

    /**
     * AI 底座实例。
     *
     * <p>默认按 {@link #provider()} 经 SPI 查找 {@code DecisionClient}；
     * 实现方可覆写为常驻客户端或自定义装配。</p>
     *
     * @return 决策底座
     */
    default com.chua.common.support.ai.decision.DecisionProvider client() {
        return com.chua.common.support.ai.decision.DecisionClient.create(
                com.chua.common.support.ai.decision.DecisionSetting.builder()
                        .provider(provider()).build());
    }

    /**
     * AI 决策上下文，null 表示使用执行期 input。
     *
     * @return 决策上下文
     */
    default Object state() {
        return null;
    }

    /**
     * AI 底座配置，键值由实现方解释。
     *
     * @return 不可变配置表
     */
    default Map<String, Object> settings() {
        return Map.of();
    }

    /**
     * 命中阈值判定。
     *
     * <p>默认语义：任一问题的结论标签满足期望即命中，
     * 实现方可覆写为阈值 / 加权等判定。</p>
     *
     * @param answers 标识到答案标签的映射
     * @param flattened 展平后的决策变量
     * @return 命中返回 true
     */
    boolean hit(Map<String, String> answers, Map<String, Object> flattened);

    @Override
    default RuleType type() {
        return RuleType.AI;
    }
}
