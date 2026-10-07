package com.chua.common.support.ai.rule;

import com.chua.common.support.collection.wrap.Wraps;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 一条自然规则。
 *
 * <p>规则是<b>纯数据</b>：条件、动作、优先级、结果语义全都是不可变组件，
 * 不持有执行期状态。同一个 {@code NativeRule} 实例可以被任意线程、任意次
 * 并发求值，线程安全由「无状态」保证，执行器不需要加锁。</p>
 *
 * <p><b>求值语义</b>：</p>
 * <ul>
 *   <li>顶层多个 {@code when} 之间是 <b>OR</b>——命中任意一个即认为条件成立。
 *       这让「同一条业务规则的多个触发条件」不必拆成多条规则、
 *       也不必在一条 lambda 里写一长串 {@code ||}。</li>
 *   <li>没有任何 {@code when} 时视为<b>恒真</b>，等价于无条件命中。</li>
 *   <li>多个 {@code then} 按<b>声明顺序</b>依次执行，中间结果通过
 *       {@link RuleContext#set(String, Object)} 传递，后一个动作能读到前一个写的值。</li>
 *   <li>{@code to} 至多一个，指向下一条规则的标识，与 {@code effect} 配合：
 *       只有下一条规则自己的 {@code effect} 等于当前累积结论时才真的走过去。</li>
 * </ul>
 *
 * <p><b>决策不是本类的事</b>。自然规则只做 {@code when} 条件求值，
 * 压根不触碰 {@code ai.decision} 包；需要 AI 判定的场景用 {@link AiRule}，
 * 由 AI 规则自己在 {@code matched} 里向底座求值。
 * 因此规则引擎<b>可以完全独立使用</b>，不装配任何决策实现。</p>
 *
 * @param id          规则标识，同一规则集内唯一；{@code to} 按它指向下一条规则
 * @param name        规则显示名，默认与 {@code id} 相同
 * @param description 规则描述，可为 null
 * @param message     命中后对外说明的原因，可为 null
 * @param salience   优先级，数值越大越先求值，默认 0
 * @param effect     本规则给出的结论
 * @param conditions 顶层条件，OR 关系
 * @param actions    命中后按序执行的动作
 * @param target     下一条规则的 {@code id}，可为 null
 * @param variables  规则声明的变量，注入 {@link RuleContext} 的最高优先级层
 * @author CH
 * @since 4.0.0.43
 */
public record NativeRule(String id,
                         String name,
                         String description,
                         String message,
                         int salience,
                         RuleEffect effect,
                         List<Predicate<RuleContext>> conditions,
                         List<Consumer<RuleContext>> actions,
                         String target,
                         Map<String, Object> variables) implements Rule {

    /**
     * 构造规则。
     *
     * <p>防御性拷贝：两个列表用 {@link List#copyOf(Collection)}（会拒绝 null 元素），
     * 变量表刻意<b>不</b>用 {@link Map#copyOf(Map)}——
     * 后者拒绝 null 值，而 {@code Rule.var("switch", null)} 表示
     * 「显式置空」是合法且有意义的（配置驱动、开关类场景），
     * 被拒会平白把业务语义变成 {@link NullPointerException}。
     * 因此用不可变包装 + {@link LinkedHashMap} 拷贝，允许 null 值。</p>
     *
     * @throws NullPointerException     {@code id}、{@code name}、{@code conditions}、
     *                                  {@code actions}、{@code variables} 为 null 时
     * @throws IllegalArgumentException 规则标识或显示名为空白时
     */
    public NativeRule {
        Objects.requireNonNull(id, "id 不能为 null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空白");
        }
        Objects.requireNonNull(name, "name 不能为 null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空白");
        }
        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions 不能为 null"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions 不能为 null"));
        variables = Collections.unmodifiableMap(
                Wraps.linkedMap(Objects.requireNonNull(variables, "variables 不能为 null")));
        effect = effect == null ? RuleEffect.NONE : effect;
    }

    @Override
    public RuleType type() {
        return RuleType.NATIVE;
    }

    @Override
    public boolean matched(RuleContext context) {
        Objects.requireNonNull(context, "context 不能为 null");
        if (conditions.isEmpty()) {
            return true;
        }
        for (Predicate<RuleContext> condition : conditions) {
            if (condition.test(context)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void apply(RuleContext context) {
        Objects.requireNonNull(context, "context 不能为 null");
        for (Consumer<RuleContext> action : actions) {
            action.accept(context);
        }
    }

    @Override
    public boolean hasActions() {
        return !actions.isEmpty();
    }

    @Override
    public boolean hasVariables() {
        return !variables.isEmpty();
    }
}
