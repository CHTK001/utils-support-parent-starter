package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 JDK DatagramSocket 的 UDP 同步客户端实现。
 * <p>
 * 通过数据报与服务端双向同步,支持注册、主题订阅与消息收发。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Spi("udp")
public class UdpSyncClient implements SyncClient {

    /**
     * 客户端标识
     */
    private final String clientId;

    /**
     * 服务端地址
     */
    private final String serverUrl;

    /**
     * 底层 DatagramSocket
     */
    private DatagramSocket socket;

    /**
     * 服务端地址
     */
    private InetSocketAddress serverAddress;

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * 订阅的主题映射（topic -> handler）
     */
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncFlowListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 接收线程
     */
    private Thread receiveThread;

    /**
     * 创建 UDP 同步客户端。
     *
     * @param serverUrl 服务端地址，如 udp://localhost:19391
     */
    public UdpSyncClient(String serverUrl) {
        this(UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建 UDP 同步客户端。
     *
     * @param clientId  客户端标识
     * @param serverUrl 服务端地址
     */
    public UdpSyncClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
    }

    @Override
    /** 连接 */
    public void connect() {
        if (connected) {
            return;
        }
        try {
            socket = new DatagramSocket();
            serverAddress = parseAddress(serverUrl);
            connected = true;
            sendData("register:" + clientId);
            startReceive();
            notifyListeners(SyncFlowListener::onStart);
        } catch (IOException e) {
            connected = false;
            throw new RuntimeException("UDP SyncClient 连接失败: " + serverUrl, e);
        }
    }

    @Override
    /** 断开 */
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
        notifyListeners(SyncFlowListener::onStop);
    }

    @Override
    /** 是否Connected */
    public boolean isConnected() {
        return connected;
    }

    @Override
    /** 获取ClientId */
    public String getClientId() {
        return clientId;
    }

    @Override
    /** 发送 */
    public void send(String topic, Object message) {
        checkConnected();
        sendData(topic + ":" + message);
    }

    @Override
    /** 订阅 */
    public void subscribe(String topic, SyncMessageHandler handler) {
        subscriptions.put(topic, handler);
    }

    @Override
    /** 取消订阅 */
    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
    }

    @Override
    /** 添加Listener */
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 移除Listener */
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    @Override
    /** 获取Metadata */
    public Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "udp");
    }

    @Override
    /** 关闭 */
    public void close() {
        disconnect();
    }

    /**
    * 启动接收线程。
    */
    private void startReceive() {
        receiveThread = ThreadUtils.newThread(() -> {
            byte[] buffer = new byte[8192];
            while (connected && socket != null && !socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    handlePacket(packet);
                } catch (IOException e) {
                    if (connected) {
                        notifyListeners(l -> l.onError("udp", e));
                    }
                    break;
                }
            }
        }, "udp-sync-receive-" + clientId);
        receiveThread.setDaemon(true);
        receiveThread.start();
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
        SyncMessageHandler handler = subscriptions.get(topic);
        if (handler != null) {
            handler.handle(topic, payload);
        }
        notifyListeners(l -> l.onMessage(topic, payload));
    }

    /**
     * 发送数据报。
     *
     * @param payload 消息内容
     */
    private void sendData(String payload) {
        try {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, serverAddress.getAddress(), serverAddress.getPort());
            socket.send(packet);
        } catch (IOException e) {
            throw new RuntimeException("UDP SyncClient 发送失败", e);
        }
    }

    /**
     * 校验连接状态。
     */
    private void checkConnected() {
        if (!connected) {
            throw new IllegalStateException("客户端未连接");
        }
    }

    /**
     * 解析 udp://host:port 地址。
     *
     * @param url 地址
     * @return SocketAddress
     */
    private InetSocketAddress parseAddress(String url) {
        String address = url;
        if (address.startsWith("udp://")) {
            address = address.substring("udp://".length());
        }
        if (address.startsWith("tcp://")) {
            address = address.substring("tcp://".length());
        }
        int colon = address.lastIndexOf(':');
        String host = colon > 0 ? address.substring(0, colon) : "127.0.0.1";
        int port = colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : 19391;
        return new InetSocketAddress(host, port);
    }

    /**
     * 通知监听器。
     *
     * @param action 动作
     */
    private void notifyListeners(java.util.function.Consumer<SyncFlowListener> action) {
        for (SyncFlowListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception ignored) {
            }
        }
    }
}
