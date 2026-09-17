package com.chua.common.support.task.taskrunner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 整体运行结果 — 一次 任务runner 执行的最终产物。
 *
 * <p>包含整体成败判定（由 {@link CompletionPolicy} 分层评估得出）、
 * 全部节点的明细结果与总耗时。</p>
 *
 * @param success    整体是否达标
 * @param runnerName 运行名称
 * @param durationMs 总耗时毫秒
 * @param nodeResults 所有节点结果明细，含 SKIPPED 节点
 * @param error      首个导致整体失败的原因，成功时为 空
 * @author CH
 * @since 4.0.0.42
*/
public record RunResult(boolean success, String runnerName, long durationMs,
                        List<TaskResult> nodeResults, Throwable error) {

    /**
    * 构造校验：runner名称 与 节点结果 必填，失败时必须携带 错误。
    */
    public RunResult {
        Objects.requireNonNull(runnerName, "runnerName must not be null");
        nodeResults = List.copyOf(nodeResults);
        if (!success && error == null) {
            throw new IllegalArgumentException("整体失败时必须携带 error");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs 不能为负数, got: " + durationMs);
        }
    }

    /**
    * 按节点 标识 查找节点结果。
    *
    * @param nodeId 节点 标识
    * @return 节点结果；未找到时返回空 Optional
    */
    public Optional<TaskResult> findNode(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return nodeResults.stream()
                .filter(r -> r.id().equals(nodeId))
                .findFirst();
    }

    /**
    * 统计指定状态的节点数量。
    *
    * @param status 目标状态
    * @return 数量
    */
    public long countByStatus(TaskResult.Status status) {
        Objects.requireNonNull(status, "status must not be null");
        return nodeResults.stream()
                .filter(r -> r.status() == status)
                .count();
    }
}
