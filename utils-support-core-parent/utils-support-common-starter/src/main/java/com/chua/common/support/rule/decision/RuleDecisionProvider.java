package com.chua.common.support.rule.decision;

import com.chua.common.support.rule.RuleContext;

/**
 * 决策实现 SPI。
 *
 * <p>规则文件里的 {@code decision} 节点只声明「要问哪张决策表、期望什么结果」，
 * 具体怎么算出结果由本接口的实现负责。规则引擎不认识任何具体实现，
 * 因此新增一种决策方式（决策表、评分卡、远程风控、模型推理……）
 * 只需要新增一个实现类并注册进 SPI，不必改动引擎。</p>
 *
 * <h3>为什么把决策抽出 SPI</h3>
 * <p>规则条件本身是「布尔判定」，而决策要回答的是「结果是哪个枚举值」，
 * 并且往往需要多行候选、优先级、命中策略这类规则表达式表达不了的语义。
 * 硬编码进引擎会让引擎越来越大，也没法让业务方自带决策逻辑。</p>
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li><b>必须无状态且线程安全</b>：同一张决策表会被多个会话并发求值；</li>
 *   <li>求值失败应抛 {@link com.chua.common.support.rule.RuleException}，
 *       不要返回 null —— {@code null} 无法区分「决策结果是 null」
 *       和「决策算不出来」，前者是合法业务结果；</li>
 *   <li>实现应尊重 {@link com.chua.common.support.rule.RuleContext#halt()}，
 *       便于在决策过程中提前终止本轮推理。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface RuleDecisionProvider {

    /**
     * 本实现能处理哪些决策表。
     *
     * <p>一张表只交给第一个返回 true 的实现，避免多个实现互相抢。
     * 典型写法是按表的 {@code kind} 字段分派。</p>
     *
     * @param table 决策表定义
     * @return 能处理返回 true
     */
    boolean supports(DecisionTable table);

    /**
     * 对给定上下文求值，返回决策结果。
     *
     * @param table   决策表定义
     * @param context 规则上下文
     * @return 决策结果，不可为 null 之外的「算不出来」语义
     */
    Object decide(DecisionTable table, RuleContext context);
}
