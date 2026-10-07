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

    @Override
    NativeRuleBuilder id(String id);

    @Override
    NativeRuleBuilder name(String name);

    @Override
    NativeRuleBuilder description(String description);

    @Override
    NativeRuleBuilder message(String message);

    @Override
    NativeRuleBuilder when(String expression);

    @Override
    NativeRuleBuilder when(Predicate<RuleContext> condition);

    @Override
    NativeRuleBuilder then(Consumer<RuleContext> action);

    @Override
    NativeRuleBuilder to(String target);

    @Override
    NativeRuleBuilder var(String name, Object value);

    @Override
    NativeRuleBuilder salience(int salience);

    @Override
    NativeRuleBuilder effect(RuleEffect effect);

    @Override
    NativeRule build();
}
