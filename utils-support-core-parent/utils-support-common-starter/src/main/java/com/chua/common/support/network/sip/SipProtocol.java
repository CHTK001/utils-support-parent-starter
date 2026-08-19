package com.chua.common.support.network.sip;

/**
 * SIP 信令协议常量与报文构造工具。
 *
 * <p>基于现有 Sync 传输的 {@code topic:payload} 行格式，payload 内部使用
 * {@code |} 作为字段分隔符（避开 IP:PORT 中的冒号）。信令主题统一使用
 * {@code sip/} 前缀。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SipProtocol {

    /**
     * 信令主题前缀
     */
    String TOPIC_PREFIX = "sip/";

    /**
     * 主题通配符
     */
    String TOPIC_WILDCARD = "#";

    /**
     * 客户端注册主题（客户端上报可达地址）
     */
    String CMD_REGISTER = "sip/register";

    /**
     * 注册确认主题
     */
    String CMD_REGISTERED = "sip/registered";

    /**
     * 查询对端地址主题
     */
    String CMD_FIND = "sip/find";

    /**
     * 查询结果主题
     */
    String CMD_FOUND = "sip/found";

    /**
     * 查无此人主题
     */
    String CMD_NOTFOUND = "sip/notfound";

    /**
     * 消息转发主题
     */
    String CMD_MSG = "sip/msg";

    /**
     * 响应回传主题
     */
    String CMD_RESP = "sip/resp";

    /**
     * 服务器主动推送主题（topic|message）
     */
    String CMD_PUSH = "sip/push";

    /**
     * 隧道服务注册主题（服务提供方声明对外暴露的服务）
     */
    String CMD_TUNNEL_REGISTER = "sip/tunnel/register";

    /**
     * 隧道注册确认主题
     */
    String CMD_TUNNEL_REGISTERED = "sip/tunnel/registered";

    /**
     * 隧道开启请求主题（访问方请求建立通道）
     */
    String CMD_TUNNEL_OPEN = "sip/tunnel/open";

    /**
     * 隧道开启成功主题
     */
    String CMD_TUNNEL_OPENED = "sip/tunnel/opened";

    /**
     * 隧道开启失败主题
     */
    String CMD_TUNNEL_ERROR = "sip/tunnel/error";

    /**
     * 隧道关闭主题
     */
    String CMD_TUNNEL_CLOSE = "sip/tunnel/close";

    /**
     * 字段分隔符
     */
    String SEPARATOR = "|";

    /**
     * 构造注册报文（携带 HMAC-SHA256 签名）。
     *
     * @param host      可达地址
     * @param port      可达端口
     * @param signature 签名（HMAC-SHA256(token, clientId+host+port)）
     * @return 报文内容
     */
    static String register(String host, int port, String signature) {
        return host + SEPARATOR + port + SEPARATOR + signature;
    }

    /**
     * 构造查询报文。
     *
     * @param requestId 请求标识
     * @param peerId    对端标识
     * @return 报文内容
     */
    static String find(String requestId, String peerId) {
        return requestId + SEPARATOR + peerId;
    }

    /**
     * 构造查询结果报文。
     *
     * @param requestId 请求标识
     * @param peerId    对端标识
     * @param host      对端地址
     * @param port      对端端口
     * @return 报文内容
     */
    static String found(String requestId, String peerId, String host, int port) {
        return requestId + SEPARATOR + peerId + ":" + host + ":" + port;
    }

    /**
     * 构造查无此人报文。
     *
     * @param requestId 请求标识
     * @param peerId    对端标识
     * @return 报文内容
     */
    static String notFound(String requestId, String peerId) {
        return requestId + SEPARATOR + peerId;
    }

    /**
     * 构造消息或转发报文。
     *
     * @param id      目标或来源标识
     * @param content 消息内容
     * @return 报文内容
     */
    static String msg(String id, String content) {
        return id + SEPARATOR + content;
    }

    /**
     * 构造响应报文。
     *
     * @param id        目标或来源标识
     * @param requestId 请求标识
     * @param content   响应内容
     * @return 报文内容
     */
    static String resp(String id, String requestId, String content) {
        return id + SEPARATOR + requestId + SEPARATOR + content;
    }
}
