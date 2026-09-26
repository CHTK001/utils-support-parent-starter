package com.chua.common.support.network.rpc;

import java.util.Map;
import java.util.Objects;

/**
 * RPC 连接信息，描述一条服务端连接。
 *
 * <p>各协议实现按自身能力填充字段：Dubbo/Netty 类协议可获得完整连接地址，
 * JSON-RPC（HTTP 短连接）仅能提供最近请求的来源地址。</p>
 *
 * @param protocol       协议名称（dubbo / sofa / json / native）
 * @param localAddress   本地监听地址
 * @param localPort      本地监听端口
 * @param remoteAddress  对端（客户端）地址
 * @param remotePort     对端端口
 * @param state          连接状态（ACTIVE / IDLE / CLOSED 等）
 * @param createTime     连接建立时间戳（毫秒），未知为 0
 * @param lastActiveTime 最近活跃时间戳（毫秒）
 * @param attributes     扩展属性
 * @author CH
 * @since 4.0.0.42
 */
public record RpcConnectionInfo(String protocol, String localAddress, Integer localPort,
                                String remoteAddress, Integer remotePort, String state,
                                long createTime, long lastActiveTime,
                                Map<String, String> attributes) {

    /**
     * 规范构造器：扩展属性做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * 三个已知构造点（json / dubbo / sofa）均传 {@code Collections.emptyMap()}，
     * 且无 null 键值，故采用 {@link Map#copyOf}。</p>
     *
     * <p>{@code remoteAddress} / {@code remotePort} 在 sofa 协议下显式传 null
     * （服务端侧无对端地址），{@code protocol} / {@code state} 为协议固定字面量，
     * 均不做空值敌对处理。</p>
     *
     * @param protocol       协议名称
     * @param localAddress   本地监听地址
     * @param localPort      本地监听端口
     * @param remoteAddress  对端地址，未知为 null
     * @param remotePort     对端端口，未知为 null
     * @param state          连接状态
     * @param createTime     连接建立时间戳
     * @param lastActiveTime 最近活跃时间戳
     * @param attributes     扩展属性
     */
    public RpcConnectionInfo {
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes 不能为 null"));
    }
}
