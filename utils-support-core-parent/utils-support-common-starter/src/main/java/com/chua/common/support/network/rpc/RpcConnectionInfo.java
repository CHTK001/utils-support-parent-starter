package com.chua.common.support.network.rpc;

import java.util.Map;

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
}