package com.chua.common.support.network.discovery.peermesh;

import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 路由表传播：负责向全表节点发送心跳及 NEW_PEER 推送。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MembershipPropagation {

    private final MeshConfig config;
    private final NodeTable nodeTable;
    private final String localServerId;
    private final PeerMeshDiscovery discovery;
    /**
     * 是否为 UDP 通信模式
     */
    private final boolean udp;

    /**
     * 构造函数。
     *
     * @param config 配置
     * @param nodeTable 节点表
     * @param localServerId 本地 serverId
     * @param discovery PeerMeshDiscovery 实例，用于发送消息
     */
    public MembershipPropagation(MeshConfig config, NodeTable nodeTable,
                                 String localServerId, PeerMeshDiscovery discovery) {
        this.config = config;
        this.nodeTable = nodeTable;
        this.localServerId = localServerId;
        this.discovery = discovery;
        this.udp = "udp".equalsIgnoreCase(config.getMode());
    }

    /**
     * 发送心跳至所有已知节点。
     */
    public void sendHeartbeat() {
        List<NodeTable.NodeEntry> known = new ArrayList<>(nodeTable.getAllEntries().values());
        String payload = Json.toJson(known);
        MessageProtocol.PeerMeshMessage msg = new MessageProtocol.PeerMeshMessage(MessageProtocol.TYPE_HEARTBEAT, payload);
        for (NodeTable.NodeEntry entry : known) {
            if (localServerId.equals(entry.getDiscovery().getServerId())) {
                continue;
            }
            try {
                if (udp) {
                    discovery.sendUdpMessage(entry.getDiscovery(), msg);
                } else {
                    discovery.sendMessage(entry.getDiscovery(), msg, true);
                }
            } catch (Exception e) {
                log.debug("心跳发送失败至 {}: {}", entry.getDiscovery().getServerId(), e.getMessage());
            }
        }
    }

    /**
     * 推送新节点到所有已知节点。
     *
     * @param newEntry 新节点条目
     */
    public void pushNewPeer(NodeTable.NodeEntry newEntry) {
        if (newEntry == null) {
            return;
        }
        String payload = Json.toJson(newEntry);
        MessageProtocol.PeerMeshMessage msg = new MessageProtocol.PeerMeshMessage(MessageProtocol.TYPE_NEW_PEER, payload);
        for (NodeTable.NodeEntry entry : nodeTable.getAllEntries().values()) {
            if (localServerId.equals(entry.getDiscovery().getServerId())) {
                continue;
            }
            try {
                if (udp) {
                    discovery.sendUdpMessage(entry.getDiscovery(), msg);
                } else {
                    discovery.sendMessage(entry.getDiscovery(), msg);
                }
            } catch (Exception e) {
                log.debug("NEW_PEER 推送失败: {}", e.getMessage());
            }
        }
    }
}