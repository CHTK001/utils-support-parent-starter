package com.chua.common.support.ai.rule;

import com.chua.common.support.ai.decision.DecisionQuery;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 决策规则构造器。
 *
 * <p>与 {@link NativeRuleBuilder} 对应：同一类型下 Builder 与 Rule 一一对应，
 * 一个构造器只允许构造一条 {@link AiRule}。实现由 laya 模块经 SPI 提供，
 * 默认底座为 laya-java。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface AiRuleBuilder extends RuleBuilder {

    /**
     * 设置规则 ID。
     *
     * @param id 规则 ID
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder id(String id);

    /**
     * 设置名称。
     *
     * @param name 名称
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder name(String name);

    /**
     * 设置描述。
     *
     * @param description 描述文本
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder description(String description);

    /**
     * 设置命中规则时的提示消息。
     *
     * @param message 提示消息
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder message(String message);

    /**
     * 设置条件表达式。
     *
     * @param expression 条件表达式
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder when(String expression);

    /**
     * 设置条件谓词。
     *
     * @param condition 条件谓词
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder when(java.util.function.Predicate<RuleContext> condition);

    /**
     * 设置命中规则后执行的动作。
     *
     * @param action 命中后的动作
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder then(Consumer<RuleContext> action);

    /**
     * 设置动作的目标。
     *
     * @param target 动作目标
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder to(String target);

    /**
     * 声明规则内变量。
     *
     * @param name 名称
     * @param value 变量值
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder var(String name, Object value);

    /**
     * 设置规则优先级。
     *
     * @param salience 优先级（越大越先执行）
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder salience(int salience);

    /**
     * 设置规则效果。
     *
     * @param effect 规则效果
     * @return 当前构建器，便于链式调用
     */
    @Override
    AiRuleBuilder effect(RuleEffect effect);

    /**
     * 构建最终对象。
     *
     * @return 构建结果
     */
    @Override
    AiRule build();

    /**
     * 声明一个 AI 决策问题。
     *
     * @param query 决策问题
     * @return 自身，便于链式调用
     * @throws NullPointerException 问题为 null 时
     */
    AiRuleBuilder query(DecisionQuery query);

    /**
     * 声明一个带期望结论的 AI 决策问题。
     *
     * @param query 决策问题
     * @param expect 期望的结论标签
     * @return 自身，便于链式调用
     * @throws NullPointerException 问题为 null 时
     */
    AiRuleBuilder query(DecisionQuery query, String expect);

    /**
     * 声明一个带期望结论与置信度阈值的 AI 决策问题。
     *
     * @param query 决策问题
     * @param expect 期望的结论标签
     * @param threshold 置信度阈值
     * @return 自身，便于链式调用
     * @throws NullPointerException 问题为 null 时
     */
    AiRuleBuilder query(DecisionQuery query, String expect, Double threshold);

    /**
     * 批量声明 AI 决策问题。
     *
     * @param queries 决策问题列表
     * @return 自身，便于链式调用
     * @throws NullPointerException 列表为 null 时
     */
    AiRuleBuilder queries(List<AiRuleQuery> queries);

    /**
     * 指定 AI 底座的决策上下文。
     *
     * <p>默认取执行期 input 作为 state；显式指定后优先使用本值。</p>
     *
     * @param state 决策上下文
     * @return 自身，便于链式调用
     */
    AiRuleBuilder state(Object state);

    /**
     * 指定本地模型资产目录。
     *
     * @param modelPath 模型目录，可为 null（使用底座默认）
     * @return 自身，便于链式调用
     */
    AiRuleBuilder modelPath(String modelPath);

    /**
     * 指定推理线程数。
     *
     * @param threads 推理线程数，可为 null（使用底座默认）；非 null 时必须为正数
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 线程数为负或零时
     */
    AiRuleBuilder threads(Integer threads);

    /**
     * 指定校准文件路径。
     *
     * @param calibrationPath 校准文件路径，可为 null（使用模型自带校准）
     * @return 自身，便于链式调用
     */
    AiRuleBuilder calibrationPath(String calibrationPath);
}
