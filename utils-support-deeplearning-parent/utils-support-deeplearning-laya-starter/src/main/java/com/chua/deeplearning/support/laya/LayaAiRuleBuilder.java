package com.chua.deeplearning.support.laya;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.decision.DecisionQuery;
import com.chua.common.support.ai.rule.AiRule;
import com.chua.common.support.ai.rule.AiRuleBuilder;
import com.chua.common.support.ai.rule.AiRuleQuery;
import com.chua.common.support.ai.rule.RuleBuilder;
import com.chua.common.support.ai.rule.RuleContext;
import com.chua.common.support.ai.rule.RuleEffect;
import com.chua.common.support.ai.rule.RuleException;
import com.chua.common.support.ai.rule.RuleExpression;
import com.chua.common.support.ai.rule.RuleType;
import com.chua.common.support.spi.annotations.Spi;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Laya AI 决策规则构造器。
 *
 * <p>经 SPI 以 {@code ai} 名称注册，与 {@code native} 底座一一对应：
 * 一个构造器只允许构造一条 {@link LayaAiRule}。
 * 决策问题在构造期只做声明，真正的 AI 求值由规则在
 * {@link LayaAiRule#matched} 里按需向自己的底座触发。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("ai")
public final class LayaAiRuleBuilder implements AiRuleBuilder {

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
     * 声明的决策问题
     */
    private final List<AiRuleQuery> queries = Wraps.list();

    /**
     * 命中后执行的动作
     */
    private final List<Consumer<RuleContext>> actions = Wraps.list();

    /**
     * 兼容自然规则写法的本地条件，AI 规则同样支持 when/then 混用
     */
    private final List<Predicate<RuleContext>> conditions = Wraps.list();

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
     * AI 决策上下文，null 表示使用执行期 input
     */
    private Object state;

    /**
     * 本地模型资产目录
     */
    private String modelPath;

    /**
     * 推理线程数
     */
    private Integer threads;

    /**
     * 校准文件路径
     */
    private String calibrationPath;

    /**
     * 是否已构造过规则，一个构造器只允许构造一条规则
     */
    private boolean built;

    /**
     * SPI 初始化用的无参构造器。
     */
    public LayaAiRuleBuilder() {
    }

    /**
     * 带规则标识的构造器。
     *
     * @param id 规则标识，同时作为默认显示名
     */
    public LayaAiRuleBuilder(String id) {
        id(id);
    }

    @Override
    public RuleType type() {
        return RuleType.AI;
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
    public AiRuleBuilder id(String id) {
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
    public AiRuleBuilder name(String name) {
        requireUsable();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为 null 或空白");
        }
        this.name = name;
        return this;
    }

    @Override
    public AiRuleBuilder message(String message) {
        requireUsable();
        this.message = message;
        return this;
    }

    @Override
    public AiRuleBuilder description(String description) {
        requireUsable();
        this.description = description;
        return this;
    }

    @Override
    public AiRuleBuilder when(String expression) {
        requireUsable();
        RuleExpression compiled = RuleExpression.of(expression);
        conditions.add(compiled::test);
        return this;
    }

    @Override
    public AiRuleBuilder when(Predicate<RuleContext> condition) {
        requireUsable();
        conditions.add(Objects.requireNonNull(condition, "condition 不能为 null"));
        return this;
    }

    @Override
    public AiRuleBuilder then(Consumer<RuleContext> action) {
        requireUsable();
        actions.add(Objects.requireNonNull(action, "action 不能为 null"));
        return this;
    }

    @Override
    public AiRuleBuilder to(String target) {
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
    public AiRuleBuilder var(String name, Object value) {
        requireUsable();
        if (name == null || name.isBlank()) {
            throw new NullPointerException("变量名不能为 null 或空白");
        }
        variables.put(name, value);
        return this;
    }

    @Override
    public AiRuleBuilder salience(int salience) {
        requireUsable();
        this.salience = salience;
        return this;
    }

    @Override
    public AiRuleBuilder effect(RuleEffect effect) {
        requireUsable();
        this.effect = effect == null ? RuleEffect.NONE : effect;
        return this;
    }

    @Override
    public AiRuleBuilder query(DecisionQuery query) {
        return query(query, null, null);
    }

    @Override
    public AiRuleBuilder query(DecisionQuery query, String expect) {
        return query(query, expect, null);
    }

    @Override
    public AiRuleBuilder query(DecisionQuery query, String expect, Double threshold) {
        requireUsable();
        Objects.requireNonNull(query, "query 不能为 null");
        for (AiRuleQuery item : queries) {
            if (item.id().equals(query.id())) {
                throw new RuleException("决策问题标识重复: " + query.id());
            }
        }
        queries.add(new AiRuleQuery(query, expect, threshold));
        return this;
    }

    @Override
    public AiRuleBuilder queries(List<AiRuleQuery> queries) {
        requireUsable();
        Objects.requireNonNull(queries, "queries 不能为 null");
        for (AiRuleQuery item : queries) {
            Objects.requireNonNull(item, "queries 不能包含 null 元素");
            query(item.query(), item.expect(), item.threshold());
        }
        return this;
    }

    @Override
    public AiRuleBuilder state(Object state) {
        requireUsable();
        this.state = state;
        return this;
    }

    @Override
    public AiRuleBuilder modelPath(String modelPath) {
        requireUsable();
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public AiRuleBuilder threads(Integer threads) {
        requireUsable();
        if (threads != null && threads <= 0) {
            throw new IllegalArgumentException("threads 必须为正数，实际 " + threads);
        }
        this.threads = threads;
        return this;
    }

    @Override
    public AiRuleBuilder calibrationPath(String calibrationPath) {
        requireUsable();
        this.calibrationPath = calibrationPath;
        return this;
    }

    @Override
    public AiRule build() {
        requireUsable();
        if (id == null || id.isBlank()) {
            throw new RuleException("规则标识未指定，请先调用 id(String)");
        }
        if (queries.isEmpty()) {
            throw new RuleException("AI 规则 " + id + " 必须声明至少一个决策问题");
        }
        if (target != null && target.isBlank()) {
            throw new RuleException("规则 " + id + " 的下一条规则标识不能为空白");
        }
        built = true;
        return new LayaAiRule(id, name, description, message, salience, effect, List.copyOf(queries),
                List.copyOf(conditions), List.copyOf(actions), target, Wraps.linkedMap(variables),
                "laya", state, modelPath, threads, calibrationPath);
    }

    /**
     * 显式声明本构造器归属 AI 类型，避免 SPI 误装配。
     *
     * @return AI 规则构造器
     */
    public static RuleBuilder ai() {
        return new LayaAiRuleBuilder();
    }
}
