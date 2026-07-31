package com.chua.common.support.network.discovery.peermesh;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.Discovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.util.ArrayList;
import java.util.List;

/**
 * UDP 模式探针：广播发现对等节点。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class UdpModeProbe implements ProbeStrategy {

    private static final Logger log = LoggerFactory.getLogger(UdpModeProbe.class);
    private static final int RECEIVE_TIMEOUT_MS = 2000;

    private final MeshConfig config;
    private final String localServerId;
    private final List<NodeTable.NodeEntry> discovered = new ArrayList<>();
    private volatile boolean stopped;

    /**
     * 构造函数。
     *
     * @param config 配置
     * @param localServerId 本地 serverId
     */
    public UdpModeProbe(MeshConfig config, String localServerId) {
        this.config = config;
        this.localServerId = localServerId;
    }

    @Override
    public void start() throws Exception {
        int port = config.getPort();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            // 构造探测消息
            String probeMsg = "{\"type\":\"probe\"}";
            byte[] data = probeMsg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
            DatagramPacket sendPacket = new DatagramPacket(data, data.length, broadcastAddr, port);
            socket.send(sendPacket);
            log.debug("UDP 探测已发送至广播地址:{}:{}", broadcastAddr.getHostAddress(), port);
            // 接收响应
            byte[] buf = new byte[65507];
            DatagramPacket recvPacket = new DatagramPacket(buf, buf.length);
            long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MS;
            while (!stopped && System.currentTimeMillis() < deadline) {
                try {
                    socket.receive(recvPacket);
                    String msg = new String(recvPacket.getData(), recvPacket.getOffset(),
                            recvPacket.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                    handleResponse(msg);
                } catch (SocketTimeoutException e) {
                    // 正常超时退出
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("UDP 探测异常: {}", e.getMessage());
        }
    }

    @Override
    public void stop() throws Exception {
        stopped = true;
    }

    @Override
    public List<NodeTable.NodeEntry> getDiscoveredNodes() {
        return discovered;
    }

    /**
     * 处理收到的响应消息。
     *
     * @param msg 响应内容
     */
    private void handleResponse(String msg) {
        try {
            Discovery d = Json.fromJson(msg, Discovery.class);
            if (d != null && d.getServerId() != null && !d.getServerId().equals(localServerId)) {
                discovered.add(new NodeTable.NodeEntry(d, System.currentTimeMillis(), 0));
                log.debug("UDP 发现节点: {}", d.getServerId());
            }
        } catch (Exception e) {
            log.debug("解析 UDP 响应失败: {}", e.getMessage());
        }
    }
}