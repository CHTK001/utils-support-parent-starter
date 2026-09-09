package com.chua.common.support.network.protocol.sync.websocket;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.utils.IdUtils;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.sync.AbstractSyncServer;
import com.chua.common.support.network.protocol.sync.SyncSession;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ?? smart-http ? WebSocket ????????
 */
@Slf4j
@Spi("websocket-sync")
public class WebSocketSyncServer extends AbstractSyncServer {

    private HttpBootstrap webSocketServer;
    private final ConcurrentMap<String, SmartHttpSyncSocket> connections = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> handshakeIndex = new ConcurrentHashMap<>();
    private ExecutorService messageExecutor;
    private int effectiveMaxConnections;
    private int effectiveWorkerThreads;
    private String syncPath;

    public WebSocketSyncServer(ServerSetting serverSetting) {
        super(serverSetting);
    }

    @Override
    protected boolean doStart() {
        configureHighConcurrency();
        messageExecutor = createMessageExecutor();
        syncPath = normalizePath(serverSetting.getOptions().getString("path", "/sync"), "/sync");

        webSocketServer = new HttpBootstrap()
                .setPort(serverSetting.getPort())
                .webSocketHandler(new SmartHttpWebSocketHandler());
        webSocketServer.configuration()
                .host(serverSetting.getHost())
                .threadNum(Math.max(effectiveWorkerThreads, 2))
                .setWsIdleTimeout(resolveWsIdleTimeout())
                .bannerEnabled(false)
                .serverName("utils-support-common-starter/ws-sync");
        webSocketServer.start();

        log.info("WebSocket????????? [smart-http] - {}:{} - path={} - ?????: {} - ????: {}",
                serverSetting.getHost(), serverSetting.getPort(), syncPath, effectiveMaxConnections, effectiveWorkerThreads);
        return true;
    }

    private void configureHighConcurrency() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();

        if (serverSetting.isAutomaticOptimization()) {
            effectiveWorkerThreads = cpuCores * 4;
            long memoryPerConnection = 64 * 1024L;
            long availableForConnections = (long) (maxMemory * 0.6);
            effectiveMaxConnections = (int) Math.min(100000, availableForConnections / memoryPerConnection);
            log.info("WebSocket??????????? - CPU??: {}, ????: {}MB, ????: {}, ????: {}",
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

    private long resolveWsIdleTimeout() {
        if (serverSetting.isKeepAliveEnabled()) {
            return 0L;
        }
        if (serverSetting.getIdleTimeout() > 0) {
            return serverSetting.getIdleTimeout();
        }
        return 60000L;
    }

    private ExecutorService createMessageExecutor() {
        int corePoolSize;
        int maxPoolSize;
        int queueCapacity;
        long keepAliveTime;

        if (serverSetting.isAutomaticOptimization()) {
            corePoolSize = effectiveWorkerThreads;
            maxPoolSize = effectiveWorkerThreads * 2;
            queueCapacity = effectiveMaxConnections;
            keepAliveTime = 30000;
        } else {
            corePoolSize = effectiveWorkerThreads;
            maxPoolSize = corePoolSize * 4;
            queueCapacity = 10000;
            keepAliveTime = 60000;
        }

        AtomicInteger threadCounter = new AtomicInteger(0);
        ThreadFactory threadFactory = r -> {
            Thread thread = new Thread(r, "ws-sync-worker-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        RejectedExecutionHandler rejectedHandler = (r, executor) -> {
            if (!executor.isShutdown()) {
                r.run();
            }
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveTime,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                threadFactory,
                rejectedHandler
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private String normalizePath(String path, String fallback) {
        String value = (path == null || path.isEmpty()) ? fallback : path;
        return value.startsWith("/") ? value : ("/" + value);
    }

    @Override
    protected void doStop() throws Exception {
        for (Map.Entry<String, SmartHttpSyncSocket> entry : connections.entrySet()) {
            try {
                entry.getValue().close(1001, "Server shutting down");
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("??WebSocket????: {}", entry.getKey(), e);
                }
            }
        }
        connections.clear();
        handshakeIndex.clear();

        if (messageExecutor != null && !messageExecutor.isShutdown()) {
            messageExecutor.shutdown();
            if (!messageExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                messageExecutor.shutdownNow();
            }
        }

        if (webSocketServer != null) {
            webSocketServer.shutdown();
            webSocketServer = null;
        }

        log.info("WebSocket???????");
    }

    @Override
    protected void doSendRaw(SyncSession session, String json) throws Exception {
        SmartHttpSyncSocket socket = connections.get(session.getSessionId());
        if (socket != null && socket.isOpen()) {
            socket.send(json);
        }
    }

    private final class SmartHttpWebSocketHandler extends WebSocketHandler {

        @Override
        public void whenHeaderComplete(org.smartboot.http.server.impl.WebSocketRequestImpl request,
                                       org.smartboot.http.server.impl.WebSocketResponseImpl response) {
            String requestPath = request.getRequestURI() != null ? request.getRequestURI() : "/";
            if (!syncPath.equals(requestPath)) {
                response.close(1008, "Unsupported websocket path");
                return;
            }

            if (connections.size() >= effectiveMaxConnections) {
                response.close(1011, "Maximum connections reached");
                return;
            }

            String sessionId = IdUtils.createNanoId(32);
            SmartHttpSyncSocket socket = new SmartHttpSyncSocket(sessionId, response);
            WebSocketSyncSession session = new WebSocketSyncSession(
                    sessionId,
                    socket::isOpen,
                    text -> {
                        try {
                            socket.send(text);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    },
                    () -> {
                        try {
                            socket.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
            );

            connections.put(sessionId, socket);
            handshakeIndex.put(buildSocketKey(request.getRequestURI(), request.getRemoteAddress()), sessionId);
            registerSession(session);

            if (log.isDebugEnabled()) {
                log.debug("WebSocket?????? [smart-http]: {} (?????: {})", sessionId, connections.size());
            }
        }

        @Override
        public void handle(WebSocketRequest request, WebSocketResponse response) {
            String connectionId = handshakeIndex.get(buildSocketKey(request.getRequestURI(), request.getRemoteAddress()));
            SmartHttpSyncSocket socket = connectionId == null ? null : connections.get(connectionId);
            if (socket == null) {
                log.warn("?????????WebSocket??: {}", request.getRequestURI());
                return;
            }

            socket.bind(response);
            int opcode = request.getFrameOpcode();
            if (opcode == 9) {
                try {
                    response.pong(request.getPayload());
                    response.flush();
                } catch (Exception e) {
                    log.debug("回复WebSocket ping失败: {}", connectionId, e);
                }
                return;
            }
            if (opcode == 10) {
                if (log.isDebugEnabled()) {
                    log.debug("收到WebSocket pong: {}", connectionId);
                }
                return;
            }
            if (opcode != 1 && opcode != 2) {
                return;
            }

            byte[] payload = request.getPayload() == null ? new byte[0] : request.getPayload();
            String textMessage = new String(payload, StandardCharsets.UTF_8);
            Runnable task = () -> handleMessage(connectionId, textMessage);
            if (messageExecutor != null && !messageExecutor.isShutdown()) {
                messageExecutor.execute(task);
            } else {
                task.run();
            }
        }

        @Override
        public void onClose(org.smartboot.http.server.impl.Request request) {
            String connectionId = handshakeIndex.remove(buildSocketKey(request.getRequestURI(), request.getRemoteAddress()));
            if (connectionId == null) {
                return;
            }
            SmartHttpSyncSocket socket = connections.remove(connectionId);
            if (socket != null) {
                socket.invalidate();
            }
            unregisterSession(connectionId);

            if (log.isDebugEnabled()) {
                log.debug("WebSocket?????? [smart-http]: {}", connectionId);
            }
        }
    }

    private String buildSocketKey(String uri, InetSocketAddress remoteAddress) {
        String path = uri == null ? "/" : uri;
        if (remoteAddress == null) {
            return path + "|unknown";
        }
        return path + "|" + remoteAddress;
    }

    private static final class SmartHttpSyncSocket {
        private final String sessionId;
        private volatile WebSocketResponse response;
        private volatile boolean open = true;

        private SmartHttpSyncSocket(String sessionId, WebSocketResponse response) {
            this.sessionId = sessionId;
            this.response = response;
        }

        private void bind(WebSocketResponse response) {
            this.response = response;
        }

        private void send(String text) throws IOException {
            ensureOpen();
            response.sendTextMessage(text);
            response.flush();
        }

        private void close() throws IOException {
            close(1000, "Session closed");
        }

        private void close(int code, String reason) throws IOException {
            if (response != null) {
                response.close(code, reason);
            }
            invalidate();
        }

        private boolean isOpen() {
            return open && response != null;
        }

        private void invalidate() {
            open = false;
            response = null;
        }

        private void ensureOpen() throws IOException {
            if (!isOpen()) {
                throw new IOException("WebSocket?????: " + sessionId);
            }
        }
    }
}
