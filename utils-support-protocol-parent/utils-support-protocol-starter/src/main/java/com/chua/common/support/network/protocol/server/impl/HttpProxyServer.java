package com.chua.common.support.network.protocol.server.impl;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.server.AbstractProtocolServer;
import lombok.extern.slf4j.Slf4j;
import org.smartboot.http.server.HttpBootstrap;
import org.smartboot.http.server.HttpRequest;
import org.smartboot.http.server.HttpResponse;
import org.smartboot.http.server.HttpServerHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * 基于 smart-http 的 HTTP 代理服务器。
 * <p>
 * 当前实现支持标准 HTTP 转发，不支持 CONNECT 隧道。
 */
@Slf4j
@Spi({"http-proxy", "http-proxy-server"})
@SpiDescribe("HTTP代理服务器")
public class HttpProxyServer extends AbstractProtocolServer {

    private String runMode = "smart-http";

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong totalRequests = new AtomicLong(0);

    private HttpBootstrap httpBootstrap;
    private HttpClient httpClient;
    private ExecutorService requestExecutor;

    public HttpProxyServer(ServerSetting serverSetting) {
        super(serverSetting);
    }

    @Override
    protected void doStart() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(Math.max(1000L, serverSetting.getConnectionTimeoutMillis())))
                .build();
        this.requestExecutor = createRequestExecutor();

        httpBootstrap = new HttpBootstrap()
                .setPort(serverSetting.getPort())
                .httpHandler(new InternalProxyHandler());

        configureSmartHttp();
        httpBootstrap.start();

        log.info("HTTP代理服务器启动 [smart-http] - {}:{} - 自动优化: {} - 线程: {}",
                serverSetting.getHost(),
                serverSetting.getPort(),
                serverSetting.isAutomaticOptimization(),
                resolveThreadNum());
    }

    private void configureSmartHttp() {
        var configuration = httpBootstrap.configuration();
        configuration.host(serverSetting.getHost())
                .bannerEnabled(false)
                .threadNum(resolveThreadNum())
                .setHttpIdleTimeout(calculateSocketReadTimeout());
    }

    private int resolveThreadNum() {
        if (serverSetting.getWorkerThreads() > 0) {
            return serverSetting.getWorkerThreads();
        }
        return serverSetting.isAutomaticOptimization()
                ? Math.max(Runtime.getRuntime().availableProcessors() * 4, 8)
                : Math.max(Runtime.getRuntime().availableProcessors() * 2, 4);
    }

    private long calculateSocketReadTimeout() {
        if (serverSetting.isAutomaticOptimization()) {
            return 5000L;
        }
        long timeout = serverSetting.getReadTimeoutMillis();
        if (timeout <= 0) {
            timeout = serverSetting.getSocketTimeout();
        }
        return timeout > 0 ? timeout : 30000L;
    }

    private ExecutorService createRequestExecutor() {
        if (serverSetting.isAutomaticOptimization()) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        int threads = resolveThreadNum();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "http-proxy-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    protected void doStop() throws Exception {
        if (requestExecutor != null && !requestExecutor.isShutdown()) {
            requestExecutor.shutdown();
        }
        if (httpBootstrap != null) {
            httpBootstrap.shutdown();
            httpBootstrap = null;
        }
        log.info("HTTP代理服务器停止");
    }

    @Override
    public String getServerInfo() {
        return String.format("HttpProxyServer[%s:%d, mode=%s, running=%s, active=%d, total=%d]",
                serverSetting.getHost(),
                serverSetting.getPort(),
                runMode,
                isRunning(),
                activeConnections.get(),
                totalConnections.get());
    }

    @Override
    public String getProtocolName() {
        return "HTTP-PROXY";
    }

    public String getRunMode() {
        return runMode;
    }

    private class InternalProxyHandler extends HttpServerHandler {

        private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
                "connection",
                "proxy-connection",
                "keep-alive",
                "transfer-encoding",
                "te",
                "trailer",
                "upgrade"
        );

        @Override
        public void handle(HttpRequest request, HttpResponse response, CompletableFuture<Object> future) {
            totalRequests.incrementAndGet();
            totalConnections.incrementAndGet();
            activeConnections.incrementAndGet();

            Runnable task = () -> handleProxyRequest(request, response, future);
            if (requestExecutor != null && !requestExecutor.isShutdown()) {
                requestExecutor.execute(task);
            } else {
                Thread.startVirtualThread(task);
            }
        }

        private void handleProxyRequest(HttpRequest request, HttpResponse response, CompletableFuture<Object> future) {
            try {
                String method = request.getMethod();
                if ("CONNECT".equalsIgnoreCase(method)) {
                    writeText(response, future, 501, "CONNECT not supported by smart-http proxy");
                    return;
                }

                URI targetUri = resolveTargetUri(request);
                if (targetUri == null) {
                    writeText(response, future, 400, "Bad Request: missing target host");
                    return;
                }

                byte[] body = request.getInputStream() != null ? request.getInputStream().readAllBytes() : new byte[0];
                var builder = java.net.http.HttpRequest.newBuilder(targetUri)
                        .method(method, body.length > 0 ? BodyPublishers.ofByteArray(body) : BodyPublishers.noBody());

                for (String headerName : request.getHeaderNames()) {
                    if (headerName == null) {
                        continue;
                    }
                    String normalized = headerName.toLowerCase(Locale.ROOT);
                    if (HOP_BY_HOP_HEADERS.contains(normalized)) {
                        continue;
                    }
                    for (String value : request.getHeaders(headerName)) {
                        builder.header(headerName, value);
                    }
                }

                var proxyResponse = httpClient.send(builder.build(), BodyHandlers.ofByteArray());

                int status = proxyResponse.statusCode();
                response.setHttpStatus(status, status >= 200 && status < 300 ? "OK" : "ERROR");

                proxyResponse.headers().map().forEach((key, values) -> {
                    if (key == null) {
                        return;
                    }
                    String normalized = key.toLowerCase(Locale.ROOT);
                    if (HOP_BY_HOP_HEADERS.contains(normalized) || "content-length".equals(normalized)) {
                        return;
                    }
                    for (String value : values) {
                        response.addHeader(key, value);
                    }
                    if ("content-type".equalsIgnoreCase(key) && !values.isEmpty()) {
                        response.setContentType(values.get(0));
                    }
                });

                byte[] respBody = proxyResponse.body();
                response.setContentLength(respBody == null ? 0 : respBody.length);
                if (respBody != null && respBody.length > 0) {
                    response.write(respBody);
                }

                future.complete(null);
            } catch (Exception e) {
                log.error("HTTP代理处理失败", e);
                writeText(response, future, 502, "Bad Gateway: " + e.getMessage());
            } finally {
                activeConnections.decrementAndGet();
            }
        }

        private URI resolveTargetUri(HttpRequest request) {
            String rawUrl = request.getRequestURL();
            if (rawUrl != null && (rawUrl.startsWith("http://") || rawUrl.startsWith("https://"))) {
                return URI.create(rawUrl);
            }

            String hostHeader = request.getHeader("Host");
            if (hostHeader == null || hostHeader.isEmpty()) {
                return null;
            }

            String path = request.getRequestURI();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String query = request.getQueryString();
            if (query != null && !query.isEmpty() && !path.contains("?")) {
                path = path + "?" + query;
            }
            String scheme = request.isSecure() ? "https" : "http";
            return URI.create(scheme + "://" + hostHeader + path);
        }

        private void writeText(HttpResponse response, CompletableFuture<Object> future, int status, String message) {
            try {
                byte[] body = message.getBytes(StandardCharsets.UTF_8);
                response.setHttpStatus(status, status >= 200 && status < 300 ? "OK" : "ERROR");
                response.setContentType("text/plain; charset=UTF-8");
                response.setContentLength(body.length);
                response.write(body);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }
    }
}
