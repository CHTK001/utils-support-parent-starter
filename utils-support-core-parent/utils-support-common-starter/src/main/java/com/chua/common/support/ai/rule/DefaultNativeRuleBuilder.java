package com.chua.common.support.ai.rule;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 自然规则构造器默认实现。
 *
 * <p>经 SPI 以 {@code native} 名称注册，供
 * {@link Rule#definition(String)}、{@link Rule#builder()}、
 * {@link Rule#builder(RuleType)} 装配。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("native")
public final class DefaultNativeRuleBuilder implements NativeRuleBuilder {

    /**
     * 规则标识
     */
    private String id;

    /**
     * 规则显示名，默认与标识相同
     */
    private String name;

    /**
     * 规则描述
     */
    private String description;

    /**
     * 命中后对外说明的原因
     */
    private String message;

    /**
     * 优先级
     */
    private int salience;

    /**
     * 结果语义
     */
    private RuleEffect effect = RuleEffect.NONE;

    /**
     * 顶层条件，OR 关系
     */
    private final List<Predicate<RuleContext>> conditions = Wraps.list();

    /**
     * 命中后按序执行的动作
     */
    private final List<Consumer<RuleContext>> actions = Wraps.list();

    /**
     * 下一条规则的标识
     */
    private String target;

    /**
     * 是否已指定过下一条规则
     */
    private boolean targetSet;

    /**
     * 规则声明的变量
     */
    private final Map<String, Object> variables = Wraps.linkedMap();

    /**
     * 是否已构造过规则，一个构造器只允许构造一条规则
     */
    private boolean built;

    /**
     * SPI 初始化用的无参构造器。
     */
    public DefaultNativeRuleBuilder() {
    }

    /**
     * 带规则标识的构造器。
     *
     * @param id 规则标识，同时作为默认显示名
     * @throws IllegalArgumentException 规则标识为 null 或空白时
     */
    public DefaultNativeRuleBuilder(String id) {
        id(id);
    }

    @Override
    public RuleType type() {
        return RuleType.NATIVE;
    }

    /**
     * 校验构造器尚未使用。
     *
     * @throws RuleException 构造器已构造过规则时
     */
    private void requireUsable() {
        if (built) {
            throw new RuleException("规则构造器已构造过规则，不允许重复使用"
                    + (id == null ? "" : ": " + id));
        }
    }

    @Override
    public NativeRuleBuilder id(String id) {
        requireUsable();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为 null 或空白");
        }
        if (this.id != null && !this.id.equals(id)) {
            throw new RuleException("规则标识已指定为 " + this.id + "，不允许改为 " + id);
        }
        this.id = id;
        if (this.name == null) {
            this.name = id;
        }
        return this;
    }

    @Override
    public NativeRuleBuilder name(String name) {
        requireUsable();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为 null 或空白");
        }
        this.name = name;
        return this;
    }

    @Override
    public NativeRuleBuilder message(String message) {
        requireUsable();
        this.message = message;
        return this;
    }

    @Override
    public NativeRuleBuilder description(String description) {
        requireUsable();
        this.description = description;
        return this;
    }

    @Override
    public NativeRuleBuilder when(String expression) {
        requireUsable();
        // 编译一次并复用：写成 lambda 内编译会导致每次规则求值都重新解析
        RuleExpression compiled = RuleExpression.of(expression);
        conditions.add(compiled::test);
        return this;
    }

    @Override
    public NativeRuleBuilder when(Predicate<RuleContext> condition) {
        requireUsable();
        conditions.add(Objects.requireNonNull(condition, "condition 不能为 null"));
        return this;
    }

    @Override
    public NativeRuleBuilder then(Consumer<RuleContext> action) {
        requireUsable();
        actions.add(Objects.requireNonNull(action, "action 不能为 null"));
        return this;
    }

    @Override
    public NativeRuleBuilder to(String target) {
        requireUsable();
        Objects.requireNonNull(target, "target 不能为 null");
        if (targetSet) {
            throw new RuleException("规则 " + id + " 已指定过下一条规则: " + this.target);
        }
        this.target = target;
        this.targetSet = true;
        return this;
    }

    @Override
    public NativeRuleBuilder var(String name, Object value) {
        requireUsable();
        if (name == null || name.isBlank()) {
            throw new NullPointerException("变量名不能为 null 或空白");
        }
        variables.put(name, value);
        return this;
    }

    @Override
    public NativeRuleBuilder salience(int salience) {
        requireUsable();
        this.salience = salience;
        return this;
    }

    @Override
    public NativeRuleBuilder effect(RuleEffect effect) {
        requireUsable();
        this.effect = effect == null ? RuleEffect.NONE : effect;
        return this;
    }

    @Override
    public NativeRule build() {
        requireUsable();
        if (id == null || id.isBlank()) {
            throw new RuleException("规则标识未指定，请先调用 id(String)");
        }
        if (target != null && target.isBlank()) {
            throw new RuleException("规则 " + id + " 的下一条规则标识不能为空白");
        }
        built = true;
        return new NativeRule(id, name, description, message, salience, effect, conditions, actions, target, variables);
    }
}
