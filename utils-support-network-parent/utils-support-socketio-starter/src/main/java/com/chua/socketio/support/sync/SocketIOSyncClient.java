package com.chua.socketio.support.sync;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import io.socket.client.IO;
import io.socket.client.Socket;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Socket.IO 协议下的 SyncClient 实现，基于 socket.io-client-java。
 * <p>支持自动重连（间隔 3 秒）与按 topic 的事件订阅。</p>
 *
 * @author CH
 * @since 4.0.0
 */
public class SocketIOSyncClient implements SyncClient {

    /**
     * 客户端唯一标识
     */
    private final String clientId = UUID.randomUUID().toString();

    /**
     * 服务端 URL
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
     * Socket.IO socket 实例
     */
    private Socket socket;

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
    public SocketIOSyncClient(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    @Override
    public void connect() {
        if (connected) {
            return;
        }
        try {
            doConnect();
        } catch (Exception e) {
            throw new RuntimeException("Socket.IO 连接失败", e);
        }
    }

    @Override
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
        subscriptions.clear();
        notifyListeners(SyncFlowListener::onStop);
    }

    @Override
    public boolean isConnected() {
        return connected && socket != null && socket.connected();
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public void send(String topic, Object message) {
        if (!connected || socket == null) {
            throw new IllegalStateException("客户端未连接");
        }
        socket.emit(topic, message);
    }

    @Override
    public void subscribe(String topic, SyncMessageHandler handler) {
        subscriptions.put(topic, handler);
        if (connected && socket != null) {
            ensureTopicListener(topic);
        }
    }

    @Override
    public void unsubscribe(String topic) {
        SyncMessageHandler removed = subscriptions.remove(topic);
        if (removed != null && socket != null) {
            socket.off(topic);
        }
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
        return Map.of("clientId", clientId, "protocol", "socketio");
    }

    @Override
    public void close() {
        disconnect();
    }

    private void doConnect() throws Exception {
        URI uri = URI.create(serverUrl);
        IO.Options options = new IO.Options();
        options.reconnection = true;
        options.reconnectionDelay = RECONNECT_INTERVAL;
        options.reconnectionDelayMax = RECONNECT_INTERVAL * 2;

        socket = IO.socket(uri, options);

        socket.on(Socket.EVENT_CONNECT, args -> {
            connected = true;
            reconnectCount.set(0);
            notifyListeners(SyncFlowListener::onStart);
        });

        socket.on(Socket.EVENT_DISCONNECT, args -> {
            connected = false;
            notifyListeners(SyncFlowListener::onStop);
        });

        socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
            if (connected) {
                notifyListeners(l -> l.onError("connect", new Exception("连接错误")));
            }
        });

        socket.connect();

        for (String topic : subscriptions.keySet()) {
            ensureTopicListener(topic);
        }
    }

    private void ensureTopicListener(String topic) {
        if (socket == null) {
            return;
        }
        socket.on(topic, args -> {
            SyncMessageHandler handler = subscriptions.get(topic);
            if (handler != null && args.length > 0) {
                handler.handle(topic, args[0]);
            }
        });
    }

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
