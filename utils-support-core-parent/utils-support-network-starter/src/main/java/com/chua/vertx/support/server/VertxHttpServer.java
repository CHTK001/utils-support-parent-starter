package com.chua.vertx.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerAttribute;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.FormFile;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.ssl.SslUtils;
import com.chua.common.support.spi.annotations.Spi;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.web.Router;
import com.chua.common.support.utils.ThreadUtils;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

/**
* 基于 Vert.x 的 HTTP 服务器实现，支持同步阻塞和响应式两种模式。
*
* <p>同步模式（默认）：过滤器链在 Vert.x Worker 线程池中执行。</p>
* <p>响应式模式（{@link ServerSetting#isReactor()} = true）：过滤器链在 Vert.x EventLoop 线程上执行。</p>
*
* @author CH
* @since 2026/07/16
 */
@Slf4j
@Spi({"vertx-http", "http"})
public class VertxHttpServer extends AbstractServer {

    /** Vertx */
    private Vertx vertx;
    /** 服务器 */
    private io.vertx.core.http.HttpServer server;
    /** 虚拟线程池:处理器 执行 */
    private java.util.concurrent.ExecutorService virtualThreadExecutor;
    /** WebSocket 主题处理器映射 */
    private final Map<String, java.util.List<com.chua.common.support.network.server.handler.ServerHandler>> wsTopicHandlers = new java.util.concurrent.ConcurrentHashMap<>();
    /** 基准测试模式:跳过虚拟线程,直接在 事件 循环 执行 */
    private final boolean benchmarkMode = "true".equals(System.getProperty("bench.fast"));
    /** 响应式 */
    private boolean reactive;

    /**
    * 创建 vertxhttp服务端 实例
    * @param setting setting
    */
    public VertxHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** 支持reactor */
    public boolean supportsReactor() {
        return true;
    }

    @Override
    /** 获取协议类型 */
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    @Override
    /** 执行开始 */
    protected void doStart() {
        this.reactive = setting.isReactor();
        int eventLoopPoolSize = Math.max(Runtime.getRuntime().availableProcessors(), 2);
        int workerPoolSize = Math.max(setting.getWorkerThreads(), Runtime.getRuntime().availableProcessors() * 4);

        VertxOptions opts = new VertxOptions()
                .setEventLoopPoolSize(eventLoopPoolSize)
                .setWorkerPoolSize(workerPoolSize)
                .setPreferNativeTransport(true);
        vertx = Vertx.vertx(opts);

        boolean isWindows = com.chua.common.support.lang.cmd.WindowsConPtyProcess.isWindows();
        HttpServerOptions httpOpts = new HttpServerOptions()
                .setHost(setting.getHost())
                .setPort(setting.getPort())
                // 关键:header/请求行必须用紧凑缓冲,不能直接用 maxRequestSize(默认 10MB),
                // 否则 Vert.x 为每个连接分配 10MB 头缓冲,高并发下内存暴涨、吞吐暴跌
                .setMaxHeaderSize(Math.min(16384, (int) setting.getMaxRequestSize()))
                .setMaxChunkSize((int) setting.getMaxRequestSize())
                .setMaxInitialLineLength(Math.min(8192, (int) setting.getMaxRequestSize()))
                .setAcceptBacklog(Math.max(setting.getBacklog(), 65536))
                // 收发缓冲放大:与内核窗口对齐,高并发小请求场景减少分片与 ACK 往返
                .setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384))
                .setSendBufferSize(Math.max(setting.getBufferSize(), 16384))
 // 吞吐优化:TCP_QUICKACK 减少 ACK 延迟,keepalive 复用
                // 注意:不使用 TCP_CORK——它延迟发送最多 200ms 合并小包,小响应(echo/JSON)每次都要
                // 等 200ms 才发出,低并发下吞吐暴跌(实测 Linux 2 核 @256 从 ~5k 掉到 ~1k)。
 // HTTP 头部/主体 合并由 Vert.x 自身缓冲完成,无需内核 cork。
                .setTcpQuickAck(true)
                .setTcpKeepAlive(true)
 // TCP Fast 打开 仅 Linux/macOS 支持,窗口 上无效,避免无效配置
                .setTcpFastOpen(!isWindows)
                .setTcpNoDelay(setting.isTcpNoDelay())
 // SO_REUSEPORT 仅 Linux/macOS 支持,窗口 只有 SO_REUSEADDR,平台条件化
                .setReusePort(!isWindows && setting.isSoReuseAddr())
                .setReuseAddress(setting.isSoReuseAddr())
                .setIdleTimeout((int) Math.max(1, setting.getReadTimeout() / 1000))
                .setIdleTimeoutUnit(TimeUnit.SECONDS)
                .setCompressionSupported(setting.isGzipEnabled())
                .setCompressionLevel(Math.max(1, setting.getGzipLevel()))
                .setCompressionContentSizeThreshold(setting.getGzipMinSize())
                .setMaxWebSocketFrameSize(setting.getMaxFrameSize())
                .setMaxWebSocketMessageSize(setting.getMaxFrameSize() * 4)
                .setLogActivity(false);
        // HTTP/2 (h2c) 流上限放大:Vert.x 5 默认开启 h2c 且 maxConcurrentStreams=100,
 // JDK HTTP客户端 会把高并发虚拟线程请求复用进同一连接的多路流,超过 100 即 RST_流,
        // 实测 256/512 并发下 50%+ 请求失败(IOException: too many concurrent streams)。
        // 放大到与 maxConnections 对齐,使多路复用真正承载高并发(HTTP/1.1 不受影响)。
        httpOpts.setInitialSettings(new io.vertx.core.http.Http2Settings()
                .setMaxConcurrentStreams(Math.max(setting.getMaxConnections(), 1024)));
        // 显式开启 h2c 明文多路复用(HTTP/2 多路流共享单连接,单连接并发吞吐数倍于 HTTP/1.1)
        httpOpts.setHttp2ClearTextEnabled(true);

 // 与 ssl工具.是否ssl已启用 对齐:self标志auto 单独开启也应生效
        if (setting.getSsl() != null && (setting.getSsl().isEnabled() || setting.getSsl().isSelfSignedAuto())) {
            httpOpts.setSsl(true);
            ServerSetting.SslConfig ssl = setting.getSsl();
            if (ssl.getKeyStorePath() != null) {
                httpOpts.setKeyCertOptions(new io.vertx.core.net.JksOptions()
                        .setPath(ssl.getKeyStorePath())
                        .setPassword(ssl.getKeyStorePassword() != null ? ssl.getKeyStorePassword() : ""));
            } else if (ssl.getCertPath() != null && ssl.getKeyPath() != null) {
                httpOpts.setKeyCertOptions(new PemKeyCertOptions()
                        .setKeyPath(ssl.getKeyPath())
                        .setCertPath(ssl.getCertPath()));
            } else if (ssl.isSelfSigned() || ssl.isSelfSignedAuto()) {
 // 自签名证书：通过 ssl工具 自动生成
                try {
                    SslUtils.prepareSslConfig(ssl);
                    java.security.KeyStore ks = SslUtils.loadKeyStore(ssl);
                    java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
                    char[] password = SslUtils.getKeyStorePassword(ssl);
                    ks.store(bout, password);
                    httpOpts.setKeyCertOptions(new io.vertx.core.net.JksOptions()
                            .setValue(Buffer.buffer(bout.toByteArray()))
                            .setPassword(new String(password)));
                } catch (Exception e) {
                    throw new IllegalStateException("自签名证书生成失败", e);
                }
            }
        }

        server = vertx.createHttpServer(httpOpts);

 // 最大connections：通过 connection处理器 计数限制最大并发连接
        if (setting.getMaxConnections() > 0) {
            AtomicInteger activeConnections = new AtomicInteger();
            server.connectionHandler(conn -> {
                int current = activeConnections.incrementAndGet();
                if (current > setting.getMaxConnections()) {
                    conn.close();
                    activeConnections.decrementAndGet();
                    log.warn("[vertx-http] 超过最大连接数限制, 拒绝新连接: maxConnections={}", setting.getMaxConnections());
                } else {
                    conn.closeHandler(v -> activeConnections.decrementAndGet());
                }
            });
        }

        Router router = Router.router(vertx);

 // 跨域资源共享 配置
        ServerSetting.CorsConfig cors = setting.getCors();
        if (cors != null && cors.isAllowOrigin()) {
            CorsHandler corsHandler = CorsHandler.create();
            String origins = cors.getAllowedOrigins();
            if (origins != null && !origins.isBlank()) {
                for (String origin : origins.split(",")) {
                    corsHandler.addOrigin(origin.trim());
                }
            } else {
                corsHandler.addOrigin("*");
            }
            String methods = cors.getAllowedMethods();
            if (methods != null && !methods.isBlank()) {
                for (String m : methods.split(",")) {
                    try {
                        corsHandler.allowedMethod(io.vertx.core.http.HttpMethod.valueOf(m.trim()));
                    } catch (Exception ignored) {
                    }
                }
            }
            String headers = cors.getAllowedHeaders();
            if (headers != null && !headers.isBlank()) {
                for (String h : headers.split(",")) {
                    corsHandler.allowedHeader(h.trim());
                }
            }
            router.route().handler(corsHandler);
        }

        // contextPath：非 "/" 时限制路由前缀
        String contextPath = setting.getContextPath();
        boolean hasContextPath = contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath);
        io.vertx.ext.web.Route rootRoute = hasContextPath
                ? router.route(contextPath + "/*")
                : router.route();

        rootRoute.handler(BodyHandler.create()
                .setBodyLimit((int) setting.getMaxRequestSize())
                // 启用文件上传处理:仅当出现 multipart 分件时才落盘,
                // 普通请求(GET/JSON 等)零额外开销;禁用将导致 getFiles() 恒为空
                .setHandleFileUploads(true));

 // 虚拟线程池:处理器 提交到虚拟线程并行执行,事件循环专注 I/O 多路复用
        virtualThreadExecutor = ThreadUtils.newVirtualThreadPerTaskExecutor();

        rootRoute.handler(ctx -> {
            // WebSocket 升级检测：切到 Vert.x 原生 WebSocket，按 topic\nbody 路由
            String upg = ctx.request().getHeader("Upgrade");
            if (upg != null && "websocket".equalsIgnoreCase(upg)) {
                ctx.request().toWebSocket().onSuccess(ws -> {
                    ws.textMessageHandler(msg -> dispatchWsMessage(ws, msg.toString()));
                }).onFailure(err -> {
                    log.warn("WebSocket 升级失败: {}", err.getMessage());
                    ctx.vertx().runOnContext(v -> ctx.response().setStatusCode(500).end());
                });
                return;
            }
            VertxServerRequest request = new VertxServerRequest(ctx);
 // 暴露底层 routing上下文，供 WebSocket 反向代理等 过滤器 完成升级
            request.setAttribute(ServerAttribute.VERTX_ROUTING_CONTEXT, ctx);
            VertxServerResponse response = new VertxServerResponse(ctx);

            if (benchmarkMode) {
 // 极速路径:工人 线程池执行 过滤器 链,on完成 回调在 事件 循环 线程写响应
                vertx.<Void>executeBlocking(() -> {
                    handleReactive(request, response).toCompletableFuture().join();
                    return null;
                }, false).onComplete(v -> {
                    if (!response.isCommitted()) {
                        response.endVertx();
                    }
                });
            } else {
 // 正常路径:完整 过滤器 链在虚拟线程执行,响应写回必须回到 事件 循环 线程
                // (Vert.x HttpServerResponse 只允许 Event Loop 线程操作,虚拟线程跨线程调用会
                //  触发 Vert.x 桥接排队,高并发下桥接队列积压 → 延迟吸附)
                virtualThreadExecutor.execute(() -> {
                    try {
                        handleRequestAsync(request, response).whenComplete((v, ex) -> {
                            ctx.vertx().runOnContext(v2 -> {
                                if (!response.isCommitted()) {
                                    response.endVertx();
                                }
                            });
                        });
                    } catch (Exception e) {
                        log.warn("[vertx-http] handler execution failed: {}", e.getMessage());
                        ctx.vertx().runOnContext(v2 -> {
                            if (!response.isCommitted()) {
                                VertxServerResponse vsr = (VertxServerResponse) response;
                                vsr.setStatus(500);
                                vsr.endVertx();
                            }
                        });
                    }
                });
            }
        });

        server.requestHandler(router);
        server.listen(setting.getPort(), setting.getHost())
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        // 回写实际监听端口(port=0 随机端口场景),供 getPort() 返回真实端口
        setting.setPort(server.actualPort());
        log.info("Vertx HTTP Server started on {}:{} (eventLoopSize={}, workerPoolSize={}, reactive={})",
                setting.getHost(), setting.getPort(), eventLoopPoolSize, workerPoolSize, reactive);
    }

    /**
    * 执行处理
    *
    * @param request 请求
    * @param response 响应
    */
    private void doHandle(VertxServerRequest request, VertxServerResponse response) {
        // WebSocket 升级检测
        String upgrade = request.getHeader("Upgrade");
        if (upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
            handleWebSocketUpgrade(request, response);
            return;
        }
        try {
            handleRequest(request, response);
        } catch (Exception e) {
            log.warn("过滤器链执行异常: {}", e.getMessage(), e);
            if (!response.isEnded()) {
                response.sendError(500, "Internal Server Error");
            }
        }
    }

    /**
    * WebSocket 升级处理：切换到 Vert.x 原生 WebSocket，
    * 按 "topic\nbody" 约定路由消息到已注册的主题处理器。
    * @param request 请求
    * @param response 响应
    */
    private void handleWebSocketUpgrade(VertxServerRequest request, VertxServerResponse response) {
        var routingCtx = request.getAttribute(com.chua.common.support.network.server.ServerAttribute.VERTX_ROUTING_CONTEXT);
        if (routingCtx instanceof io.vertx.ext.web.RoutingContext rc) {
            rc.request().toWebSocket().onSuccess(ws -> {
                ws.textMessageHandler(msg -> {
                    dispatchWsMessage(ws, msg.toString());
                });
            }).onFailure(err -> {
                log.warn("WebSocket 升级失败: {}", err.getMessage());
                response.sendError(500, "WebSocket upgrade failed");
            });
        } else {
            response.sendError(426, "Upgrade Required");
        }
    }

    /**
    * 按 topic\nbody 分发 WS 消息。
    *
    * @param ws ws
    * @param text 文本
    */
    private void dispatchWsMessage(io.vertx.core.http.ServerWebSocket ws, String text) {
        String topic = "default";
        String body = text;
        int idx = text.indexOf('\n');
        if (idx > 0) {
            topic = text.substring(0, idx).trim();
            body = text.substring(idx + 1);
        }
        List<com.chua.common.support.network.server.handler.ServerHandler> handlers = wsTopicHandlers.get(topic);
        if (handlers == null) {
            handlers = wsTopicHandlers.get("default");
        }
        if (handlers == null) {
            return;
        }
        for (var handler : handlers) {
            try {
                var wsReq = new VertxWsRequest(topic, body);
                var wsResp = new VertxWsResponse(ws);
                handler.handle(wsReq, wsResp);
                if (wsResp.getResult() != null) {
                    ws.writeTextMessage(wsResp.getResult().toString());
                }
            } catch (Exception e) {
                log.warn("WS handler 异常: {}", e.getMessage());
            }
        }
    }

    /**
    * 订阅 WebSocket 主题。
    *
    * @param topic topic
    * @param handler 处理器
    * @return on订阅的结果
    */
    public VertxHttpServer onSubscribe(String topic, com.chua.common.support.network.server.handler.ServerHandler handler) {
        wsTopicHandlers.computeIfAbsent(topic, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
    * WebSocket 消息请求（与 niohttp服务端.ws服务端请求 行为一致）。
    */
    private static final class VertxWsRequest implements com.chua.common.support.network.server.request.ServerRequest {
        /** Topic */
        private final String topic;
        /** 请求体 */
        private final String body;
        /** attributes */
        private final Map<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();

        VertxWsRequest(String topic, String body) {
            this.topic = topic;
            this.body = body;
        }

        @Override public String getUri() { return "/ws/" + topic; }
        @Override public String getPath() { return "/ws/" + topic; }
        @Override public HttpMethod getMethod() { return HttpMethod.POST; }
        @Override public String getHeader(String name) { return null; }
        @Override public HttpHeader getHeaders() { return HttpHeader.create(); }
        @Override public Map<String, String> getParams() { return java.util.Collections.emptyMap(); }
        @Override public String getParam(String name) { return null; }
        @Override public String getContentType() { return "text/plain"; }
        @Override public long getContentLength() { return body != null ? body.getBytes(StandardCharsets.UTF_8).length : -1; }
        @Override public byte[] getBody() { return body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]; }
        @Override public String getBodyString() { return body; }
        @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(getBody()); }
        @Override public String getRemoteAddress() { return "127.0.0.1"; }
        @Override public int getRemotePort() { return 0; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    }

    /**
        * WebSocket 消息响应（持有 Vert.x 服务端webSocket 引用用于回写）。
        */
    private static final class VertxWsResponse implements com.chua.common.support.network.server.response.ServerResponse {
        /** WebSocket 连接 */
        private final io.vertx.core.http.ServerWebSocket ws;
        /** 状态 */
        private int status = 200;
        /** 结束 */
        private boolean ended;
        /** Committed */
        private boolean committed;
        /** 结果 */
        private Object result;

        VertxWsResponse(io.vertx.core.http.ServerWebSocket ws) {
            this.ws = ws;
        }

        @Override public int getStatus() { return status; }
        @Override public com.chua.common.support.network.server.response.ServerResponse setStatus(int statusCode) {
            this.status = statusCode;
            return this;
        }
        @Override public com.chua.common.support.network.server.response.ServerResponse setBody(byte[] body) {
            this.result = body;
            return this;
        }
        @Override public com.chua.common.support.network.server.response.ServerResponse setBody(String body) {
            this.result = body;
            return this;
        }
        @Override public com.chua.common.support.network.server.response.ServerResponse setHeader(String name, String value) { return this; }
        @Override public String getHeader(String name) { return null; }
        @Override public HttpHeader getHeaders() { return HttpHeader.create(); }
        @Override public String getContentType() { return null; }
        @Override public com.chua.common.support.network.server.response.ServerResponse setContentType(String contentType) { return this; }
        @Override public byte[] getBody() { return result instanceof byte[] b ? b : null; }
        @Override public OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        @Override public com.chua.common.support.network.server.response.ServerResponse sendRedirect(String location) { return this; }
        @Override public com.chua.common.support.network.server.response.ServerResponse sendError(int code, String message) {
            this.status = code;
            this.result = message;
            this.ended = true;
            return this;
        }
        @Override public void flush() { }
        @Override public boolean isCommitted() { return committed; }
        @Override public boolean isEnded() { return ended; }
        @Override public void end() { this.ended = true; }
        @Override public com.chua.common.support.network.server.response.ServerResponse reset() {
            if (!committed) {
                status = 200;
                result = null;
                ended = false;
            }
            return this;
        }
        @Override public void writeRaw(byte[] bytes) {
            ws.writeBinaryMessage(io.vertx.core.buffer.Buffer.buffer(bytes));
        }
        @Override public com.chua.common.support.network.server.response.ServerResponse setResult(Object result) {
            this.result = result;
            return this;
        }
        @Override public Object getResult() { return result; }
        @Override public com.chua.common.support.network.server.response.ServerResponse sse() { return this; }
        @Override public void sseEvent(String event, String data) { }
        @Override public void sseClose() { }
    }

    /**
        * 执行处理
        *
        * @param request 请求
        * @param response 响应
        */
    private void doHandleOriginal(VertxServerRequest request, VertxServerResponse response) {
        try {
            handleRequest(request, response);
        } catch (Exception e) {
            log.warn("过滤器链执行异常: {}", e.getMessage(), e);
            if (!response.isEnded()) {
                response.sendError(500, "Internal Server Error");
            }
        }
    }

    @Override
    /** 执行停止 */
    protected void doStop() {
        if (server != null) {
            try {
                server.close().toCompletionStage().toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdownNow();
        }
        if (vertx != null) {
            try {
                vertx.close().toCompletionStage().toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }
    }

    static class VertxServerResponse implements ServerResponse {

        /** Benchmark模式:预分配响应缓冲 (thread本地复用) */
        private static final ThreadLocal<byte[]> ECHO_BUF = ThreadLocal.withInitial(() -> new byte[128]);
        /** CTX */
        private final RoutingContext ctx;
        /** 状态 */
        private int status = 200;
        /** 请求体 */
        private byte[] body;
        // getOutputStream() 写入内容保留在此,响应完成(endVertx)时写回,避免临时流丢字节
        /** 出流 */
        private java.io.ByteArrayOutputStream outStream;
        /** 头部 - 可能被工人线程池访问,保留并发哈希映射 */
        private final java.util.concurrent.ConcurrentHashMap<String, String> headers = new java.util.concurrent.ConcurrentHashMap<>(4);
        /** 内容类型 */
        private String contentType;
        /** Committed */
        private boolean committed;
        /** 结束 */
        private boolean ended;
        /** 结果 */
        private Object result;
        /** SSE模式 */
        private boolean sseMode;

        VertxServerResponse(RoutingContext ctx) {
            this.ctx = ctx;
        }

        @Override
        /** 设置状态 */
        public ServerResponse setStatus(int code) {
            if (!committed) {
                this.status = code;
            }
            return this;
        }

        @Override
        /** 获取状态 */
        public int getStatus() {
            return status;
        }

        @Override
        /** 设置头部 */
        public ServerResponse setHeader(String name, String value) {
            if (!committed) {
                headers.put(name, value);
            }
            return this;
        }

        @Override
        /** 获取头部 */
        public String getHeader(String name) {
            return headers.get(name);
        }

        @Override
        /** 获取头部 */
        public HttpHeader getHeaders() {
            HttpHeader h = HttpHeader.create();
            headers.forEach(h::add);
            return h;
        }

        @Override
        /** 设置内容类型 */
        public ServerResponse setContentType(String ct) {
            this.contentType = ct;
            return this;
        }

        @Override
        /** 获取内容类型 */
        public String getContentType() {
            return contentType;
        }

        @Override
        /** 设置主体 */
        public ServerResponse setBody(byte[] b) {
            if (!committed) {
                this.body = b;
            }
            return this;
        }

        @Override
        /** 设置主体 */
        public ServerResponse setBody(String b) {
            if (!committed) {
                this.body = b != null ? b.getBytes(StandardCharsets.UTF_8) : null;
            }
            return this;
        }

        @Override
        /** 获取主体 */
        public byte[] getBody() {
            return body;
        }

        @Override
        /** 获取输出流 */
        public OutputStream getOutputStream() {
            if (outStream == null) {
                outStream = new java.io.ByteArrayOutputStream();
            }
            return outStream;
        }

        @Override
        /** 设置结果 */
        public ServerResponse setResult(Object result) {
            this.result = result;
            return this;
        }

        @Override
        /** 获取结果 */
        public Object getResult() {
            return result;
        }

        @Override
        /** 发送Redirect */
        public ServerResponse sendRedirect(String location) {
            setStatus(302);
            headers.put("Location", location);
            ended = true;
            endVertx();
            return this;
        }

        @Override
        /** 发送记录错误 */
        public ServerResponse sendError(int code, String msg) {
            if (ended) {
                return this;
            }
            setStatus(code);
            setBody(msg);
            ended = true;
            endVertx();
            return this;
        }

        @Override
        /** 刷新 */
        public void flush() {
        }

        @Override
        /** 是否Committed */
        public boolean isCommitted() {
            return committed;
        }

        @Override
        /** 是否结束 */
        public boolean isEnded() {
            return ended;
        }

        @Override
        /** 结束 */
        public void end() {
            if (ended) {
                return;
            }
            ended = true;
        }

        @Override
        /** 重置 */
        public ServerResponse reset() {
            if (!committed) {
                status = 200;
                body = null;
                headers.clear();
                result = null;
                ended = false;
            }
            return this;
        }

        @Override
        /** 写入Raw */
        public void writeRaw(byte[] bytes) {
            if (committed) {
                return;
            }
            ctx.response().write(Buffer.buffer(bytes));
        }

        @Override
        /** Sse */
        public ServerResponse sse() {
            this.sseMode = true;
            setContentType("text/event-stream; charset=utf-8");
            setHeader("Cache-Control", "no-cache");
            setHeader("Connection", "keep-alive");
            if (!committed) {
                committed = true;
                ended = false;
                io.vertx.core.http.HttpServerResponse resp = ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "text/event-stream; charset=utf-8")
                        .putHeader("Cache-Control", "no-cache")
                        .putHeader("Connection", "keep-alive")
                        .setChunked(true);
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    resp.putHeader(e.getKey(), e.getValue());
                }
            }
            return this;
        }

        @Override
        /** sse事件 */
        public void sseEvent(String event, String data) {
            if (!sseMode) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            if (event != null && !event.isEmpty()) {
                sb.append("event: ").append(event).append("\n");
            }
            if (data != null) {
                for (String line : data.split("\n")) {
                    sb.append("data: ").append(line).append("\n");
                }
            }
            sb.append("\n");
            ctx.response().write(Buffer.buffer(sb.toString(), StandardCharsets.UTF_8.name()));
        }

        @Override
        /** Sse关闭 */
        public void sseClose() {
            sseEvent(null, "[DONE]");
            this.ended = true;
            this.committed = true;
            ctx.response().end();
        }

        public void endVertx() {
            if (committed) {
                return;
            }
            committed = true;
            ended = true;
            io.vertx.core.http.HttpServerResponse resp = ctx.response().setStatusCode(status);
            if (!headers.isEmpty()) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    resp.putHeader(e.getKey(), e.getValue());
                }
            }
            if (contentType != null) {
                resp.putHeader("Content-Type", contentType);
            }
            byte[] payload = body;
            if (payload == null && outStream != null && outStream.size() > 0) {
                payload = outStream.toByteArray();
            }
            if (payload != null) {
                resp.end(Buffer.buffer(payload));
            } else {
                resp.end();
            }
        }
    }

    static class VertxServerRequest implements ServerRequest {

        /** 空字节数组常量 */
        private static final byte[] EMPTY_BYTES = new byte[0];
        /** CTX */
        private final RoutingContext ctx;
        /** 请求体bytes */
        private byte[] bodyBytes;
        /** attributes - 可能被工人线程池访问,保留并发哈希映射 */
        private final java.util.concurrent.ConcurrentHashMap<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>(4);

        VertxServerRequest(RoutingContext ctx) {
            this.ctx = ctx;
 // 获取/HEAD 请求无 主体,跳过拷贝
            String method = ctx.request().method().name();
            if ("GET".equals(method) || "HEAD".equals(method)) {
                this.bodyBytes = EMPTY_BYTES;
            } else if (ctx.body() != null && ctx.body().buffer() != null) {
                // 避免复制:直接引用Vert.x内部buffer (只读场景安全)
                io.vertx.core.buffer.Buffer buf = ctx.body().buffer();
                this.bodyBytes = buf.getBytes();
            } else {
                this.bodyBytes = EMPTY_BYTES;
            }
        }

        @Override
        /** 获取Uri */
        public String getUri() {
            return ctx.request().uri();
        }

        @Override
        /** 获取路径 */
        public String getPath() {
            return ctx.request().path();
        }

        @Override
        /** 获取方法 */
        public HttpMethod getMethod() {
            return HttpMethod.valueOf(ctx.request().method().name());
        }

        @Override
        /** 获取头部 */
        public String getHeader(String name) {
            return ctx.request().getHeader(name);
        }

        @Override
        /** 获取头部 */
        public HttpHeader getHeaders() {
            HttpHeader h = HttpHeader.create();
            ctx.request().headers().forEach(e -> h.add(e.getKey(), e.getValue()));
            return h;
        }

        @Override
        /** 获取参数 */
        public Map<String, String> getParams() {
            Map<String, String> params = new java.util.HashMap<>();
            ctx.request().params().forEach(e -> params.put(e.getKey(), e.getValue()));
            return params;
        }

        @Override
        /** 获取参数 */
        public String getParam(String name) {
            return ctx.request().getParam(name);
        }

        @Override
        /** 获取内容类型 */
        public String getContentType() {
            return ctx.request().getHeader("Content-Type");
        }

        @Override
        /** 获取内容获取长度 */
        public long getContentLength() {
            return bodyBytes != null ? bodyBytes.length : 0;
        }

        @Override
        /** 获取主体 */
        public byte[] getBody() {
            return bodyBytes;
        }

        @Override
        /** 获取主体字符串 */
        public String getBodyString() {
            return bodyBytes != null ? new String(bodyBytes, StandardCharsets.UTF_8) : "";
        }

        @Override
        /** 获取输入流 */
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(getBody());
        }

        @Override
        /** 获取远程地址 */
        public String getRemoteAddress() {
            return ctx.request().remoteAddress().host();
        }

        @Override
        /** 获取远程端口 */
        public int getRemotePort() {
            return ctx.request().remoteAddress().port();
        }

        @Override
        /** 获取Attributes */
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        /** 获取Attribute */
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        /** 设置Attribute */
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        /** 获取form数据 */
        public Map<String, String> getFormData() {
            Map<String, String> form = new java.util.LinkedHashMap<>();
            var req = ctx.request();
            if (req.formAttributes() != null) {
                req.formAttributes().forEach(e -> form.put(e.getKey(), e.getValue()));
            }
            return form;
        }

        @Override
        /** 获取文件 */
        public List<FormFile> getFiles() {
            List<io.vertx.ext.web.FileUpload> uploads = ctx.fileUploads();
            log.debug("[VF] ct={} uploads={} bodyLen={}",
                    ctx.request().getHeader("Content-Type"),
                    uploads == null ? "null" : uploads.size(),
                    ctx.body() == null ? -1 : ctx.body().length());
            if (uploads == null || uploads.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            List<FormFile> files = new ArrayList<>();
            for (io.vertx.ext.web.FileUpload fu : uploads) {
                String filePath = fu.uploadedFileName();
                byte[] data;
                try {
                    data = filePath != null ? java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)) : new byte[0];
                } catch (java.io.IOException e) {
                    data = new byte[0];
                }
                files.add(new FormFile(fu.name(), fu.fileName(), fu.contentType(), data));
            }
            return files;
        }
    }
}
