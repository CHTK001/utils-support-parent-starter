package com.chua.common.support.network.discovery.peermesh;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * 超时剔除管理器：定期清理长时间未活动的节点。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
@Slf4j
public class EvictionManager {

    private final MeshConfig config;
    private final NodeTable nodeTable;
    private final PeerMeshDiscovery discovery;

    /**
     * 构造函数。
     *
     * @param config 配置
     * @param nodeTable 节点表
     * @param discovery PeerMeshDiscovery 实例
     */
    public EvictionManager(MeshConfig config, NodeTable nodeTable, PeerMeshDiscovery discovery) {
        this.config = config;
        this.nodeTable = nodeTable;
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