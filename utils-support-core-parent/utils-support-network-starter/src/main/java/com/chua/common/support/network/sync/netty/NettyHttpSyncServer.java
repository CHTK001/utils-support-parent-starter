package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
* 基于 Netty 的 HTTP 同步服务端实现。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("netty-http")
public class NettyHttpSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer {

    /** 客户端 */
    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();
    /** 消息队列 */
    private final Map<String, java.util.Queue<String>> messageQueues = new ConcurrentHashMap<>();
    /** 监听器 */
    private final List<SyncServerListener> listeners = new ArrayList<>();
    /** 服务器 */
    private com.sun.net.httpserver.HttpServer server;

    /**
    * 创建 nettyhttp同步服务端 实例
    * @param setting setting
    */
    public NettyHttpSyncServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** 执行开始 */
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
            server.start();
        } catch (Exception e) {
            throw new RuntimeException("Netty HTTP SyncServer 启动失败", e);
        }
    }

    @Override
    /** 执行停止 */
    protected void doStop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        clients.clear();
        messageQueues.clear();
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object message) {
        String payload = topic + ":" + message.toString();
        messageQueues.computeIfAbsent(topic, k -> new java.util.LinkedList<>()).add(payload);
    }

    @Override
    /** 发送 */
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
    /** 获取连接客户端 */
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    /** 获取客户端metadata */
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = clients.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    @Override
    /** 添加监听器 */
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 移除监听器 */
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    /** 获取协议类型 */
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    /**
    * 拉取消息
    *
    * @param topics topics
    * @param timeout 超时
    * @return 拉手消息的结果
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
    *
    * @param exchange exchange
    * @param code 编码
    * @param body 主体
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
    * extract参数
    *
    * @param body 主体
    * @param key 键
    * @return extract参数的结果
    */
    private String extractParam(String body, String key) {
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
    * 解析查询
    *
    * @param query 查询
    * @return 解析查询的结果
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
    *
    * @param action 动作
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
