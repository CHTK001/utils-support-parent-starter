package com.chua.common.support.ai.probe;

import org.jspecify.annotations.NullUnmarked;

/**
 * 探测进度事件。
 *
 * <p>用于 {@link ChatClient#probe(java.util.function.Consumer, java.util.function.Consumer, java.util.function.Consumer)}
 * 回调中的进度推送，包含当前进度百分比、状态消息、时间戳及当前维度结果（如已完成）。</p>
 *
 * @param progress        进度百分比（0-100）
 * @param message         状态消息
 * @param timestamp       事件时间戳（毫秒，{@link System#currentTimeMillis()}）
 * @param currentDimension 当前正在执行/已完成的维度
 * @param result          当前维度的探测结果（若已完成该维度则非 null，进行中时为 null）
 * @author CH
 * @since 4.0.0.42
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public record ProbeProgress(
    int progress,
    String message,
    long timestamp,
    ProbeDimension currentDimension,
    ProbeResult result
) {
    /**
     * 创建进行中的进度事件（无结果）。
     *
     * @param progress 进度百分比
     * @param message 状态消息
     * @param dimension 当前维度
     * @return ProbeProgress 实例
     */
    public static ProbeProgress inProgress(int progress, String message, ProbeDimension dimension) {
        return new ProbeProgress(progress, message, System.currentTimeMillis(), dimension, null);
    }

    /**
     * 创建已完成维度的进度事件。
     *
     * @param progress 进度百分比
     * @param message 状态消息
     * @param dimension 当前维度
     * @param result 探测结果
     * @return ProbeProgress 实例
     */
    public static ProbeProgress completed(int progress, String message, ProbeDimension dimension, ProbeResult result) {
        return new ProbeProgress(progress, message, System.currentTimeMillis(), dimension, result);
    }
}