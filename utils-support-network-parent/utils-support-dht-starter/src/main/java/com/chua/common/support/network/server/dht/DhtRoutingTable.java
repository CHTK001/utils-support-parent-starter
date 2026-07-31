package com.chua.common.support.network.server.dht;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kademlia 路由表。
 * <p>
 * 维护 160 个 K-Bucket，每个 Bucket 对应 160 位 ID 空间的一个前缀长度。
 * 提供节点插入、查找、以及基于 XOR 距离的最近节点查询功能。
 * </p>
 *
 * @author CH
 */
@Slf4j
public class DhtRoutingTable {

    /**
     * 本地节点的 ID
     */
    private final KademliaNodeId selfId;

    /**
     * K 值（每个 Bucket 的最大容量）
     */
    private final int k;

    /**
     * 160 个 K-Bucket 数组
     */
    private final KBucket[] buckets;

    /**
     * 构造路由表。
     *
     * @param selfId 本地节点 ID
     * @param k      K 值（每个 Bucket 容量）
     */
    public DhtRoutingTable(KademliaNodeId selfId, int k) {
        this.selfId = selfId;
        this.k = k;
        this.buckets = new KBucket[KademliaNodeId.ID_LENGTH];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new KBucket(k, i);
        }
    }

    /**
     * 插入节点到路由表。
     * <p>
     * 根据节点 ID 与本地 ID 的 XOR 距离计算目标 Bucket 索引，
     * 如果 Bucket 已满且最旧节点连续 3 次 Ping 失败，则替换该节点。
     * </p>
     *
     * @param peer 要插入的节点
     * @return 插入成功返回 true
     */
    public synchronized boolean insert(DhtPeer peer) {
        if (peer.getHost() == null || "0.0.0.0".equals(peer.getHost())) {
            return false;
        }
        KademliaNodeId peerId = KademliaNodeId.fromHex(peer.getNodeId());
        if (peerId.equals(selfId)) {
            return false;
        }
        int bucketIndex = selfId.getBucketIndex(peerId);
        KBucket bucket = buckets[bucketIndex];
        boolean inserted = bucket.insert(peer);
        if (!inserted) {
            KBucketEntry oldest = bucket.getAllPeers().stream()
                    .map(p -> new KBucketEntry(p))
                    .min(Comparator.comparingLong(KBucketEntry::getLastSeen))
                    .orElse(null);
            if (oldest != null) {
                peer = peer.toBuilder().failedPings(oldest.getPeer().getFailedPings() + 1).build();
                if (oldest.getPeer().getFailedPings() >= 3) {
                    bucket.remove(oldest.getPeer().getNodeId());
                    bucket.insert(peer);
                    return true;
                }
            }
        }
        return inserted;
    }

    /**
     * 根据节点 ID 查找路由表中的节点。
     *
     * @param nodeId 要查找的节点 ID
     * @return 找到的 DhtPeer，未找到返回 null
     */
    public synchronized DhtPeer findPeer(String nodeId) {
        int bucketIndex = selfId.getBucketIndex(KademliaNodeId.fromHex(nodeId));
        KBucketEntry entry = buckets[bucketIndex].find(nodeId);
        return entry != null ? entry.getPeer() : null;
    }

    /**
     * 根据节点 ID 从路由表中移除节点。
     *
     * @param nodeId 要移除的节点 ID
     */
    public synchronized void removePeer(String nodeId) {
        int bucketIndex = selfId.getBucketIndex(KademliaNodeId.fromHex(nodeId));
        buckets[bucketIndex].remove(nodeId);
    }

    /**
     * 查找距离目标节点最近的 count 个节点。
     * <p>
     * 首先从目标 Bucket 获取，不够时从临近 Bucket 扩展，
     * 最后按 XOR 距离排序后返回前 count 个。
     * </p>
     *
     * @param target 目标节点 ID
     * @param count  需要的节点数量
     * @return 最近节点的列表
     */
    public synchronized List<DhtPeer> findClosestPeers(KademliaNodeId target, int count) {
        List<DhtPeer> result = new ArrayList<>();
        int bucketIndex = selfId.getBucketIndex(target);
        addBucketPeers(result, buckets[bucketIndex], count);
        if (result.size() < count) {
            for (int i = 1; i < KademliaNodeId.ID_LENGTH; i++) {
                int lower = bucketIndex - i;
                int upper = bucketIndex + i;
                if (lower >= 0) {
                    addBucketPeers(result, buckets[lower], count - result.size());
                }
                if (result.size() >= count) {
                    break;
                }
                if (upper < KademliaNodeId.ID_LENGTH) {
                    addBucketPeers(result, buckets[upper], count - result.size());
                }
                if (result.size() >= count) {
                    break;
                }
            }
        }
        result.sort(Comparator.comparingInt(p -> {
            KademliaNodeId pid = KademliaNodeId.fromHex(p.getNodeId());
            return pid.xor(target).getInt().bitLength();
        }));
        return result.subList(0, Math.min(count, result.size()));
    }

    /**
     * 将指定 Bucket 中的节点添加到结果列表中，直到达到 maxCount 上限。
     *
     * @param result   结果列表
     * @param bucket   源 Bucket
     * @param maxCount 最大添加数量
     */
    private void addBucketPeers(List<DhtPeer> result, KBucket bucket, int maxCount) {
        for (DhtPeer p : bucket.getAllPeers()) {
            if (result.size() >= maxCount) {
                break;
            }
            if (!result.contains(p)) {
                result.add(p);
            }
        }
    }

    /**
     * 获取路由表中所有节点。
     *
     * @return 全部节点列表
     */
    public synchronized List<DhtPeer> getAllPeers() {
        List<DhtPeer> all = new ArrayList<>();
        for (KBucket bucket : buckets) {
            all.addAll(bucket.getAllPeers());
        }
        return all;
    }

    /**
     * 获取路由表中的节点总数。
     *
     * @return 节点总数
     */
    public synchronized int totalPeers() {
        int count = 0;
        for (KBucket bucket : buckets) {
            count += bucket.size();
        }
        return count;
    }

    /**
     * 获取路由表中所有节点的 ID 集合。
     *
     * @return 节点 ID 的 Set
     */
    public synchronized Set<String> getAllPeerIds() {
        Set<String> ids = new HashSet<>();
        for (KBucket bucket : buckets) {
            for (DhtPeer p : bucket.getAllPeers()) {
                ids.add(p.getNodeId());
            }
        }
        return ids;
    }
}
