package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.tcp.JdkTcpClient;
import com.chua.common.support.network.tcp.TcpClient;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
* scatter 同步辅助工具：集中处理 TCP 短连接、超时、重试与 请求id 生成。
*
* <p>所有 Scatter 模块的网络调用统一通过此类，避免散落的重复代码与遗漏的超时控制。</p>
*
* @author CH
* @since 4.0.0.42
*
@Slf4j
public final class ScatterSyncHelper {

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 3;
    /** 请求 标识 原子计数（线程安全，按目标节点隔离） */
    private static final ConcurrentHashMap<String, Integer> REQUEST_ID_SEQ = new ConcurrentHashMap<>();
    /** 可注入的自定义 TCP 客户端（SPI/测试场景），空 时自动创建 */
    private static volatile TcpClient customClient;

    /**
    * scatter同步助手。
     */
    private ScatterSyncHelper() {
    }

    /**
    * 注入自定义 TCP 客户端（未启动前调用，SPI/测试场景）。
    *
    * @param client 客户端
     */
    public static void setCustomClient(TcpClient client) {
        customClient = client;
    }

    /** 测试后清理静态状态，防止跨测试干扰。 */
    public static void resetForTest() {
        customClient = null;
        REQUEST_ID_SEQ.clear();
        com.chua.common.support.scatter.discovery.AbstractScatterDiscovery.resetAllCaches();
    }

    /** 测试类结束后清理所有实例，防止跨测试类污染。 */
    public static void resetAll() {
        customClient = null;
        REQUEST_ID_SEQ.clear();
        com.chua.common.support.scatter.discovery.AbstractScatterDiscovery.resetAllCaches();
        com.chua.common.support.scatter.discovery.AbstractScatterDiscovery.resetInstances();
    }

    /**
    * 拉取目标节点的服务表（带超时 + 重试）。
    *
    * @param context        请求上下文
    * @param node           目标节点
    * @param timeoutMillis  单次超时毫秒
    * @return 同步结果（失败时返回 空）
     */
    public static ScatterResult<List<Discovery>> fetch(ScatterContext context, ScatterNode node,
                                                       long timeoutMillis) {
        return fetchWithRetry(context, node, timeoutMillis, MAX_RETRIES);
    }

    private static ScatterResult<List<Discovery>> fetchWithRetry(ScatterContext context, ScatterNode node,
                                                                 long timeoutMillis, int remaining) {
        try {
            ScatterFrame req = new ScatterFrame(ScatterProtocol.TYPE_REQ, nextRequestId(node),
                    context.getPath(), new byte[0]);
            byte[] response;
            if (node.getProtocol() != null && "udp".equalsIgnoreCase(node.getProtocol())) {
                response = sendUdp(node.getHost(), node.getPort(), req.encode(), timeoutMillis);
            } else {
                response = sendTcp(node.getHost(), node.getPort(), req.encode(), timeoutMillis);
            }
            if (response == null || response.length == 0) {
                return remaining > 1 ? fetchWithRetry(context, node, timeoutMillis, remaining - 1)
                        : ScatterResult.timeout(node.getNodeId(), "空响应: " + node.getEndpoint());
            }
            ScatterFrame resp = ScatterFrame.decode(response);
            if (resp.getType() == ScatterProtocol.TYPE_RESP && resp.getPayload().length > 0) {
                String json = new String(resp.getPayload(), StandardCharsets.UTF_8);
                List<Discovery> data = com.chua.common.support.lang.json.Json.fromJsonToList(json, Discovery.class);
                return ScatterResult.success(node.getNodeId(), data);
            }
            return remaining > 1 ? fetchWithRetry(context, node, timeoutMillis, remaining - 1)
                    : ScatterResult.failure(node.getNodeId(), "响应类型异常: " + resp.getType());
        } catch (Exception e) {
            if (remaining > 1) {
                log.debug("scatter 拉取重试 ({}/{}): {}", MAX_RETRIES - remaining + 1, MAX_RETRIES, e.getMessage());
                return fetchWithRetry(context, node, timeoutMillis, remaining - 1);
            }
            return ScatterResult.timeout(node.getNodeId(), "请求失败: " + e.getMessage());
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
            byte[] response;
            if (node.getProtocol() != null && "udp".equalsIgnoreCase(node.getProtocol())) {
                response = sendUdp(node.getHost(), node.getPort(), frame.encode(), timeoutMillis);
            } else {
                response = sendTcp(node.getHost(), node.getPort(), frame.encode(), timeoutMillis);
            }
            if (response != null && response.length > 0) {
                ScatterFrame resp = ScatterFrame.decode(response);
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
    * @param node 节点
     /**
      * broadcast。
      * @param nodes 节点
      * @param frame 帧
      * @param timeoutMillis 超时millis
      */
     * @return 下一个请求id的结果
     * @param host 主机
     * @param port 端口
     * @param payload payload
      * @param node 节点
     /**
     * broadcast。
     * @param nodes 节点
     * @param frame 帧
     * @param timeoutMillis 超时millis
      */
     */
    public static void broadcast(List<ScatterNode> nodes, ScatterFrame frame, long timeoutMillis) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        for (ScatterNode node : nodes) {
            try {
                if (node.getProtocol() != null && "udp".equalsIgnoreCase(node.getProtocol())) {
                    sendUdp(node.getHost(), node.getPort(), frame.encode(), timeoutMillis);
                } else {
                    sendTcp(node.getHost(), node.getPort(), frame.encode(), timeoutMillis);
                }
            } catch (Exception e) {
                log.debug("scatter 广播失败: {} - {}", node.getNodeId(), e.getMessage());
            }
        }
    }

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

    private static byte[] sendUdp(String host, int port, byte[] payload, long timeoutMillis) throws Exception {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.setSoTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            socket.send(new java.net.DatagramPacket(payload, payload.length,
                    new java.net.InetSocketAddress(host, port)));
            byte[] buffer = new byte[65536];
            java.net.DatagramPacket respPacket = new java.net.DatagramPacket(buffer, buffer.length);
            socket.receive(respPacket);
            return java.util.Arrays.copyOf(respPacket.getData(), respPacket.getLength());
        }
    }

    private static int nextRequestId(ScatterNode node) {
        String key = node.getNodeId() + ":" + node.getHost() + ":" + node.getPort();
        int seq = REQUEST_ID_SEQ.merge(key, 1, Integer::sum);
 // 防止整型溢出：超出 最大_值 时重置为 1
        if (seq <= 0) {
            REQUEST_ID_SEQ.put(key, 1);
            return 1;
        }
        return seq;
    }
}
