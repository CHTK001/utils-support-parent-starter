package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.spi.annotations.Spi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 NIO SocketChannel + 虚拟线程的 TCP 同步客户端实现。
 * <p>
 * 通过 TCP 长连接与服务端双向同步，支持注册、主题订阅与消息收发。
 * 读取由虚拟线程承载（阻塞读让出载体线程，连接数不再消耗 OS 线程），
 * 行切分后按订阅表/监听器分发。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
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
     * 底层通道
     */
    private SocketChannel channel;

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
     * 接收虚拟线程
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
    /** 连接 */
    public void connect() {
        if (connected) {
            return;
        }
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(true);
            channel.socket().setTcpNoDelay(true);
            channel.connect(parseAddress(serverUrl));
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
            ThreadUtils.sleep(10L);
        }
    }

    @Override
    /** 断开 */
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            channel = null;
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
        sendLine(topic + ":" + message);
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
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "tcp");
    }

    @Override
    /** 关闭 */
    public void close() {
        disconnect();
    }

    /**
     * 启动虚拟线程读取：阻塞读让出载体线程，行到达后按订阅/监听器分发。
     */
    private void startRead() {
        // 捕获本次连接的通道：旧线程退出时不误标已被重连替换的新连接
        SocketChannel connChannel = channel;
        readThread = Thread.ofVirtual().name("tcp-sync-read-" + clientId).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    java.nio.channels.Channels.newInputStream(connChannel), StandardCharsets.UTF_8))) {
                String line;
                while (connected && (line = reader.readLine()) != null) {
                    handleLine(line);
                }
            } catch (IOException e) {
                if (connected) {
                    notifyListeners(l -> l.onError("tcp", e));
                }
            } finally {
                // 对端断开（EOF/读异常）：标记断开，供连接池摘除并重连。
                // 仅当底层通道未被重连替换时才置 false，避免新连接被旧线程误标断开。
                if (channel == connChannel) {
                    connected = false;
                }
            }
        });
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
            ByteBuffer buffer = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            // 发送失败即连接已断：标记断开，供连接池下一轮重建
            connected = false;
            if (readThread != null) {
                readThread.interrupt();
                readThread = null;
            }
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
