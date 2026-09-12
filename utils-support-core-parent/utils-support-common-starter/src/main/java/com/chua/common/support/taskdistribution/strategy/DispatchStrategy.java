package com.chua.common.support.taskdistribution.strategy;

/**
* 派发策略枚举。
*
* <p>描述中间件从候选工作端中选择执行者的策略。</p>
*
* @author CH
* @since 4.0.0.42
 */
public enum DispatchStrategy {
    /**
    * 取第一个匹配的工作端
     */
    FIRST,

    /**
    * 取最后一个匹配的工作端
     */
    LAST,

    /**
    * 随机选择一个工作端
     */
    RANDOM,

    /**
    * 轮询选择工作端
     */
    ROUND,

    /**
    * 按权重加权随机选择工作端
     */
    WEIGHT
}