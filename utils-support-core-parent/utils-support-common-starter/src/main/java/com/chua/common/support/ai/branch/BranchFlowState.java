package com.chua.common.support.ai.branch;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.rule.RuleContext;
import com.chua.common.support.ai.rule.RuleException;
import com.chua.common.support.ai.rule.RuleExpression;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 链式分支共享状态。
 *
 * <p>{@link BranchWhenBuilder} 与 {@link BranchThenBuilder} 只是门面，
 * 真实的可变状态全部收拢在这里：已终结分支、当前分支草稿、序号与终结标记。
 * 每次 {@code when / then} 都返回新的门面实例（单独 builder），
 * 底下共享同一个状态，保证链不断。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
final class BranchFlowState {

    /**
     * 已终结的分支
     */
    final List<Branch> done = Wraps.list();

    /**
     * 当前分支标识
     */
    String id;

    /**
     * 当前分支显示名
     */
    String name;

    /**
     * 当前分支描述
     */
    String description;

    /**
     * 当前分支拦截原因
     */
    String message;

    /**
     * 当前分支条件
     */
    final List<Predicate<RuleContext>> conditions = Wraps.list();

    /**
     * 当前分支动作
     */
    final List<Consumer<RuleContext>> actions = Wraps.list();

    /**
     * 是否已有当前分支
     */
    boolean open;

    /**
     * 是否已终结，不允许再写
     */
    boolean built;

    /**
     * 自动分支计数
     */
    int sequence;

    /**
     * 起新分支。
     */
    void openBranch(Predicate<RuleContext> condition) {
        requireWritable();
        Objects.requireNonNull(condition, "condition 不能为 null");
        closeCurrent();
        open = true;
        conditions.add(condition);
    }

    /**
     * 起新分支（表达式形态）。
     */
    void openBranch(String expression) {
        requireWritable();
        RuleExpression compiled = RuleExpression.of(expression);
        openBranch(compiled::test);
    }

    /**
     * 追加动作到当前分支。
     */
    void addThen(Consumer<RuleContext> action) {
        actions.add(Objects.requireNonNull(action, "action 不能为 null"));
    }

    /**
     * 终结当前分支并收录。
     */
    void closeCurrent() {
        if (!open) {
            return;
        }
        sequence++;
        String resolvedId = id == null ? "branch-" + sequence : id;
        String resolvedName = name == null ? resolvedId : name;
        done.add(new Branch(resolvedId, resolvedName, description, message,
                List.copyOf(conditions), List.copyOf(actions)));
        id = null;
        name = null;
        description = null;
        message = null;
        conditions.clear();
        actions.clear();
        open = false;
    }

    /**
     * 校验尚未终结。
     */
    void requireWritable() {
        if (built) {
            throw new RuleException("分支链已终结，不允许继续写入");
        }
    }

    /**
     * 校验有当前分支。
     */
    void requireOpen() {
        if (!open) {
            throw new RuleException("当前没有待写入的分支，请先调用 when");
        }
    }
}
