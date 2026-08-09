package com.chua.common.support.network.discovery.peermesh;

import com.chua.common.support.lang.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * UDP 模式探针：通过广播探测发现对等节点。
 * <p>
 * 探测消息与响应统一使用 {@link MessageProtocol} 编码，向广播地址发送 TYPE_PROBE，
 * 接收对端回复的 TYPE_PONG（携带节点条目列表）。
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

            MessageProtocol.PeerMeshMessage probe = new MessageProtocol.PeerMeshMessage(
                    MessageProtocol.TYPE_PROBE, "");
            byte[] data = MessageProtocol.encode(probe);
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
            DatagramPacket sendPacket = new DatagramPacket(data, data.length, broadcastAddr, port);
            socket.send(sendPacket);
            log.debug("UDP 探测已发送至广播地址:{}:{}", broadcastAddr.getHostAddress(), port);

            byte[] buf = new byte[65507];
            DatagramPacket recvPacket = new DatagramPacket(buf, buf.length);
            long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MS;
            while (!stopped && System.currentTimeMillis() < deadline) {
                try {
                    socket.receive(recvPacket);
                    handleResponse(recvPacket);
                } catch (SocketTimeoutException e) {
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
     * 处理收到的 UDP 响应包。
     *
     * @param packet 数据包
     */
    private void handleResponse(DatagramPacket packet) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
            MessageProtocol.PeerMeshMessage msg = MessageProtocol.decode(buf);
            if (msg == null || msg.type() != MessageProtocol.TYPE_PONG) {
                return;
            }
            List<NodeTable.NodeEntry> entries = Json.fromJsonToList(msg.payload(), NodeTable.NodeEntry.class);
            if (entries == null) {
                return;
            }
            for (NodeTable.NodeEntry entry : entries) {
                if (entry == null || entry.getDiscovery() == null) {
                    continue;
                }
                if (localServerId.equals(entry.getDiscovery().getServerId())) {
                    continue;
                }
                discovered.add(entry);
                log.debug("UDP 发现节点: {}", entry.getDiscovery().getServerId());
            }
        } catch (Exception e) {
            log.debug("解析 UDP 响应失败: {}", e.getMessage());
        }
    }
}
