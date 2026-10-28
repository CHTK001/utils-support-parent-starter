package com.chua.common.support.ai.rule;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 自然规则构造器。
 *
 * <p>只能经 {@link Rule#definition(String)}、
 * {@link Rule#builder()} 或 {@link Rule#builder(RuleType)} 获得，
 * 不对外开放构造器。构造器自身<b>不是</b>线程安全的：
 * 它在定义期被单线程使用，产出的 {@link Rule} 才是不可变、可并发求值的那一份。</p>
 *
 * <pre>{@code
 * Rule fraud = Rule.definition("反欺诈拦截")
 *         .salience(100)
 *         .effect(RuleEffect.DENY)
 *         .var("threshold", 0.8D)
 *         .when(ctx -> "true".equals(ctx.get("fraud")))
 *         .when(ctx -> ctx.number("risk", 0.0D) >= 90.0D)
 *         .then(ctx -> ctx.set("拦截原因", "命中反欺诈"))
 *         .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface NativeRuleBuilder extends RuleBuilder {

    /**
     * 设置规则 ID。
     *
     * @param id 规则 ID
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder id(String id);

    /**
     * 设置名称。
     *
     * @param name 名称
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder name(String name);

    /**
     * 设置描述。
     *
     * @param description 描述文本
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder description(String description);

    /**
     * 设置命中规则时的提示消息。
     *
     * @param message 提示消息
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder message(String message);

    /**
     * 设置条件表达式。
     *
     * @param expression 条件表达式
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder when(String expression);

    /**
     * 设置条件谓词。
     *
     * @param condition 条件谓词
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder when(Predicate<RuleContext> condition);

    /**
     * 设置命中规则后执行的动作。
     *
     * @param action 命中后的动作
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder then(Consumer<RuleContext> action);

    /**
     * 设置动作的目标。
     *
     * @param target 动作目标
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder to(String target);

    /**
     * 声明规则内变量。
     *
     * @param name 名称
     * @param value 变量值
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder var(String name, Object value);

    /**
     * 设置规则优先级。
     *
     * @param salience 优先级（越大越先执行）
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder salience(int salience);

    /**
     * 设置规则效果。
     *
     * @param effect 规则效果
     * @return 当前构建器，便于链式调用
     */
    @Override
    NativeRuleBuilder effect(RuleEffect effect);

    /**
     * 构建最终对象。
     *
     * @return 构建结果
     */
    @Override
    NativeRule build();
}
