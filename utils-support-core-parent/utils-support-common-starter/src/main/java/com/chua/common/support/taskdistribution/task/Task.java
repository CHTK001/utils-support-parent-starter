package com.chua.common.support.taskdistribution.task;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 任务模型。
 *
 * <p>任务分发框架的核心数据载体，包含任务标识、类型、数据、标签和链路追踪信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class Task<T> {

    /**
     * 全局唯一任务 标识（由 任务id生成器 自动生成）
     */
    private String taskId;

    /**
     * 链路追踪 标识（贯穿整个任务链，子任务继承父任务的 追踪id）
     */
    private String traceId;

    /**
     * 父任务 标识（子任务链时记录来源，根任务为 空）
     */
    private String parentTaskId;

    /**
     * 任务类型（用于匹配 任务执行器）
     */
    private String taskType;

    /**
     * 任务负载数据
     */
    private T payload;

    /**
     * 任务标签（用于能力匹配和路由）
     */
    @Builder.Default
    private Map<String, String> tags = new HashMap<>(); // 标签

    /**
     * 分片数量（>1 表示需要分片执行）
     */
    @Builder.Default
    /** Shard数量 */
    private int shardCount = 1;

    /**
     * 分片键（分片时用于计算分片归属）
     */
    private String shardKey;

    /**
     * 当前分片索引（分片执行时标记，-1 表示未分片）
     */
    @Builder.Default
    /** Shard索引 */
    private int shardIndex = -1;

    /**
     * 执行超时时间（毫秒）
     */
    @Builder.Default
    /** 超时MS */
    private long timeoutMs = 30000;

    /**
     * 最大重试次数
     */
    @Builder.Default
    /** 最大值重试次数 */
    private int maxRetries = 3;

    /**
     * 任务优先级
     */
    @Builder.Default
    /** 优先级 */
    private TaskPriority priority = TaskPriority.MEDIUM;

    /**
     * 是否暂停（已创建但暂停派发，工作端可继续执行已派发的任务）
     */
    @Builder.Default
    /** Paused */
    private boolean paused = false;

    /**
     * 发布端节点 标识
     */
    private String sourceNodeId;
}
