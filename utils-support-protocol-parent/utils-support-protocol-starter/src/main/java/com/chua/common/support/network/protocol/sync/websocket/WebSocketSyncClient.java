package com.chua.common.support.network.protocol.sync.websocket;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.text.json.Json;
import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.network.protocol.sync.*;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * WebSocket同步数据客户端实现
 * <p>
 * 基于Java 11内置WebSocket API实现的同步数据客户端。
 * 继承 {@link AbstractSyncClient}，复用连接管理和监听器功能。
 * <p>
 * 特性:
 * 1. 使用Java 11原生WebSocket API
 * 2. 自动重连机制
 * 3. 异步非阻塞通信
 * 4. 消息分片处理
 *
 * @author CH
 * @version 2.0.0
 * @since 2024/12/01
 */
@Slf4j
@Spi("websocket-sync")
public class WebSocketSyncClient extends AbstractSyncClient {

    /**
     * Java 11 WebSocket客户端
     */
    private WebSocket webSocket;

    /**
     * HTTP客户端（创建WebSocket）
     */
    private HttpClient httpClient;

    /**
     * 重连调度器
     */
    private ScheduledExecutorService reconnectScheduler;

    /**
     * 重连次数
     */
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    /**
     * 是否正在重连
     */
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    /**
     * 是否主动关闭（主动关闭不重连）
     */
    private final AtomicBoolean manualClose = new AtomicBoolean(false);

    /**
     * WebSocket连接状态
     */
    private final AtomicBoolean wsConnected = new AtomicBoolean(false);

    /**
     * 重连间隔（秒）
     */
    private int reconnectInterval = 5;

    /**
     * 最大重连次数，-1表示无限重连
     */
    private int maxReconnectAttempts = -1;

    /**
     * 服务器URI
     */
    private URI serverUri;

    /**
     * 消息缓冲区（处理消息分片）
     */
    private final StringBuilder messageBuffer = new StringBuilder();

    public WebSocketSyncClient(ClientSetting clientSetting) {
        super(clientSetting);
        // 从配置中读取重连参数
        if (clientSetting.getConnectTimeoutMillis() > 0) {
            this.reconnectInterval = (int) (clientSetting.getConnectTimeoutMillis() / 1000 / 2);
            if (this.reconnectInterval < 1) {
                this.reconnectInterval = 5;
            }
        }
        // 从 options 读取重连参数
        Object autoReconnect = clientSetting.getOption("autoReconnect");
        Object maxAttempts = clientSetting.getOption("maxReconnectAttempts");
        Object interval = clientSetting.getOption("reconnectInterval");

        if (Boolean.FALSE.equals(autoReconnect)) {
            this.maxReconnectAttempts = 0;
        } else if (maxAttempts instanceof Number) {
            this.maxReconnectAttempts = ((Number) maxAttempts).intValue();
        }
        if (interval instanceof Number) {
            this.reconnectInterval = ((Number) interval).intValue();
        }

        initReconnectScheduler();
    }

    /**
     * 初始化重连调度器
     */
    private void initReconnectScheduler() {
        reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "websocket-sync-reconnect-" + clientId.substring(0, 8));
            thread.setDaemon(true);
            return thread;
        });
    }

    // ==================== AbstractSyncClient 抽象方法实现 ====================

    @Override
    protected boolean doConnect() throws Exception {
        manualClose.set(false);
        reconnecting.set(false);

        String path = clientSetting.getPath();
        if (path == null || path.isEmpty()) {
            path = "/sync";
        }
        String url = String.format("ws://%s:%d%s",
                clientSetting.getHost(),
                clientSetting.getPort(),
                path);

        serverUri = new URI(url);

        // 创建HTTP客户端
        long timeout = clientSetting.getConnectTimeoutMillis() > 0
                ? clientSetting.getConnectTimeoutMillis()
                : 10000;

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout))
                .executor(Executors.newCachedThreadPool(r -> {
                    Thread thread = new Thread(r, "ws-client-" + clientId.substring(0, 8));
                    thread.setDaemon(true);
                    return thread;
                }))
                .build();

        // 创建WebSocket连接
        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .buildAsync(serverUri, new InternalWebSocketListener())
                    .get(timeout, TimeUnit.MILLISECONDS);

            if (webSocket != null && !webSocket.isInputClosed()) {
                wsConnected.set(true);
                reconnectAttempts.set(0);
                log.info("WebSocket连接已建立: clientId={}", clientId);
                notifyConnectionState(true);
                return true;
            }
        } catch (TimeoutException e) {
            log.warn("WebSocket连接超时");
        } catch (Exception e) {
            log.error("WebSocket连接失败: {}", e.getMessage());
        }

        scheduleReconnect();
        return false;
    }

    @Override
    protected void doDisconnect() throws Exception {
        manualClose.set(true);
        wsConnected.set(false);
        if (webSocket != null && !webSocket.isOutputClosed()) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing").join();
        }
    }

    @Override
    protected void doSubscribe(String topic) {
        if (isConnected()) {
            publish(SyncTopic.DATA.getName(), "subscribe:" + topic);
        }
    }

    @Override
    protected void doUnsubscribe(String topic) {
        if (isConnected()) {
            publish(SyncTopic.DATA.getName(), "unsubscribe:" + topic);
        }
    }

    @Override
    protected void doPublish(String topic, Object data) throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("WebSocket未连接");
        }
        SyncMessage message = SyncMessage.of(topic, data, clientId);
        String json = Json.toJson(message);
        webSocket.sendText(json, true).join();
    }

    @Override
    public boolean isConnected() {
        return connected.get() && wsConnected.get() && webSocket != null && !webSocket.isInputClosed();
    }

    /**
     * 调度重连
     */
    private void scheduleReconnect() {
        if (manualClose.get()) {
            if (log.isDebugEnabled()) {
                log.debug("客户端已主动关闭，不进行重连");
            }
            return;
        }

        if (!reconnecting.compareAndSet(false, true)) {
            if (log.isDebugEnabled()) {
                log.debug("已在重连中，跳过");
            }
            return;
        }

        if (maxReconnectAttempts >= 0 && reconnectAttempts.get() >= maxReconnectAttempts) {
            log.error("WebSocket客户端达到最大重连次数 {}，停止重连", maxReconnectAttempts);
            reconnecting.set(false);
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        log.info("WebSocket客户端 {} 秒后尝试第 {} 次重连", reconnectInterval, attempts);

        if (reconnectScheduler != null && !reconnectScheduler.isShutdown()) {
            reconnectScheduler.schedule(this::doReconnect, reconnectInterval, TimeUnit.SECONDS);
        } else {
            reconnecting.set(false);
        }
    }

    /**
     * 执行重连
     */
    private void doReconnect() {
        if (manualClose.get()) {
            reconnecting.set(false);
            return;
        }

        if (isConnected()) {
            if (log.isDebugEnabled()) {
                log.debug("WebSocket连接，跳过重连");
            }
            reconnecting.set(false);
            return;
        }

        log.info("WebSocket客户端开始第 {} 次重连...", reconnectAttempts.get());

        try {
            // 关闭旧连接
            closeWebSocket();

            // 重新连接
            long timeout = clientSetting.getConnectTimeoutMillis() > 0
                    ? clientSetting.getConnectTimeoutMillis()
                    : 10000;

            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .buildAsync(serverUri, new InternalWebSocketListener())
                    .get(timeout, TimeUnit.MILLISECONDS);

            if (webSocket != null && !webSocket.isInputClosed()) {
                wsConnected.set(true);
                reconnectAttempts.set(0);
                reconnecting.set(false);
                log.info("WebSocket客户端重连成功");
                notifyConnectionState(true);

                // 重新订阅之前的主题
                resubscribeTopics();
            } else {
                log.warn("WebSocket客户端重连失败");
                reconnecting.set(false);
                scheduleReconnect();
            }
        } catch (TimeoutException e) {
            log.warn("WebSocket客户端第 {} 次重连超时", reconnectAttempts.get());
            reconnecting.set(false);
            scheduleReconnect();
        } catch (Exception e) {
            log.error("WebSocket客户端第 {} 次重连失败: {}", reconnectAttempts.get(), e.getMessage());
            reconnecting.set(false);
            scheduleReconnect();
        }
    }

    /**
     * 关闭WebSocket连接
     */
    private void closeWebSocket() {
        wsConnected.set(false);
        if (webSocket != null) {
            try {
                if (!webSocket.isOutputClosed()) {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting");
                }
            } catch (Exception ignored) {
            }
            webSocket = null;
        }
    }

    /**
     * 重新订阅主题（重连后调用）
     */
    private void resubscribeTopics() {
        if (subscribedTopics.isEmpty()) {
            return;
        }
        log.info("重新订阅 {} 个主题...", subscribedTopics.size());
        for (String topic : subscribedTopics) {
            try {
                publish(SyncTopic.DATA.getName(), "subscribe:" + topic);
            } catch (Exception e) {
                log.warn("重新订阅主题失败: {}", topic, e);
            }
        }
    }

    @Override
    public void close() {
        manualClose.set(true);
        // 关闭重连调度器
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
        }
        closeWebSocket();
        super.close();
    }

    // ==================== 内部类 ====================

    /**
     * Java 11 WebSocket监听器实现
     */
    private class InternalWebSocketListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            if (log.isDebugEnabled()) {
                log.debug("WebSocket连接打开");
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);

            if (last) {
                // 消息完整，处理完整消息
                String message = messageBuffer.toString();
                messageBuffer.setLength(0);

                try {
                    SyncMessage syncMessage = Json.fromJson(message, SyncMessage.class);
                    if (syncMessage != null) {
                        String topic = syncMessage.getTopic();
                        Object msgData = syncMessage.getData();
                        // 不做客户端过滤，所有消息都传递给ListenerManager
                        // DataListener 接收所有消息，TopicListener 由ListenerManager根据topic匹配
                        notifyMessage(topic, msgData);
                    }
                } catch (Exception e) {
                    log.error("解析消息失败: {}", message, e);
                }
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (log.isDebugEnabled()) {
                log.debug("收到二进制消息，长度: {}", data.remaining());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            if (log.isDebugEnabled()) {
                log.debug("收到Ping消息");
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            if (log.isDebugEnabled()) {
                log.debug("收到Pong消息");
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("WebSocket连接关闭: clientId={}, code={}, reason={}", clientId, statusCode, reason);

            wsConnected.set(false);
            connected.set(false);

            // 服务器关闭连接，需要重连
            if (!manualClose.get()) {
                notifyConnectionState(false);
                log.info("服务器断开连接，准备重连...");
                scheduleReconnect();
            }

            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("WebSocket错误: clientId={}", clientId, error);

            wsConnected.set(false);
            connected.set(false);

            // 发生错误，尝试重连
            if (!manualClose.get()) {
                notifyConnectionState(false);
                scheduleReconnect();
            }
        }
    }
}
