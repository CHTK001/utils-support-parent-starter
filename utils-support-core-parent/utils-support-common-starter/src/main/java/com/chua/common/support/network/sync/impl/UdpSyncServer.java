package com.chua.common.support.network.sync.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.sync.SyncAddressResolver;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.support.AbstractSyncServer;
import com.chua.common.support.network.sync.support.SyncClientInfo;
import com.chua.common.support.network.sync.support.SyncFrameCodec;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 基于 JDK DatagramSocket 的 UDP 同步服务端实现。
 * <p>
 * 提供主题发布与客户端注册能力,消息以数据报方式推送。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Slf4j
@Spi("udp")
public class UdpSyncServer extends AbstractSyncServer<UdpSyncServer.ClientInfo> {

    /**
     * 来源地址索引（address -> clientId）：UDP 无连接，每个非 register 数据报都要
     * 反查 clientId。原实现对全部 clients 线性扫描（每包 O(n)），索引后 O(1)。
     * 注册时同步写入；正常部署一个地址对应一个 clientId。
     */
    private final Map<InetSocketAddress, String> addressIndex = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * UDP 服务器
     */
    private DatagramSocket server;

    /**
     * 接收线程
     */
    private Thread receiveThread;

    /**
     * 创建 UDP 同步服务端 (默认配置)。
     */
    public UdpSyncServer() {
        this(ServerSetting.defaults());
    }

    /**
     * 创建 UDP 同步服务端。
     *
     * @param setting 服务端配置
     */
    public UdpSyncServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 获取Protocol
     */
    @Override
    public String getProtocol() {
        return "udp";
    }

    /**
     * 创建Server
     */
    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new UdpSyncServer(setting);
    }

    /**
     * 创建Client
     */
    @Override
    public SyncClient createClient(Object setting) {
        String url = SyncAddressResolver.resolveAddress(setting, "udp://127.0.0.1:19391");
        return new UdpSyncClient(url);
    }

    /**
     * Do开始
     */
    @Override
    protected void doStart() {
        rejectStreamEncryption("同步协议没有密钥协商字段，需要加密请自行封装上层载荷");
        try {
            server = new DatagramSocket(new InetSocketAddress(setting.getHost(), setting.getPort()));
            receiveThread = ThreadUtils.newThread(this::receiveLoop, "udp-sync-receive-" + setting.getPort());
            receiveThread.setDaemon(true);
            receiveThread.start();
        } catch (IOException e) {
            throw new RuntimeException("UDP SyncServer 启动失败", e);
        }
    }

    /**
     * Do停止
     */
    @Override
    protected void doStop() {
        if (server != null) {
            server.close();
            server = null;
        }
        clients.clear();
        addressIndex.clear();
    }

    /**
     * 发送数据报到指定客户端。
     */
    @Override
    protected void sendToClient(ClientInfo client, String frame) {
        sendTo(client.address(), frame);
    }

    /**
     * UDP 定向发送后同步通知消息监听器（保持既有语义）。
     */
    @Override
    protected void afterSend(String clientId, String topic, Object message) {
        notifyMessageListener(clientId, topic, message);
    }

    /**
     * 获取ProtocolType
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.UDP;
    }

    /**
     * 接收数据报循环。
     */
    private void receiveLoop() {
        byte[] buffer = new byte[setting.getBufferSize()];
        while (server != null && !server.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                server.receive(packet);
                handlePacket(packet);
            } catch (IOException e) {
                if (server != null && !server.isClosed()) {
                    notifyListener(l -> l.onError(null, e));
                }
                break;
            }
        }
    }

    /**
     * 处理数据报。
     *
     * @param packet 数据报
     */
    private void handlePacket(DatagramPacket packet) {
        String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8)
                .trim();
        String topic = SyncFrameCodec.topicOf(message, message);
        String payload = SyncFrameCodec.payloadOf(message);
        if (SyncFrameCodec.TOPIC_REGISTER.equals(topic)) {
            InetSocketAddress address = new InetSocketAddress(packet.getAddress(), packet.getPort());
            ClientInfo client = new ClientInfo(address);
            client.metadata.put("clientId", payload);
            client.metadata.put("address", address.toString());
            clients.put(payload, client);
            addressIndex.put(address, payload);
            notifyListener(l -> l.onClientConnected(payload, client.metadata));
        } else {
            // UDP 无连接：按来源地址反查已注册的 clientId，保证 send(clientId,...) 能定向回发
            String clientId = findClientIdByAddress(packet);
            notifyMessageListener(clientId, topic, payload);
        }
    }

    /**
     * 按数据报来源地址反查已注册的 clientId。
     *
     * @param packet 数据报
     * @return clientId，未注册返回 null
     */
    private String findClientIdByAddress(DatagramPacket packet) {
        // 哈希索引反查：键与注册时构造的 InetSocketAddress 等值（同地址同端口）
        return addressIndex.get(new InetSocketAddress(packet.getAddress(), packet.getPort()));
    }

    /**
     * 发送数据报到客户端。
     *
     * @param address 目标地址
     * @param payload 消息内容
     */
    private void sendTo(InetSocketAddress address, String payload) {
        try {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address.getAddress(), address.getPort());
            server.send(packet);
        } catch (IOException e) {
            log.debug("[sync] UDP 数据报发送失败，已忽略: {}", e.getMessage());
        }
    }

    /**
     * 客户端信息。
     *
     */
    static final class ClientInfo implements SyncClientInfo {

        /**
         * 客户端地址
         */
        private final InetSocketAddress address;

        /**
         * 客户端元数据
         */
        private final Map<String, Object> metadata = Wraps.map();

        /**
         * 创建客户端信息。
         *
         * @param address 客户端地址
         */
        private ClientInfo(InetSocketAddress address) {
            this.address = address;
        }

        /**
         * 获取客户端地址。
         *
         * @return 客户端地址
         */
        InetSocketAddress address() {
            return address;
        }

        /**
         * 获取客户端元数据。
         *
         * @return 元数据映射
         */
        @Override
        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }
}
