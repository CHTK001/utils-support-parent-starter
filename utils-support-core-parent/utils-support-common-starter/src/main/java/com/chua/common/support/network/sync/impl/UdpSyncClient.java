package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.sync.SyncAddressResolver;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncSetting;
import com.chua.common.support.network.sync.support.AbstractSyncClient;
import com.chua.common.support.network.sync.support.SyncFrameCodec;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 基于 JDK DatagramSocket 的 UDP 同步客户端实现。
 * <p>
 * 通过数据报与服务端双向同步,支持注册、主题订阅与消息收发。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Slf4j
@Spi("udp")
public class UdpSyncClient extends AbstractSyncClient {

    /**
     * 底层 DatagramSocket
     */
    private DatagramSocket socket;

    /**
     * 服务端地址
     */
    private InetSocketAddress serverAddress;

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
        super(clientId, serverUrl);
    }

    /**
     * 创建 UDP 同步客户端，服务端地址由服务发现解析。
     * <p>发现不到时回落到 {@link SyncSetting#getServerUrl()}。</p>
     *
     * @param clientId 客户端标识
     * @param setting  同步通道配置
     */
    public UdpSyncClient(String clientId, SyncSetting setting) {
        this(clientId, SyncAddressResolver.resolve(setting));
    }

    /**
     * 连接
     */
    @Override
    public void connect() {
        if (isConnected()) {
            return;
        }
        try {
            socket = new DatagramSocket();
            serverAddress = parseAddress(serverUrl);
            setConnected(true);
            sendData("register:" + clientId);
            startReceive();
            notifyListeners(SyncFlowListener::onStart);
        } catch (IOException e) {
            setConnected(false);
            throw new RuntimeException("UDP SyncClient 连接失败: " + serverUrl, e);
        }
    }

    /**
     * 断开
     */
    @Override
    public void disconnect() {
        if (!isConnected()) {
            return;
        }
        setConnected(false);
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

    /**
     * 发送
     */
    @Override
    public void send(String topic, Object message) {
        checkConnected();
        sendData(SyncFrameCodec.encode(topic, message));
    }

    /**
     * 获取协议标识。
     */
    @Override
    protected String protocol() {
        return "udp";
    }

    /**
     * 启动接收线程。
     */
    private void startReceive() {
        receiveThread = ThreadUtils.newThread(() -> {
            // [P3C A20 豁免]
            byte[] buffer = new byte[8192];
            while (isConnected() && socket != null && !socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    handlePacket(packet);
                } catch (IOException e) {
                    if (isConnected()) {
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
        String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8)
                .trim();
        String topic = SyncFrameCodec.topicOf(message, message);
        String payload = SyncFrameCodec.payloadOf(message);
        dispatchMessage(topic, payload);
    }

    /**
     * 发送数据报。
     *
     * @param payload 消息内容
     */
    private void sendData(String payload) {
        try {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, serverAddress.getAddress(),
                    serverAddress.getPort());
            socket.send(packet);
        } catch (IOException e) {
            throw new RuntimeException("UDP SyncClient 发送失败", e);
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
        // [P3C A20 豁免]
        int port = colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : 19391;
        return new InetSocketAddress(host, port);
    }
}
