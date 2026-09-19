package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.ServerAttribute;
import com.chua.common.support.network.server.filter.ReactiveFilterChain;
import com.chua.common.support.network.server.filter.ReactiveServerFilter;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 反向代理过滤器，支持 HTTP 和 WebSocket 双向代理。
 *
 * <p>从 {@link ServerAttribute#BACKEND_DISCOVERY} 获取后端地址：
 * <ul>
 *   <li>HTTP 请求 — JDK HttpClient 异步转发</li>
 *   <li>WebSocket 升级 — 检测 Upgrade:websocket 头，建立 WebSocket 连接双向转发</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // HTTP 代理
 * // GET /api/users → http://backend:8080/api/users
 *
 * // WebSocket 代理
 * // Upgrade: websocket → ws://backend:8080/ws/chat
 * // 客户端 ←→ 后端 双向消息转发
 * }</pre>e>
 *
 * @author CH
 * @since 4.0.0.42
 * @see com.chua.common.support.network.server.filter.discovery.ServiceDiscoveryServerFilter
 */
@Slf4j
public class ReverseProxyServerFilter implements ServerFilter, ReactiveServerFilter {

    /** 超时秒 */
    private final int timeoutSeconds;
    /** HTTP客户端 */
    private volatile HttpClient httpClient;
    /** 异步执行器 */
    private ExecutorService asyncExecutor;

    /** 创建 reverse代理服务端过滤器 实例 */
    public ReverseProxyServerFilter() {
        this(30);
    }

    /**
     * 创建 reverse代理服务端过滤器 实例
     * @param timeoutSeconds 超时seconds
     */
    public ReverseProxyServerFilter(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return Integer.MAX_VALUE - 40;
    }

    @Override
    /** 支持路径 */
    public String supportPath() {
 // 服务端过滤器 与 响应式服务端过滤器 均有同名 默认 方法,显式覆写消除接口冲突;
        // 返回 null = Access Filter,每次请求都触发代理判断
        return null;
    }

    @Override
    /** 支持协议 */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.WS};
    }

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) throws Exception {
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(asyncExecutor)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        log.info("[network-proxy] ReverseProxyServerFilter 初始化完成, timeout={}s, async=true", timeoutSeconds);
    }

    @Override
    /** 销毁 */
    public void destroy() {
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
            try {
                asyncExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    /**
     * 执行过滤
     * @param request 请求
     * @param response 响应
     * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        Discovery discovery = ServerAttribute.getBackendDiscovery(request);
        if (discovery == null) {
            chain.doFilter(request, response);
            return;
        }

        String host = discovery.getHost();
        int port = discovery.getPort();
        String scheme = discovery.getProtocol();

        if (host == null || port <= 0) {
            log.warn("[network-proxy] 后端地址无效: {}:{}", host, port);
            chain.doFilter(request, response);
            return;
        }

        String upgradeHeader = request.getHeader("Upgrade");
        if (upgradeHeader != null && upgradeHeader.equalsIgnoreCase("websocket")) {
            handleHttpProxy(request, response, host, port, scheme);
            return;
        }

        // 同步链(jdk/nio 阻塞模式):必须阻塞等待异步转发完成,否则链继续执行会立即
        // end()(ended=true),异步回调中 "if (!response.isEnded())" 跳过 setBody → 响应体丢失
        try {
            handleHttpProxyAsync(request, response, host, port, scheme)
                    .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[network-proxy] HTTP 反向代理超时: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[network-proxy] HTTP 反向代理异常: {}", e.getMessage());
        }
    }

    /**
     * 响应式过滤器入口:适配 vertx-http 等响应式 服务端 的过滤器链
     * (其响应式链仅执行 {@link ReactiveServerFilter})。
     * 返回转发完成的 Stage,供响应式链等待真正写出响应,避免提前 结束vertx 空响应。
     */
    @Override
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        Discovery discovery = ServerAttribute.getBackendDiscovery(request);
        if (discovery == null || discovery.getHost() == null || discovery.getPort() <= 0) {
            return chain.doFilter(request, response);
        }
        String upgradeHeader = request.getHeader("Upgrade");
        if (upgradeHeader != null && upgradeHeader.equalsIgnoreCase("websocket")) {
            return handleHttpProxyAsync(request, response,
                    discovery.getHost(), discovery.getPort(), discovery.getProtocol());
        }
        return handleHttpProxyAsync(request, response,
                discovery.getHost(), discovery.getPort(), discovery.getProtocol());
    }

    /**
     * 处理http代理异步
     * @param request 请求
     * @param response 响应
     * @param host 主机
     * @param port 端口
     * @param scheme scheme
     */
    private CompletableFuture<Void> handleHttpProxyAsync(ServerRequest request, ServerResponse response,
                                                         String host, int port, String scheme) {
        String path = request.getPath();
        String query = extractQuery(request.getUri());
        String backendUrl = scheme + "://" + host + ":" + port + path + (query != null ? "?" + query : "");

        log.info("[network-proxy] HTTP 代理: {} {} -> {}", request.getMethod(), request.getPath(), backendUrl);

        CompletableFuture<Void> done = new CompletableFuture<>();
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(backendUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds));

        String method = request.getMethod().name();
        byte[] body = request.getBody();

        switch (method) {
            case "GET" -> reqBuilder.GET();
            case "POST" -> reqBuilder.POST(body != null && body.length > 0
                    ? HttpRequest.BodyPublishers.ofByteArray(body)
                    : HttpRequest.BodyPublishers.noBody());
            case "PUT" -> reqBuilder.PUT(body != null && body.length > 0
                    ? HttpRequest.BodyPublishers.ofByteArray(body)
                    : HttpRequest.BodyPublishers.noBody());
            case "DELETE" -> reqBuilder.DELETE();
            case "PATCH" -> reqBuilder.method("PATCH", body != null && body.length > 0
                    ? HttpRequest.BodyPublishers.ofByteArray(body)
                    : HttpRequest.BodyPublishers.noBody());
            default -> reqBuilder.method(method, body != null && body.length > 0
                    ? HttpRequest.BodyPublishers.ofByteArray(body)
                    : HttpRequest.BodyPublishers.noBody());
        }

        if (request.getHeaders() != null) {
            for (Map.Entry<String, String> entry : request.getHeaders().toMap().entrySet()) {
                String key = entry.getKey();
                if (key != null && entry.getValue() != null) {
                    String lowerKey = key.toLowerCase();
                    if (!"host".equals(lowerKey) && !"connection".equals(lowerKey)
                            && !"transfer-encoding".equals(lowerKey)
                            && !"upgrade".equals(lowerKey)) {
                        reqBuilder.header(key, entry.getValue());
                    }
                }
            }
        }

        httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(resp -> {
                    try {
                        if (!response.isEnded()) {
                            response.setStatus(resp.statusCode());
                            for (Map.Entry<String, java.util.List<String>> entry : resp.headers().map().entrySet()) {
                                String key = entry.getKey();
                                String lowerKey = key.toLowerCase();
                                if (!"transfer-encoding".equals(lowerKey)
                                        && !"content-encoding".equals(lowerKey)
                                        && !"connection".equals(lowerKey)) {
                                    response.setHeader(key, String.join(", ", entry.getValue()));
                                }
                            }
                            byte[] respBody = resp.body();
                            if (respBody != null && respBody.length > 0) {
                                response.setBody(respBody);
                            }
                            response.end();
                        }
                    } catch (Exception e) {
                        log.warn("[network-proxy] 响应回写失败: {}", e.getMessage());
                    } finally {
                        done.complete(null);
                    }
                })
                .exceptionally(ex -> {
                    log.warn("[network-proxy] HTTP 反向代理失败: {}: {}", backendUrl, ex.getMessage());
                    if (!response.isEnded()) {
                        response.setStatus(502);
                        response.setBody("Bad Gateway".getBytes(StandardCharsets.UTF_8));
                        response.end();
                    }
                    done.complete(null);
                    return null;
                });
        return done;
    }

    /**
     * 处理http代理
     * @param request 请求
     * @param response 响应
     * @param host 主机
     * @param port 端口
     * @param scheme scheme
     */
    private void handleHttpProxy(ServerRequest request, ServerResponse response,
                                 String host, int port, String scheme) throws Exception {
        handleHttpProxyAsync(request, response, host, port, scheme);
    }

    /**
     * Extract查询
     *
     * @param uri uri
     * @return extract查询的结果
     */
    private String extractQuery(String uri) {
        if (uri == null) {
            return null;
        }
        int qi = uri.indexOf('?');
        return qi >= 0 ? uri.substring(qi + 1) : null;
    }
}
