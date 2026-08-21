package com.chua.armeria.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.FormFile;
import com.chua.common.support.network.server.request.MultipartParser;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Armeria 的 HTTP 服务器实现。
 *
 * <p>继承 {@link AbstractServer}，通过 SPI 以键 {@code armeria-http} 注册。
 * Armeria 是一款高性能异步 HTTP 服务器，相比 JDK HttpServer 具备更高的并发能力。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"armeria-http"})
public class ArmeriaHttpServer extends AbstractServer {

    /**
     * Armeria 服务端实例
     */
    private com.linecorp.armeria.server.Server server;

    /**
     * 创建 ArmeriaHttpServer 实例
     * @param setting setting
     */
    public ArmeriaHttpServer(ServerSetting setting) {
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

    /**
     * 请求处理线程池，使用虚拟线程避免阻塞 event loop
     */
    private final java.util.concurrent.ExecutorService requestExecutor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    @Override
    /** Do开始 */
    protected void doStart() {
        ServerBuilder sb = com.linecorp.armeria.server.Server.builder();

        if (setting.getSsl() != null && setting.getSsl().isEnabled()) {
            ServerSetting.SslConfig ssl = setting.getSsl();
            if (ssl.getCertPath() != null && ssl.getKeyPath() != null) {
                try {
                    sb.tls(
                            new java.io.File(ssl.getCertPath()),
                            new java.io.File(ssl.getKeyPath())
                    );
                } catch (Exception e) {
                    throw new RuntimeException("SSL 配置失败", e);
                }
            }
        }

        sb.http(setting.getPort());
        sb.maxRequestLength(setting.getMaxRequestSize());
        // 压测场景下认证可能耗时较长,提高请求超时避免被提前断开
        sb.requestTimeout(java.time.Duration.ofSeconds(60));

        sb.serviceUnder("/", (ctx, req) ->
            HttpResponse.from(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    var aggReq = req.aggregate().get();
                    ArmeriaServerRequest request = new ArmeriaServerRequest(ctx, aggReq);
                    ArmeriaServerResponse response = new ArmeriaServerResponse(ctx);
                    var stage = handleRequestWithStage(request, response);
                    // 等待响应式链路完成(若走了 reactive 链),再构建响应,避免竞态
                    if (stage != null) {
                        stage.toCompletableFuture().join();
                    }
                    response.endArmeria();
                    return response.buildAggregatedResponse().toHttpResponse();
                } catch (Exception e) {
                    log.warn("请求聚合失败: {}", e.getMessage(), e);
                    return com.linecorp.armeria.common.HttpResponse.of(500);
                }
            }, requestExecutor))
        );

        server = sb.build();
        server.start().join();
        log.info("Armeria HttpServer started on port {}", setting.getPort());
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        requestExecutor.shutdown();
        if (server != null) {
            server.stop().join();
            log.info("Armeria HttpServer stopped");
        }
    }

    // ======================== Response ========================

    /**
     * Armeria 响应封装，实现 {@link ServerResponse} 接口。
     *
     * <p>内部维护响应状态、头、体，最终由 {@link #buildAggregatedResponse()} 生成
     * Armeria 的 {@link AggregatedHttpResponse} 对象。</p>
     *
     */
    static class ArmeriaServerResponse implements ServerResponse {

        /**
         * Armeria 服务请求上下文
         */
        private final com.linecorp.armeria.server.ServiceRequestContext ctx;

        /**
         * HTTP 状态码，默认 200
         */
        private int status = 200;

        /**
         * 响应体字节数组
         */
        private byte[] body;

        /**
         * 响应头键值对
         */
        private final Map<String, String> headers = new ConcurrentHashMap<>();

        /**
         * Content-Type 值
         */
        private String contentType;

        /**
         * 是否已提交（禁止修改）
         */
        private boolean committed;

        /**
         * 是否已结束
         */
        private boolean ended;

        /**
         * 响应结果对象（供后续序列化使用）
         */
        private Object result;

        ArmeriaServerResponse(com.linecorp.armeria.server.ServiceRequestContext ctx) {
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
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        /** 设置Result */
        public ServerResponse setResult(Object r) {
            this.result = r;
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
            endArmeria();
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
            endArmeria();
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
        }

        /**
         * 标记 Armeria 层面的响应为已完成，禁止后续修改。
         */
        void endArmeria() {
            if (committed) {
                return;
            }
            committed = true;
            ended = true;
        }

        /**
         * 构建 Armeria {@link AggregatedHttpResponse}。
         *
         * @return 聚合响应对象
         */
        AggregatedHttpResponse buildAggregatedResponse() {
            ResponseHeadersBuilder hdrs = ResponseHeaders.builder(status);
            headers.forEach((k, v) -> hdrs.add(k, v));
            if (contentType != null) {
                hdrs.contentType(MediaType.parse(contentType));
            }
            HttpData data = body != null ? HttpData.wrap(body) : HttpData.empty();
            return AggregatedHttpResponse.of(hdrs.build(), data);
        }
    }

    // ======================== Request ========================

    /**
     * Armeria 请求封装，实现 {@link ServerRequest} 接口。
     *
     * <p>包装 Armeria 的 {@link com.linecorp.armeria.common.AggregatedHttpRequest}，
     * 提供统一的请求属性、参数、表单、文件上传等访问能力。</p>
     *
     */
    static class ArmeriaServerRequest implements ServerRequest {

        /**
         * Armeria 服务请求上下文
         */
        private final com.linecorp.armeria.server.ServiceRequestContext ctx;
        /**
         * 聚合后的 HTTP 请求
         */
        private final com.linecorp.armeria.common.AggregatedHttpRequest aggReq;

        /**
         * 请求体字节数组
         */
        private byte[] bodyBytes;

        /**
         * 请求属性映射
         */
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        ArmeriaServerRequest(
                com.linecorp.armeria.server.ServiceRequestContext ctx,
                com.linecorp.armeria.common.AggregatedHttpRequest aggReq) {
            this.ctx = ctx;
            this.aggReq = aggReq;
            if (aggReq.content().isEmpty()) {
                this.bodyBytes = new byte[0];
            } else {
                this.bodyBytes = aggReq.content().array();
            }
        }

        @Override
        /** 获取Uri */
        public String getUri() {
            return ctx.request().uri().toString();
        }

        @Override
        /** 获取Path */
        public String getPath() {
            return ctx.path();
        }

        @Override
        /** 获取Method */
        public HttpMethod getMethod() {
            return HttpMethod.valueOf(ctx.method().name());
        }

        @Override
        /** 获取Header */
        public String getHeader(String name) {
            return aggReq.headers().get(name);
        }

        @Override
        /** 获取Headers */
        public HttpHeader getHeaders() {
            HttpHeader h = HttpHeader.create();
            aggReq.headers().forEach(e -> h.add(e.getKey().toString(), e.getValue()));
            return h;
        }

        @Override
        /** 获取Params */
        public Map<String, String> getParams() {
            Map<String, String> result = new java.util.LinkedHashMap<>();
            ctx.queryParams().forEach((k, v) -> result.put(k, v));
            return result;
        }

        @Override
        /** 获取Param */
        public String getParam(String name) {
            return ctx.queryParam(name);
        }

        @Override
        /** 获取ContentType */
        public String getContentType() {
            var ct = aggReq.headers().contentType();
            if (ct != null) {
                return ct.toString();
            }
            return "";
        }

        @Override
        /** 获取Content获取长度 */
        public long getContentLength() {
            if (bodyBytes != null) {
                return bodyBytes.length;
            }
            return 0;
        }

        @Override
        /** 获取Body */
        public byte[] getBody() {
            return bodyBytes;
        }

        @Override
        /** 获取BodyString */
        public String getBodyString() {
            if (bodyBytes != null) {
                return new String(bodyBytes, StandardCharsets.UTF_8);
            }
            return "";
        }

        @Override
        /** 获取InputStream */
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(getBody());
        }

        @Override
        /** 获取RemoteAddress */
        public String getRemoteAddress() {
            return ctx.remoteAddress().getAddress().getHostAddress();
        }

        @Override
        /** 获取RemotePort */
        public int getRemotePort() {
            return ctx.remoteAddress().getPort();
        }

        @Override
        /** 获取Attributes */
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        /** 获取Attribute */
        public Object getAttribute(String n) {
            return attributes.get(n);
        }

        @Override
        /** 设置Attribute */
        public void setAttribute(String n, Object v) {
            attributes.put(n, v);
        }

        @Override
        /** 获取FormData */
        public Map<String, String> getFormData() {
            String ct = getContentType();
            if (ct == null) {
                return Map.of();
            }
            String lower = ct.toLowerCase();
            if (lower.startsWith("application/x-www-form-urlencoded")) {
                String qs = aggReq.content().toStringUtf8();
                if (qs.isEmpty()) {
                    return Map.of();
                }
                Map<String, String> form = new java.util.LinkedHashMap<>();
                for (String pair : qs.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length > 0) {
                        try {
                            form.put(
                                    java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name()),
                                    kv.length > 1
                                            ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name())
                                            : "");
                        } catch (Exception e) {
                            // 忽略解码异常
                        }
                    }
                }
                return form;
            }
            if (lower.startsWith("multipart/form-data")) {
                MultipartParser parser = ServiceProvider.of(MultipartParser.class).getExtension("fileupload");
                if (parser != null) {
                    return parser.parseFormFields(bodyBytes, ct);
                }
            }
            return Map.of();
        }

        @Override
        /** 获取Files */
        public List<FormFile> getFiles() {
            String ct = getContentType();
            if (ct == null || !ct.toLowerCase().startsWith("multipart/form-data")) {
                return List.of();
            }
            MultipartParser parser = ServiceProvider.of(MultipartParser.class).getExtension("fileupload");
            if (parser == null) {
                return List.of();
            }
            return parser.parse(bodyBytes, ct);
        }
    }
}
