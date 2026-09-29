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
 * <p>本 record 不可变，状态更新一律经 {@code toBuilder()} 派生新实例
 * （注解 {@code @Builder(toBuilder = true)}）：收到对端消息时刷新
 * {@link #lastSeen}，路由表 K 桶已满需挤掉最冷节点时递增 {@link #failedPings}。
 * 实现 {@link Serializable}，可被 {@code DhtRoutingTable} 持久化。</p>
 *
 * <p>可空性：规范构造器不做任何非空校验。{@link #host} 为 {@code null} 或
 * {@code "0.0.0.0"} 表示地址不可用，{@code DhtRoutingTable#insert} 会直接拒绝入表；
 * {@link #nodeId} 必须能被 {@code KademliaNodeId#fromHex} 解析，否则在入表时抛异常。
 * 其余三个分量为原始类型，{@link #lastSeen} 未显式赋值时为 0，表示「从未收到过消息」，
 * 此时 {@link #isReachable(long)} 因差值极大而恒返回 {@code true}。</p>
 *
 * @param nodeId 节点的 160 位 Kademlia 节点 标识，为 <b>40 字符十六进制字符串</b>
 *               （160 位 = 20 字节 = 40 个 hex 字符），由 {@code KademliaNodeId#toString}
 *               产出、由 {@code KademliaNodeId#fromHex} 解析回字节数组。
 *               取值来源为 DHT 消息头声明的发送方标识 {@code msg.getSenderId()}，
 *               响应类消息则为本节点 {@code selfId}。它同时充当路由表查找键：
 *               {@code findPeer} / {@code removePeer} 用它计算 XOR 距离以定位 K 桶，
 *               入表时还会与本节点 {@code selfId} 比对，相同即拒绝（不允许自己入表）。
 *               格式非法或为 {@code null} 时 {@code fromHex} 抛异常，故不应传空
 * @param host 节点的 主机地址，IP 字面量或域名。取值来源优先取消息头声明的
 *             {@code senderHost}，缺省时回退为数据报来源地址
 *             {@code sender.getHostString()}（已剥离 NAT 前地址）。
 *             约定：{@code null} 或 {@code "0.0.0.0"} 表示地址不可用，
 *             {@code insert} 遇到这两种值返回 {@code false}。允许为 {@code null}，
 *             但该节点将无法入表；与 {@link #port} 组合成 {@link #toAddress()} 发起通信
 * @param port 节点的 端口号，即对端 DHT 服务监听端口。取值来源优先取消息头声明的
 *             {@code senderPort}，仅当其大于 0 时才采用，否则回退为数据报来源端口
 *             {@code sender.getPort()}。原始 {@code int}，语义取值范围 1-65535
 *             （合法 TCP/UDP 端口），非法端口会导致后续套接字通信失败。
 *             允许为 0，表示「尚未获知真实端口」
 * @param lastSeen 最近一次从该节点收到消息的时刻，取值为
 *                 {@code System.currentTimeMillis()} 的 <b>epoch 毫秒时间戳</b>
 *                 （不是 {@link java.time.Duration}，也不是纳秒）。
 *                 取值来源为 {@code DhtProtocol#handleMessage} 等处收到对端消息时
 *                 立即打点，以及 {@code KBucketEntry#refresh} 在条目被确认活跃时
 *                 同步回写到 peer 上。原始 {@code long}，单位毫秒；未赋值时为 0
 *                 （纪元起点）。差值以 {@code System.currentTimeMillis()} 计算，
 *                 故两端须同机或已做时钟对齐，否则 {@link #isReachable(long)}
 *                 的可达性判断不可靠
 * @param failedPings 对该节点 连续 Ping 失败（等价于「连续未被确认活跃」）的次数，
 *                    是 Kademlia 路由表淘汰机制的计数依据。取值来源为
 *                    {@code DhtRoutingTable#insert}：目标 K 桶已满且插入失败时，
 *                    取桶内最冷节点的计数加一写入新节点；一旦发现最冷节点计数
 *                    <b>大于等于 3</b>，即把它移出并用新节点顶替。
 *                    成功收到消息时由 {@code KBucketEntry#refresh} 重置为 0。
 *                    原始 {@code int}，非负，语义上限 3（达到即触发淘汰），
 *                    超过 3 的取值无额外意义
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
     * 将当前节点转换为 inetSocket地址 用于网络通信。
     *
     * @return InetSocketAddress 实例
     */
    public InetSocketAddress toAddress() {
        return new InetSocketAddress(host, port);
    }
}
