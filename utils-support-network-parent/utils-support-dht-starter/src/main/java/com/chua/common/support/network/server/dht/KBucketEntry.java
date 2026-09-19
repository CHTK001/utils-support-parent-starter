package com.chua.common.support.network.server.dht;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/**
 * K-Bucket 条目。
 * <p>
 * 包装了 {@link DhtPeer} 节点及其最近活跃时间戳，
 * 用于 K-Bucket 的 LRU 淘汰策略和过期判断。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@AllArgsConstructor
public class KBucketEntry implements Serializable {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 条目中包含的远程节点信息
     */
    private DhtPeer peer;

    /**
     * 最近一次从该节点收到消息的时间戳（毫秒）
     */
    private long lastSeen;

    /**
     * 使用指定节点创建条目，初始 最后一个seen 为当前系统时间。
     *
     * @param peer 远程节点
     */
    public KBucketEntry(DhtPeer peer) {
        this.peer = peer;
        this.lastSeen = System.currentTimeMillis();
    }

    /**
     * 判断该条目是否已过期（超过指定时间未收到消息）。
     *
     * @param timeoutMs 超时阈值（毫秒）
     * @return 如果超时返回 true
     */
    public boolean isStale(long timeoutMs) {
        return System.currentTimeMillis() - lastSeen > timeoutMs;
    }

    /**
     * 刷新条目的活跃时间戳，同时更新 peer 的 最后一个seen 并重置失败计数。
     * <p>
     * 如果 peer 为 空，则仅更新当前条目的 最后一个seen。
     * </p>
     */
    public void refresh() {
        this.lastSeen = System.currentTimeMillis();
        if (null != peer) {
            this.setPeer(peer.toBuilder().lastSeen(this.lastSeen).failedPings(0).build());
        }
    }
}
