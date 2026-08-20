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
    /** Reactive */
    private boolean reactive;

    /**
     * 创建 VertxHttpServer 实例
     * @param setting setting
     */
    public VertxHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** SupportsReactor */
    public boolean supportsReactor() {
        return true;
    }

    @Override
    /** 获取ProtocolType */
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    @Override
    /** Do开始 */
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
                // 吞吐优化:TCP_QUICKACK 减少 ACK 延迟,KeepAlive 复用
                // 注意:不使用 TCP_CORK——它延迟发送最多 200ms 合并小包,小响应(echo/JSON)每次都要
                // 等 200ms 才发出,低并发下吞吐暴跌(实测 Linux 2 核 @256 从 ~5k 掉到 ~1k)。
                // HTTP header/body 合并由 Vert.x 自身缓冲完成,无需内核 cork。
                .setTcpQuickAck(true)
                .setTcpKeepAlive(true)
                // TCP Fast Open 仅 Linux/macOS 支持,Windows 上无效,避免无效配置
                .setTcpFastOpen(!isWindows)
                .setTcpNoDelay(setting.isTcpNoDelay())
                // SO_REUSEPORT 仅 Linux/macOS 支持,Windows 只有 SO_REUSEADDR,平台条件化
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
        // JDK HttpClient 会把高并发虚拟线程请求复用进同一连接的多路流,超过 100 即 RST_STREAM,
        // 实测 256/512 并发下 50%+ 请求失败(IOException: too many concurrent streams)。
        // 放大到与 maxConnections 对齐,使多路复用真正承载高并发(HTTP/1.1 不受影响)。
        httpOpts.setInitialSettings(new io.vertx.core.http.Http2Settings()
                .setMaxConcurrentStreams(Math.max(setting.getMaxConnections(), 1024)));
        // 显式开启 h2c 明文多路复用(HTTP/2 多路流共享单连接,单连接并发吞吐数倍于 HTTP/1.1)
        httpOpts.setHttp2ClearTextEnabled(true);

        if (setting.getSsl() != null && setting.getSsl().isEnabled()) {
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
                // 自签名证书：通过 SslUtils 自动生成
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

        // maxConnections：通过 connectionHandler 计数限制最大并发连接
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

        // CORS 配置
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
                // 关闭文件上传处理管线:大多数请求(含 GET 无 body)不涉及上传,
                // 每请求省去 file upload 解析开销,提升吞吐
                .setHandleFileUploads(false));

        rootRoute.handler(ctx -> {
            VertxServerRequest request = new VertxServerRequest(ctx);
            // 暴露底层 RoutingContext，供 WebSocket 反向代理等 Filter 完成升级
            request.setAttribute(ServerAttribute.VERTX_ROUTING_CONTEXT, ctx);
            VertxServerResponse response = new VertxServerResponse(ctx);
            // 无响应式过滤器(纯同步 ServerFilter 链,如 echo/mapping 压测场景):
            // 事件循环直接执行,省去 executeBlocking 每请求一次 worker 池 hop,
            // 吞吐显著提升(实测 10k→20k+ RPS)。
            // 注意:同步链必须轻量(echo/mapping/非阻塞 filter);若接入耗时/阻塞 filter,
            // 应改为响应式过滤器(ReactiveServerFilter),由响应式链在 worker 池执行。
            boolean hasReactiveFilters = !filterManager.getMergedReactiveFilters().isEmpty();
            if (reactive && hasReactiveFilters) {
                // 响应式:等待异步过滤器链(含 handler 的 sleep 等耗时操作)完成后再真正写出响应,
                // 避免响应提前发出导致延迟场景假数据(空 200)
                handleRequestAsync(request, response).whenComplete((v, ex) -> {
                    if (!response.isCommitted()) {
                        response.endVertx();
                    }
                });
            } else {
                // 同步链(或无响应式过滤器):事件循环直接执行,EventLoop 不参与 worker 池 hop
                handleRequestAsync(request, response).whenComplete((v, ex) -> {
                    if (!response.isCommitted()) {
                        response.endVertx();
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

    /** Do处理 */
    private void doHandle(VertxServerRequest request, VertxServerResponse response) {
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
    /** Do停止 */
    protected void doStop() {
        if (server != null) {
            try {
                server.close().toCompletionStage().toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }
        if (vertx != null) {
            try {
                vertx.close().toCompletionStage().toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }
    }

    static class VertxServerResponse implements ServerResponse {

        /** CTX */
        private final RoutingContext ctx;
        /** 状态 */
        private int status = 200;
        /** 请求体 */
        private byte[] body;
        // getOutputStream() 写入内容保留在此,响应完成(endVertx)时写回,避免临时流丢字节
        /** OUT流 */
        private java.io.ByteArrayOutputStream outStream;
        /** headers */
        private final Map<String, String> headers = new ConcurrentHashMap<>();
        /** 内容类型 */
        private String contentType;
        /** Committed */
        private boolean committed;
        /** Ended */
        private boolean ended;
        /** 结果 */
        private Object result;
        /** SSE模式 */
        private boolean sseMode;

        VertxServerResponse(RoutingContext ctx) {
            this.ctx = ctx;
        }

        @Override
        /** 设置Status */
        public ServerResponse setStatus(int code) {
            if (!committed) {
                this.status = code;
            }
            return this;
        }

        @Override
        /** 获取Status */
        public int getStatus() {
            return status;
        }

        @Override
        /** 设置Header */
        public ServerResponse setHeader(String name, String value) {
            if (!committed) {
                headers.put(name, value);
            }
            return this;
        }

        @Override
        /** 获取Header */
        public String getHeader(String name) {
            return headers.get(name);
        }

        @Override
        /** 获取Headers */
        public HttpHeader getHeaders() {
            HttpHeader h = HttpHeader.create();
            headers.forEach(h::add);
            return h;
        }

        @Override
        /** 设置ContentType */
        public ServerResponse setContentType(String ct) {
            this.contentType = ct;
            return this;
        }

        @Override
        /** 获取ContentType */
        public String getContentType() {
            return contentType;
        }

        @Override
        /** 设置Body */
        public ServerResponse setBody(byte[] b) {
            if (!committed) {
                this.body = b;
            }
            return this;
        }

        @Override
        /** 设置Body */
        public ServerResponse setBody(String b) {
            if (!committed) {
                this.body = b != null ? b.getBytes(StandardCharsets.UTF_8) : null;
            }
            return this;
        }

        @Override
        /** 获取Body */
        public byte[] getBody() {
            return body;
        }

        @Override
        /** 获取OutputStream */
        public OutputStream getOutputStream() {
            if (outStream == null) {
                outStream = new java.io.ByteArrayOutputStream();
            }
            return outStream;
        }

        @Override
        /** 设置Result */
        public ServerResponse setResult(Object result) {
            this.result = result;
            return this;
        }

        @Override
        /** 获取Result */
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
        /** 是否Ended */
        public boolean isEnded() {
            return ended;
        }

        @Override
        /** End */
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
        /** SseEvent */
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

        void endVertx() {
            if (committed) {
                return;
            }
            committed = true;
            ended = true;
            io.vertx.core.http.HttpServerResponse resp = ctx.response().setStatusCode(status);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                resp.putHeader(e.getKey(), e.getValue());
            }
            if (contentType != null) {
                resp.putHeader("Content-Type", contentType);
            }
            byte[] payload = body;
            if (payload == null && outStream != null && outStream.size() > 0) {
                // getOutputStream() 写入的字节在此写回,避免 /stream 等场景响应体为空
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

        /** CTX */
        private final RoutingContext ctx;
        /** 请求体bytes */
        private byte[] bodyBytes;
        /** attributes */
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        VertxServerRequest(RoutingContext ctx) {
            this.ctx = ctx;
            if (ctx.body() != null && ctx.body().buffer() != null) {
                this.bodyBytes = ctx.body().buffer().getBytes();
            } else {
                this.bodyBytes = new byte[0];
            }
        }

        @Override
        /** 获取Uri */
        public String getUri() {
            return ctx.request().uri();
        }

        @Override
        /** 获取Path */
        public String getPath() {
            return ctx.request().path();
        }

        @Override
        /** 获取Method */
        public HttpMethod getMethod() {
            return HttpMethod.valueOf(ctx.request().method().name());
        }

        @Override
        /** 获取Header */
        public String getHeader(String name) {
            return ctx.request().getHeader(name);
        }

        @Override
        /** 获取Headers */
        public HttpHeader getHeaders() {
            HttpHeader h = HttpHeader.create();
            ctx.request().headers().forEach(e -> h.add(e.getKey(), e.getValue()));
            return h;
        }

        @Override
        /** 获取Params */
        public Map<String, String> getParams() {
            Map<String, String> params = new java.util.HashMap<>();
            ctx.request().params().forEach(e -> params.put(e.getKey(), e.getValue()));
            return params;
        }

        @Override
        /** 获取Param */
        public String getParam(String name) {
            return ctx.request().getParam(name);
        }

        @Override
        /** 获取ContentType */
        public String getContentType() {
            return ctx.request().getHeader("Content-Type");
        }

        @Override
        /** 获取Content获取长度 */
        public long getContentLength() {
            return bodyBytes != null ? bodyBytes.length : 0;
        }

        @Override
        /** 获取Body */
        public byte[] getBody() {
            return bodyBytes;
        }

        @Override
        /** 获取BodyString */
        public String getBodyString() {
            return bodyBytes != null ? new String(bodyBytes, StandardCharsets.UTF_8) : "";
        }

        @Override
        /** 获取InputStream */
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(getBody());
        }

        @Override
        /** 获取RemoteAddress */
        public String getRemoteAddress() {
            return ctx.request().remoteAddress().host();
        }

        @Override
        /** 获取RemotePort */
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
        /** 获取FormData */
        public Map<String, String> getFormData() {
            Map<String, String> form = new java.util.LinkedHashMap<>();
            var req = ctx.request();
            if (req.formAttributes() != null) {
                req.formAttributes().forEach(e -> form.put(e.getKey(), e.getValue()));
            }
            return form;
        }

        @Override
        /** 获取Files */
        public List<FormFile> getFiles() {
            List<io.vertx.ext.web.FileUpload> uploads = ctx.fileUploads();
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