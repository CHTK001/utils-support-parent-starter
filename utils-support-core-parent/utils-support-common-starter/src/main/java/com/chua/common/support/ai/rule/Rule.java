package com.chua.common.support.ai.rule;

import com.chua.common.support.content.ObjectContent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 一条规则。
 *
 * <p>规则是<b>纯数据</b>：条件、动作、优先级、结果语义全都是不可变组件，
 * 不持有执行期状态。同一个 {@code Rule} 实例可以被任意线程、任意次
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
 * <p><b>决策不是自然规则的事</b>。规则默认只做 {@code when} 条件求值，
 * 压根不触碰 {@code ai.decision} 包；需要 AI 判定的场景用 {@link AiRule}，
 * 由 AI 规则自己在 {@code matched} 里向底座求值。
 * 这也意味着规则引擎<b>可以完全独立使用</b>，
 * 不装配任何决策实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Rule {

    /**
     * 开始定义一条自然规则。
     *
     * <p>入参是<b>规则标识</b>（{@code id}），同时作为默认显示名。
     * 需要区分机器标识与人类可读名时用
     * {@link RuleBuilder#name(String)} 覆盖显示名。</p>
     *
     * @param id 规则标识
     * @return 自然规则构造器
     * @throws IllegalArgumentException 规则标识为空白时
     */
    static NativeRuleBuilder definition(String id) {
        RuleBuilder builder = RuleType.NATIVE.builder(id);
        if (!(builder instanceof NativeRuleBuilder nativeBuilder)) {
            throw new RuleException("NATIVE 规则构造器类型异常: " + builder.getClass().getName());
        }
        return nativeBuilder;
    }

    /**
     * 开始定义一条规则。
     *
     * <p>无参形态：按类型默认底座经 SPI 初始化，规则标识后续用
     * {@link RuleBuilder#id(String)} 指定。</p>
     *
     * @return 自然规则构造器
     */
    static RuleBuilder builder() {
        return RuleType.NATIVE.builder();
    }

    /**
     * 开始定义一条规则。
     *
     * <p>枚举形态：按规则类型走 SPI 初始化不同的 {@code xxBuilder}。</p>
     *
     * @param type 规则类型
     * @return 规则构造器
     * @throws NullPointerException 规则类型为 null 时
     */
    static RuleBuilder builder(RuleType type) {
        Objects.requireNonNull(type, "type 不能为 null");
        return type.builder();
    }

    /**
     * 开始定义一条规则。
     *
     * <p>枚举形态：按规则类型初始化对应的构造器。</p>
     *
     * @param type 规则类型
     * @param id   规则标识
     * @return 规则构造器
     * @throws NullPointerException 规则类型为 null 时
     */
    static RuleBuilder builder(RuleType type, String id) {
        Objects.requireNonNull(type, "type 不能为 null");
        return type.builder(id);
    }

    /**
     * 规则类型。
     *
     * @return 规则类型
     */
    RuleType type();

    /**
     * 规则标识，同一规则集内唯一；{@code to} 按它指向下一条规则。
     *
     * @return 规则标识
     */
    String id();

    /**
     * 规则显示名，默认与 {@code id} 相同。
     *
     * @return 规则显示名
     */
    String name();

    /**
     * 规则描述，用于说明业务含义、归属与维护信息。
     *
     * <p>与 {@link #name()} 的区别：{@code name} 给人读的短名，
     * {@code description} 是给人看的长说明，可写清触发场景、负责人、
     * 变更记录。未声明时返回 null。</p>
     *
     * @return 规则描述，可为 null
     */
    default String description() {
        return null;
    }

    /**
     * 命中后对外说明的原因。
     *
     * <p>拦截类规则用它直接给出拦截原因，调用方从
     * {@link RuleResult#messages()} 按规则标识取，不再需要每个
     * {@code then} 里手写 {@code ctx.set("拦截原因", ...)}。
     * 未声明时返回 null。</p>
     *
     * @return 拦截原因，可为 null
     */
    default String message() {
        return null;
    }

    /**
     * 优先级，数值越大越先求值，默认 0。
     *
     * @return 优先级
     */
    int salience();

    /**
     * 本规则给出的结论。
     *
     * @return 结果语义
     */
    RuleEffect effect();

    /**
     * 顶层条件，OR 关系。
     *
     * @return 不可变条件列表
     */
    List<Predicate<RuleContext>> conditions();

    /**
     * 命中后按序执行的动作。
     *
     * @return 不可变动作列表
     */
    List<Consumer<RuleContext>> actions();

    /**
     * 下一条规则的 {@code id}，可为 null。
     *
     * @return 下一条规则标识
     */
    String target();

    /**
     * 规则声明的变量，注入 {@link RuleContext} 的最高优先级层。
     *
     * @return 不可变变量表
     */
    Map<String, Object> variables();

    /**
     * 判断条件是否成立。
     *
     * <p>顶层 OR 语义；无条件时视为恒真。</p>
     *
     * @param context 规则上下文
     * @return 条件成立返回 true
     */
    boolean matched(RuleContext context);

    /**
     * 执行动作，按声明顺序。
     *
     * <p>动作异常直接向上抛，不做吞没或部分执行回滚——
     * 规则动作通常是记录中间变量或触发外部调用，
     * 悄悄跳过一半会让执行状态无法推理。</p>
     *
     * @param context 规则上下文
     */
    void apply(RuleContext context);

    /**
     * 是否有动作需要执行。
     *
     * @return 存在动作返回 true
     */
    boolean hasActions();

    /**
     * 声明了变量。
     *
     * @return 变量表非空返回 true
     */
    boolean hasVariables();
}
