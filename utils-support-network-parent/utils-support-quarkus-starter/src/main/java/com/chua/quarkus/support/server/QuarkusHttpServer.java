package com.chua.quarkus.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.FormFile;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* 基于 Vert.x 的 Quarkus 风格 HTTP 服务器实现，支持同步阻塞和响应式两种模式。
*
* <p>Quarkus 框架底层使用 Vert.x 作为 HTTP 引擎，本实现提供与 Quarkus 兼容的
* HTTP 服务器功能。通过 SPI 键 {@code quarkus} 或 {@code quarkus-http} 选取。</p>
*
* <p>同步模式（默认）：过滤器链在 Vert.x Worker 线程池中执行。</p>
* <p>响应式模式（{@link ServerSetting#isReactor()} = true）：过滤器链在 Vert.x EventLoop 线程上执行。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi({"quarkus-http", "quarkus"})
public class QuarkusHttpServer extends AbstractServer {

    /**
    * Vert.x 实例
     */
    private Vertx vertx;

    /**
    * Vert.x HTTP 服务器
     */
    private io.vertx.core.http.HttpServer server;

    /**
    * 是否使用 事件循环 线程（响应式模式）。
     */
    private boolean reactive;

    /**
    * 创建 quarkushttp服务端 实例
    * @param setting setting
     */
    public QuarkusHttpServer(ServerSetting setting) {
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
        int eventLoopPoolSize = Math.max(setting.getBossThreads(), 2);
        int workerPoolSize = Math.max(setting.getWorkerThreads(), Runtime.getRuntime().availableProcessors() * 4);

        VertxOptions opts = new VertxOptions()
                .setEventLoopPoolSize(eventLoopPoolSize)
                .setWorkerPoolSize(workerPoolSize)
                .setPreferNativeTransport(true);
        vertx = Vertx.vertx(opts);

        HttpServerOptions httpOpts = new HttpServerOptions()
                .setHost(setting.getHost())
                .setPort(setting.getPort())
                .setMaxHeaderSize((int) setting.getMaxRequestSize())
                .setAcceptBacklog(Math.max(setting.getBacklog(), 2048))
                .setTcpFastOpen(true)
                .setTcpNoDelay(setting.isTcpNoDelay())
                .setReusePort(setting.isSoReuseAddr())
                .setLogActivity(false);

        if (setting.getSsl() != null && setting.getSsl().isEnabled()) {
            httpOpts.setSsl(true);
            ServerSetting.SslConfig ssl = setting.getSsl();
            if (ssl.getCertPath() != null && ssl.getKeyPath() != null) {
                httpOpts.setKeyCertOptions(new PemKeyCertOptions()
                        .setKeyPath(ssl.getKeyPath())
                        .setCertPath(ssl.getCertPath()));
            }
        }

        server = vertx.createHttpServer(httpOpts);
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create()
                .setBodyLimit(setting.getMaxRequestSize())
                .setHandleFileUploads(true));

        router.route().handler(ctx -> {
            QuarkusServerRequest request = new QuarkusServerRequest(ctx);
            QuarkusServerResponse response = new QuarkusServerResponse(ctx);
            if (reactive) {
                doHandle(request, response);
                if (!response.isCommitted()) {
                    response.endVertx();
                }
            } else {
                ctx.vertx().executeBlocking(() -> {
                    doHandle(request, response);
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
        // port=0 时 Vert.x 自动分配随机端口，需同步回 setting 供 getPort() 返回正确值
        if (setting.getPort() == 0) {
            setting.setPort(server.actualPort());
        }
        log.info("Quarkus HttpServer started on {}:{}", setting.getHost(), setting.getPort());
    }

    /**
    * 执行处理
    *
    * @param request 请求
    * @param response 响应
     */
    private void doHandle(QuarkusServerRequest request, QuarkusServerResponse response) {
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
        if (vertx != null) {
            try {
                vertx.close().toCompletionStage().toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }
        log.info("Quarkus HttpServer stopped");
    }

    // ======================== Response ========================
    /**
    * quarkus服务端响应类。
    *
    * @author CH
    * @since 4.0.0
     */

    static class QuarkusServerResponse implements ServerResponse {

        /**
        * ctx
         */
        private final RoutingContext ctx;
        /**
        * 状态
         */
        private int status = 200;
        /**
        * 数据内容
         */
        private byte[] body;
        /**
        * 头部
         */
        private final Map<String, String> headers = new ConcurrentHashMap<>();
        /**
        * 内容 类型
         */
        private String contentType;
        /**
        * committed
         */
        private boolean committed;
        /**
        * 结束
         */
        private boolean ended;
        /**
        * 结果
         */
        private Object result;
        /**
        * sse Mode
         */
        private boolean sseMode;

        QuarkusServerResponse(RoutingContext ctx) {
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
            return new java.io.ByteArrayOutputStream();
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
            byte[] resolvedBody = body != null ? body : resolveResult(result);
            if (resolvedBody != null) {
                resp.end(Buffer.buffer(resolvedBody));
            } else {
                resp.end();
            }
        }
    }

        /**
        * 由 设置结果 设置的结果对象派生出响应体字节（对齐 抽象服务端#转换结果 语义）：
        * 字符串 → UTF-8、byte[] → 原样、路径 → 文件字节、其他 → 转为字符串() 字节。
        * 仅在 主体 未显式设置时生效，避免覆盖 设置主体。
        *
        * @param r 处理器 通过 设置结果 设置的结果对象
        * @return 派生的响应体字节；r 为 空 返回 空
         */
        private static byte[] resolveResult(Object r) {
            if (r == null) {
                return null;
            }
            if (r instanceof String s) {
                return s.getBytes(StandardCharsets.UTF_8);
            }
            if (r instanceof byte[] b) {
                return b;
            }
            if (r instanceof java.nio.file.Path p) {
                try { return java.nio.file.Files.readAllBytes(p); } catch (Exception e) { return null; }
            }
            return r.toString().getBytes(StandardCharsets.UTF_8);
        }

    // ======================== Request ========================
    /**
    * quarkus服务端请求类。
    *
    * @author CH
    * @since 4.0.0
     */

    static class QuarkusServerRequest implements ServerRequest {

        /**
        * ctx
         */
        private final RoutingContext ctx;
        /**
        * 内容 Bytes
         */
        private byte[] bodyBytes;
        /**
        * attributes
         */
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        QuarkusServerRequest(RoutingContext ctx) {
            this.ctx = ctx;
 // 预缓存 主体
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
