package com.chua.common.support.ai.branch;

import com.chua.common.support.ai.rule.RuleContext;
import com.chua.common.support.ai.rule.RuleException;
import com.chua.common.support.ai.rule.RuleExpression;
import com.chua.common.support.collection.wrap.Wraps;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 分支构造器默认实现。
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class DefaultBranchBuilder implements BranchBuilder {

    /**
     * 分支标识
     */
    private String id;

    /**
     * 分支显示名，默认与标识相同
     */
    private String name;

    /**
     * 分支描述
     */
    private String description;

    /**
     * 命中后对外说明的原因
     */
    private String message;

    /**
     * 条件，OR 关系
     */
    private final List<Predicate<RuleContext>> conditions = Wraps.list();

    /**
     * 命中后按序执行的动作
     */
    private final List<Consumer<RuleContext>> actions = Wraps.list();

    /**
     * 是否已构造过分支，一个构造器只允许构造一条分支
     */
    private boolean built;

    /**
     * 校验构造器尚未使用。
     */
    private void requireUsable() {
        if (built) {
            throw new RuleException("分支构造器已构造过分支，不允许重复使用"
                    + (id == null ? "" : ": " + id));
        }
    }

    @Override
    public BranchBuilder id(String id) {
        requireUsable();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为 null 或空白");
        }
        if (this.id != null && !this.id.equals(id)) {
            throw new RuleException("分支标识已指定为 " + this.id + "，不允许改为 " + id);
        }
        this.id = id;
        if (this.name == null) {
            this.name = id;
        }
        return this;
    }

    @Override
    public BranchBuilder name(String name) {
        requireUsable();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为 null 或空白");
        }
        this.name = name;
        return this;
    }

    @Override
    public BranchBuilder description(String description) {
        requireUsable();
        this.description = description;
        return this;
    }

    @Override
    public BranchBuilder message(String message) {
        requireUsable();
        this.message = message;
        return this;
    }

    @Override
    public BranchBuilder when(String expression) {
        requireUsable();
        RuleExpression compiled = RuleExpression.of(expression);
        conditions.add(compiled::test);
        return this;
    }

    @Override
    public BranchBuilder when(Predicate<RuleContext> condition) {
        requireUsable();
        conditions.add(Objects.requireNonNull(condition, "condition 不能为 null"));
        return this;
    }

    @Override
    public BranchBuilder then(Consumer<RuleContext> action) {
        requireUsable();
        actions.add(Objects.requireNonNull(action, "action 不能为 null"));
        return this;
    }

    @Override
    public Branch build() {
        requireUsable();
        if (id == null || id.isBlank()) {
            throw new RuleException("分支标识未指定，请先调用 id(String)");
        }
        built = true;
        return new Branch(id, name, description, message, conditions, actions);
    }
}
