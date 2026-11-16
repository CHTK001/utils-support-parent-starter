package com.chua.common.support.network.frp;

/**
 * FRP 显式映射协议：认证信令与数据平面共用同一监听端口。
 *
 * <p>所有连接建立后第一行为握手行，按前缀区分类型：</p>
 * <ul>
 *   <li>{@code AUTH|clientId|host|port|signature}：认证连接（短交互）。
 *       用初始共享密钥签名换取会话 token，验签通过后返回 {@code TOKEN|token}，
 *       随后保持为信令长连接，按行收发映射规则与隧道信令。</li>
 *   <li>{@code CONNECT|channelId|role|signature}：数据平面连接。
 *       携带会话 token 派生的签名校验，通过后与对端裸字节流双向桥接（TCP 代理模式）。</li>
 * </ul>
 *
 * <p>映射规则为显式三元组（目标地址、目标端口、本地端口）：</p>
 * <ul>
 *   <li>{@code EXPOSE}（提供方）：本机 {@code localPort} 上的服务以
 *       {@code targetHost:targetPort} 为逻辑标识对外提供；</li>
 *   <li>{@code MAP}（访问方）：本机监听 {@code localPort}，访问它即访问远端
 *       {@code targetHost:targetPort} 服务。</li>
 * </ul>
 * 服务端按「{@code targetHost:targetPort} → 提供方 clientId」精确路由，不做任何猜测匹配。
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface FrpProtocol {

    /**
     * 认证握手前缀（AUTH|clientId|host|port|signature）
     */
    String PREFIX_AUTH = "AUTH";

    /**
     * 认证成功响应（TOKEN|token）
     */
    String PREFIX_TOKEN = "TOKEN";

    /**
     * 暴露规则注册（EXPOSE|token|ruleId|targetHost|targetPort|localPort）
     */
    String PREFIX_EXPOSE = "EXPOSE";

    /**
     * 暴露规则注册成功（EXPOSE_OK|ruleId）
     */
    String PREFIX_EXPOSE_OK = "EXPOSE_OK";

    /**
     * 暴露规则撤销（EXPOSE_DEL|token|ruleId）
     */
    String PREFIX_EXPOSE_DEL = "EXPOSE_DEL";

    /**
     * 映射规则注册（MAP|token|ruleId|targetHost|targetPort|localPort）
     */
    String PREFIX_MAP = "MAP";

    /**
     * 映射规则注册成功（MAP_OK|ruleId）
     */
    String PREFIX_MAP_OK = "MAP_OK";

    /**
     * 映射规则撤销（MAP_DEL|token|ruleId）
     */
    String PREFIX_MAP_DEL = "MAP_DEL";

    /**
     * 隧道开启请求（OPEN|token|requestId|targetHost|targetPort）
     */
    String PREFIX_OPEN = "OPEN";

    /**
     * 隧道开启成功（OPENED|requestId|channelId）
     */
    String PREFIX_OPENED = "OPENED";

    /**
     * 隧道开启通知（发给服务提供方：TUNNEL_OPEN|token|channelId|targetHost|targetPort）
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
     * 数据平面连接握手前缀（CONNECT|channelId|role|signature）
     */
    String PREFIX_CONNECT = "CONNECT";

    /**
     * 访问方角色
     */
    String ROLE_VISITOR = "visitor";

    /**
     * 提供方角色
     */
    String ROLE_PROVIDER = "provider";

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