// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.sync.netty;

import com.chua.common.support.collection.wrap.Wraps;
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

    /**
     * 客户端
    */
    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();
    /**
     * 消息队列
    */
    private final Map<String, java.util.Queue<String>> messageQueues = new ConcurrentHashMap<>();
    /**
     * 监听器
    */
    private final List<SyncServerListener> listeners = Wraps.list();
    /**
     * 服务器
    */
    private com.sun.net.httpserver.HttpServer server;

    /**
     * 创建 nettyhttp同步服务端 实例
     * @param setting setting
     */
    public NettyHttpSyncServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 执行开始
    */
    @Override
    protected void doStart() {
        try {
            server = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress(setting.getHost(), setting.getPort()), 0);
// [P3C C1 豁免] 场景化创建线程，需文档化
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/api/sync/send", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    // 必须真正写完并关闭 exchange：只调 sendResponseHeaders(405, 0) 会走
                    // chunked 编码，响应体不关闭就没有结束块，客户端一直等到超时。
                    sendResponse(exchange, 405, "{\"error\":\"method not allowed\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
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
                    sendResponse(exchange, 405, "{\"error\":\"method not allowed\"}");
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
                clients.putIfAbsent(clientId, Wraps.map());
                String[] topics = topicsStr.split(",");
                int timeout = timeoutStr != null ? Integer.parseInt(timeoutStr) : 30;
                String message = pullMessage(topics, timeout);
                if (message != null) {
                    sendResponse(exchange, 200, message);
                } else {
                    // 204 无响应体：长度必须传 -1，不能按 chunked(0) 发
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                }
            });
            server.start();
            // 回写实际端口：配置 port=0（随机端口）时，不回写就永远拿不到真实端口
            setting.setPort(server.getAddress().getPort());
        } catch (Exception e) {
            throw new RuntimeException("Netty HTTP SyncServer 启动失败", e);
        }
    }

    /**
     * 执行停止
    */
    @Override
    protected void doStop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        clients.clear();
        messageQueues.clear();
    }

    /**
     * 发布
    */
    @Override
    public void publish(String topic, Object message) {
        String payload = topic + ":" + message.toString();
        messageQueues.computeIfAbsent(topic, k -> Wraps.deque()).add(payload);
    }

    /**
     * 发送
    */
    @Override
    public void send(String clientId, String topic, Object message) {
        Map<String, Object> meta = clients.get(clientId);
        if (meta == null) {
            return;
        }
        messageQueues.computeIfAbsent(topic, k -> Wraps.deque())
                .add(topic + ":" + message.toString());
        notifyListener(l -> l.onMessage(clientId, topic, message));
    }

    /**
     * 获取连接客户端
    */
    @Override
    public List<String> getConnectedClients() {
        return Wraps.list(clients.keySet());
    }

    /**
     * 获取客户端metadata
    */
    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = clients.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    /**
     * 添加监听器
    */
    @Override
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除监听器
    */
    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取协议类型
    */
    @Override
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
     * @param body 主体
     * @param code 编码
     * @param exchange exchange
     * @return 返回值
     * @throws IOException 异常
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
        if (body == null || body.isEmpty()) {
            return null;
        }
        // 客户端发的是 application/x-www-form-urlencoded（topic=...&message=...）。
        // 原实现只找 "\"key\"" 这种 JSON 片段，表单参数永远解析不出来，
        // POST /api/sync/send 一律回 400，上行消息被静默丢弃。
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String name = decode(pair.substring(0, idx));
            if (key.equals(name)) {
                return decode(pair.substring(idx + 1));
            }
        }
        // 兼容既有 JSON 形式的 {"key":"value"}
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
     * URL 解码（失败时原样返回）。
     *
     * @param value 原始值
     * @return 解码结果
     */
    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 解析查询
     *
     * @param query 查询
     * @return 解析查询的结果
     */
    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = Wraps.map();
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
    }}