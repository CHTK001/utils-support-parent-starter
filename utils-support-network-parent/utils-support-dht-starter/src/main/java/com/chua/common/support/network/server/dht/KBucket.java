package com.chua.common.support.network.server.dht;

import lombok.extern.slf4j.Slf4j;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
* Kademlia K-Bucket。
* <p>
* 维护一个按最近活跃时间排序的双端队列（最近活跃在队尾），
* 遵循 Kademlia 规范的插入策略：已存在则移到队尾，未满则直接添加，
* 已满时若最旧节点已超时则替换，否则拒绝。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class KBucket {

    /**
    * Bucket 容量上限（K 值）
    */
    private final int k;

    /**
    * 节点条目的双端队列，队首为最近最少活跃的节点，队尾为最近活跃的节点
    */
    private final Deque<KBucketEntry> entries;

    /**
    * 当前 Bucket 在路由表中的索引位置
    */
    private final int bucketIndex;

    /**
    * 构造一个容量为 k 的 K-Bucket。
    *
    * @param k            Bucket 容量
    * @param bucketIndex  在路由表中的索引
    */
    public KBucket(int k, int bucketIndex) {
        this.k = k;
        this.bucketIndex = bucketIndex;
        this.entries = new ConcurrentLinkedDeque<>();
    }

    /**
    * 插入一个节点到 Bucket 中。
    * <p>
    * 遵循 Kademlia 规范：
    * 1. 如果节点已存在，移到队尾并刷新时间戳。
    * 2. 如果 Bucket 未满，添加到队尾。
    * 3. 如果已满且队首节点已超时，替换队首节点。
    * 4. 否则拒绝插入。
    * </p>
    *
    * @param peer 要插入的节点
    * @return 插入成功返回 true，被拒绝返回 false
    */
    public synchronized boolean insert(DhtPeer peer) {
        KBucketEntry existing = find(peer.getNodeId());
        if (existing != null) {
            entries.remove(existing);
            entries.addLast(existing);
            existing.refresh();
            return true;
        }
        if (entries.size() < k) {
            entries.addLast(new KBucketEntry(peer));
            return true;
        }
        KBucketEntry oldest = entries.peekFirst();
        if (oldest != null && oldest.isStale(30_000)) {
            entries.pollFirst();
            entries.addLast(new KBucketEntry(peer));
            return true;
        }
        return false;
    }

    /**
    * 根据节点 标识 从 Bucket 中移除节点。
    *
    * @param nodeId 要移除的节点 标识
    * @return 移除成功返回 true
    */
    public synchronized boolean remove(String nodeId) {
        return entries.removeIf(e -> e.getPeer().getNodeId().equals(nodeId));
    }

    /**
    * 根据节点 标识 查找 Bucket 中的条目。
    *
    * @param nodeId 要查找的节点 标识
    * @return 找到的 kbucketentry，未找到返回 空
    */
    public synchronized KBucketEntry find(String nodeId) {
        for (KBucketEntry e : entries) {
            if (e.getPeer().getNodeId().equals(nodeId)) {
                return e;
            }
        }
        return null;
    }

    /**
    * 获取指定数量的最近活跃节点列表。
    *
    * @param count 需要的节点数量
    * @return 节点列表
    */
    public synchronized List<DhtPeer> getPeers(int count) {
        return entries.stream()
                .map(KBucketEntry::getPeer)
                .limit(count)
                .collect(Collectors.toList());
    }

    /**
    * 获取 Bucket 中所有节点。
    *
    * @return 全部节点列表
    */
    public synchronized List<DhtPeer> getAllPeers() {
        return entries.stream().map(KBucketEntry::getPeer).collect(Collectors.toList());
    }

    /**
    * 获取 Bucket 中当前节点数量。
    *
    * @return 节点数量
    */
    public synchronized int size() {
        return entries.size();
    }

    /**
    * 判断 Bucket 是否已满。
    *
    * @return 达到容量上限返回 true
    */
    public synchronized boolean isFull() {
        return entries.size() >= k;
    }

    /**
    * 清空 Bucket 中的所有节点。
    */
    public synchronized void clear() {
        entries.clear();
    }
}
