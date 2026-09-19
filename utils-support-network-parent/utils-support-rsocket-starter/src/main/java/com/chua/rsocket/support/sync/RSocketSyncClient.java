package com.chua.rsocket.support.sync;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import io.rsocket.core.RSocketConnector;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.DefaultPayload;
import io.rsocket.RSocket;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * rSocket 协议下的 同步客户端 实现，基于 fire和forget + 请求流 模型。
 * <p>支持断开后重连（默认无限次）与按 topic 的流订阅。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RSocketSyncClient implements SyncClient {

    /**
     * 客户端唯一标识
     */
    private final String clientId = UUID.randomUUID().toString();

    /**
     * 服务端 URL（如 {@code tcp://host:port}）
     */
    private final String serverUrl;

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * topic -> 消息处理器映射
     */
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();

    /**
     * 生命周期监听器列表
     */
    private final java.util.List<SyncFlowListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * topic -> 流订阅句柄映射（断开时统一释放）
     */
    private final Map<String, Disposable> streamDisposables = new ConcurrentHashMap<>();

    /**
     * 当前 rSocket 连接
     */
    private RSocket socket;

    /**
     * 重连计数器
     */
    private final AtomicInteger reconnectCount = new AtomicInteger(0);

    /**
     * 最大重连次数，0 表示无限重连
     */
    private static final int MAX_RECONNECT = 0;

    /**
     * 重连间隔（毫秒）
     */
    private static final long RECONNECT_INTERVAL = 3000;

    /**
     * @param serverUrl 服务端 URL
     */
    public RSocketSyncClient(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    /**
     * 连接到 rSocket 服务端并启动所有已订阅 topic。
     */
    @Override
    public void connect() {
        if (connected) {
            return;
        }
        try {
            doConnect();
            connected = true;
            reconnectCount.set(0);
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            throw new RuntimeException("RSocket 连接失败", e);
        }
    }

    /**
     * 断开连接：释放所有流订阅、关闭 Socket。
     */
    @Override
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        for (Disposable disposable : streamDisposables.values()) {
            disposable.dispose();
        }
        streamDisposables.clear();
        if (socket != null) {
            socket.dispose();
            socket = null;
        }
        subscriptions.clear();
        notifyListeners(SyncFlowListener::onStop);
    }

    /**
     * @return 连接状态
     */
    @Override
    public boolean isConnected() {
        return connected && socket != null && !socket.isDisposed();
    }

    /**
     * @return 当前客户端标识
     */
    @Override
    public String getClientId() {
        return clientId;
    }

    /**
     * 通过 fire和forget 发送消息，载荷格式为 {@code topic:message}。
     *
     * @param topic   主题
     * @param message 消息内容（调用 转为字符串）
     */
    @Override
    public void send(String topic, Object message) {
        if (!connected || socket == null) {
            throw new IllegalStateException("客户端未连接");
        }
        try {
            String payload = topic + ":" + message.toString();
            socket.fireAndForget(DefaultPayload.create(payload))
                    .subscribe();
        } catch (Exception e) {
            throw new RuntimeException("发送消息失败", e);
        }
    }

    /**
     * 订阅 topic：若已连接立即启动流，未连接则在 {@link #doConnect()} 末尾统一启动。
     *
     * @param topic   主题
     * @param handler 消息处理器
     */
    @Override
    public void subscribe(String topic, SyncMessageHandler handler) {
        subscriptions.put(topic, handler);
        if (connected && socket != null) {
            startStream(topic);
        }
    }

    /**
     * 取消订阅并释放对应流。
     *
     * @param topic 主题
     */
    @Override
    public void unsubscribe(String topic) {
        SyncMessageHandler removed = subscriptions.remove(topic);
        if (removed != null) {
            Disposable disposable = streamDisposables.remove(topic);
            if (disposable != null) {
                disposable.dispose();
            }
        }
    }

    @Override
    /** 添加监听器 */
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 移除监听器 */
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    @Override
    /** 获取Metadata */
    public Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "protocol", "rsocket");
    }

    /**
     * 关闭客户端（等价 断开连接）。
     */
    @Override
    public void close() {
        disconnect();
    }

    /**
     * 实际执行 rSocketconnector 建立连接，连接成功后会为每个已订阅 topic 启动流。
     */
    private void doConnect() {
        URI uri = URI.create(serverUrl);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 7000;

        socket = RSocketConnector.create()
                .keepAlive(Duration.ofSeconds(30), Duration.ofSeconds(90))
                .connect(TcpClientTransport.create(host, port))
                .block(Duration.ofSeconds(10));

        for (String topic : subscriptions.keySet()) {
            startStream(topic);
        }
    }

    /**
     * 开始流
     *
     * @param topic topic
     */
    private void startStream(String topic) {
        if (socket == null || socket.isDisposed()) {
            return;
        }
        Disposable disposable = socket.requestStream(DefaultPayload.create(topic))
                .subscribe(payload -> {
                            String data = payload.getDataUtf8();
                            SyncMessageHandler handler = subscriptions.get(topic);
                            if (handler != null) {
                                handler.handle(topic, data);
                            }
                        },
                        error -> {
                            if (connected) {
                                notifyListeners(l -> l.onError("stream", error));
                                attemptReconnect();
                            }
                        });
        streamDisposables.put(topic, disposable);
    }

    /** 尝试reconnect */
    private void attemptReconnect() {
        if (MAX_RECONNECT > 0 && reconnectCount.incrementAndGet() > MAX_RECONNECT) {
            return;
        }
        try {
            Thread.sleep(RECONNECT_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!connected) {
            return;
        }
        try {
            disconnect();
            doConnect();
            reconnectCount.set(0);
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 通知监听器
     *
     * @param action 动作
     */
    private void notifyListeners(java.util.function.Consumer<SyncFlowListener> action) {
        for (SyncFlowListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
