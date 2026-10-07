package com.chua.common.support.ai.rule;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 规则构造器 SPI 接口。
 *
 * <p>不同规则类型各有自己的 {@code xxBuilder} 实现，经 SPI 按
 * {@link RuleType} 装配：{@code NATIVE} 对应
 * {@link NativeRuleBuilder}（common-starter 自带），{@code AI} 对应
 * laya 模块的实现（默认 laya-java 底座）。调用方经
 * {@link Rule#builder()}（无参）或 {@link Rule#builder(RuleType)}（枚举参数）
 * 拿到构造器，不直接 new 实现类。</p>
 *
 * <p>SPI 实现约定：</p>
 * <ol>
 *   <li>必须提供 <b>public 无参构造器</b>（供 SPI 初始化），可额外提供
 *       {@code (String id)} 单参构造器；规则标识最终以
 *       {@link #id(String)} 为准。</li>
 *   <li>{@link #type()} 必须与注册的规则类型一致，装配时会被校验。</li>
 *   <li><b>一个构造器只允许构造一条规则</b>：{@link #build()} 成功后再次调用
 *       任何写方法或 {@code build()} 必须抛 {@link RuleException}。</li>
 *   <li>构造器自身<b>不是</b>线程安全的：定义期单线程使用，
 *       产出的 {@link Rule} 才是不可变、可并发求值的那一份。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface RuleBuilder {

    /**
     * 本构造器归属的规则类型。
     *
     * @return 规则类型
     */
    RuleType type();

    /**
     * 指定规则标识。
     *
     * <p>无参 SPI 初始化后必须调用本方法指定标识；已指定过不同标识时抛异常。</p>
     *
     * @param id 规则标识，不可为空白
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 规则标识为空白时
     * @throws RuleException            已指定过不同标识或构造器已使用时
     */
    RuleBuilder id(String id);

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
    RuleBuilder name(String name);

    /**
     * 声明规则描述。
     *
     * <p>写清业务含义、触发场景与维护信息，便于规则多了之后可检索、可交接。</p>
     *
     * @param description 规则描述，可为 null（表示不声明）
     * @return 自身，便于链式调用
     */
    RuleBuilder description(String description);

    /**
     * 声明命中后对外说明的原因。
     *
     * <p>拦截类规则用它直接给出拦截原因：命中后执行器把
     * {@link Rule#message()} 按规则标识收集到
     * {@link RuleResult#messages()}，调用方按标识取。</p>
     *
     * @param message 拦截原因，可为 null（表示不声明）
     * @return 自身，便于链式调用
     */
    RuleBuilder message(String message);

    /**
     * 追加一个顶层条件（表达式形态），与已有条件是 OR 关系。
     *
     * <p>表达式在<b>构造期</b>就被编译，
     * 语法错误立刻抛 {@link RuleException}，不会留到规则执行时才暴露。</p>
     *
     * @param expression 表达式文本
     * @return 自身，便于链式调用
     * @throws RuleException 表达式为空或语法错误时
     */
    RuleBuilder when(String expression);

    /**
     * 追加一个顶层条件（lambda 形态），与已有条件是 OR 关系。
     *
     * @param condition 条件
     * @return 自身，便于链式调用
     * @throws NullPointerException 条件为 null 时
     */
    RuleBuilder when(Predicate<RuleContext> condition);

    /**
     * 追加一个动作，按声明顺序执行。
     *
     * @param action 动作
     * @return 自身，便于链式调用
     * @throws NullPointerException 动作为 null 时
     */
    RuleBuilder then(Consumer<RuleContext> action);

    /**
     * 指定下一条规则的标识，至多一次。
     *
     * <p>与 {@code effect} 配合使用：目标规则只有在它自己给出的
     * {@code effect} 等于当前累积结论时才真的被跳转到。</p>
     *
     * @param target 下一条规则的标识
     * @return 自身，便于链式调用
     * @throws RuleException       重复指定时
     * @throws NullPointerException 目标为 null 时
     */
    RuleBuilder to(String target);

    /**
     * 声明一个规则变量。
     *
     * <p>声明的变量落在 {@link RuleContext} 的<b>最高优先级层</b>。
     * 值允许为 null：{@code var("开关", null)} 表示「显式置空」，
     * 与「没声明这个变量」是两件不同的事。</p>
     *
     * @param name  变量名
     * @param value 变量值，允许为 null
     * @return 自身，便于链式调用
     * @throws NullPointerException 变量名为 null 或空白时
     */
    RuleBuilder var(String name, Object value);

    /**
     * 指定优先级，数值越大越先求值。
     *
     * @param salience 优先级
     * @return 自身，便于链式调用
     */
    RuleBuilder salience(int salience);

    /**
     * 指定命中后的结果语义。
     *
     * @param effect 结果语义，null 按 {@link RuleEffect#NONE} 处理
     * @return 自身，便于链式调用
     */
    RuleBuilder effect(RuleEffect effect);

    /**
     * 构造规则。
     *
     * <p>一个构造器只能成功调用一次；重复调用抛 {@link RuleException}。</p>
     *
     * @return 不可变规则
     * @throws RuleException 规则标识缺失、下一条规则标识非法或构造器已使用时
     */
    Rule build();

    /**
     * 追加多个顶层条件（表达式形态），与已有条件是 OR 关系。
     *
     * @param expressions 表达式文本列表
     * @return 自身，便于链式调用
     * @throws NullPointerException 列表为 null 时
     */
    default RuleBuilder whenAll(List<String> expressions) {
        if (expressions == null) {
            throw new NullPointerException("expressions 不能为 null");
        }
        for (String expression : expressions) {
            when(expression);
        }
        return this;
    }

    /**
     * 批量声明规则变量。
     *
     * @param variables 变量表
     * @return 自身，便于链式调用
     * @throws NullPointerException 变量表为 null 时
     */
    default RuleBuilder vars(Map<String, Object> variables) {
        if (variables == null) {
            throw new NullPointerException("variables 不能为 null");
        }
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            var(entry.getKey(), entry.getValue());
        }
        return this;
    }
}
