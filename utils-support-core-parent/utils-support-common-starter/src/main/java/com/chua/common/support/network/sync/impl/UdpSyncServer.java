package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
* 基于 JDK DatagramSocket 的 UDP 同步服务端实现。
* <p>
* 提供主题发布与客户端注册能力,消息以数据报方式推送。
* </p>
*
* @author CH
* @since 2026-07-25
 */
@Spi("udp")
public class UdpSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer, SyncProtocol {

    /**
    * 客户端注册表（clientId -> 地址与元数据）
     */
    private final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();

    /**
    * 监听器列表
     */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();

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

    @Override
    /** 获取Protocol */
    public String getProtocol() {
        return "udp";
    }

    @Override
    /** 创建Server */
    public SyncServer createServer(ServerSetting setting) {
        return new UdpSyncServer(setting);
    }

    @Override
    /** 创建Client */
    public SyncClient createClient(Object setting) {
        String url = setting instanceof String ? (String) setting : "udp://127.0.0.1:19391";
        return new UdpSyncClient(url);
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            server = new DatagramSocket(new InetSocketAddress(setting.getHost(), setting.getPort()));
            receiveThread = ThreadUtils.newThread(this::receiveLoop, "udp-sync-receive-" + setting.getPort());
            receiveThread.setDaemon(true);
            receiveThread.start();
        } catch (IOException e) {
            throw new RuntimeException("UDP SyncServer 启动失败", e);
        }
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        if (server != null) {
            server.close();
            server = null;
        }
        clients.clear();
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object message) {
        String payload = topic + ":" + message;
        for (ClientInfo client : clients.values()) {
            sendTo(client.address, payload);
        }
    }

    @Override
    /** 发送 */
    public void send(String clientId, String topic, Object message) {
        ClientInfo client = clients.get(clientId);
        if (client == null) {
            return;
        }
        sendTo(client.address, topic + ":" + message);
        notifyListener(l -> l.onMessage(clientId, topic, message));
    }

    @Override
    /** 获取ConnectedClients */
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    /** 获取ClientMetadata */
    public Map<String, Object> getClientMetadata(String clientId) {
        ClientInfo client = clients.get(clientId);
        return client != null ? Collections.unmodifiableMap(client.metadata) : Collections.emptyMap();
    }

    @Override
    /** 添加Listener */
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 移除Listener */
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    /** 获取ProtocolType */
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
        String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
        int colon = message.indexOf(':');
        String topic = colon > 0 ? message.substring(0, colon) : message;
        String payload = colon > 0 ? message.substring(colon + 1) : message;
        if ("register".equals(topic)) {
            InetSocketAddress address = new InetSocketAddress(packet.getAddress(), packet.getPort());
            ClientInfo client = new ClientInfo(address);
            client.metadata.put("clientId", payload);
            client.metadata.put("address", address.toString());
            clients.put(payload, client);
            notifyListener(l -> l.onClientConnected(payload, client.metadata));
        } else {
            // UDP 无连接：按来源地址反查已注册的 clientId，保证 send(clientId,...) 能定向回发
            String clientId = findClientIdByAddress(packet);
            notifyListener(l -> l.onMessage(clientId, topic, payload));
        }
    }

    /**
    * 按数据报来源地址反查已注册的 clientId。
    *
    * @param packet 数据报
    * @return clientId，未注册返回 null
     */
    private String findClientIdByAddress(DatagramPacket packet) {
        for (Map.Entry<String, ClientInfo> entry : clients.entrySet()) {
            ClientInfo info = entry.getValue();
            if (info.address != null
                    && info.address.getAddress().equals(packet.getAddress())
                    && info.address.getPort() == packet.getPort()) {
                return entry.getKey();
            }
        }
        return null;
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
        } catch (IOException ignored) {
        }
    }

    /**
    * 通知监听器。
    *
    * @param action 动作
     */
    private void notifyListener(java.util.function.Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception ignored) {
            }
        }
    }

    /**
    * 客户端信息。
    *
     */
    private static final class ClientInfo {

        /**
        * 客户端地址
         */
        private final InetSocketAddress address;

        /**
        * 客户端元数据
         */
        private final Map<String, Object> metadata = new HashMap<>();

        /**
        * 创建客户端信息。
        *
        * @param address 客户端地址
         */
        private ClientInfo(InetSocketAddress address) {
            this.address = address;
        }
    }
}
