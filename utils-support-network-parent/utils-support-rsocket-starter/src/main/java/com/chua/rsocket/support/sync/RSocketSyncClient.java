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

public class RSocketSyncClient implements SyncClient {

    private final String clientId = UUID.randomUUID().toString();
    private final String serverUrl;
    private volatile boolean connected;
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();
    private final java.util.List<SyncFlowListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Disposable> streamDisposables = new ConcurrentHashMap<>();

    private RSocket socket;

    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private static final int MAX_RECONNECT = 0;
    private static final long RECONNECT_INTERVAL = 3000;

    public RSocketSyncClient(String serverUrl) {
        this.serverUrl = serverUrl;
    }

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

    @Override
    public boolean isConnected() {
        return connected && socket != null && !socket.isDisposed();
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
        try {
            String payload = topic + ":" + message.toString();
            socket.fireAndForget(DefaultPayload.create(payload))
                    .subscribe();
        } catch (Exception e) {
            throw new RuntimeException("发送消息失败", e);
        }
    }

    @Override
    public void subscribe(String topic, SyncMessageHandler handler) {
        subscriptions.put(topic, handler);
        if (connected && socket != null) {
            startStream(topic);
        }
    }

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
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "protocol", "rsocket");
    }

    @Override
    public void close() {
        disconnect();
    }

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
