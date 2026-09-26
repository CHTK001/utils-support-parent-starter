package com.chua.common.support.rule;

import java.util.Objects;

/**
 * 规则事件。
 *
 * <p>描述一次规则引擎运行过程中发生的可观测事件，
 * 由 {@link RuleListener} 消费，用于审计、指标采集与调试。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @param type    事件类型
 * @param ruleName 规则名，非规则事件为 null
 * @param detail  事件明细，可为 null
 * @param error   关联异常，可为 null
 */
public record RuleEvent(RuleEventType type, String ruleName, Object detail, Throwable error) {

    /**
     * 规范构造器：事件类型为空值敌对。
     *
     * <p>value class 前置条件——引用组件不接受 null。
     * {@code ruleName} / {@code detail} / {@code error} 在全部构造点
     * 都被显式传 {@code null}（非规则事件无规则名、无明细即无异常），
     * 属正常语义，故不校验。</p>
     *
     * @param type     事件类型
     * @param ruleName 规则名，非规则事件为 null
     * @param detail   事件明细，可为 null
     * @param error    关联异常，可为 null
     */
    public RuleEvent {
        type = Objects.requireNonNull(type, "type 不能为 null");
    }

    /**
     * 创建规则事件。
     *
     * @param type 事件类型
     * @return 规则事件
     */
    public static RuleEvent of(RuleEventType type) {
        return new RuleEvent(type, null, null, null);
    }

    /**
     * 创建规则事件。
     *
     * @param type     事件类型
     * @param ruleName 规则名
     * @return 规则事件
     */
    public static RuleEvent of(RuleEventType type, String ruleName) {
        return new RuleEvent(type, ruleName, null, null);
    }

    /**
     * 创建规则事件。
     *
     * @param type     事件类型
     * @param ruleName 规则名
     * @param detail   事件明细
     * @return 规则事件
     */
    public static RuleEvent of(RuleEventType type, String ruleName, Object detail) {
        return new RuleEvent(type, ruleName, detail, null);
    }

    /**
     * 创建规则事件。
     *
     * @param type     事件类型
     * @param ruleName 规则名
     * @param detail   事件明细
     * @param error    关联异常
     * @return 规则事件
     */
    public static RuleEvent of(RuleEventType type, String ruleName, Object detail, Throwable error) {
        return new RuleEvent(type, ruleName, detail, error);
    }
}
