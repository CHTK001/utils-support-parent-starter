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
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.ext.web.RoutingContext;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * WebSocket 反向代理过滤器，实现客户端与后端之间真正的双向帧转发。
 *
 * <p>基于 Vert.x {@link WebSocketClient} 实现：</p>
 * <ol>
 *   <li>检测 {@code Upgrade: websocket} 请求头并确认存在后端 Discovery（由负载均衡链注入）；</li>
 *   <li>通过请求中的 {@link ServerAttribute#VERTX_ROUTING_CONTEXT} 拿到底层 Vert.x
 *       {@link HttpServerRequest}，调用 {@code toWebSocket()} 完成服务端升级；</li>
 *   <li>用 {@link WebSocketClient#connect(int, String, String)} 连接后端；</li>
 *   <li>双向帧转发（文本/二进制/控制帧）+ 关闭联动。</li>
 * </ol>
 *
 * <p>非 WebSocket 请求或缺少 Vert.x 上下文时放行至过滤器链（可安全运行在 JDK Server 之上）。</p>
 *
 * @author CH
 * @since 2026/08/15
 * @see com.chua.common.support.network.server.filter.discovery.ServiceDiscoveryServerFilter
 */
@Slf4j
public class VertxWebSocketProxyFilter implements ServerFilter, ReactiveServerFilter {

    /** Vertx */
    private Vertx vertx;
    /** WebSocket客户端 */
    private WebSocketClient webSocketClient;

    @Override
    /** 获取订单 */
    public int getOrder() {
        return Integer.MAX_VALUE - 45;
    }

    @Override
    /** 支持路径 */
    public String supportPath() {
        return null;
    }

    @Override
    /** 支持协议 */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.WS};
    }

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) {
        this.vertx = Vertx.vertx();
 // 后端 WebSocket 客户端性能配置:tcpno延迟 减小包延迟,帧大小上限放大,
        // 连接超时防后端不可达时挂起,提升代理转发吞吐
        this.webSocketClient = vertx.createWebSocketClient(new io.vertx.core.http.WebSocketClientOptions()
                .setTcpNoDelay(true)
                .setConnectTimeout(5000)
                .setMaxFrameSize(1024 * 1024)
                .setMaxMessageSize(4 * 1024 * 1024));
        log.info("[network-proxy] VertxWebSocketProxyFilter 初始化完成, vertx=true");
    }

    @Override
    /** 销毁 */
    public void destroy() {
        if (webSocketClient != null) {
            webSocketClient.close();
        }
        if (vertx != null) {
            vertx.close();
        }
        log.info("[network-proxy] VertxWebSocketProxyFilter 已关闭");
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
        if (tryProxyWebSocket(request, response)) {
            return;
        }
        chain.doFilter(request, response);
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
        if (tryProxyWebSocket(request, response)) {
            return CompletableFuture.completedFuture(null);
        }
        return chain.doFilter(request, response);
    }

    /**
     * 尝试 WebSocket 反向代理。成功返回 true（请求已被接管），否则返回 false。
     * @param request 请求
     * @param response 响应
     * @return 尝试代理webSocket的结果
     */
    private boolean tryProxyWebSocket(ServerRequest request, ServerResponse response) {
        String upgrade = request.getHeader("Upgrade");
        if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
            return false;
        }
        Discovery discovery = ServerAttribute.getBackendDiscovery(request);
        if (discovery == null) {
            return false;
        }
        // 协议校验：后端必须是 ws/wss 才由 WebSocket 代理接管，否则放行给 HTTP 代理
        if (!discovery.isWebsocket()) {
            log.debug("[network-proxy] WebSocket 请求但后端协议为 {}, 放行至 HTTP 代理", discovery.getProtocol());
            return false;
        }
        Object ctxObj = request.getAttribute(ServerAttribute.VERTX_ROUTING_CONTEXT);
        if (!(ctxObj instanceof RoutingContext ctx)) {
            log.warn("[network-proxy] WebSocket 代理需要 Vert.x 上下文, 当前请求不支持升级");
            return false;
        }
        HttpServerRequest httpReq = ctx.request();
        if (!httpReq.canUpgradeToWebSocket()) {
            return false;
        }

        String path = request.getUri() != null ? request.getUri() : "/";
        String backendHost = discovery.getHost();
        int backendPort = discovery.getPort();

        httpReq.toWebSocket()
                .onSuccess(clientWs -> {
                    webSocketClient.connect(backendPort, backendHost, path)
                            .onSuccess(backendWs -> pipe(clientWs, backendWs))
                            .onFailure(err -> {
                                log.warn("[network-proxy] WebSocket 后端连接失败: {}:{}: {}",
                                        backendHost, backendPort, err.getMessage());
                                clientWs.close();
                            });
                })
                .onFailure(err -> {
                    log.warn("[network-proxy] WebSocket 升级失败: {}", err.getMessage());
                    sendError(response, 502, "Bad Gateway: upgrade failed");
                });
        return true;
    }

    /**
     * 建立客户端与后端之间的双向帧管道。
     * @param clientWs 客户端ws
     * @param backendWs backendws
     */
    private void pipe(ServerWebSocket clientWs, WebSocket backendWs) {
        clientWs.frameHandler(frame -> {
            if (!backendWs.isClosed()) {
                backendWs.writeFrame(frame);
            }
        });
        backendWs.frameHandler(frame -> {
            if (!clientWs.isClosed()) {
                clientWs.writeFrame(frame);
            }
        });
        clientWs.closeHandler(v -> backendWs.close());
        backendWs.closeHandler(v -> clientWs.close());
        clientWs.exceptionHandler(err -> {
            log.debug("[network-proxy] 客户端 WebSocket 异常: {}", err.getMessage());
            backendWs.close();
        });
        backendWs.exceptionHandler(err -> {
            log.debug("[network-proxy] 后端 WebSocket 异常: {}", err.getMessage());
            clientWs.close();
        });
        log.info("[network-proxy] WebSocket 双向代理已建立: {} -> {}:{}",
                clientWs.path(), backendWs.remoteAddress());
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
}
