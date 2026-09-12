package com.chua.common.support.network.server.dht;

/**
 * DHT 消息类型枚举。
 * <p>
 * 定义了 DHT 协议中所有的消息类型，包括请求类型和对应的响应类型。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum DhtMessageType {

    /**
     * Ping 探测请求，用于检测节点是否存活
     */
    PING,

    /**
     * Ping 探测响应
     */
    PONG,

    /**
      * 查找节点请求，查询目标节点 标识 附近的节点列表
     */
    FIND_NODE,

    /**
     * 查找节点响应，返回目标节点附近的节点列表
     */
    FIND_NODE_RESPONSE,

    /**
     * 存储值请求，将键值对存储到 DHT 网络中
     */
    STORE,

    /**
     * 存储值响应
     */
    STORE_RESPONSE,

    /**
     * 查找值请求，根据键从 DHT 网络中查询对应的值
     */
    FIND_VALUE,

    /**
     * 查找值响应，返回查询到的值或近邻节点列表
     */
    FIND_VALUE_RESPONSE,

    /**
     * Bootstrap 请求，用于新节点加入网络时发现其他节点
     */
    BOOTSTRAP,

    /**
     * Bootstrap 响应
     */
    BOOTSTRAP_RESPONSE,

    /**
     * NAT 检测请求，用于探测节点的公网地址
     */
    NAT_DETECT,

    /**
     * NAT 检测响应，携带发送者看到的请求来源地址
     */
    NAT_DETECT_RESPONSE,

    /**
      * 获取 Peers 请求，用于 钻头torrent DHT 查询某个 infohash 的 peers
     */
    GET_PEERS,

    /**
     * 获取 Peers 响应，返回 peers 列表或近邻节点
     */
    GET_PEERS_RESPONSE,

    /**
     * 宣告 Peer 请求，将自己宣告为某个 infohash 的下载者
     */
    ANNOUNCE_PEER,

    /**
     * 宣告 Peer 响应
     */
    ANNOUNCE_PEER_RESPONSE
}
