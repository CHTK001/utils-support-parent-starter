package com.chua.common.support.network.protocol.sync.http;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.utils.IdUtils;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.sync.AbstractSyncServer;
import com.chua.common.support.network.protocol.sync.SyncMessage;
import com.chua.common.support.network.protocol.sync.SyncSession;
import com.chua.common.support.text.json.Json;
import lombok.extern.slf4j.Slf4j;
import org.smartboot.http.common.enums.HttpStatus;
import org.smartboot.http.common.io.BufferOutputStream;
import org.smartboot.http.server.HttpBootstrap;
import org.smartboot.http.server.HttpRequest;
import org.smartboot.http.server.HttpResponse;
import org.smartboot.http.server.HttpServerHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Spi("http-stream-sync")
public class HttpStreamingSyncServer extends AbstractSyncServer {

    private static final String DEFAULT_STREAM_PATH = "/stream";
    private static final String DEFAULT_SYNC_PATH = "/sync";
    private static final String HEALTH_PATH = "/health";

    private HttpBootstrap httpServer;
    private ExecutorService requestExecutor;
    private ScheduledExecutorService keepAliveScheduler;
    private final AtomicLong totalRequests = new AtomicLong(0);
    private int effectiveMaxConnections;
    private String streamPath;
    private String syncPath;
    private long keepAliveIntervalMillis;

    public HttpStreamingSyncServer(ServerSetting serverSetting) {
        super(serverSetting);
    }

    @Override
    protected boolean doStart() {
        configureHighConcurrency();
        requestExecutor = createRequestExecutor();
        streamPath = normalizePath(serverSetting.getOptions().getString("streamPath", DEFAULT_STREAM_PATH), DEFAULT_STREAM_PATH);
        syncPath = normalizePath(serverSetting.getOptions().getString("syncPath", DEFAULT_SYNC_PATH), DEFAULT_SYNC_PATH);
        keepAliveIntervalMillis = serverSetting.getOptions().getLongValue("keepAliveIntervalMillis", 15000L);

        httpServer = new HttpBootstrap()
                .setPort(serverSetting.getPort())
                .httpHandler(new InternalHttpHandler());
        httpServer.configuration()
                .host(serverSetting.getHost())
                .threadNum(resolveThreadNum())
                .readBufferSize(resolveReadBufferSize())
                .writeBufferSize(resolveWriteBufferSize())
                .setHttpIdleTimeout(resolveIdleTimeout())
                .bannerEnabled(false)
                .serverName("utils-support-common-starter/http-stream-sync");
        httpServer.configuration().setMaxRequestSize(resolveMaxRequestSize());
        httpServer.start();
        startKeepAlive();

        log.info("HTTP Streaming????????? [smart-http] - {}:{} - streamPath={} syncPath={} ????: {} ????: {}",
                serverSetting.getHost(), serverSetting.getPort(), streamPath, syncPath,
                serverSetting.isAutomaticOptimization(), effectiveMaxConnections);
        return true;
    }

    @Override
    protected void doStop() {
        if (keepAliveScheduler != null && !keepAliveScheduler.isShutdown()) {
            keepAliveScheduler.shutdown();
        }
        if (requestExecutor != null && !requestExecutor.isShutdown()) {
            requestExecutor.shutdown();
        }
        if (httpServer != null) {
            httpServer.shutdown();
            httpServer = null;
        }
        log.info("HTTP Streaming???????");
    }

    @Override
    protected void doSendRaw(SyncSession session, String json) {
        if (session instanceof HttpStreamingSyncSession streamingSession) {
            streamingSession.sendRaw(json);
        } else if (log.isDebugEnabled()) {
            log.debug("???Streaming????: {}", session.getSessionId());
        }
    }

    public long getTotalRequests() {
        return totalRequests.get();
    }

    private void configureHighConcurrency() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (serverSetting.isAutomaticOptimization()) {
            long memoryPerConnection = 64 * 1024L;
            long availableForConnections = (long) (maxMemory * 0.5);
            effectiveMaxConnections = (int) Math.min(50000, availableForConnections / memoryPerConnection);
            log.info("HTTP Streaming?????? - CPU??: {}, ????: {}MB, ????: {}",
                    cpuCores, maxMemory / 1024 / 1024, effectiveMaxConnections);
        } else {
            effectiveMaxConnections = serverSetting.getMaxConnections() > 0
                    ? serverSetting.getMaxConnections()
                    : 10000;
        }
    }

    private ExecutorService createRequestExecutor() {
        if (serverSetting.isAutomaticOptimization()) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        return Executors.newFixedThreadPool(resolveThreadNum(), r -> {
            Thread t = new Thread(r, "http-stream-sync-worker");
            t.setDaemon(true);
            return t;
        });
    }

    private int resolveThreadNum() {
        if (serverSetting.getWorkerThreads() > 0) {
            return serverSetting.getWorkerThreads();
        }
        return Math.max(Runtime.getRuntime().availableProcessors() * 2, 4);
    }

    private int resolveReadBufferSize() {
        if (serverSetting.getReceiveBufferSize() > 0) {
            return serverSetting.getReceiveBufferSize();
        }
        return serverSetting.isAutomaticOptimization() ? 16 * 1024 : 8 * 1024;
    }

    private int resolveWriteBufferSize() {
        if (serverSetting.getSendBufferSize() > 0) {
            return serverSetting.getSendBufferSize();
        }
        return serverSetting.isAutomaticOptimization() ? 16 * 1024 : 8 * 1024;
    }

    private long resolveMaxRequestSize() {
        if (serverSetting.getMaxRequestSize() > 0) {
            return serverSetting.getMaxRequestSize();
        }
        return serverSetting.isAutomaticOptimization() ? 16L * 1024 * 1024 : 8L * 1024 * 1024;
    }

    private long resolveIdleTimeout() {
        if (serverSetting.isKeepAliveEnabled()) {
            return 0L;
        }
        if (serverSetting.getReadTimeoutMillis() > 0) {
            return serverSetting.getReadTimeoutMillis();
        }
        return serverSetting.isAutomaticOptimization() ? 5000L : 30000L;
    }

    private String normalizePath(String path, String fallback) {
        String value = (path == null || path.isEmpty()) ? fallback : path;
        return value.startsWith("/") ? value : ("/" + value);
    }

    private String getSessionId(HttpRequest request) {
        String sessionId = request.getHeader("x-session-id");
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = request.getParameter("sessionId");
        }
        if (sessionId == null || sessionId.isEmpty()) {
            String remoteIp = request.getRemoteAddr();
            if (remoteIp == null || remoteIp.isEmpty()) {
                remoteIp = "unknown";
            }
            sessionId = remoteIp + "-" + IdUtils.createNanoId(8);
        }
        return sessionId;
    }

    private void startKeepAlive() {
        if (keepAliveIntervalMillis <= 0) {
            return;
        }
        keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "http-stream-sync-keepalive");
            t.setDaemon(true);
            return t;
        });
        keepAliveScheduler.scheduleAtFixedRate(this::sendKeepAlive,
                keepAliveIntervalMillis, keepAliveIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void sendKeepAlive() {
        for (SyncSession session : sessions.values()) {
            if (session instanceof HttpStreamingSyncSession streamingSession && session.isConnected()) {
                try {
                    streamingSession.sendKeepAlive();
                } catch (Exception e) {
                    log.warn("??KeepAlive??: {}", session.getSessionId(), e);
                    session.close();
                    unregisterSession(session.getSessionId());
                }
            }
        }
    }

    private final class InternalHttpHandler extends HttpServerHandler {
        @Override
        public void handle(HttpRequest request, HttpResponse response, CompletableFuture<Object> future) {
            totalRequests.incrementAndGet();
            String uri = request.getRequestURI();
            String method = request.getMethod();

            try {
                if (HEALTH_PATH.equals(uri)) {
                    writeJson(response, HttpStatus.OK, String.format(
                            "{\"status\":\"UP\",\"totalRequests\":%d,\"automaticOptimization\":%s,\"streamPath\":\"%s\"}",
                            totalRequests.get(), serverSetting.isAutomaticOptimization(), streamPath));
                    future.complete(null);
                    return;
                }

                if (streamPath.equals(uri)) {
                    if (!"GET".equalsIgnoreCase(method)) {
                        writeJson(response, HttpStatus.METHOD_NOT_ALLOWED,
                                "{\"code\":405,\"message\":\"Method Not Allowed\"}");
                        future.complete(null);
                        return;
                    }
                    handleStream(request, response, future);
                    return;
                }

                if (syncPath.equals(uri)) {
                    if (!"POST".equalsIgnoreCase(method)) {
                        writeJson(response, HttpStatus.METHOD_NOT_ALLOWED,
                                "{\"code\":405,\"message\":\"Method Not Allowed\"}");
                        future.complete(null);
                        return;
                    }
                    dispatchSync(request, response, future);
                    return;
                }

                writeJson(response, HttpStatus.NOT_FOUND, "{\"code\":404,\"message\":\"Not Found\"}");
                future.complete(null);
            } catch (Exception e) {
                log.error("处理HTTP Streaming请求失败", e);
                try {
                    writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR,
                            "{\"code\":500,\"message\":\"Internal Server Error\"}");
                } catch (Exception ignored) {
                }
                future.completeExceptionally(e);
            }
        }

        private void dispatchSync(HttpRequest request, HttpResponse response, CompletableFuture<Object> future) {
            Runnable task = () -> {
                try {
                    handleSync(request, response);
                    future.complete(null);
                } catch (Exception e) {
                    log.error("处理Streaming同步请求失败", e);
                    try {
                        writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR,
                                "{\"code\":500,\"message\":\"Internal Server Error\"}");
                    } catch (Exception ignored) {
                    }
                    future.completeExceptionally(e);
                }
            };

            if (requestExecutor != null && !requestExecutor.isShutdown()) {
                requestExecutor.execute(task);
            } else {
                task.run();
            }
        }

        private void handleStream(HttpRequest request, HttpResponse response, CompletableFuture<Object> future) throws Exception {
            if (effectiveMaxConnections > 0 && sessions.size() >= effectiveMaxConnections) {
                writeJson(response, HttpStatus.TOO_MANY_REQUESTS,
                        "{\"code\":429,\"message\":\"Maximum connections reached\"}");
                future.complete(null);
                return;
            }

            String sessionId = getSessionId(request);
            SyncSession old = sessions.get(sessionId);
            if (old != null) {
                old.close();
                unregisterSession(sessionId);
            }

            response.setHttpStatus(HttpStatus.OK);
            response.setContentType("text/event-stream; charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            response.setHeader("X-Session-Id", sessionId);

            OutputStream outputStream = createStreamingOutput(response);
            HttpStreamingSyncSession syncSession = new HttpStreamingSyncSession(sessionId, request.getRemoteAddress(), outputStream);
            try {
                registerSession(syncSession);
                syncSession.sendKeepAlive();
                future.complete(null);
                log.info("HTTP Streaming会话建立 [smart-http]: {} - {}", sessionId, syncSession.getRemoteAddressString());
            } catch (Exception e) {
                syncSession.close();
                unregisterSession(sessionId);
                throw e;
            }
        }

        private void handleSync(HttpRequest request, HttpResponse response) throws Exception {
            byte[] body = request.getInputStream() != null ? request.getInputStream().readAllBytes() : new byte[0];
            if (body.length == 0) {
                writeJson(response, HttpStatus.BAD_REQUEST, "{\"code\":400,\"message\":\"Empty request body\"}");
                return;
            }

            SyncMessage syncMessage = Json.fromJson(new String(body, StandardCharsets.UTF_8), SyncMessage.class);
            if (syncMessage == null) {
                writeJson(response, HttpStatus.BAD_REQUEST, "{\"code\":400,\"message\":\"Invalid message format\"}");
                return;
            }

            String sessionId = getSessionId(request);
            SyncSession syncSession = sessions.get(sessionId);
            if (syncSession == null) {
                syncSession = new HttpSyncSession(sessionId, request.getRemoteAddress());
            }
            notifyMessage(syncSession, syncMessage);
            writeJson(response, HttpStatus.OK, "{\"code\":200,\"message\":\"OK\"}");
        }

        private void writeJson(HttpResponse response, HttpStatus status, String json) throws Exception {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            response.setHttpStatus(status);
            response.setContentType("application/json; charset=UTF-8");
            response.setContentLength(body.length);
            response.write(body);
            response.close();
        }

        private OutputStream createStreamingOutput(HttpResponse response) {
            BufferOutputStream stream = response.getOutputStream();
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    stream.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    stream.write(b, off, len);
                }

                @Override
                public void flush() throws IOException {
                    stream.flush();
                }

                @Override
                public void close() throws IOException {
                    try {
                        stream.close();
                    } finally {
                        response.close();
                    }
                }
            };
        }
    }
}
