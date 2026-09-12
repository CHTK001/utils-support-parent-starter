package com.chua.common.support.network.server.dht;

import com.chua.common.support.lang.json.Json;
import lombok.Builder;

import java.io.Serializable;
import java.net.InetSocketAddress;

/**
 * DHT 远端对等节点。
 * <p>
   * 表示 DHT 网络中的一个节点，包含节点 标识、网络地址以及与当前节点的交互状态。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Builder(toBuilder = true)
public record DhtPeer(
        /**
          * 节点的 160 位 Kademlia 节点 标识（十六进制字符串）
         */
        String nodeId,
        /**
         * 节点的主机地址（IP 或域名）
         */
        String host,
        /**
         * 节点的端口号
         */
        int port,
        /**
         * 最近一次从该节点收到消息的时间戳（毫秒）
         */
        long lastSeen,
        /**
         * 对该节点连续 Ping 失败的次数，超过阈值后将替换出路由表
         */
        int failedPings
) implements Serializable {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
      * 获取节点的 160 位 Kademlia 节点 标识。
     *
     * @return 节点 标识
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * 获取节点的主机地址。
     *
     * @return 主机地址
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取节点的端口号。
     *
     * @return 端口号
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取最近一次收到消息的时间戳。
     *
     * @return 时间戳（毫秒）
     */
    public long getLastSeen() {
        return lastSeen;
    }

    /**
     * 获取连续 Ping 失败次数。
     *
     * @return 失败次数
     */
    public int getFailedPings() {
        return failedPings;
    }

    /**
     * 判断节点在指定超时时间内是否可达。
     *
     * @param timeoutMs 超时时间（毫秒）
     * @return 如果 最后一个seen 在 超时ms 内返回 true，否则 false
     */
    public boolean isReachable(long timeoutMs) {
        return System.currentTimeMillis() - lastSeen < timeoutMs;
    }

    /**
     * 将当前节点序列化为 JSON 字符串。
     *
     * @return JSON 字符串
     */
    public String toFullString() {
        return Json.toJson(this);
    }

    /**
      * 将当前节点转换为 inet套接字地址 用于网络通信。
     *
     * @return InetSocketAddress 实例
     */
    public InetSocketAddress toAddress() {
        return new InetSocketAddress(host, port);
    }
}
