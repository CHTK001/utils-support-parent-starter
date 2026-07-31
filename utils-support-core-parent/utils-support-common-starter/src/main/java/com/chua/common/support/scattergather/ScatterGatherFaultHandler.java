package com.chua.common.support.scattergather;

/**
 * 节点故障处理器。
 * <p>在节点故障标记与恢复时回调。</p>
 *
 * @author CH
 */
public interface ScatterGatherFaultHandler {

    /**
     * 节点被标记为故障时调用。
     *
     * @param nodeId        节点ID
     * @param failureCount  连续失败次数
     */
    void onNodeMarkedFaulty(String nodeId, int failureCount);

    /**
     * 节点恢复时调用。
     *
     * @param nodeId        节点ID
     * @param successCount  连续成功次数
     */
    void onNodeRecovered(String nodeId, int successCount);
}
