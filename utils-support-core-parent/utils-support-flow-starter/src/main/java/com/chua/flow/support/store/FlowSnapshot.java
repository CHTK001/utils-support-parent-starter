package com.chua.flow.support.store;

import com.chua.common.support.task.flow.FlowStatus;
import com.chua.common.support.task.flow.FlowTrace;

import java.time.Duration;
import java.util.List;

/**
 * 一次流程执行的完整快照。
 *
 * <p>描述一次实例运行的执行结果，包含执行号、节点执行轨迹、最终状态与起止时间，
 * 供落库持久化、全链路追踪与前端重放使用。</p>
 *
 * @param executionNo 执行号（通常复用实例 标识）
 * @param flowId      流程 标识
 * @param traces      节点执行轨迹（输入/输出快照）
 * @param status      最终执行状态
 * @param startAt     开始时间戳（毫秒）
 * @param endAt       结束时间戳（毫秒）
 * @author CH
 * @since 4.0.0.42
 */
public record FlowSnapshot(
        String executionNo,
        String flowId,
        List<FlowTrace> traces,
        FlowStatus status,
        long startAt,
        long endAt
) {

    /**
     * 计算本次执行总耗时。
     *
     * @return 耗时（毫秒）
     */
    public long duration() {
        return endAt - startAt;
    }

    /**
     * 计算本次执行总耗时的可读形式。
     *
     * @return 可读耗时，如 "235ms"
     */
    public String durationText() {
        return Duration.ofMillis(duration()).toString();
    }
}