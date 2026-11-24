// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.tcp.JdkTcpClient;
import com.chua.common.support.network.tcp.TcpClient;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterFrameAuth;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * scatter 同步辅助工具：集中处理 TCP 短连接、超时、重试与 requestId 生成。
 *
 * <p>所有 Scatter 模块的网络调用统一通过此类，避免散落的重复代码与遗漏的超时控制。</p>
 *
 * @author CH
 * @since 4.0.0.42
 **/
@Slf4j
public final class ScatterSyncHelper {

    /**
     * 最大重试次数
     */
    private static final int MAX_RETRIES = 3;
    /**
     * 请求 ID 原子计数（线程安全，按目标节点隔离）
     */
    private static final ConcurrentHashMap<String, Integer> REQUEST_ID_SEQ = new ConcurrentHashMap<>();
    /**
     * 可注入的自定义 TCP 客户端（SPI/测试场景），null 时自动创建
     */
    private static volatile TcpClient customClient;
    /**
     * 帧鉴权共享密钥（进程级，客户端收发共用；AbstractScatterDiscovery 启动时注入）
     */
    private static volatile String authToken;

    /**
     * 构造方法，创建 ScatterSyncHelper 实例。
     */
    private ScatterSyncHelper() {
    }

    /**
     * 注入自定义 TCP 客户端（未启动前调用，SPI/测试场景）。
     * @param client 客户端，不允许为 null
     */
    public static void setCustomClient(TcpClient client) {
        customClient = client;
    }

    /**
     * 设置帧鉴权共享密钥（进程级，本 JVM 所有 scatter 客户端收发共用；
     * 与 {@link #setCustomClient} 的进程级注入同口径）。集群内节点须配置相同密钥。
     *
     * @param token 共享密钥；空/空白表示不启用帧鉴权
     */
    public static void setAuthToken(String token) {
        authToken = token;
    }

    /**
     * 对出站帧字节追加鉴权签名尾部（帧鉴权开启时），供本工具与 discovery 直发路径统一使用。
     *
     * @param frameBytes 原始帧字节（ScatterFrame#encode 的输出），不允许为 null
     * @return 签名后的线格式字节（未开启鉴权时与入参相同）
     */
    public static byte[] sign(byte[] frameBytes) {
        String token = authToken;
        return (token == null || token.isBlank()) ? frameBytes : ScatterFrameAuth.sign(frameBytes, token);
    }

    /**
     * 校验入站线格式字节并剥离签名尾部（帧鉴权开启时）；未开启时原样返回。
     *
     * @param wireBytes 线格式字节（可能带签名尾部），不允许为 null
     * @return 可解码的原始帧字节；验签失败返回 null
     */
    public static byte[] verify(byte[] wireBytes) {
        String token = authToken;
        if (token == null || token.isBlank()) {
            return wireBytes;
        }
        return ScatterFrameAuth.verifyAndStrip(wireBytes, token);
    }

    /**
     * 测试后清理静态状态，防止跨测试干扰。
     */
    public static void resetForTest() {
        customClient = null;
        authToken = null;
        REQUEST_ID_SEQ.clear();
    }

    /**
     * 拉取目标节点的服务表（带超时 + 重试）。
     *
     * @param context       请求上下文
     * @param node          目标节点
     * @param timeoutMillis 单次超时毫秒
     * @return 同步结果（失败时返回 null）
     */
    public static ScatterResult<List<Discovery>> fetch(ScatterContext context, ScatterNode node,
                                                       long timeoutMillis) {
        return fetchWithRetry(context, node, timeoutMillis, MAX_RETRIES, null);
    }

    /**
     * 拉取目标节点的服务表（带超时 + 重试），REQ 携带本端服务表（入网加速：被拉方在
     * {@code handle(REQ)} 中先合并拉取方自表再回表，双向 gossip）。
     *
     * @param context       请求上下文
     * @param node          目标节点
     * @param timeoutMillis 单次超时毫秒
     * @param announceSelf  随 REQ 携带的本端服务表（null/空表示不携带，兼容旧帧格式）
     * @return 同步结果（失败时返回 null）
     */
    public static ScatterResult<List<Discovery>> fetch(ScatterContext context, ScatterNode node,
                                                       long timeoutMillis, List<Discovery> announceSelf) {
        return fetchWithRetry(context, node, timeoutMillis, MAX_RETRIES, announceSelf);
    }

    private static ScatterResult<List<Discovery>> fetchWithRetry(ScatterContext context, ScatterNode node,
                                                                 long timeoutMillis, int remaining,
                                                                 List<Discovery> announceSelf) {
        try {
            ScatterFrame req = new ScatterFrame(ScatterProtocol.TYPE_REQ, nextRequestId(node),
                    context.getPath(), encodeAnnounce(announceSelf));
            byte[] signed = sign(req.encode());
            byte[] response;
            if (node.getProtocol() != null && "udp".equalsIgnoreCase(node.getProtocol())) {
                response = sendUdp(node.getHost(), node.getPort(), signed, timeoutMillis);
            } else {
                response = sendTcp(node.getHost(), node.getPort(), signed, timeoutMillis);
            }
            if (response == null || response.length == 0) {
                return remaining > 1 ? fetchWithRetry(context, node, timeoutMillis, remaining - 1, announceSelf)
                        : ScatterResult.timeout(node.getNodeId(), "空响应: " + node.getEndpoint());
            }
            byte[] verified = verify(response);
            if (verified == null) {
                return ScatterResult.failure(node.getNodeId(), "响应验签失败: " + node.getEndpoint());
            }
            ScatterFrame resp = ScatterFrame.decode(verified);
            if (resp.getType() == ScatterProtocol.TYPE_RESP && resp.getPayload().length > 0) {
                String json = new String(resp.getPayload(), StandardCharsets.UTF_8);
                List<Discovery> data = com.chua.common.support.lang.json.Json.fromJsonToList(json, Discovery.class);
                return ScatterResult.success(node.getNodeId(), data);
            }
            return remaining > 1 ? fetchWithRetry(context, node, timeoutMillis, remaining - 1, announceSelf)
                    : ScatterResult.failure(node.getNodeId(), "响应类型异常: " + resp.getType());
        } catch (Exception e) {
            if (remaining > 1) {
                log.debug("scatter 拉取重试 ({}/{}): {}", MAX_RETRIES - remaining + 1, MAX_RETRIES, e.getMessage());
                return fetchWithRetry(context, node, timeoutMillis, remaining - 1, announceSelf);
            }
            return ScatterResult.timeout(node.getNodeId(), "请求失败: " + e.getMessage());
        }
    }

    /**
     * 编码 REQ 自表载荷（JSON 数组）；空表返回空载荷（兼容旧帧格式）。
     * @param announce 方法入参 announce
     * @return 载荷字节
     */
    private static byte[] encodeAnnounce(List<Discovery> announce) {
        if (announce == null || announce.isEmpty()) {
            return new byte[0];
        }
        try {
            return com.chua.common.support.lang.json.Json.toJson(announce)
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("REQ 自表编码失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * 向节点推送数据（带超时，无响应值场景）。
     *
     * @param node          目标节点
     * @param frame         帧
     * @param timeoutMillis 超时毫秒
     * @return true=收到 ACK
     */
    public static boolean push(ScatterNode node, ScatterFrame frame, long timeoutMillis) {
        try {
            byte[] signed = sign(frame.encode());
            byte[] response;
            if (node.getProtocol() != null && "udp".equalsIgnoreCase(node.getProtocol())) {
                response = sendUdp(node.getHost(), node.getPort(), signed, timeoutMillis);
            } else {
                response = sendTcp(node.getHost(), node.getPort(), signed, timeoutMillis);
            }
            if (response != null && response.length > 0) {
                byte[] verified = verify(response);
                if (verified == null) {
                    log.debug("scatter 推送响应验签失败: {}", node.getNodeId());
                    return false;
                }
                ScatterFrame resp = ScatterFrame.decode(verified);
                return resp.getType() == ScatterProtocol.TYPE_ACK;
            }
            return false;
        } catch (Exception e) {
            log.debug("scatter 推送失败: {} - {}", node.getNodeId(), e.getMessage());
            return false;
        }
    }

    /**
     * 批量广播帧（逐个节点，失败不中断）。
     *
     * @param nodes         目标节点列表
     * @param frame         帧
     * @param timeoutMillis 超时毫秒
     */
    public static void broadcast(List<ScatterNode> nodes, ScatterFrame frame, long timeoutMillis) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        byte[] signed = sign(frame.encode());
        for (ScatterNode node : nodes) {
            try {
                if (node.getProtocol() != null && "udp".equalsIgnoreCase(node.getProtocol())) {
                    sendUdp(node.getHost(), node.getPort(), signed, timeoutMillis);
                } else {
                    sendTcp(node.getHost(), node.getPort(), signed, timeoutMillis);
                }
            } catch (Exception e) {
                log.debug("scatter 广播失败: {} - {}", node.getNodeId(), e.getMessage());
            }
        }
    }

    /**
     * 发送Tcp。
     *
     * @param host 主机，不允许为 null
     * @param port 端口，不允许为 null
     * @param payload 方法入参 payload
     * @param timeoutMillis 超时时间毫秒数，不允许为 null
     * @return 结果值
     * @throws Exception 当执行过程不满足前置条件时
     */
    private static byte[] sendTcp(String host, int port, byte[] payload, long timeoutMillis) throws Exception {
        TcpClient client = customClient;
        if (client != null) {
            return client.call(host, port, payload);
        }
        try (JdkTcpClient jdkClient = new JdkTcpClient(1,
                (int) Math.min(timeoutMillis / 3, 5000),
                (int) Math.min(timeoutMillis - timeoutMillis / 3, 8000))) {
            return jdkClient.call(host, port, payload);
        }
    }

    /**
     * 发送Udp。
     *
     * @param host 主机，不允许为 null
     * @param port 端口，不允许为 null
     * @param payload 方法入参 payload
     * @param timeoutMillis 超时时间毫秒数，不允许为 null
     * @return 结果值
     * @throws Exception 当执行过程不满足前置条件时
     */
    private static byte[] sendUdp(String host, int port, byte[] payload, long timeoutMillis) throws Exception {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.setSoTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            socket.send(new java.net.DatagramPacket(payload, payload.length,
                    new java.net.InetSocketAddress(host, port)));
            var buffer = new byte[65536];
            java.net.DatagramPacket respPacket = new java.net.DatagramPacket(buffer, buffer.length);
            socket.receive(respPacket);
            return java.util.Arrays.copyOf(respPacket.getData(), respPacket.getLength());
        }
    }

    /**
     * 下一个请求ID。
     *
     * @param node 节点，不允许为 null
     * @return 结果数值
     */
    private static int nextRequestId(ScatterNode node) {
        String key = node.getNodeId() + ":" + node.getHost() + ":" + node.getPort();
        int seq = REQUEST_ID_SEQ.merge(key, 1, Integer::sum);
        // 防止整型溢出：超出 MAX_VALUE 时重置为 1
        if (seq <= 0) {
            REQUEST_ID_SEQ.put(key, 1);
            return 1;
        }
        return seq;
    }
}