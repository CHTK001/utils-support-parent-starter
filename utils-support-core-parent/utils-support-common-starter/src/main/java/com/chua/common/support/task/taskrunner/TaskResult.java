package com.chua.common.support.task.taskrunner;

import java.util.Objects;

/**
 * 单节点执行结果。
 *
 * <p>记录 DAG 中一个任务节点的执行产物：状态、返回数据、异常与耗时。</p>
 *
 * @param id       节点 标识，与注册时的 任务 标识 一致
 * @param status   执行状态
 * @param data     任务返回值，无返回值或失败时为 空
 * @param error    失败原因，仅 {@link Status#FAILED} 时非 空
 * @param duration 执行耗时（毫秒），SKIPPED 为 0
 * @author CH
 * @since 4.0.0.42
 * @return 任务结果的结果
 */
public record TaskResult(String id, Status status, Object data, Throwable error, long duration) {

    /**
     * 节点执行状态枚举。
     * @author CH
     * @since 4.0.0
     */
    public enum Status {
        /**
         * 执行成功（含降级兜底成功的场景）。
         */
        SUCCESS,

        /**
         * 执行失败，重试耗尽且无降级结果。
         */
        FAILED,

        /**
         * 被跳过：前置节点失败/被跳过导致依赖不满足，或因快速失败策略被取消。
         */
        SKIPPED
    }

    /**
      * 构造校验：标识 与 状态 必填。
     */
    public TaskResult {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (status == Status.FAILED && error == null) {
            throw new IllegalArgumentException("FAILED 状态必须携带 error");
        }
        if (duration < 0) {
            throw new IllegalArgumentException("duration 不能为负数, got: " + duration);
        }
    }

    /**
     * 创建成功结果。
     *
     * @param id       节点 标识
     * @param data     返回值
     * @param duration 耗时毫秒
     * @return 成功结果
     */
    public static TaskResult success(String id, Object data, long duration) {
        return new TaskResult(id, Status.SUCCESS, data, null, duration);
    }

    /**
     * 创建失败结果。
     *
     * @param id       节点 标识
     * @param error    失败原因
     * @param duration 耗时毫秒
     * @return 失败结果
     */
    public static TaskResult failed(String id, Throwable error, long duration) {
        return new TaskResult(id, Status.FAILED, null, Objects.requireNonNull(error), duration);
    }

    /**
     * 创建跳过结果。
     *
     * @param id 节点 标识
     * @return 跳过结果
     */
    public static TaskResult skipped(String id) {
        return new TaskResult(id, Status.SKIPPED, null, null, 0);
    }

    /**
     * 按类型取回任务数据。
     *
     * @param <T>  期望类型
     * @param type 期望的数据类型
     * @return 类型化后的数据，data 为 空 时返回 空
     * @throws IllegalStateException 当数据类型与期望不一致时
     */
    @SuppressWarnings("unchecked")
    public <T> T getAs(Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        if (data == null) {
            return null;
        }
        if (!type.isInstance(data)) {
            throw new IllegalStateException(
                    "节点 " + id + " 数据类型不符: 期望 " + type.getName() + " 实际 " + data.getClass().getName());
        }
        return (T) data;
    }
}
