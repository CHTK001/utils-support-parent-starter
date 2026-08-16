package com.chua.common.support.scattergather;

import lombok.extern.slf4j.Slf4j;

/**
 * Scatter-Gather 节点生命周期与结果监听器。
 * <p>监听本地查询、远程调用、聚合、重试、故障及降级等全链路事件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ScatterGatherNodeListener<O> {

    /**
     * 节点服务启动时调用。
     *
     * @param setting 当前节点配置
     */
    default void onStart(ScatterGatherSetting setting) {
    }

    /**
     * 节点服务停止时调用。
     *
     * @param setting 当前节点配置
     */
    default void onStop(ScatterGatherSetting setting) {
    }

    /**
     * 本地查询完成时调用。
     *
     * @param result 本地查询结果
     */
    default void onLocalResult(ScatterGatherResult<O> result) {
    }

    /**
     * 远程节点返回结果时调用（每节点一次）。
     *
     * @param result 远程查询结果
     */
    default void onRemoteResult(ScatterGatherResult<O> result) {
    }

    /**
     * 聚合完成时调用。
     *
     * @param aggregate 聚合后的最终结果
     */
    default void onAggregate(O aggregate) {
    }

    /**
     * 重试时调用。
     *
     * @param node    目标节点
     * @param attempt 当前重试次数
     * @param e       异常信息
     */
    default void onRetry(ScatterGatherNode node, int attempt, Exception e) {
    }

    /**
     * 节点被标记为故障时调用。
     *
     * @param node         目标节点
     * @param failureCount 连续失败次数
     */
    default void onFaulty(ScatterGatherNode node, int failureCount) {
    }

    /**
     * 触发降级时调用。
     *
     * @param node         目标节点
     * @param fallbackValue 降级返回值
     */
    default void onFallback(ScatterGatherNode node, Object fallbackValue) {
    }
}
