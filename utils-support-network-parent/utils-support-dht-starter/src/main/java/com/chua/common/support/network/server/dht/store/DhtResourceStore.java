package com.chua.common.support.network.server.dht.store;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * DHT 资源（InfoHash）对等节点存储。
 * <p>
 * 用于 BitTorrent DHT 场景，记录每个 infohash 对应的 peers。
 * </p>
 *
 * @author CH
 */
@Slf4j
public class DhtResourceStore {

    /**
     * infohash 到 DiscoveredPeer 列表的映射
     */
    private final Map<String, CopyOnWriteArrayList<DiscoveredPeer>> store = new ConcurrentHashMap<>();

    /**
     * 记录某个 infohash 的 peer。
     *
     * @param infohash     infohash 字符串
     * @param host         peer 主机地址
     * @param port         peer 端口号
     * @param sourceNodeId 发现该 peer 的源节点 ID
     */
    public void recordPeer(String infohash, String host, int port, String sourceNodeId) {
        DiscoveredPeer peer = new DiscoveredPeer(host, port, sourceNodeId, System.currentTimeMillis());
        store.computeIfAbsent(infohash, k -> new CopyOnWriteArrayList<>()).add(peer);
    }

    /**
     * 从响应中批量记录 peers。
     *
     * @param infohash infohash 字符串
     * @param peers    peer 列表
     */
    public void recordPeersFromResponse(String infohash, List<DiscoveredPeer> peers) {
        if (peers == null || peers.isEmpty()) {
            return;
        }
        store.computeIfAbsent(infohash, k -> new CopyOnWriteArrayList<>()).addAll(peers);
    }

    /**
     * 获取指定 infohash 的所有 peers。
     *
     * @param infohash infohash 字符串
     * @return peer 列表
     */
    public List<DiscoveredPeer> getPeers(String infohash) {
        List<DiscoveredPeer> peers = store.get(infohash);
        if (peers == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(peers);
    }

    /**
     * 获取所有已记录的 infohash 集合。
     *
     * @return infohash 集合
     */
    public Set<String> getInfohashes() {
        return new LinkedHashSet<>(store.keySet());
    }

    /**
     * 获取指定 infohash 的 peer 数量。
     *
     * @param infohash infohash 字符串
     * @return peer 数量
     */
    public int peerCount(String infohash) {
        List<DiscoveredPeer> peers = store.get(infohash);
        return peers == null ? 0 : peers.size();
    }

    /**
     * 获取已记录的 infohash 总数。
     *
     * @return infohash 数量
     */
    public int totalInfohashes() {
        return store.size();
    }

    /**
     * 获取所有 peer 记录总数。
     *
     * @return peer 记录总数
     */
    public int totalPeerRecords() {
        return store.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 获取全部 infohash 和对应的 peers 映射。
     *
     * @return 只读的映射快照
     */
    public Map<String, List<DiscoveredPeer>> getAll() {
        Map<String, List<DiscoveredPeer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, CopyOnWriteArrayList<DiscoveredPeer>> e : store.entrySet()) {
            result.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return result;
    }

    /**
     * 发现的 peer 记录。
     * <p>
     * 记录从 DHT 网络中发现的 peer 信息，包括网络地址和来源。
     * </p>
     */
    public static class DiscoveredPeer {

        /**
         * peer 主机地址。
         */
        public final String host;

        /**
         * peer 端口号。
         */
        public final int port;

        /**
         * 发现该 peer 的源节点 ID。
         */
        public final String sourceNodeId;

        /**
         * 发现时间戳。
         */
        public final long discoveredAt;

        /**
         * 构造发现记录。
         *
         * @param host         peer 主机地址
         * @param port         peer 端口号
         * @param sourceNodeId 源节点 ID
         * @param discoveredAt 发现时间戳
         */
        public DiscoveredPeer(String host, int port, String sourceNodeId, long discoveredAt) {
            this.host = host;
            this.port = port;
            this.sourceNodeId = sourceNodeId;
            this.discoveredAt = discoveredAt;
        }
    }
}
