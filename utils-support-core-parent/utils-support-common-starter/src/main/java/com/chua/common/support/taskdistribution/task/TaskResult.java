package com.chua.common.support.taskdistribution.task;

import lombok.Builder;
import lombok.Data;

/**
 * 任务执行结果。
 *
 * <p>由工作端执行完成后返回，包含执行状态、数据、错误信息和链路追踪标识。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class TaskResult<T> {

    /**
     * 任务 ID（与 Task.taskId 一致）
     */
    private String taskId;

    /**
     * 链路追踪 ID
     */
    private String traceId;

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 结果数据
     */
    private T data;

    /**
     * 错误信息（success 为 false 时填充）
     */
    private String errorMessage;

    /**
     * 执行节点 ID
     */
    private String workerNodeId;

    /**
     * 执行耗时（毫秒）
     */
    private long durationMs;

    /**
     * 创建成功结果。
     *
     * @param taskId      任务 ID
     * @param data        结果数据
     * @param workerNodeId 执行节点 ID
     * @param <T>         数据类型
     * @return 成功结果
     */
    public static <T> TaskResult<T> success(String taskId, T data, String workerNodeId) {
        return TaskResult.<T>builder()
                .taskId(taskId)
                .success(true)
                .data(data)
                .workerNodeId(workerNodeId)
                .build();
    }

    /**
     * 创建失败结果。
     *
     * @param taskId       任务 ID
     * @param errorMessage 错误信息
     * @param workerNodeId 执行节点 ID
     * @param <T>          数据类型
     * @return 失败结果
     */
    public static <T> TaskResult<T> failure(String taskId, String errorMessage, String workerNodeId) {
        return TaskResult.<T>builder()
                .taskId(taskId)
                .success(false)
                .errorMessage(errorMessage)
                .workerNodeId(workerNodeId)
                .build();
    }
}