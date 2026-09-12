package com.chua.common.support.network.server.dht;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * DHT 爬虫监听器。
 * <p>
 * 用于监听 DHT 网络爬取过程中的事件回调，包括 infohash 发现与 peers 收集。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DhtCrawlListener {

    /**
     * 当发现新的 infohash 时触发回调。
     *
     * @param infohash 发现的 infohash 值
     * @param source 消息来源的 套接字 地址
     */
    default void onInfohash(String infohash, InetSocketAddress source) {
    }

    /**
     * 当收集到 peers 列表时触发回调。
     *
     * @param infohash 对应的 infohash
     * @param peers 收集到的 peer 节点列表
     */
    default void onPeers(String infohash, List<DhtPeer> peers) {
    }
}
