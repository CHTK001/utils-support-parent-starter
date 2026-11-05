// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.sync.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.chua.common.support.network.server.AbstractServer;

/**
 * 基于 JDK HttpServer 的 HTTP 同步服务端实现。
 * <p>
 * 提供主题发布、客户端消息拉取等能力。
 * </p>
 *
 * <p>与 {@code JdkHttpServer} 同样的连接层边界：{@code com.sun.net.httpserver} 不暴露
 * accept 钩子，本实现无法按 {@code maxConnections} 做连接预算，连接数指标恒为 0；
 * 长轮询的并发量由虚拟线程 executor 承载，容量取决于内核 fd 与系统参数。</p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Slf4j
@Spi("http")
public class HttpSyncServer extends AbstractServer implements SyncServer, SyncProtocol {


    /**
     * 获取Protocol
     */
    @Override
    public String getProtocol() {
        return "http";
    }

    /**
     * 创建Server
     */
    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new HttpSyncServer(setting);
    }

    /**
     * 创建Client
     */
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
     * 长轮询唤醒源。
     *
     * <p>{@code /api/sync/pull} 是长轮询接口，旧实现在循环里 {@code sleep(100)} 主动轮询，
     * 每条挂起的拉取连接都要睡上若干个 100ms：既让新消息至少延迟 100ms 送达，
     * 也在连接数上万时制造大量无效唤醒。改为「发布即唤醒」，队列变化时由
     * {@link #publish} / {@link #send} 通知，延迟降到唤醒开销量级。</p>
     */
    private final Object messageSignal = new Object();

    /**
     * 处理请求的执行器（stop 时关闭，避免线程池泄漏）。
     */
    private java.util.concurrent.ExecutorService handlerExecutor;

    /**
     * 监听器列表
     */
    private final List<SyncServerListener> listeners = Wraps.list();

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

    /**
     * Do开始
     */
    @Override
    protected void doStart() {
        try {
            // backlog 取配置值（0 会让内核用极小默认值）：百万连接目标下握手队列是关键一环
            server = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress(setting.getHost(), setting.getPort()),
                    setting.resolveBacklog(1024));
// [P3C C1 豁免] 场景化创建线程，需文档化
            handlerExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(handlerExecutor);

            server.createContext("/api/sync/send", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, 0);
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes());
                String topic = extractParam(body, "topic");
                String message = extractParam(body, "message");
                String cid = extractParam(body, "clientId");
                if (topic != null && message != null && cid != null) {
                    send(cid, topic, message);
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

                clients.putIfAbsent(clientId, Wraps.map());
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
                    clients.putIfAbsent(clientId, Wraps.map());
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

    /**
     * Do停止
     */
    @Override
    protected void doStop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (handlerExecutor != null) {
            handlerExecutor.shutdownNow();
            handlerExecutor = null;
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
        signalMessage();
    }

    /**
     * 唤醒所有挂起的长轮询拉取（发布/投递后调用）。
     *
     * <p>用 {@code notifyAll} 而不是按 topic 精确唤醒：一条通知只做一次
     * {@link System#currentTimeMillis()} 检查，代价远低于「按 topic 维护等待集合」的复杂度；
     * 未命中自己 topic 的等待者会被重新挂起，不会丢消息也不会忙等。</p>
     */
    private void signalMessage() {
        synchronized (messageSignal) {
            messageSignal.notifyAll();
        }
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
        signalMessage();
        notifyListener(l -> l.onMessage(clientId, topic, message));
    }

    /**
     * 获取ConnectedClients
     */
    @Override
    public List<String> getConnectedClients() {
        return Wraps.list(clients.keySet());
    }

    /**
     * 获取ClientMetadata
     */
    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = clients.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    /**
     * 添加Listener
     */
    @Override
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除Listener
     */
    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取ProtocolType
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    // ==================== 内部方法 ====================

    /**
     * 拉取消息（阻塞直到有消息或超时）。
     *
     * <p>旧实现是 {@code sleep(100)} 轮询：新消息平均延迟 50ms、挂起连接多时还会
     * 产生大量无效唤醒。这里改为在 {@link #messageSignal} 上等待，由
     * {@link #publish} / {@link #send} 在入队后 {@code notifyAll} 唤醒；
     * 等待期间释放监视器，连接数再多也只是虚拟线程挂起。</p>
     *
     * @param topics  方法入参 topics
     * @param timeout 超时时间（秒），不允许为 null
     * @return 结果字符串
     */
    private String pullMessage(String[] topics, int timeout) {
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        while (true) {
            String message = pollFromTopics(topics);
            if (message != null) {
                return message;
            }
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                return null;
            }
            synchronized (messageSignal) {
                // 先复查再等待，避免「入队与等待之间」的通知丢失
                message = pollFromTopics(topics);
                if (message != null) {
                    return message;
                }
                remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    return null;
                }
                try {
                    messageSignal.wait(Math.min(remain, 1000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /**
     * 按 topic 顺序取出第一条消息。
     *
     * @param topics 主题列表
     * @return 消息，全部为空时返回 null
     */
    private String pollFromTopics(String[] topics) {
        for (String topic : topics) {
            java.util.Queue<String> queue = messageQueues.get(topic);
            if (queue != null && !queue.isEmpty()) {
                return queue.poll();
            }
        }
        return null;
    }

    /**
     * 发送响应
     * @param body 请求体，不允许为 null
     * @param code 编码，不允许为 null
     * @param exchange 方法入参 exchange
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
     * 提取参数（支持 form-urlencoded 和 JSON 格式）
     * @param body 请求体，不允许为 null
     * @param key 键，不允许为 null
     * @return 结果字符串
     */
    private String extractParam(String body, String key) {
        if (body == null || key == null) {
            return null;
        }

        String encodedKey = key;
        try {
            encodedKey = java.net.URLEncoder.encode(key, "UTF-8");
        } catch (Exception e) {
            log.debug("[sync] URL 编码失败，使用原始 key: {}", e.getMessage());
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
                    } catch (Exception e) {
                        log.debug("[sync] URL 解码失败，使用原始值: {}", e.getMessage());
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
     * @param query 查询，不允许为 null
     * @return 结果映射，无数据时为空映射
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
     * @param action 方法入参 action
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