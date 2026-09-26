package com.chua.common.support.rule.file;

import com.chua.common.support.rule.RuleException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 规则文件中单条规则的定义。
 *
 * <p>对应规则文件 {@code rules} 数组的一个元素。字段与运行时
 * {@link com.chua.common.support.rule.Rule} 一一对应，
 * 但保留「未指定」与「显式指定」的差别，便于热更新时做增量判断。</p>
 *
 * <h3>字段</h3>
 * <pre>{@code
 * {
 *   "name":          "大额订单断路",     // 必填，规则库内唯一
 *   "description":   "金额超阈值直接断路",
 *   "salience":      200,                // 优先级，越大越先
 *   "agendaGroup":   "MAIN",
 *   "activationGroup": null,
 *   "noLoop":        true,
 *   "lockOnActive":  false,
 *   "enabled":       true,
 *   "when":          { ... },
 *   "then":          [ ... ]
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleSpec {

    /**
     * 规则名
     */
    private final String name;

    /**
     * 规则描述
     */
    private final String description;

    /**
     * 优先级
     */
    private final int salience;

    /**
     * 议程分组
     */
    private final String agendaGroup;

    /**
     * 激活分组
     */
    private final String activationGroup;

    /**
     * 是否禁止自身再激活
     */
    private final boolean noLoop;

    /**
     * 焦点期间是否只激活一次
     */
    private final boolean lockOnActive;

    /**
     * 是否启用
     */
    private final boolean enabled;

    /**
     * 条件树
     */
    private final RuleConditionSpec when;

    /**
     * 动作列表
     */
    private final List<RuleActionSpec> then;

    /**
     * 创建规则定义。
     *
     * @param name            规则名
     * @param description     规则描述
     * @param salience        优先级
     * @param agendaGroup     议程分组
     * @param activationGroup 激活分组
     * @param noLoop          是否禁止自身再激活
     * @param lockOnActive    焦点期间是否只激活一次
     * @param enabled         是否启用
     * @param when            条件树
     * @param then            动作列表
     */
    public RuleSpec(String name, String description, int salience, String agendaGroup,
                    String activationGroup, boolean noLoop, boolean lockOnActive,
                    boolean enabled, RuleConditionSpec when, List<RuleActionSpec> then) {
        if (name == null || name.isBlank()) {
            throw new RuleException("规则名不能为空");
        }
        if (when == null) {
            throw new RuleException("规则[" + name + "]缺少 when 段");
        }
        this.name = name.trim();
        this.description = description;
        this.salience = salience;
        this.agendaGroup = agendaGroup;
        this.activationGroup = activationGroup;
        this.noLoop = noLoop;
        this.lockOnActive = lockOnActive;
        this.enabled = enabled;
        this.when = when;
        this.then = then == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(then));
    }

    /**
     * 获取规则名。
     *
     * @return 规则名
     */
    public String name() {
        return name;
    }

    /**
     * 获取规则描述。
     *
     * @return 规则描述
     */
    public String description() {
        return description;
    }

    /**
     * 获取优先级。
     *
     * @return 优先级
     */
    public int salience() {
        return salience;
    }

    /**
     * 获取议程分组。
     *
     * @return 议程分组，未指定返回 null
     */
    public String agendaGroup() {
        return agendaGroup;
    }

    /**
     * 获取激活分组。
     *
     * @return 激活分组，未指定返回 null
     */
    public String activationGroup() {
        return activationGroup;
    }

    /**
     * 是否禁止自身再激活。
     *
     * @return 禁止返回 true
     */
    public boolean noLoop() {
        return noLoop;
    }

    /**
     * 焦点期间是否只激活一次。
     *
     * @return 锁定返回 true
     */
    public boolean lockOnActive() {
        return lockOnActive;
    }

    /**
     * 是否启用。
     *
     * @return 启用返回 true
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 获取条件树。
     *
     * @return 条件树
     */
    public RuleConditionSpec when() {
        return when;
    }

    /**
     * 获取动作列表。
     *
     * @return 动作列表，只读
     */
    public List<RuleActionSpec> then() {
        return then;
    }

    @Override
    public String toString() {
        return "RuleSpec[" + name + ", salience=" + salience
                + ", enabled=" + enabled + ", when=" + when + ", then=" + then + "]";
    }
}
