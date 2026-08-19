package com.chua.vertx.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.ProxyTargetResolver;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.spi.annotations.Spi;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Vert.x 事件循环的 HTTP 反向代理服务器，与
 * {@link com.chua.common.support.network.server.proxy.TcpProxyServer} 对齐。
 *
 * <p>接收前端 HTTP 请求 → 按 {@link ProxyTargetResolver} 解析后端地址 →
 * 经 HttpClient 转发后端 → 回传响应。全链路事件循环异步，天然高吞吐。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("vertx-http-proxy")
public class VertxHttpProxyServer extends AbstractServer {

    /**
     * 后端地址解析器
     */
    private final ProxyTargetResolver<InetSocketAddress> targetResolver;

    /** Vertx */
    private Vertx vertx;
    /** 服务器 */
    private HttpServer server;
    /** HTTP客户端 */
    private HttpClient httpClient;

    public VertxHttpProxyServer(ServerSetting setting) {
        super(setting);
        // 与 TcpProxyServer 一致：SPI 加载时 resolver 未提供，拒绝所有连接，调用方自行注入
        this.targetResolver = remote -> null;
    }

    public VertxHttpProxyServer(ServerSetting setting, ProxyTargetResolver<InetSocketAddress> targetResolver) {
        super(setting);
        this.targetResolver = targetResolver;
    }

    public VertxHttpProxyServer(ServerSetting setting, InetSocketAddress backend) {
        super(setting);
        this.targetResolver = remote -> backend;
    }

    @Override
    protected void doStart() {
        try {
            VertxOptions opts = new VertxOptions()
                    .setEventLoopPoolSize(Math.max(Runtime.getRuntime().availableProcessors(), 2))
                    .setWorkerPoolSize(Math.max(setting.getWorkerThreads(),
                            Runtime.getRuntime().availableProcessors() * 4))
                    .setPreferNativeTransport(true);
            vertx = Vertx.vertx(opts);
            httpClient = vertx.createHttpClient(new HttpClientOptions()
                    .setTcpNoDelay(true)
                    .setConnectTimeout(setting.getReadTimeout())
                    // 连接池/keep-alive 复用：proxy 高并发转发关键，避免每请求新建后端连接
                    .setKeepAlive(true)
                    .setKeepAliveTimeout(60)
                    .setPipelining(false)
                    .setHttp2MultiplexingLimit(128)
                    .setTcpFastOpen(true)
                    .setTcpCork(true)
                    .setTcpQuickAck(true));

            HttpServerOptions options = new HttpServerOptions()
                    .setHost(setting.getHost())
                    .setPort(setting.getPort())
                    .setAcceptBacklog(Math.max(setting.getBacklog(), 2048))
                    .setReuseAddress(setting.isSoReuseAddr())
                    .setMaxHeaderSize(16384)
                    .setTcpNoDelay(setting.isTcpNoDelay())
                    // 吞吐优化:收发缓冲放大 + TCP_CORK/QUICKACK/FastOpen/KeepAlive
                    .setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384))
                    .setSendBufferSize(Math.max(setting.getBufferSize(), 16384))
                    .setTcpCork(true)
                    .setTcpQuickAck(true)
                    .setTcpFastOpen(true)
                    .setTcpKeepAlive(true);
            server = vertx.createHttpServer(options);
            server.requestHandler(this::handleProxy);
            // Vert.x 5.x:listen 返回 Future,异步完成;用 latch 等监听就绪并回填端口
            CountDownLatch ready = new CountDownLatch(1);
            server.listen().onSuccess(s -> {
                setting.setPort(server.actualPort());
                log.info("Vertx HttpProxyServer started on {}:{} (eventLoops={}, reactive=true)",
                        setting.getHost(), setting.getPort(),
                        Runtime.getRuntime().availableProcessors());
                ready.countDown();
            }).onFailure(err -> {
                log.error("Vertx HttpProxyServer 启动失败: {}", err.getMessage(), err);
                ready.countDown();
            });
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Vertx HttpProxyServer 监听启动超时");
            }
        } catch (Exception e) {
            throw new RuntimeException("Vertx HttpProxyServer 启动失败", e);
        }
    }

    /**
     * 代理处理：解析后端 → HttpClient 转发 → 回传响应。
     *
     * @param front 前端请求
     */
    private void handleProxy(HttpServerRequest front) {
        InetSocketAddress backend;
        try {
            backend = targetResolver.resolve(null);
        } catch (Exception e) {
            log.warn("vertx-http-proxy 解析后端地址失败: {}", e.getMessage());
            front.response().setStatusCode(502).end();
            return;
        }
        if (backend == null || backend.getPort() <= 0) {
            log.warn("vertx-http-proxy 后端地址无效: {}", backend);
            front.response().setStatusCode(502).end();
            return;
        }
        // 转发到后端：方法/路径/查询串透传，请求体管道，响应回传
        Future<HttpClientRequest> reqFuture = httpClient.request(front.method(),
                backend.getPort(), backend.getHostString(), front.uri());
        reqFuture.onSuccess(req -> {
            // 请求头透传（Host 保留后端）
            front.headers().forEach(req::putHeader);
            HttpServerResponse resp = front.response();
            // Vert.x 5:req.send(request) 会抛 "Request has already been read"；
            // 无 body（GET/HEAD）直接 send()，有 body 用 pipeTo 管道透传
            if (front.isEnded()) {
                req.send().onSuccess(backResp -> forwardResponse(resp, backResp))
                        .onFailure(err -> resp.setStatusCode(502).end());
            } else {
                front.pipeTo(req);
                req.response().onSuccess(backResp -> forwardResponse(resp, backResp))
                        .onFailure(err -> resp.setStatusCode(502).end());
            }
        }).onFailure(err -> {
            front.response().setStatusCode(502).end();
        });
    }

    /**
     * 回传后端响应。
     *
     * @param resp      前端响应
     * @param backResp  后端响应
     */
    private void forwardResponse(HttpServerResponse resp,
                                 io.vertx.core.http.HttpClientResponse backResp) {
        resp.setStatusCode(backResp.statusCode());
        backResp.headers().forEach(resp::putHeader);
        backResp.pipeTo(resp);
    }

    @Override
    protected void doStop() {
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            log.info("Vertx HttpProxyServer stopped");
        }
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception ignored) {
            }
        }
        if (vertx != null) {
            try {
                vertx.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }
}
