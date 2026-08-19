package com.chua.kcp.support.sync;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.kcp.support.client.KcpClient;
import com.chua.kcp.support.server.KcpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 基于 kcp-base 的 KCP 同步客户端实现。
 *
 * <p>通过 KCP 可靠 UDP 长连接与服务端双向同步，支持注册、主题订阅与消息收发，
 * 与 {@link KcpSyncServer} 配对使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("kcp")
public class KcpSyncClient implements SyncClient {

    /**
     * 日志实例
     */
    private static final Logger log = LoggerFactory.getLogger(KcpSyncClient.class);

    /**
     * 服务端 URL（kcp://host:port）
     */
    private final String serverUrl;
    /**
     * 客户端标识
     */
    private final String clientId;
    /**
     * 底层 KCP 客户端实例
     */
    private KcpClient kcpClient;
    /**
     * 是否已连接
     */
    private boolean connected;

    /**
     * 连接前注册的订阅缓存（topic -> handler），连接成功后统一应用
     */
    private final Map<String, SyncMessageHandler> pendingSubscriptions = new ConcurrentHashMap<>();

    /**
     * 创建 KcpSyncClient 实例
     * @param serverUrl serverUrl
     */
    public KcpSyncClient(String serverUrl) {
        this("kcp-sync-client", serverUrl);
    }

    /**
     * 创建 KcpSyncClient 实例
     * @param clientId clientId
     * @param String String
     */
    public KcpSyncClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
    }

    @Override
    /** 连接 */
    public synchronized void connect() {
        if (connected) {
            return;
        }
        kcpClient = new KcpClient(clientId, serverUrl);
        kcpClient.connect();
        connected = true;
        // 应用连接前注册的订阅
        for (Map.Entry<String, SyncMessageHandler> entry : pendingSubscriptions.entrySet()) {
            kcpClient.subscribe(entry.getKey(), (t, p) -> entry.getValue().handle(t, p));
        }
        pendingSubscriptions.clear();
    }

    @Override
    /** 断开 */
    public synchronized void disconnect() {
        if (!connected) {
            return;
        }
        try {
            kcpClient.disconnect();
        } catch (Exception e) {
            log.warn("KCP 断开异常: {}", e.getMessage());
        }
        connected = false;
        kcpClient = null;
    }

    @Override
    /** 是否Connected */
    public boolean isConnected() {
        return connected && kcpClient != null && kcpClient.isConnected();
    }

    /** 执行 */
    public String execute(String topic, Object message) {
        if (!isConnected()) {
            throw new IllegalStateException("KCP 未连接");
        }
        return kcpClient.execute(topic, message, 5000);
    }

    /** 执行 */
    public String execute(String topic, Object message, long timeoutMs) {
        if (!isConnected()) {
            throw new IllegalStateException("KCP 未连接");
        }
        return kcpClient.execute(topic, message, timeoutMs);
    }

    /** 执行Async */
    public CompletableFuture<String> executeAsync(String topic, Object message) {
        if (!isConnected()) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("KCP 未连接"));
            return failed;
        }
        return kcpClient.executeAsync(topic, message);
    }

    /** 执行Async */
    public CompletableFuture<String> executeAsync(String topic, Object message, long timeoutMs) {
        if (!isConnected()) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("KCP 未连接"));
            return failed;
        }
        return kcpClient.executeAsync(topic, message, timeoutMs);
    }

    /**
     * 发送消息。
     *
     * @param topic   主题
     * @param message 消息内容
     */
    @Override
    public void send(String topic, Object message) {
        if (!isConnected()) {
            throw new IllegalStateException("KCP 未连接");
        }
        kcpClient.send(topic, message);
    }

    /** 发布 */
    public void publish(String topic, Object message) {
        if (!isConnected()) {
            throw new IllegalStateException("KCP 未连接");
        }
        kcpClient.publish(topic, message);
    }

    /**
     * 添加同步事件监听器。
     *
     * @param listener 监听器
     */
    @Override
    public void addListener(SyncFlowListener listener) {
        if (kcpClient != null) {
            kcpClient.onMessage((topic, payload) -> listener.onMessage(topic, payload));
        }
    }

    /**
     * 移除同步事件监听器。
     *
     * @param listener 监听器
     */
    @Override
    public void removeListener(SyncFlowListener listener) {
        // KcpClient 不支持按实例移除监听器，空实现保持接口契约
    }

    /**
     * 获取客户端元数据。
     *
     * @return 元数据映射
     */
    @Override
    public Map<String, Object> getMetadata() {
        return kcpClient != null ? kcpClient.getMetadata() : Map.of();
    }

    @Override
    /** 订阅 */
    public void subscribe(String topic, SyncMessageHandler handler) {
        // 允许连接前注册订阅（ConnectionPool 在连接建立前调用 responseSubscriber）
        if (topic != null && handler != null) {
            pendingSubscriptions.put(topic, handler);
        }
        if (isConnected()) {
            kcpClient.subscribe(topic, (t, p) -> handler.handle(t, p));
        }
    }

    @Override
    /** 取消订阅 */
    public void unsubscribe(String topic) {
        pendingSubscriptions.remove(topic);
        if (kcpClient != null) {
            kcpClient.unsubscribe(topic);
        }
    }

    /**
     * 注册同步流程监听器。
     *
     * @param listener 监听器
     * @return 当前实例
     */
    public KcpSyncClient onFlow(SyncFlowListener listener) {
        if (!isConnected()) {
            throw new IllegalStateException("KCP 未连接");
        }
        kcpClient.onMessage((topic, payload) -> listener.onMessage(topic, payload));
        return this;
    }

    /**
     * 注册客户端元数据。
     *
     * @param meta 元数据
     */
    public void register(Map<String, Object> meta) {
        if (kcpClient != null) {
            meta.forEach((k, v) -> kcpClient.getMetadata().put(k, v));
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        disconnect();
    }

    /** 获取ClientId */
    public String getClientId() {
        return clientId;
    }

    /** 获取ServerUrl */
    public String getServerUrl() {
        return serverUrl;
    }
}
