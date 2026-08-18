package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Netty 的 HTTP 同步客户端实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NettyHttpSyncClient implements com.chua.common.support.network.sync.SyncClient {

    /** 客户端ID */
    private final String clientId = UUID.randomUUID().toString();
    /** 服务器URL */
    private final String serverUrl;
    /** HTTP客户端 */
    private final HttpClient httpClient;
    private volatile boolean connected;
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();
    /** Listeners */
    private final java.util.List<SyncFlowListener> listeners = new java.util.ArrayList<>();
    /** Pull线程 */
    private Thread pullThread;

    public NettyHttpSyncClient(String serverUrl) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public void connect() {
        if (connected) {
            return;
        }
        connected = true;
        startPull();
        notifyListeners(SyncFlowListener::onStart);
    }

    @Override
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        stopPull();
        subscriptions.clear();
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
        if (!connected) {
            throw new IllegalStateException("客户端未连接");
        }
        try {
            String body = java.net.URLEncoder.encode(topic, "UTF-8") + "=" + java.net.URLEncoder.encode(message.toString(), "UTF-8");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/api/sync/send"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("发送消息失败", e);
        }
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
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "http");
    }

    @Override
    public void close() {
        disconnect();
    }

    private void startPull() {
        pullThread = new Thread(() -> {
            while (connected) {
                try {
                    pullMessages();
                } catch (Exception e) {
                    if (connected) {
                        notifyListeners(l -> l.onError("pull", e));
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "http-sync-pull-" + clientId);
        pullThread.setDaemon(true);
        pullThread.start();
    }

    private void stopPull() {
        if (pullThread != null) {
            pullThread.interrupt();
            pullThread = null;
        }
    }

    private void pullMessages() throws Exception {
        if (subscriptions.isEmpty()) {
            Thread.sleep(500);
            return;
        }
        StringBuilder topics = new StringBuilder();
        for (String topic : subscriptions.keySet()) {
            if (topics.length() > 0) {
                topics.append(",");
            }
            topics.append(topic);
        }
        String url = serverUrl + "/api/sync/pull?clientId=" + java.net.URLEncoder.encode(clientId, "UTF-8")
                + "&topics=" + java.net.URLEncoder.encode(topics.toString(), "UTF-8")
                + "&timeout=30";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(35))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 && response.body() != null && !response.body().isEmpty()) {
            String[] parts = response.body().split(":", 2);
            if (parts.length == 2) {
                String topic = parts[0];
                String message = parts[1];
                SyncMessageHandler handler = subscriptions.get(topic);
                if (handler != null) {
                    handler.handle(topic, message);
                }
            }
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
