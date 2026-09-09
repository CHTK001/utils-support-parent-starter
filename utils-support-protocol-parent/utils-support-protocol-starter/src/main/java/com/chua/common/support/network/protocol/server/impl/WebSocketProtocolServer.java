package com.chua.common.support.network.protocol.server.impl;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.utils.IdUtils;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.TopicServletResponse;
import com.chua.common.support.network.protocol.request.WebSocketServletRequest;
import com.chua.common.support.network.protocol.session.ProtocolSession;
import com.chua.common.support.network.protocol.session.WebSocketSender;
import com.chua.common.support.network.protocol.session.WebSocketSession;
import lombok.extern.slf4j.Slf4j;
import org.smartboot.http.server.HttpBootstrap;
import org.smartboot.http.server.WebSocketHandler;
import org.smartboot.http.server.WebSocketRequest;
import org.smartboot.http.server.WebSocketResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * 基于 smart-http 的 WebSocket 服务器实现。
 */
@Slf4j
@Spi("WEBSOCKET")
@SpiDescribe(value = "WebSocket协议服务器")
public class WebSocketProtocolServer extends com.chua.common.support.network.protocol.server.AbstractTopicProtocolServer {

    private HttpBootstrap webSocketServer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentMap<String, SmartHttpWebSocketSender> connections = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> handshakeIndex = new ConcurrentHashMap<>();
    private ExecutorService messageExecutor;
    private int effectiveMaxConnections;
    private int effectiveWorkerThreads;

    public WebSocketProtocolServer(ServerSetting serverSetting) {
        super(serverSetting);
    }

    @Override
    protected ProtocolSession createSession(String connectionId, Object rawConnection, String topic) {
        if (rawConnection instanceof SmartHttpWebSocketSender sender) {
            return WebSocketSession.create(connectionId, sender);
        }
        return null;
    }

    @Override
    protected void doStart() {
        configureHighConcurrency();
        this.messageExecutor = createMessageExecutor();

        webSocketServer = new HttpBootstrap()
                .setPort(serverSetting.getPort())
                .webSocketHandler(new SmartHttpWebSocketHandler());

        configureWebSocketServer();
        webSocketServer.start();
        running.set(true);

        log.info("WebSocket服务器启动成功 [smart-http] - {}:{} - 最大连接数: {} - 工作线程: {} - 自动优化: {}",
                serverSetting.getHost(),
                serverSetting.getPort(),
                effectiveMaxConnections,
                effectiveWorkerThreads,
                serverSetting.isAutomaticOptimization());
    }

    private void configureHighConcurrency() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();

        if (serverSetting.isAutomaticOptimization()) {
            effectiveWorkerThreads = cpuCores * 4;
            long memoryPerConnection = 64 * 1024L;
            long availableForConnections = (long) (maxMemory * 0.6);
            effectiveMaxConnections = (int) Math.min(100000, availableForConnections / memoryPerConnection);

            log.info("WebSocket自动优化模式 - CPU核心: {}, 最大内存: {}MB, 工作线程: {}, 最大连接: {}",
                    cpuCores, maxMemory / 1024 / 1024, effectiveWorkerThreads, effectiveMaxConnections);
        } else {
            effectiveWorkerThreads = serverSetting.getWorkerThreads() > 0
                    ? serverSetting.getWorkerThreads()
                    : cpuCores * 2;
            effectiveMaxConnections = serverSetting.getMaxConnections() > 0
                    ? serverSetting.getMaxConnections()
                    : 10000;
        }
    }

    private void configureWebSocketServer() {
        webSocketServer.configuration()
                .host(serverSetting.getHost())
                .threadNum(Math.max(effectiveWorkerThreads, 2))
                .setWsIdleTimeout(resolveWsIdleTimeout())
                .bannerEnabled(false)
                .serverName("utils-support-common-starter/smart-http-ws");
    }

    private long resolveWsIdleTimeout() {
        if (serverSetting.getIdleTimeout() > 0) {
            return serverSetting.getIdleTimeout();
        }
        if (serverSetting.isAutomaticOptimization()) {
            return 0L;
        }
        return 30000L;
    }

    private ExecutorService createMessageExecutor() {
        if (serverSetting.isAutomaticOptimization()) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }

        int corePoolSize = effectiveWorkerThreads;
        int maxPoolSize = serverSetting.getMaxConnections() > 0
                ? Math.max(serverSetting.getMaxConnections() / 10, corePoolSize * 2)
                : corePoolSize * 4;
        int queueCapacity = serverSetting.getBacklog() > 0
                ? serverSetting.getBacklog() * 10
                : 10000;
        long keepAliveTime = serverSetting.getIdleTimeout() > 0
                ? serverSetting.getIdleTimeout()
                : 60000;

        AtomicInteger threadCounter = new AtomicInteger(0);
        ThreadFactory threadFactory = r -> {
            Thread thread = new Thread(r, "websocket-worker-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };

        RejectedExecutionHandler rejectedHandler = (r, executor) -> {
            log.warn("WebSocket消息处理线程池已满，当前活跃线程: {}, 队列大小: {}",
                    executor.getActiveCount(), executor.getQueue().size());
            if (!executor.isShutdown()) {
                r.run();
            }
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveTime,
                TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(queueCapacity),
                threadFactory,
                rejectedHandler
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private void fireMessageEvent(String connectionId, SmartHttpWebSocketSender sender, byte[] data, boolean isBinary) {
        try {
            String topic = connectionId != null ? getSessionTopic(connectionId) : null;

            try (ServletRequest request = createWebSocketRequest(connectionId, sender, data, isBinary, topic);
                 TopicServletResponse response = createTopicResponse(connectionId, topic)) {

                if (connectionId != null) {
                    ProtocolSession session = getSession(connectionId);
                    if (session != null) {
                        request.setSession(session);
                    }
                }

                if (topic != null && connectionId != null) {
                    Object message = isBinary ? data : new String(data, StandardCharsets.UTF_8);
                    fireOnEvent(topic, connectionId, message, request, response);
                }
            }
        } catch (Exception e) {
            log.error("处理消息失败", e);
            sendErrorMessage(sender, "处理消息失败: " + e.getMessage());
        }
    }

    private ServletRequest createWebSocketRequest(String connectionId, SmartHttpWebSocketSender sender,
                                                  byte[] data, boolean isBinary, String path) {
        WebSocketServletRequest.WebSocketServletRequestBuilder<?, ?> builder = WebSocketServletRequest.builder()
                .requestId(IdUtils.createSimpleUuid())
                .connectionId(connectionId)
                .clientIp(sender.getRemoteAddress())
                .clientPort(sender.getRemotePort())
                .messageType(isBinary ? "binary" : "text")
                .webSocketConnection(sender)
                .path(path)
                .url(path)
                .requestTime(java.time.LocalDateTime.now())
                .connectionTime(sender.getConnectionTime())
                .lastActivityTime(java.time.LocalDateTime.now());

        if (isBinary) {
            builder.body(data);
        } else {
            builder.message(new String(data, StandardCharsets.UTF_8));
        }

        return builder.build();
    }

    private void sendErrorMessage(SmartHttpWebSocketSender sender, String errorMessage) {
        try {
            sender.sendText("{\"error\":\"" + errorMessage + "\"}");
        } catch (Exception e) {
            log.error("发送错误消息失败", e);
        }
    }

    @Override
    protected void doStop() throws Exception {
        running.set(false);

        for (Map.Entry<String, SmartHttpWebSocketSender> entry : connections.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("关闭WebSocket连接失败: {}", entry.getKey(), e);
                }
            }
        }
        connections.clear();

        if (messageExecutor != null && !messageExecutor.isShutdown()) {
            messageExecutor.shutdown();
            try {
                if (!messageExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    messageExecutor.shutdownNow();
                    log.warn("WebSocket消息处理线程池强制关闭");
                }
            } catch (InterruptedException e) {
                messageExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (webSocketServer != null) {
            webSocketServer.shutdown();
            webSocketServer = null;
        }

        log.info("WebSocket服务器停止 [smart-http] - 总连接数: {}, 总消息数: {}", getTotalConnections(), getTotalMessages());
    }

    public String getThreadPoolStatus() {
        if (messageExecutor == null) {
            return "线程池未初始化";
        }
        if (messageExecutor instanceof ThreadPoolExecutor executor) {
            return String.format("活跃线程: %d, 核心线程: %d, 最大线程: %d, 队列大小: %d, 已完成任务: %d",
                    executor.getActiveCount(),
                    executor.getCorePoolSize(),
                    executor.getMaximumPoolSize(),
                    executor.getQueue().size(),
                    executor.getCompletedTaskCount());
        }
        return "虚拟线程执行器 (状态不可获取)";
    }

    public SmartHttpWebSocketSender getRawConnection(String connectionId) {
        return connections.get(connectionId);
    }

    private final class SmartHttpWebSocketHandler extends WebSocketHandler {

        @Override
        public void whenHeaderComplete(org.smartboot.http.server.impl.WebSocketRequestImpl request,
                                       org.smartboot.http.server.impl.WebSocketResponseImpl response) {
            String connectionId = IdUtils.createNanoId(32);
            String path = request.getRequestURI() != null ? request.getRequestURI() : "/";
            SmartHttpWebSocketSender sender = new SmartHttpWebSocketSender(connectionId, request, response);

            if (getConnectionCount() >= effectiveMaxConnections) {
                response.close(1011, "Maximum connections reached");
                return;
            }

            ProtocolSession session = onSessionOpen(connectionId, sender, path);
            if (session == null) {
                response.close(1011, "Session creation failed");
                return;
            }

            connections.put(connectionId, sender);
            handshakeIndex.put(buildSocketKey(request.getRequestURI(), request.getRemoteAddress()), connectionId);

            String protocolClient = request.getHeader("x-protocol-client");
            if ("true".equalsIgnoreCase(protocolClient) && log.isDebugEnabled()) {
                log.debug("检测到协议客户端连接，客户端ID: {}", request.getHeader("x-client-id"));
            }
        }

        @Override
        public void handle(WebSocketRequest request, WebSocketResponse response) {
            incrementMessageCount();
            String connectionId = handshakeIndex.get(buildSocketKey(request.getRequestURI(), request.getRemoteAddress()));
            SmartHttpWebSocketSender sender = connectionId == null ? null : connections.get(connectionId);
            if (sender == null) {
                log.warn("收到无法关联会话的WebSocket消息: {}", request.getRequestURI());
                return;
            }

            sender.bind(response);
            byte[] data = request.getPayload() == null ? new byte[0] : request.getPayload();
            boolean isBinary = request.getFrameOpcode() != 1;

            Runnable task = () -> fireMessageEvent(connectionId, sender, data, isBinary);
            if (messageExecutor != null && !messageExecutor.isShutdown()) {
                messageExecutor.execute(task);
            } else {
                task.run();
            }
        }

        @Override
        public void onClose(org.smartboot.http.server.impl.Request request) {
            String connectionId = handshakeIndex.remove(buildSocketKey(request.getRequestURI(), request.getRemoteAddress()));
            SmartHttpWebSocketSender sender = connectionId == null ? null : connections.remove(connectionId);
            if (sender == null) {
                return;
            }
            sender.invalidate();
            onSessionClose(sender.getConnectionId(), null);
            if (log.isDebugEnabled()) {
                log.debug("WebSocket连接关闭: {}", sender.getConnectionId());
            }
        }
    }

    private String buildSocketKey(String uri, InetSocketAddress remoteAddress) {
        String path = uri == null ? "/" : uri;
        if (remoteAddress == null) {
            return path + "|unknown";
        }
        return path + "|" + remoteAddress.toString();
    }

    private static final class SmartHttpWebSocketSender implements WebSocketSender {

        private final String connectionId;
        private final String remoteAddress;
        private final String localAddress;
        private final int remotePort;
        private final java.time.LocalDateTime connectionTime;
        private volatile WebSocketResponse response;
        private volatile boolean open = true;
        private volatile long sendTimeout = 30000;

        private SmartHttpWebSocketSender(String connectionId,
                                         org.smartboot.http.server.impl.WebSocketRequestImpl request,
                                         WebSocketResponse response) {
            this.connectionId = connectionId;
            this.remoteAddress = resolveAddress(request.getRemoteAddress());
            this.localAddress = resolveAddress(request.getLocalAddress());
            this.remotePort = request.getRemoteAddress() != null ? request.getRemoteAddress().getPort() : 0;
            this.connectionTime = java.time.LocalDateTime.now();
            this.response = response;
        }

        private void bind(WebSocketResponse response) {
            this.response = response;
        }

        private String getConnectionId() {
            return connectionId;
        }

        private int getRemotePort() {
            return remotePort;
        }

        private java.time.LocalDateTime getConnectionTime() {
            return connectionTime;
        }

        private void invalidate() {
            this.open = false;
            this.response = null;
        }

        @Override
        public void sendText(String text) throws IOException {
            ensureOpen();
            response.sendTextMessage(text);
            response.flush();
        }

        @Override
        public void sendBinary(byte[] data) throws IOException {
            ensureOpen();
            response.sendBinaryMessage(data);
            response.flush();
        }

        @Override
        public void close() throws IOException {
            if (response != null) {
                response.close();
            }
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open && response != null;
        }

        @Override
        public String getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public String getLocalAddress() {
            return localAddress;
        }

        @Override
        public void sendPing(byte[] data) throws IOException {
            ensureOpen();
            response.ping(data);
            response.flush();
        }

        @Override
        public void sendPong(byte[] data) throws IOException {
            ensureOpen();
            response.pong(data);
            response.flush();
        }

        @Override
        public void setSendTimeout(long timeoutMs) {
            this.sendTimeout = timeoutMs;
        }

        @Override
        public long getSendTimeout() {
            return sendTimeout;
        }

        @Override
        public String getConnectionStatus() {
            return String.format("SmartHttpWebSocket{open=%s, remote=%s}", isOpen(), getRemoteAddress());
        }

        private void ensureOpen() throws IOException {
            if (!isOpen()) {
                throw new IOException("WebSocket连接已关闭: " + connectionId);
            }
        }

        private static String resolveAddress(InetSocketAddress address) {
            if (address == null || address.getAddress() == null) {
                return "unknown";
            }
            return address.getAddress().getHostAddress();
        }
    }
}
