package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.ServerAttribute;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.filter.ReactiveServerFilter;
import com.chua.common.support.network.server.filter.ReactiveFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
* HTTP 反向代理过滤器，将请求转发到后端服务器。
*
* <p>基于 Vert.x {@link HttpClient} 实现异步转发，后端地址由
* {@link ServerAttribute#getBackendDiscovery(ServerRequest)} 决定，
* 通常由 {@link com.chua.common.support.network.server.filter.discovery.ServiceDiscoveryServerFilter}
* 在请求进入时设置（该过滤器内部使用现有负载均衡体系）。</p>
*
* <p>使用示例：</p>
* <pre>{@code
* HttpReverseProxyFilter proxy = new HttpReverseProxyFilter();
* server.addFilter(proxy);
* }</pre>lter(proxy);
* }</pre>
*
* @author CH
* @since 2026/07/24
* @see com.chua.common.support.network.server.filter.discovery.ServiceDiscoveryServerFilter
 */
@Slf4j
public class HttpReverseProxyFilter implements ServerFilter, ReactiveServerFilter {

    /** Vertx */
    private Vertx vertx;
    /** HTTP客户端 */
    private HttpClient httpClient;

    @Override
    /** 获取订单 */
    public int getOrder() {
        return Integer.MAX_VALUE - 50;
    }

    @Override
    /** 支持路径 */
    public String supportPath() {
        return null;
    }

    @Override
    /** 支持协议 */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) {
        this.vertx = Vertx.vertx();
        this.httpClient = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout(5000)
                .setTcpNoDelay(true));
        log.info("[network-proxy] HttpReverseProxyFilter 初始化完成, vertx=httpClient");
    }

    @Override
    /** 销毁 */
    public void destroy() {
        if (httpClient != null) {
            httpClient.close();
        }
        if (vertx != null) {
            vertx.close();
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
        proxyAsync(discovery, request, response, null);
    }

    @Override
    /**
    * 执行过滤
    * @param request 请求
    * @param response 响应
    * @param chain chain
     */
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        Discovery discovery = ServerAttribute.getBackendDiscovery(request);
        if (discovery == null) {
            return chain.doFilter(request, response);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        proxyAsync(discovery, request, response, future);
        return future;
    }

    /**
    * 代理异步
    * @param discovery discovery
    * @param request 请求
    * @param response 响应
    * @param completionFuture 完成期货
     */
    private void proxyAsync(Discovery discovery, ServerRequest request, ServerResponse response,
                            CompletableFuture<Void> completionFuture) {
        String host = discovery.getHost();
        int port = discovery.getPort();
        String path = extractPath(request);
        byte[] reqBody = request.getBody();

        io.vertx.core.http.HttpMethod method =
                io.vertx.core.http.HttpMethod.valueOf(request.getMethod().name());

        httpClient.request(method, port, host, path)
                .onSuccess(req -> {
                    copyHeaders(request, req);
                    req.putHeader("Host", host + ":" + port)
                            .putHeader("Connection", "close");

                    Buffer content = reqBody != null && reqBody.length > 0
                            ? Buffer.buffer(reqBody) : Buffer.buffer();
                    req.send(content)
                            .onSuccess(resp -> handleBackendResponse(resp, response, completionFuture))
                            .onFailure(err -> {
                                log.warn("[network-proxy] HTTP 反向代理后端异常: {}", err.getMessage());
                                sendError(response, 502, "Bad Gateway");
                                completeExceptionally(completionFuture, err);
                            });
                })
                .onFailure(err -> {
                    log.warn("[network-proxy] HTTP 反向代理连接失败: {}:{}: {}", host, port, err.getMessage());
                    sendError(response, 502, "Bad Gateway: connection failed");
                    completeExceptionally(completionFuture, err);
                });
    }

    /**
    * 复制头部
    *
    * @param request 请求
    * @param req req
     */
    private void copyHeaders(ServerRequest request, HttpClientRequest req) {
        if (request.getHeaders() != null) {
            for (Map.Entry<String, String> entry : request.getHeaders().toMap().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    String lower = entry.getKey().toLowerCase();
                    if (!"host".equals(lower) && !"connection".equals(lower)) {
                        req.putHeader(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }

    /**
    * 处理backend响应
    * @param resp resp
    * @param response 响应
    * @param completionFuture 完成期货
     */
    private void handleBackendResponse(HttpClientResponse resp, ServerResponse response,
                                       CompletableFuture<Void> completionFuture) {
        if (response.isEnded()) {
            complete(completionFuture, null);
            return;
        }
        response.setStatus(resp.statusCode());
        resp.headers().forEach(entry -> {
            String key = entry.getKey();
            String lower = key.toLowerCase();
            if (!"transfer-encoding".equals(lower)
                    && !"content-encoding".equals(lower)
                    && !"content-length".equals(lower)) {
                response.setHeader(key, entry.getValue());
            }
        });
        resp.body()
                .onSuccess(buf -> {
                    if (!response.isEnded()) {
                        if (buf != null && buf.length() > 0) {
                            response.setBody(buf.getBytes());
                        }
                        response.end();
                    }
                    complete(completionFuture, null);
                })
                .onFailure(err -> {
                    log.warn("[network-proxy] HTTP 反向代理响应体读取失败: {}", err.getMessage());
                    sendError(response, 502, "Bad Gateway");
                    completeExceptionally(completionFuture, err);
                });
    }

    /**
    * extract路径
    *
    * @param request 请求
    * @return extract路径的结果
     */
    private String extractPath(ServerRequest request) {
        String path = request.getPath();
        if (path == null) {
            path = "/";
        }
        String uri = request.getUri();
        if (uri != null && uri.contains("?")) {
            path = uri;
        }
        return path;
    }

    /**
    * 发送记录错误
    *
    * @param response 响应
    * @param code 编码
    * @param msg msg
     */
    private void sendError(ServerResponse response, int code, String msg) {
        if (!response.isEnded()) {
            response.setStatus(code);
            response.setBody(msg.getBytes(StandardCharsets.UTF_8));
            response.end();
        }
    }

    /**
    * 完成
    *
    * @param future 期货
    * @param value 值
     */
    private static void complete(CompletableFuture<Void> future, Void value) {
        if (future != null) {
            future.complete(value);
        }
    }

    /**
    * 完成exceptionally
    *
    * @param future 期货
    * @param cause cause
     */
    private static void completeExceptionally(CompletableFuture<Void> future, Throwable cause) {
        if (future != null) {
            future.completeExceptionally(cause);
        }
    }
}
