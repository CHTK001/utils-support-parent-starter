package com.chua.common.support.network.discovery.peermesh;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 超时剔除管理器：定期清理长时间未活动的节点。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class EvictionManager {

    /** 配置 */
    private final MeshConfig config;
    /** 节点表 */
    private final NodeTable nodeTable;
    /** 本地服务器ID */
    private final String localServerId;
    /** Discovery */
    private final PeerMeshDiscovery discovery;

    /**
     * 构造函数。
     *
     * @param config 配置
     * @param nodeTable 节点表
     * @param localServerId 本地 serverId（剔除时排除自身）
     * @param discovery PeerMeshDiscovery 实例
     */
    public EvictionManager(MeshConfig config, NodeTable nodeTable, String localServerId,
                           PeerMeshDiscovery discovery) {
        this.config = config;
        this.nodeTable = nodeTable;
        this.localServerId = localServerId;
        this.discovery = discovery;
    }

    /**
     * 执行一次剔除检查。
     */
    public void evict() {
        long now = System.currentTimeMillis();
        long timeoutMs = config.getEvictTimeout() * 1000L;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, NodeTable.NodeEntry> entry : nodeTable.getAllEntries().entrySet()) {
            if (localServerId.equals(entry.getKey())) {
                continue;
            }
            if (now - entry.getValue().getLastSeen() > timeoutMs) {
                toRemove.add(entry.getKey());
            }
        }
        for (String serverId : toRemove) {
            NodeTable.NodeEntry removed = nodeTable.remove(serverId);
            if (removed != null) {
                discovery.removeServicesByServerId(serverId);
                log.debug("节点剔除: {}", serverId);
            }
        }
    }
}
