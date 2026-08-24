package com.chua.common.support.task.taskrunner;

import java.util.Objects;

/**
 * 运行事件 — TaskRunner 执行过程中对外发布的生命周期事件。
 *
 * <p>通过 {@link RunnerListener} 同步回调，或经 {@code TaskRunner#watch()} 以
 * {@code Flux<RunnerEvent>} 响应式消费。</p>
 *
 * @param type      事件类型
 * @param nodeId    关联节点 ID，运行级事件（RUN_STARTED/RUN_COMPLETED）为 null
 * @param message   事件描述，如失败原因摘要
 * @param timestamp 事件产生时间戳（毫秒）
 * @author CH
 * @since 4.0.0.42
 */
public record RunnerEvent(Type type, String nodeId, String message, long timestamp) {

    /**
     * 事件类型枚举。
     */
    public enum Type {
        /**
         * 整个运行开始。
         */
        RUN_STARTED,

        /**
         * 单节点开始执行。
         */
        NODE_STARTED,

        /**
         * 单节点执行成功。
         */
        NODE_COMPLETED,

        /**
         * 单节点执行失败。
         */
        NODE_FAILED,

        /**
         * 单节点被跳过（依赖不满足或被快速失败取消）。
         */
        NODE_SKIPPED,

        /**
         * 整个运行结束（无论成败）。
         */
        RUN_COMPLETED
    }

    /**
     * 构造校验：type 与 timestamp 必填。
     */
    public RunnerEvent {
        Objects.requireNonNull(type, "type must not be null");
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp 不能为负数, got: " + timestamp);
        }
    }

    /**
     * 创建运行级事件。
     *
     * @param type  事件类型，须为 RUN_STARTED / RUN_COMPLETED
     * @param msg   事件描述
     * @param now   时间戳毫秒
     * @return 运行级事件
     */
    public static RunnerEvent runLevel(Type type, String msg, long now) {
        return new RunnerEvent(type, null, msg, now);
    }

    /**
     * 创建节点级事件。
     *
     * @param type  事件类型，须为 NODE_* 系列
     * @param node  节点 ID
     * @param msg   事件描述
     * @param now   时间戳毫秒
     * @return 节点级事件
     */
    public static RunnerEvent nodeLevel(Type type, String node, String msg, long now) {
        return new RunnerEvent(type, Objects.requireNonNull(node, "node must not be null"), msg, now);
    }
}
