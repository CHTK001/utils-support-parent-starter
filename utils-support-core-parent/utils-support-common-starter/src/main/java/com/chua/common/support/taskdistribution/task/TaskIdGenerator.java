package com.chua.common.support.taskdistribution.task;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务 标识 生成器。
 *
 * <p>生成全局唯一任务 ID，格式：{@code nodeId + "-" + timestamp + "-" + seq}。</p>
 *
 * <p>可读性强且唯一，便于链路追踪和日志排查。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TaskIdGenerator {

    /**
     * 序列号计数器
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    /**
     * 默认节点 标识
     */
    private static volatile String DEFAULT_NODE_ID = "node";

    /**
     * 设置默认节点 标识（应用启动时调用）
     *
     * @param nodeId 节点 标识
     */
    public static void setDefaultNodeId(String nodeId) {
        if (nodeId != null && !nodeId.isEmpty()) {
            DEFAULT_NODE_ID = nodeId;
        }
    }

    /**
     * 生成任务 标识。
     *
     * @param nodeId 节点 标识
     * @return 格式为 "节点标识-时间戳-seq" 的唯一 标识
     */
    public static String generateId(String nodeId) {
        String safeNodeId = (nodeId != null && !nodeId.isEmpty()) ? nodeId : DEFAULT_NODE_ID;
        return safeNodeId + "-" + System.currentTimeMillis() + "-" + SEQUENCE.incrementAndGet();
    }

    /**
     * 生成链路追踪 标识，与任务 标识 相同。
     *
     * @param nodeId 节点 标识
     * @return 追踪 标识
     */
    public static String generateTraceId(String nodeId) {
        return generateId(nodeId);
    }

    /**
     * 使用默认节点 标识 生成任务 标识。
     *
     * @return 任务 标识
     */
    public static String generateId() {
        return generateId(DEFAULT_NODE_ID);
    }
}
