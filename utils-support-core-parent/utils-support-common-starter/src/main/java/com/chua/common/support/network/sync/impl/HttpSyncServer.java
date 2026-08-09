package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 JDK HttpServer 的 HTTP 同步服务端实现。
 * <p>
 * 提供主题发布、客户端消息拉取等能力。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Spi("http")
public class HttpSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer, SyncProtocol {

    @Override
    public String getProtocol() {
        return "http";
    }

    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new HttpSyncServer(setting);
    }

    @Override
    public SyncClient createClient(Object setting) {
        String url = setting instanceof String ? (String) setting : "http://127.0.0.1:19380";
        return new HttpSyncClient(url);
    }

    /**
     * 客户端注册表（clientId -> metadata）
     */
    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();

    /**
     * 消息队列（topic -> queue of message）
     */
    private final Map<String, java.util.Queue<String>> messageQueues = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncServerListener> listeners = new ArrayList<>();

    /**
     * HTTP 服务器
     */
    private com.sun.net.httpserver.HttpServer server;

    /**
     * 创建 HTTP 同步服务端 (默认配置)。
     */
    public HttpSyncServer() {
        this(ServerSetting.defaults());
    }

    /**
     * 创建 HTTP 同步服务端。
     *
     * @param setting 服务端配置
     */
    public HttpSyncServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    protected void doStart() {
        try {
            server = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress(setting.getHost(), setting.getPort()), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());

            server.createContext("/api/sync/send", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, 0);
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes());
                String topic = extractParam(body, "topic");
                String message = extractParam(body, "message");
                String cid = extractParam(body, "clientId");
                if (topic != null && message != null) {
                    publish(topic, message);
                    sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                } else {
                    sendResponse(exchange, 400, "{\"error\":\"missing params\"}");
                }
            });

            server.createContext("/api/sync/pull", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, 0);
                    return;
                }
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQuery(query);
                String clientId = params.get("clientId");
                String topicsStr = params.get("topics");
                String timeoutStr = params.get("timeout");

                if (clientId == null || topicsStr == null) {
                    sendResponse(exchange, 400, "{\"error\":\"missing params\"}");
                    return;
                }

                clients.putIfAbsent(clientId, new HashMap<>());
                String[] topics = topicsStr.split(",");
                int timeout = timeoutStr != null ? Integer.parseInt(timeoutStr) : 30;

                String message = pullMessage(topics, timeout);
                if (message != null) {
                    sendResponse(exchange, 200, message);
                } else {
                    sendResponse(exchange, 204, "");
                }
            });

            server.createContext("/api/sync/register", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, 0);
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes());
                String clientId = extractParam(body, "clientId");
                if (clientId != null) {
                    clients.putIfAbsent(clientId, new HashMap<>());
                    notifyListener(l -> l.onClientConnected(clientId, clients.get(clientId)));
                    sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                } else {
                    sendResponse(exchange, 400, "{\"error\":\"missing clientId\"}");
                }
            });

            server.start();
        } catch (Exception e) {
            throw new RuntimeException("HTTP SyncServer 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        clients.clear();
        messageQueues.clear();
    }

    @Override
    public void publish(String topic, Object message) {
        String payload = topic + ":" + message.toString();
        messageQueues.computeIfAbsent(topic, k -> new java.util.LinkedList<>()).add(payload);
    }

    @Override
    public void send(String clientId, String topic, Object message) {
        Map<String, Object> meta = clients.get(clientId);
        if (meta == null) {
            return;
        }
        messageQueues.computeIfAbsent(topic, k -> new java.util.LinkedList<>())
                .add(topic + ":" + message.toString());
        notifyListener(l -> l.onMessage(clientId, topic, message));
    }

    @Override
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = clients.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    @Override
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    // ==================== 内部方法 ====================

    /**
     * 拉取消息（阻塞直到有消息或超时）
     */
    private String pullMessage(String[] topics, int timeout) {
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (String topic : topics) {
                java.util.Queue<String> queue = messageQueues.get(topic);
                if (queue != null && !queue.isEmpty()) {
                    return queue.poll();
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /**
     * 发送响应
     */
    private void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 提取参数（支持 form-urlencoded 和 JSON 格式）
     */
    private String extractParam(String body, String key) {
        if (body == null || key == null) {
            return null;
        }

        String encodedKey = key;
        try {
            encodedKey = java.net.URLEncoder.encode(key, "UTF-8");
        } catch (Exception ignored) {
        }

        if (body.contains("=")) {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String k = pair.substring(0, idx);
                    String v = pair.substring(idx + 1);
                    try {
                        k = java.net.URLDecoder.decode(k, "UTF-8");
                        v = java.net.URLDecoder.decode(v, "UTF-8");
                    } catch (Exception ignored) {
                    }
                    if (key.equals(k) || encodedKey.equals(k)) {
                        return v;
                    }
                }
            }
        }

        String pattern = "\"" + key + "\"";
        int idx = body.indexOf(pattern);
        if (idx < 0) {
            return null;
        }
        int colon = body.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        int start = body.indexOf('"', colon + 1);
        int end = body.indexOf('"', start + 1);
        if (start < 0 || end < 0) {
            return null;
        }
        return body.substring(start + 1, end);
    }

    /**
     * 解析查询参数
     */
    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String value = pair.substring(idx + 1);
                try {
                    params.put(java.net.URLDecoder.decode(key, "UTF-8"),
                            java.net.URLDecoder.decode(value, "UTF-8"));
                } catch (Exception e) {
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    /**
     * 通知监听器
     */
    private void notifyListener(java.util.function.Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
