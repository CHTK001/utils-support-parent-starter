package com.chua.common.support.network.discovery.peermesh;

import java.util.List;

/**
* 探针策略接口，用于在启动时或周期性发现对等节点。
*
* @author CH
* @since 4.0.0.42
 */
public interface ProbeStrategy {

    /**
    * 启动探针，开始扫描或连接种子。
    *
    * @throws Exception 启动失败时抛出异常
     */
    void start() throws Exception;

    /**
    * 停止探针，释放资源。
     */
    void stop() throws Exception;

    /**
    * 获取本次探针发现的所有节点条目。
    *
    * @return 节点条目列表（只读）
     */
    List<NodeTable.NodeEntry> getDiscoveredNodes();
}