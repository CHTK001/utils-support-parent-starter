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

    private Vertx vertx;
    private io.vertx.core.http.HttpServer server;
    private boolean reactive;

    public VertxHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    public boolean supportsReactor() {
        return true;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    @Override
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
                .setAcceptBacklog(Math.max(setting.getBacklog(), 128))
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
            // 无响应式过滤器(纯同步 ServerFilter 链,如压测场景):链执行放 worker 池,
            // EventLoop 只做 IO 收发,避免同步链占满事件循环线程
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
                // 同步链(或无响应式过滤器):worker 池执行,EventLoop 不阻塞
                ctx.vertx().executeBlocking(() -> {
                    handleRequestAsync(request, response);
                    return null;
                }, false).onComplete(ar -> {
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

        private final RoutingContext ctx;
        private int status = 200;
        private byte[] body;
        private final Map<String, String> headers = new ConcurrentHashMap<>();
        private String contentType;
        private boolean committed;
        private boolean ended;
        private Object result;
        private boolean sseMode;

        VertxServerResponse(RoutingContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public ServerResponse setStatus(int code) {
            if (!committed) {
                this.status = code;
            }
            return this;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public ServerResponse setHeader(String name, String value) {
            if (!committed) {
                headers.put(name, value);
            }
            return this;
        }

        @Override
        public String getHeader(String name) {
            return headers.get(name);
        }

        @Override
        public HttpHeader getHeaders() {
            HttpHeader h = HttpHeader.create();
            headers.forEach(h::add);
            return h;
        }

        @Override
        public ServerResponse setContentType(String ct) {
            this.contentType = ct;
            return this;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public ServerResponse setBody(byte[] b) {
            if (!committed) {
                this.body = b;
            }
            return this;
        }

        @Override
        public ServerResponse setBody(String b) {
            if (!committed) {
                this.body = b != null ? b.getBytes(StandardCharsets.UTF_8) : null;
            }
            return this;
        }

        @Override
        public byte[] getBody() {
            return body;
        }

        @Override
        public OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        public ServerResponse setResult(Object result) {
            this.result = result;
            return this;
        }

        @Override
        public Object getResult() {
            return result;
        }

        @Override
        public ServerResponse sendRedirect(String location) {
            setStatus(302);
            headers.put("Location", location);
            ended = true;
            endVertx();
            return this;
        }

        @Override
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
        public void flush() {
        }

        @Override
        public boolean isCommitted() {
            return committed;
        }

        @Override
        public boolean isEnded() {
            return ended;
        }

        @Override
        public void end() {
            if (ended) {
                return;
            }
            ended = true;
        }

        @Override
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
        public void writeRaw(byte[] bytes) {
            if (committed) {
                return;
            }
            ctx.response().write(Buffer.buffer(bytes));
        }

        @Override
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
            if (body != null) {
                resp.end(Buffer.buffer(body));
            } else {
                resp.end();
            }
        }
    }

    static class VertxServerRequest implements ServerRequest {

        private final RoutingContext ctx;
        private byte[] bodyBytes;
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
        public String getUri() {
            return ctx.request().uri();
        }

        @Override
        public String getPath() {
            return ctx.request().path();
        }

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.valueOf(ctx.request().method().name());
        }

        @Override
        public String getHeader(String name) {
            return ctx.request().getHeader(name);
        }

        @Override
        public HttpHeader getHeaders() {
            HttpHeader h = HttpHeader.create();
            ctx.request().headers().forEach(e -> h.add(e.getKey(), e.getValue()));
            return h;
        }

        @Override
        public Map<String, String> getParams() {
            Map<String, String> params = new java.util.HashMap<>();
            ctx.request().params().forEach(e -> params.put(e.getKey(), e.getValue()));
            return params;
        }

        @Override
        public String getParam(String name) {
            return ctx.request().getParam(name);
        }

        @Override
        public String getContentType() {
            return ctx.request().getHeader("Content-Type");
        }

        @Override
        public long getContentLength() {
            return bodyBytes != null ? bodyBytes.length : 0;
        }

        @Override
        public byte[] getBody() {
            return bodyBytes;
        }

        @Override
        public String getBodyString() {
            return bodyBytes != null ? new String(bodyBytes, StandardCharsets.UTF_8) : "";
        }

        @Override
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(getBody());
        }

        @Override
        public String getRemoteAddress() {
            return ctx.request().remoteAddress().host();
        }

        @Override
        public int getRemotePort() {
            return ctx.request().remoteAddress().port();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public Map<String, String> getFormData() {
            Map<String, String> form = new java.util.LinkedHashMap<>();
            var req = ctx.request();
            if (req.formAttributes() != null) {
                req.formAttributes().forEach(e -> form.put(e.getKey(), e.getValue()));
            }
            return form;
        }

        @Override
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