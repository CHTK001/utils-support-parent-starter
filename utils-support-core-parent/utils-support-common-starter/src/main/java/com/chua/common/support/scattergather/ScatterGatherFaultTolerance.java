package com.chua.common.support.scattergather;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 节点故障容忍控制器。
 * <p>跟踪每个节点的失败/成功次数，实现故障节点摘除和自动恢复。</p>
 *
 * @author CH
 */
@Slf4j
public class ScatterGatherFaultTolerance {

    /**
     * 节点状态映射
     */
    private final Map<String, NodeStatus> nodeStatuses = new ConcurrentHashMap<>();

    /**
     * 故障阈值
     */
    private final int failureThreshold;

    /**
     * 恢复阈值
     */
    private final int recoveryThreshold;

    /**
     * 故障处理器
     */
    private final ScatterGatherFaultHandler faultHandler;

    /**
     * 构造故障容忍控制器。
     *
     * @param failureThreshold  故障阈值
     * @param recoveryThreshold 恢复阈值
     */
    public ScatterGatherFaultTolerance(int failureThreshold, int recoveryThreshold) {
        this(failureThreshold, recoveryThreshold, null);
    }

    /**
     * 构造故障容忍控制器。
     *
     * @param failureThreshold  故障阈值
     * @param recoveryThreshold 恢复阈值
     * @param faultHandler      故障处理器
     */
    public ScatterGatherFaultTolerance(int failureThreshold, int recoveryThreshold, ScatterGatherFaultHandler faultHandler) {
        this.failureThreshold = failureThreshold;
        this.recoveryThreshold = recoveryThreshold;
        this.faultHandler = faultHandler;
    }

    /**
     * 记录节点调用失败。
     *
     * @param nodeId 节点ID
     * @return true 节点已被标记为故障
     */
    public boolean recordFailure(String nodeId) {
        NodeStatus status = nodeStatuses.computeIfAbsent(nodeId, k -> new NodeStatus());
        boolean markedFaulty = status.incrementFailure() >= failureThreshold;
        if (markedFaulty && !status.isFaulty) {
            status.isFaulty = true;
            if (faultHandler != null) {
                faultHandler.onNodeMarkedFaulty(nodeId, status.failureCount);
            }
        }
        return status.isFaulty;
    }

    /**
     * 记录节点调用成功。
     *
     * @param nodeId 节点ID
     * @return true 节点已恢复
     */
    public boolean recordSuccess(String nodeId) {
        NodeStatus status = nodeStatuses.get(nodeId);
        if (status == null) {
            return false;
        }
        boolean recovered = status.resetFailure() >= recoveryThreshold;
        if (recovered && status.isFaulty) {
            status.isFaulty = false;
            if (faultHandler != null) {
                faultHandler.onNodeRecovered(nodeId, status.successCount);
            }
        }
        return recovered;
    }

    /**
     * 检查节点是否故障。
     *
     * @param nodeId 节点ID
     * @return true 故障
     */
    public boolean isFaulty(String nodeId) {
        NodeStatus status = nodeStatuses.get(nodeId);
        return status != null && status.isFaulty;
    }

    /**
     * 获取节点失败次数。
     *
     * @param nodeId 节点ID
     * @return 失败次数
     */
    public int getFailureCount(String nodeId) {
        NodeStatus status = nodeStatuses.get(nodeId);
        return status != null ? status.failureCount : 0;
    }

    /**
     * 获取节点状态快照。
     *
     * @param nodeId 节点ID
     * @return 状态信息
     */
    public NodeStatusInfo getNodeStatus(String nodeId) {
        NodeStatus status = nodeStatuses.get(nodeId);
        if (status == null) {
            return new NodeStatusInfo(nodeId, false, 0, 0);
        }
        return new NodeStatusInfo(nodeId, status.isFaulty, status.failureCount, status.successCount);
    }

    /**
     * 清除所有节点状态。
     */
    public void clear() {
        nodeStatuses.clear();
    }

    /**
     * 节点状态内部类。
     */
    private static class NodeStatus {
        boolean isFaulty = false;
        int failureCount = 0;
        int successCount = 0;

        synchronized int incrementFailure() {
            failureCount++;
            successCount = 0;
            return failureCount;
        }

        synchronized int resetFailure() {
            failureCount = 0;
            successCount++;
            return successCount;
        }
    }

    /**
     * 节点状态信息。
     *
 * @author CH
     */
    public static class NodeStatusInfo {
        private final String nodeId;
        private final boolean faulty;
        private final int failureCount;
        private final int successCount;

        /**
         * 构造状态信息。
         *
         * @param nodeId      节点ID
         * @param faulty      是否故障
         * @param failureCount 失败次数
         * @param successCount 成功次数
         */
        public NodeStatusInfo(String nodeId, boolean faulty, int failureCount, int successCount) {
            this.nodeId = nodeId;
            this.faulty = faulty;
            this.failureCount = failureCount;
            this.successCount = successCount;
        }

        /**
         * 获取节点ID。
         *
         * @return nodeId
         */
        public String getNodeId() {
            return nodeId;
        }

        /**
         * 是否故障。
         *
         * @return true 故障
         */
        public boolean isFaulty() {
            return faulty;
        }

        /**
         * 获取失败次数。
         *
         * @return failureCount
         */
        public int getFailureCount() {
            return failureCount;
        }

        /**
         * 获取成功次数。
         *
         * @return successCount
         */
        public int getSuccessCount() {
            return successCount;
        }
    }
}
