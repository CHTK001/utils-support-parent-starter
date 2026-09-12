package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
* 基于 JDK HttpClient 的 HTTP 同步客户端实现。
* <p>
* 通过 HTTP POST 发送消息，通过长轮询（Long Polling）接收服务端推送。
* 内置心跳和自动重连机制。
* </p>
*
* @author CH
* @since 2026-07-25
 */
@Spi("http")
public class HttpSyncClient implements SyncClient {

    /**
    * 客户端标识
     */
    private final String clientId;

    /**
    * 服务端地址
     */
    private final String serverUrl;

    /**
    * HTTP 客户端
     */
    private final HttpClient httpClient;

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
    private final java.util.List<SyncFlowListener> listeners = new java.util.ArrayList<>();

    /**
    * 拉取线程
     */
    private Thread pullThread;

    /**
    * 心跳线程
     */
    private Thread heartbeatThread;

    /**
    * 重连次数
     */
    private final AtomicInteger reconnectCount = new AtomicInteger(0);

    /**
    * 最大重连次数（0 表示无限重连）
     */
    private static final int MAX_RECONNECT = 0;

    /**
    * 重连间隔（毫秒）
     */
    private static final long RECONNECT_INTERVAL = 3000;

    /**
    * 心跳间隔（秒）
     */
    private static final int HEARTBEAT_INTERVAL = 30;

    /**
    * 创建 HTTP 同步客户端。
    *
    * @param serverUrl 服务端地址，如 http://localhost:8080
     */
    public HttpSyncClient(String serverUrl) {
        this(java.util.UUID.randomUUID().toString(), serverUrl);
    }

    /**
    * 创建 HTTP 同步客户端。
    *
    * @param clientId  客户端标识
    * @param serverUrl 服务端地址
     */
    public HttpSyncClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    /** 连接 */
    public void connect() {
        if (connected) {
            return;
        }
        connected = true;
        reconnectCount.set(0);
        startPull();
        startHeartbeat();
        try {
            registerClient();
        } catch (Exception e) {
            notifyListeners(l -> l.onError("register", e));
        }
        notifyListeners(SyncFlowListener::onStart);
    }

    @Override
    /** 断开 */
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        stopPull();
        stopHeartbeat();
        subscriptions.clear();
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
        if (!connected) {
            throw new IllegalStateException("客户端未连接");
        }
        try {
            String body = "clientId=" + java.net.URLEncoder.encode(clientId, "UTF-8")
                    + "&" + java.net.URLEncoder.encode(topic, "UTF-8")
                    + "=" + java.net.URLEncoder.encode(message.toString(), "UTF-8");
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
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "http");
    }

    @Override
    /** 关闭 */
    public void close() {
        disconnect();
    }

    // ==================== 心跳 ====================

    /** 开始Heartbeat */
    private void startHeartbeat() {
        heartbeatThread = ThreadUtils.newThread(() -> {
            while (connected) {
                try {
                    ThreadUtils.sleep(HEARTBEAT_INTERVAL * 1000L);
                    if (!connected) {
                        break;
                    }
                    sendHeartbeat();
                } catch (Exception e) {
                    if (connected) {
                        notifyListeners(l -> l.onError("heartbeat", e));
                    }
                }
            }
        }, "http-sync-heartbeat-" + clientId);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    /** 停止Heartbeat */
    private void stopHeartbeat() {
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
    }

    /** 发送Heartbeat */
    private void sendHeartbeat() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/api/sync/pull?clientId=" + java.net.URLEncoder.encode(clientId, "UTF-8") + "&topics=__heartbeat__&timeout=5"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (connected) {
                notifyListeners(l -> l.onError("heartbeat", e));
            }
        }
    }

    // ==================== 拉取 ====================

    /** 开始拉取 */
    private void startPull() {
        pullThread = ThreadUtils.newThread(() -> {
            while (connected) {
                try {
                    pullMessages();
                } catch (Exception e) {
                    if (connected) {
                        notifyListeners(l -> l.onError("pull", e));
                        attemptReconnect();
                    }
                    // ThreadUtils.sleep(long) 不抛 checked 异常，直接调用即可
                    ThreadUtils.sleep(1000);
                }
            }
        }, "http-sync-pull-" + clientId);
        pullThread.setDaemon(true);
        pullThread.start();
    }

    /** 停止拉取 */
    private void stopPull() {
        if (pullThread != null) {
            pullThread.interrupt();
            pullThread = null;
        }
    }

    /** 拉取Messages */
    private void pullMessages() throws Exception {
        if (subscriptions.isEmpty()) {
            ThreadUtils.sleep(500);
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

    // ==================== 重连 ====================

    /** AttemptReconnect */
    private void attemptReconnect() {
        if (MAX_RECONNECT > 0 && reconnectCount.incrementAndGet() > MAX_RECONNECT) {
            return;
        }
        ThreadUtils.sleep(RECONNECT_INTERVAL);
        if (!connected) {
            return;
        }
        try {
            registerClient();
        } catch (Exception e) {
            // ignore
        }
    }

    /** 注册Client */
    private void registerClient() throws Exception {
        String body = "clientId=" + java.net.URLEncoder.encode(clientId, "UTF-8");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + "/api/sync/register"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ==================== 工具方法 ====================

    /** 通知Listeners */
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

// TcpSyncClientTestMark
