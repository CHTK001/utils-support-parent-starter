package com.chua.common.support.ai.branch;

import com.chua.common.support.ai.rule.RuleContext;
import com.chua.common.support.ai.rule.RuleException;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 分支构造器。
 *
 * <p>链式写法对照 if-elseif-else：</p>
 * <pre>{@code
 * BranchChain branches = BranchChain.of(
 *     Branch.definition("high").when(ctx -> ctx.intOf("risk", 0) >= 90)
 *         .message("高风险拦截").build(),
 *     Branch.definition("medium").when(ctx -> ctx.intOf("risk", 0) >= 60)
 *         .message("中风险复核").build(),
 *     Branch.definition("fallback").build());
 * BranchResult result = branches.execute(Map.of("risk", 95));
 * }</pre>
 *
 * <p>一个构造器只允许构造一条分支：{@link #build()} 成功后再次调用
 * 任何写方法或 {@code build()} 必须抛 {@link RuleException}。
 * 构造器自身<b>不是</b>线程安全的，产出的 {@link Branch} 才不可变。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface BranchBuilder {

    /**
     * 指定分支标识。
     *
     * @param id 分支标识，不可为空白
     * @return 自身，便于链式调用
     */
    BranchBuilder id(String id);

    /**
     * 覆盖分支显示名。
     *
     * @param name 显示名，不可为空白
     * @return 自身，便于链式调用
     */
    BranchBuilder name(String name);

    /**
     * 声明分支描述。
     *
     * @param description 分支描述，可为 null（表示不声明）
     * @return 自身，便于链式调用
     */
    BranchBuilder description(String description);

    /**
     * 声明命中后对外说明的原因。
     *
     * @param message 拦截原因，可为 null（表示不声明）
     * @return 自身，便于链式调用
     */
    BranchBuilder message(String message);

    /**
     * 追加一个条件（表达式形态），与已有条件是 OR 关系。
     *
     * @param expression 表达式文本
     * @return 自身，便于链式调用
     */
    BranchBuilder when(String expression);

    /**
     * 追加一个条件（lambda 形态），与已有条件是 OR 关系。
     *
     * @param condition 条件
     * @return 自身，便于链式调用
     */
    BranchBuilder when(Predicate<RuleContext> condition);

    /**
     * 追加一个动作，按声明顺序执行。
     *
     * @param action 动作
     * @return 自身，便于链式调用
     */
    BranchBuilder then(Consumer<RuleContext> action);

    /**
     * 构造分支。
     *
     * @return 不可变分支
     */
    Branch build();

    /**
     * 追加多个条件（表达式形态），与已有条件是 OR 关系。
     *
     * @param expressions 表达式文本列表
     * @return 自身，便于链式调用
     */
    default BranchBuilder whenAll(List<String> expressions) {
        if (expressions == null) {
            throw new NullPointerException("expressions 不能为 null");
        }
        for (String expression : expressions) {
            when(expression);
        }
        return this;
    }
}
