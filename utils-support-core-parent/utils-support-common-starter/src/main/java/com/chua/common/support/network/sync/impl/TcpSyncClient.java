package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 JDK Socket 的 TCP 同步客户端实现。
 * <p>
 * 通过 TCP 长连接与服务端双向同步，支持注册、主题订阅与消息收发。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Spi("tcp")
public class TcpSyncClient implements SyncClient {

    /**
     * 客户端标识
     */
    private final String clientId;

    /**
     * 服务端地址
     */
    private final String serverUrl;

    /**
     * 底层 Socket
     */
    private Socket socket;

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * 是否已注册成功
     */
    private volatile boolean registered;

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
    private Thread readThread;

    /**
     * 创建 TCP 同步客户端。
     *
     * @param serverUrl 服务端地址，如 tcp://localhost:19390
     */
    public TcpSyncClient(String serverUrl) {
        this(UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建 TCP 同步客户端。
     *
     * @param clientId  客户端标识
     * @param serverUrl 服务端地址
     */
    public TcpSyncClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
    }

    @Override
    public void connect() {
        if (connected) {
            return;
        }
        try {
            socket = new Socket();
            socket.connect(parseAddress(serverUrl));
            connected = true;
            startRead();
            sendLine("register:" + clientId);
            waitRegistered();
            notifyListeners(SyncFlowListener::onStart);
        } catch (IOException e) {
            connected = false;
            throw new RuntimeException("TCP SyncClient 连接失败: " + serverUrl, e);
        }
    }

    /**
     * 等待服务端注册确认, 保证 connect() 返回后已可收发。
     */
    private void waitRegistered() {
        long deadline = System.currentTimeMillis() + 3000L;
        while (!registered && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
        notifyListeners(SyncFlowListener::onStop);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public void send(String topic, Object message) {
        checkConnected();
        sendLine(topic + ":" + message);
    }

    @Override
    public void subscribe(String topic, SyncMessageHandler handler) {
        subscriptions.put(topic, handler);
    }

    @Override
    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
    }

    @Override
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "tcp");
    }

    @Override
    public void close() {
        disconnect();
    }

    /**
     * 启动接收线程。
     */
    private void startRead() {
        readThread = ThreadUtils.newThread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (connected && (line = reader.readLine()) != null) {
                    handleLine(line);
                }
            } catch (IOException e) {
                if (connected) {
                    notifyListeners(l -> l.onError("tcp", e));
                }
            }
        }, "tcp-sync-read-" + clientId);
        readThread.setDaemon(true);
        readThread.start();
    }

    /**
     * 处理一行消息。
     *
     * @param line 消息行
     */
    private void handleLine(String line) {
        String message = line.trim();
        int colon = message.indexOf(':');
        String topic = colon > 0 ? message.substring(0, colon) : message;
        String payload = colon > 0 ? message.substring(colon + 1) : message;
        if ("registered".equals(topic)) {
            registered = true;
            return;
        }
        SyncMessageHandler handler = subscriptions.get(topic);
        if (handler != null) {
            handler.handle(topic, payload);
        }
        notifyListeners(l -> l.onMessage(topic, payload));
    }

    /**
     * 发送一行消息。
     *
     * @param line 消息行
     */
    private void sendLine(String line) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException("TCP SyncClient 发送失败", e);
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
     * 解析 tcp://host:port 地址。
     *
     * @param url 地址
     * @return SocketAddress
     */
    private InetSocketAddress parseAddress(String url) {
        String address = url;
        if (address.startsWith("tcp://")) {
            address = address.substring("tcp://".length());
        }
        if (address.startsWith("ws://")) {
            address = address.substring("ws://".length());
        }
        int colon = address.lastIndexOf(':');
        String host = colon > 0 ? address.substring(0, colon) : "127.0.0.1";
        int port = colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : 19390;
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
