package com.chua.common.support.ai.rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 规则构造器。
 *
 * <p>只能经 {@link Rule#definition(String)} 获得，不对外开放构造器。</p>
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
 * <p>构造器自身<b>不是</b>线程安全的：它在定义期被单线程使用，
 * 产出的 {@link Rule} 才是不可变、可并发求值的那一份。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleBuilder {

    /**
     * 规则标识
     */
    private final String id;

    /**
     * 规则显示名，默认与标识相同
     */
    private String name;

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
    private final List<Predicate<RuleContext>> conditions = new ArrayList<>();

    /**
     * 命中后按序执行的动作
     */
    private final List<Consumer<RuleContext>> actions = new ArrayList<>();

    /**
     * 下一条规则的标识
     */
    private String target;

    /**
     * 规则声明的变量
     */
    private final Map<String, Object> variables = new LinkedHashMap<>();

    /**
     * 仅供 {@link Rule#definition(String)} 调用，不对外公开。
     *
     * @param id 规则标识，同时作为默认显示名
     * @throws IllegalArgumentException 规则标识为 null 或空白时
     */
    RuleBuilder(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为 null 或空白");
        }
        this.id = id;
        this.name = id;
    }

    /**
     * 覆盖规则显示名。
     *
     * <p>标识用于 {@code to} 指向与日志检索，显示名用于人读；
     * 二者相同时无需调用本方法。</p>
     *
     * @param name 显示名，不可为空白
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 显示名为空白时
     */
    public RuleBuilder name(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为 null 或空白");
        }
        this.name = name;
        return this;
    }

    /**
     * 追加一个顶层条件（表达式形态），与已有条件是 OR 关系。
     *
     * <p><b>这是 {@code when} 的默认形态</b>。表达式在<b>构造期</b>就被编译，
     * 语法错误立刻抛 {@link RuleException}，不会留到规则执行时才暴露。
     * 变量名按 {@link RuleContext#get(String)} 的规则解析：
     * 先整串精确匹配，再按 {@code .} 逐段下探，因此
     * {@code "order.amount >= 10000"} 与上下文里的嵌套 map 直接对应。</p>
     *
     * <pre>{@code
     * .when("order.amount >= 10000 AND user.level != 'VIP'")
     * .when("fraud.confidence > 0.8")           // 决策展平变量同样按路径读
     * .when("status IN ('vip','new')")
     * }</pre>
     *
     * <p>需要过程式逻辑（闭包捕获、外部 IO、复杂控制流）时用
     * {@link #when(Predicate)}，两种形态可混用。</p>
     *
     * @param expression 表达式文本
     * @return 自身，便于链式调用
     * @throws RuleException 表达式为空或语法错误时
     */
    public RuleBuilder when(String expression) {
        // 编译一次并复用：写成 lambda 内编译会导致每次规则求值都重新解析
        RuleExpression compiled = RuleExpression.of(expression);
        conditions.add(compiled::test);
        return this;
    }

    /**
     * 追加一个顶层条件（lambda 形态），与已有条件是 OR 关系。
     *
     * @param condition 条件
     * @return 自身，便于链式调用
     * @throws NullPointerException 条件为 null 时
     */
    public RuleBuilder when(Predicate<RuleContext> condition) {
        conditions.add(Objects.requireNonNull(condition, "condition 不能为 null"));
        return this;
    }

    /**
     * 追加一个动作，按声明顺序执行。
     *
     * @param action 动作
     * @return 自身，便于链式调用
     * @throws NullPointerException 动作为 null 时
     */
    public RuleBuilder then(Consumer<RuleContext> action) {
        actions.add(Objects.requireNonNull(action, "action 不能为 null"));
        return this;
    }

    /**
     * 指定下一条规则的标识，至多一次。
     *
     * <p><b>与 {@link RuleBuilder#effect(RuleEffect)} 配合使用</b>：
     * {@code effect} 是本规则给出的<b>结论</b>，{@code to} 是结论一致时
     * 要走到的下一条规则。执行时只有当下一条规则的 {@code effect}
     * 等于当前累积结论时才真的跳过去；结论不一致则链断在此处。</p>
     *
     * <pre>{@code
     * Rule.definition("big-amount").effect(RuleEffect.DENY).to("reject").build();
     * Rule.definition("reject").effect(RuleEffect.DENY).build();   // 结论一致 → 跳过去
     * }</pre>
     *
     * <p>目标规则会被重新求值一次，即使它已在主遍历中求值过。
     * 这是刻意的：目标规则的条件往往依赖本规则刚写入的中间结果，
     * 主遍历时那些变量还没写上，跳过来重跑才命中。
     * 代价是执行轨迹里会出现同名规则两次——可见且可推理。</p>
     *
     * @param target 下一条规则的标识
     * @return 自身，便于链式调用
     * @throws RuleException       重复指定时
     * @throws NullPointerException 目标为 null 时
     */
    public RuleBuilder to(String target) {
        Objects.requireNonNull(target, "target 不能为 null");
        if (this.target != null) {
            throw new RuleException("规则 " + id + " 已指定过下一条规则: " + this.target);
        }
        this.target = target;
        return this;
    }

    /**
     * 声明一个规则变量。
     *
     * <p>声明的变量落在 {@link RuleContext} 的<b>最高优先级层</b>，
     * 刻意盖过 input 与 environment——它是规则作者对本规则的显式声明。
     * 后续 {@code when} 声明的同名条件不会被外部数据顶掉。</p>
     *
     * <p>值允许为 null：{@code var("开关", null)} 表示「显式置空」，
     * 与「没声明这个变量」是两件不同的事，
     * {@link RuleContext#contains(String)} 能区分。</p>
     *
     * @param name  变量名
     * @param value 变量值，允许为 null
     * @return 自身，便于链式调用
     * @throws NullPointerException 变量名为 null 或空白时
     */
    public RuleBuilder var(String name, Object value) {
        if (name == null || name.isBlank()) {
            throw new NullPointerException("变量名不能为 null 或空白");
        }
        variables.put(name, value);
        return this;
    }

    /**
     * 指定优先级，数值越大越先求值。
     *
     * @param salience 优先级
     * @return 自身，便于链式调用
     */
    public RuleBuilder salience(int salience) {
        this.salience = salience;
        return this;
    }

    /**
     * 指定命中后的结果语义。
     *
     * @param effect 结果语义，null 按 {@link RuleEffect#NONE} 处理
     * @return 自身，便于链式调用
     */
    public RuleBuilder effect(RuleEffect effect) {
        this.effect = effect == null ? RuleEffect.NONE : effect;
        return this;
    }

    /**
     * 构造规则。
     *
     * @return 不可变规则
     * @throws RuleException 重复指定下一条规则时
     */
    public Rule build() {
        if (target != null && target.isBlank()) {
            throw new RuleException("规则 " + id + " 的下一条规则标识不能为空白");
        }
        return new Rule(id, name, salience, effect, conditions, actions, target, variables);
    }
}
