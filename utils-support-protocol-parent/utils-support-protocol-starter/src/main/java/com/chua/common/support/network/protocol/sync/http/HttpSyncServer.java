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
import org.smartboot.http.server.HttpBootstrap;
import org.smartboot.http.server.HttpRequest;
import org.smartboot.http.server.HttpResponse;
import org.smartboot.http.server.HttpServerHandler;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;


/**
 * HTTP同步数据服务端实现（单向上报）。
 */
@Slf4j
@Spi("http-sync")
public class HttpSyncServer extends AbstractSyncServer {

    private static final String SYNC_PATH = "/sync";
    private static final String HEALTH_PATH = "/health";

    private HttpBootstrap httpServer;
    private ExecutorService requestExecutor;
    private final AtomicLong totalRequests = new AtomicLong(0);
    private int effectiveMaxConnections;

    public HttpSyncServer(ServerSetting serverSetting) {
        super(serverSetting);
    }

    @Override
    protected boolean doStart() {
        configureHighConcurrency();
        requestExecutor = createRequestExecutor();

        httpServer = new HttpBootstrap()
                .setPort(serverSetting.getPort())
                .httpHandler(new InternalHttpHandler());
        httpServer.configuration()
                .host(serverSetting.getHost())
                .threadNum(resolveThreadNum())
                .setHttpIdleTimeout(calculateSocketReadTimeout())
                .bannerEnabled(false)
                .serverName("utils-support-common-starter/http-sync");
        httpServer.start();

        log.info("HTTP同步服务器启动成功 [smart-http] - {}:{} - 自动优化: {} - 最大连接: {}",
                serverSetting.getHost(), serverSetting.getPort(),
                serverSetting.isAutomaticOptimization(), effectiveMaxConnections);
        return true;
    }

    private void configureHighConcurrency() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();

        if (serverSetting.isAutomaticOptimization()) {
            long memoryPerConnection = 32 * 1024L;
            long availableForConnections = (long) (maxMemory * 0.5);
            effectiveMaxConnections = (int) Math.min(50000, availableForConnections / memoryPerConnection);
            log.info("HTTP同步服务器自动优化模式 - CPU核心: {}, 最大内存: {}MB, 最大连接: {}",
                    cpuCores, maxMemory / 1024 / 1024, effectiveMaxConnections);
        } else {
            effectiveMaxConnections = serverSetting.getMaxConnections() > 0
                    ? serverSetting.getMaxConnections()
                    : 10000;
        }
    }

    private int resolveThreadNum() {
        if (serverSetting.getWorkerThreads() > 0) {
            return serverSetting.getWorkerThreads();
        }
        return Math.max(Runtime.getRuntime().availableProcessors() * 2, 4);
    }

    private ExecutorService createRequestExecutor() {
        if (serverSetting.isAutomaticOptimization()) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        int threads = resolveThreadNum();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "http-sync-worker");
            t.setDaemon(true);
            return t;
        });
    }

    private long calculateSocketReadTimeout() {
        if (serverSetting.isAutomaticOptimization()) {
            return 5000L;
        }
        return serverSetting.getSocketTimeout() > 0 ? serverSetting.getSocketTimeout() : 30000L;
    }

    @Override
    protected void doStop() throws Exception {
        if (requestExecutor != null && !requestExecutor.isShutdown()) {
            requestExecutor.shutdown();
        }
        if (httpServer != null) {
            httpServer.shutdown();
            httpServer = null;
        }
        log.info("HTTP同步服务器停止");
    }

    @Override
    protected void doSendRaw(SyncSession session, String json) {
        log.warn("HTTP同步协议不支持主动发送消息");
    }

    @Override
    public void send(String sessionId, String topic, Object data) {
        log.warn("HTTP同步协议不支持下发消息");
    }

    @Override
    public void broadcast(String topic, Object data) {
        log.warn("HTTP同步协议不支持广播消息");
    }

    public long getTotalRequests() {
        return totalRequests.get();
    }

    private final class InternalHttpHandler extends HttpServerHandler {
        @Override
        public void handle(HttpRequest request, HttpResponse response, java.util.concurrent.CompletableFuture<Object> future) {
            totalRequests.incrementAndGet();
            Runnable task = () -> {
                try {
                    String uri = request.getRequestURI();
                    String method = request.getMethod();

                    if (HEALTH_PATH.equals(uri)) {
                        writeJson(response, HttpStatus.OK, String.format(
                                "{\"status\":\"UP\",\"totalRequests\":%d,\"automaticOptimization\":%s}",
                                totalRequests.get(), serverSetting.isAutomaticOptimization()));
                        future.complete(null);
                        return;
                    }

                    if (SYNC_PATH.equals(uri)) {
                        if (!"POST".equalsIgnoreCase(method)) {
                            writeJson(response, HttpStatus.METHOD_NOT_ALLOWED,
                                    "{\"code\":405,\"message\":\"Method Not Allowed\"}");
                            future.complete(null);
                            return;
                        }
                        handleSync(request, response);
                        future.complete(null);
                        return;
                    }

                    writeJson(response, HttpStatus.NOT_FOUND, "{\"code\":404,\"message\":\"Not Found\"}");
                    future.complete(null);
                } catch (Exception e) {
                    log.error("处理HTTP同步请求失败", e);
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
            HttpSyncSession syncSession = new HttpSyncSession(sessionId, request.getRemoteAddress());
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
    }

    private String getSessionId(HttpRequest request) {
        String sessionId = request.getHeader("x-session-id");
        if (sessionId == null || sessionId.isEmpty()) {
            String remoteIp = request.getRemoteAddr();
            if (remoteIp == null) {
                remoteIp = "unknown";
            }
            sessionId = remoteIp + "-" + IdUtils.createNanoId(8);
        }
        return sessionId;
    }
}
