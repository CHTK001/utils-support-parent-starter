package com.chua.common.support.network.sip;

/**
 * SIP 单端口协议：认证与 frp 数据平面共用同一监听端口。
 *
 * <p>所有连接建立后第一行为握手行，按前缀区分类型：</p>
 * <ul>
 *   <li>{@code AUTH|clientId|host|port|signature}：认证连接（短交互）。
 *       用初始共享密钥签名换取会话 token，验签通过后返回 {@code TOKEN|token}。</li>
 *   <li>{@code CONNECT|channelId|role|token}：数据平面连接。
 *       携带会话 token 校验，通过后与对端裸字节流双向桥接（TCP 代理模式）。</li>
 * </ul>
 * 认证连接获得 token 后可保持为信令长连接，后续按行收发信令（SERVICE / OPEN / CLOSE）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SipProtocol {

    /**
     * 认证握手前缀（AUTH|clientId|host|port|signature）
     */
    String PREFIX_AUTH = "AUTH";

    /**
     * 认证成功响应（TOKEN|token）
     */
    String PREFIX_TOKEN = "TOKEN";

    /**
     * 隧道服务注册（SERVICE|token|serviceName）
     */
    String PREFIX_SERVICE = "SERVICE";

    /**
     * 隧道服务注册成功（SERVICE_OK|serviceName）
     */
    String PREFIX_SERVICE_OK = "SERVICE_OK";

    /**
     * 隧道开启请求（OPEN|token|requestId|serviceName）
     */
    String PREFIX_OPEN = "OPEN";

    /**
     * 隧道开启成功（OPENED|requestId|channelId）
     */
    String PREFIX_OPENED = "OPENED";

    /**
     * 隧道开启通知（发给服务提供方：TUNNEL_OPEN|token|channelId|serviceName）
     */
    String PREFIX_TUNNEL_OPEN = "TUNNEL_OPEN";

    /**
     * 错误（ERROR|requestId|reason）
     */
    String PREFIX_ERROR = "ERROR";

    /**
     * 隧道关闭（CLOSE|token|channelId）
     */
    String PREFIX_CLOSE = "CLOSE";

    /**
     * 心跳请求（PING|token）
     */
    String PREFIX_PING = "PING";

    /**
     * 心跳响应（PONG|token）
     */
    String PREFIX_PONG = "PONG";

    /**
     * 数据平面连接握手前缀（CONNECT|channelId|role|token）
     */
    String PREFIX_CONNECT = "CONNECT";

    /**
     * 多路复用数据面握手前缀（MUXCONN|首个channelId|role|token），
     * 单条连接承载同角色全部隧道：帧格式 [4B 长度][16B channelId][payload]，空 payload 为通道关闭标记
     */
    String PREFIX_MUXCONN = "MUXCONN";

    /**
     * 通配服务名：provider 以此注册时表示"动态目标中继"模式，
     * visitor 请求的 serviceName 即为目标 host:port，由 provider 现场拨号
     */
    String WILDCARD_SERVICE = "*";

    /**
     * 字段分隔符
     */
    String SEPARATOR = "|";

    /**
     * 拼接行。
     *
     * @param fields 字段
     * @return 拼接结果
     */
    static String line(String... fields) {
        return String.join(SEPARATOR, fields);
    }
}
