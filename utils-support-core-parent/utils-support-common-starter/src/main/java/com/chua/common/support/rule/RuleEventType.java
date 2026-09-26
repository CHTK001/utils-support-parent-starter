package com.chua.common.support.rule;

/**
 * 规则事件类型。
 */
public enum RuleEventType {

    /**
     * 规则被触发
     */
    RULE_FIRED,

    /**
     * 规则激活（进入议程）
     */
    RULE_ACTIVATED,

    /**
     * 规则因激活分组冲突被取消
     */
    RULE_CANCELLED,

    /**
     * 规则执行抛出异常
     */
    RULE_FAILED,

    /**
     * 事实被插入工作内存
     */
    FACT_INSERTED,

    /**
     * 事实被撤销
     */
    FACT_RETRACTED,

    /**
     * 事实被修改
     */
    FACT_UPDATED,

    /**
     * 一轮推理结束
     */
    CYCLE_COMPLETED,

    /**
     * 达到最大轮次上限后强制停止
     */
    LIMIT_REACHED
}
