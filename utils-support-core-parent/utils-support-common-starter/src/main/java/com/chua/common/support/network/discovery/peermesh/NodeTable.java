package com.chua.common.support.network.discovery.peermesh;

import com.chua.common.support.network.discovery.Discovery;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的节点表，支持按 epoch 合并（高 epoch 胜出）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NodeTable {

    /**
     * 节点条目：包含发现信息、最后见到时间和 epoch。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeEntry {
        /**
         * 发现信息
         */
        private Discovery discovery;

        /**
         * 最后见到时间戳（毫秒）
         */
        private long lastSeen;

        /**
         * 节点 epoch（越大越新）
         */
        private int epoch;
    }

    /**
     * 内部存储：key = serverId，value = NodeEntry。
     */
    private final ConcurrentHashMap<String, NodeEntry> nodes = new ConcurrentHashMap<>();

    /**
     * 插入或更新节点。
     * 如果新条目的 epoch 大于等于现有条目，则替换；否则忽略。
     *
     * @param serverId 节点唯一标识
     * @param discovery 发现信息
     * @param epoch 节点 epoch（越大越新）
     * @param nowMs 当前时间戳（毫秒）
     */
    public void upsert(String serverId, Discovery discovery, int epoch, long nowMs) {
        Objects.requireNonNull(serverId, "serverId must not be null");
        Objects.requireNonNull(discovery, "discovery must not be null");
        nodes.compute(serverId, (key, old) -> {
            if (old == null || epoch >= old.epoch) {
                NodeEntry entry = new NodeEntry();
                entry.setDiscovery(discovery);
                entry.setLastSeen(nowMs);
                entry.setEpoch(epoch);
                return entry;
            }
            return old;
        });
    }

    /**
     * 根据 serverId 移除节点。
     *
     * @param serverId 节点唯一标识
     * @return 移除的节点条目，若不存在则返回 null
     */
    public NodeEntry remove(String serverId) {
        return nodes.remove(serverId);
    }

    /**
     * 获取指定节点的条目。
     *
     * @param serverId 节点唯一标识
     * @return 节点条目，若不存在则返回 null
     */
    public NodeEntry get(String serverId) {
        return nodes.get(serverId);
    }

    /**
     * 获取所有节点的发现信息副本。
     *
     * @return 不可变的 Discovery 集合
     */
    public Collection<Discovery> getAllDiscoveries() {
        return Collections.unmodifiableCollection(
                nodes.values().stream()
                        .map(NodeEntry::getDiscovery)
                        .toList()
        );
    }

    /**
     * 获取所有节点条目的映射（只读）。
     *
     * @return 不可变的 serverId -> NodeEntry 映射
     */
    public Map<String, NodeEntry> getAllEntries() {
        return Collections.unmodifiableMap(nodes);
    }

    /**
     * 与另一个节点表合并，采用高 epoch 优先的策略。
     *
     * @param other 另一个节点表
     * @param nowMs 当前时间戳（毫秒），用于更新 lastSeen
     */
    public void mergeFrom(NodeTable other, long nowMs) {
        Objects.requireNonNull(other, "other must not be null");
        other.nodes.forEach((serverId, entry) -> {
            upsert(serverId, entry.getDiscovery(), entry.getEpoch(), nowMs);
        });
    }

    /**
     * 清空所有节点。
     */
    public void clear() {
        nodes.clear();
    }

    /**
     * 获取当前节点数量。
     *
     * @return 节点数量
     */
    public int size() {
        return nodes.size();
    }

    /**
     * 判断节点表是否为空。
     *
     * @return 为空返回 true
     */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }
}
