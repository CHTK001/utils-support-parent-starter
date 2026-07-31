package com.chua.remote.support.gateway.transport.http;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.protocol.support.network.protocol.filter.ServletFilter;
import com.chua.protocol.support.network.protocol.request.HttpServletRequest;
import com.chua.protocol.support.network.protocol.request.HttpServletResponse;
import com.chua.protocol.support.network.protocol.request.RequestAttributes;
import com.chua.protocol.support.network.protocol.request.RequestHeaders;
import com.chua.protocol.support.network.protocol.request.RequestParameters;
import com.chua.protocol.support.network.protocol.request.ServletRequest;
import com.chua.protocol.support.network.protocol.request.ServletResponse;
import com.chua.protocol.support.network.protocol.server.DefaultServletFilterChain;
import com.chua.protocol.support.network.protocol.server.ServletFilterChain;
import com.chua.remote.support.gateway.config.Protocol;
import com.chua.remote.support.gateway.core.router.TargetEntry;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP API gateway proxy adapter backed by the protocol servlet filter chain. * @author CH
 */
@Slf4j
class ApiGatewayProxyFilterForwarder {

    // a t t r_ d i s c o v e r y
    private static final String ATTR_DISCOVERY = "ServiceDiscoveryServletFilter:discovery";
    // a t t r_ s e r v i c e_ p a t h
    private static final String ATTR_SERVICE_PATH = "ServiceDiscoveryServletFilter:servicePath";
    // a t t r_ t i m e o u t
    private static final String ATTR_TIMEOUT = "ServiceDiscoveryServletFilter:timeout";
    // a t t r_ p r o t o c o l
    private static final String ATTR_PROTOCOL = "ServiceDiscoveryServletFilter:protocol";
    // a t t r_ p r o t o c o l_ t y p e
    private static final String ATTR_PROTOCOL_TYPE = "ServiceDiscoveryServletFilter:protocolType";
    // a t t r_ t a r g e t_ i d
    private static final String ATTR_TARGET_ID = "ApiGatewayProxy:targetId";
    // d e f a u l t_ t i m e o u t_ m i l l i s
    private static final int DEFAULT_TIMEOUT_MILLIS = 30000;
    // p r o x y_ e x e c u t o r
    private static final ExecutorService PROXY_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of("connection",
            "proxy-connection",
            "proxy-authorization",
            "proxy-authenticate",
            "keep-alive",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade");
    private static final Set<String> RESTRICTED_REQUEST_HEADERS = Set.of("host",
            "content-length",
            "expect");
    private static final Set<String> RESPONSE_HEADERS_ALLOW_MULTIPLE = Set.of("set-cookie",
            "www-authenticate");

    private final TargetRegistry targetRegistry;
    private final HttpClient httpClient;

    ApiGatewayProxyFilterForwarder(TargetRegistry targetRegistry) {
        this.targetRegistry = targetRegistry;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(10000))
                .build();
    }

    void forward(ChannelHandlerContext ctx, FullHttpRequest nettyRequest, String targetId) {
        HttpServletRequest request;
        try {
            request = toServletRequest(ctx, nettyRequest);
        }
 catch (Exception e) {
            log.warn("[HttpApi] convert proxy request failed: {}", e.getMessage(), e);
            writeJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"bad proxy request\"}");
            return;
        }

        PROXY_EXECUTOR.execute(() -> {
            HttpServletResponse response = createResponse(request);
            try {
                List<ServletFilter> filters = List.of(
                        new TargetRegistryDiscoveryFilter(targetRegistry, targetId),
                        new TerminalHttpProxyFilter(httpClient)
                );
                new DefaultServletFilterChain(filters).doFilter(request, response);
                writeServletResponse(ctx, response);
            }
 catch (Exception e) {
                log.warn("[HttpApi] proxy filter chain failed: targetId={}, error={}", targetId, e.getMessage(), e);
                writeJson(ctx, HttpResponseStatus.BAD_GATEWAY,
                        "{\"error\":\"backend connection failed: " + escapeJson(safeMessage(e)) + "\"}");
            }
 finally {
                closeQuietly(request);
                closeQuietly(response);
            }
        });
    }

    private HttpServletRequest toServletRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        RequestHeaders headers = new RequestHeaders();
        java.util.Iterator<? extends Map.Entry<? extends CharSequence, ? extends CharSequence>> iterator =
                request.headers().iteratorCharSequence();
        while (iterator.hasNext()) {
            Map.Entry<? extends CharSequence, ? extends CharSequence> entry = iterator.next();
            if (entry.getKey() != null && entry.getValue() != null) {
                headers.add(entry.getKey().toString(), entry.getValue().toString());
            }
        }

        RequestParameters parameters = new RequestParameters();
        decoder.parameters().forEach((name, values) -> {
            if (values != null) {
                for (String value : values) {
                    parameters.add(name, value);
                }
            }
        });

        byte[] body = copyBody(request.content());
        InetSocketAddress local = asInetSocketAddress(ctx.channel().localAddress());
        InetSocketAddress remote = asInetSocketAddress(ctx.channel().remoteAddress());

        return HttpServletRequest.builder()
                .requestId(java.util.UUID.randomUUID().toString())
                .method(request.method().name())
                .url(request.uri())
                .path(defaultPath(decoder.path()))
                .queryString(rawQuery(request.uri()))
                .protocolVersion(request.protocolVersion().text())
                .serverIp(addressHost(local, "127.0.0.1"))
                .serverPort(addressPort(local, 0))
                .clientIp(addressHost(remote, "127.0.0.1"))
                .clientPort(addressPort(remote, 0))
                .contentType(headers.getContentType())
                .contentLength((long) body.length)
                .headers(headers)
                .parameters(parameters)
                .attributes(new RequestAttributes())
                .body(body)
                .requestTime(LocalDateTime.now())
                .build();
    }

    private static HttpServletResponse createResponse(HttpServletRequest request) {
        return HttpServletResponse.builder()
                .requestId(request.getRequestId())
                .statusCode(200)
                .statusMessage("OK")
                .success(true)
                .headers(new RequestHeaders())
                .build();
    }

    private static byte[] copyBody(ByteBuf content) {
        if (content == null || content.readableBytes() <= 0) {
            return new byte[0];
        }
        byte[] body = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), body);
        return body;
    }

    private static String defaultPath(String path) {
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static String rawQuery(String uri) {
        if (uri == null) {
            return null;
        }
        int queryIndex = uri.indexOf('?');
        if (queryIndex < 0 || queryIndex == uri.length() - 1) {
            return null;
        }
        int fragmentIndex = uri.indexOf('#', queryIndex + 1);
        return uri.substring(queryIndex + 1, fragmentIndex > queryIndex ? fragmentIndex : uri.length());
    }

    private static InetSocketAddress asInetSocketAddress(java.net.SocketAddress address) {
        return address instanceof InetSocketAddress inetSocketAddress ? inetSocketAddress : null;
    }

    private static String addressHost(InetSocketAddress address, String fallback) {
        if (address == null) {
            return fallback;
        }
        if (address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return address.getHostString();
    }

    private static int addressPort(InetSocketAddress address, int fallback) {
        return address != null ? address.getPort() : fallback;
    }

    private void writeServletResponse(ChannelHandlerContext ctx, HttpServletResponse servletResponse) {
        int statusCode = servletResponse.getStatusCode() > 0 ? servletResponse.getStatusCode() : 200;
        byte[] body = servletResponse.getBody();
        if (body == null) {
            body = new byte[0];
        }
        ByteBuf content = Unpooled.copiedBuffer(body);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(statusCode),
                content
        );
        copyResponseHeaders(servletResponse, response.headers());
        if (!response.headers().contains(HttpHeaderNames.CONTENT_TYPE) && servletResponse.getContentType() != null) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, servletResponse.getContentType());
        }
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        setCorsHeaders(response.headers());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void copyResponseHeaders(HttpServletResponse servletResponse, HttpHeaders targetHeaders) {
        RequestHeaders responseHeaders = servletResponse.getHeaders();
        if (responseHeaders == null || responseHeaders.isEmpty()) {
            return;
        }
        for (String name : responseHeaders.getNames()) {
            if (name == null) {
                continue;
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP_HEADERS.contains(normalized) || "content-length".equals(normalized)) {
                continue;
            }
            List<String> values = responseHeaders.getAll(name);
            if (RESPONSE_HEADERS_ALLOW_MULTIPLE.contains(normalized)) {
                for (String value : values) {
                    if (value != null) {
                        targetHeaders.add(name, value);
                    }
                }
            } else if (values != null && !values.isEmpty() && values.get(0) != null) {
                targetHeaders.set(name, values.get(0));
            }
        }
    }

    private static void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        ByteBuf content = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        setCorsHeaders(response.headers());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void setCorsHeaders(HttpHeaders headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, X-Target-Id");
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        }
 catch (Exception ignored) {
        }
    }

    private static String safeMessage(Exception e) {
        return e == null || e.getMessage() == null || e.getMessage().isBlank() ? "unknown error" : e.getMessage();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static final class TargetRegistryDiscoveryFilter implements ServletFilter {
        private final TargetRegistry targetRegistry;
        private final String targetId;

        private TargetRegistryDiscoveryFilter(TargetRegistry targetRegistry, String targetId) {
            this.targetRegistry = targetRegistry;
            this.targetId = targetId;
        }

        /**
         * doFilter
         * @param request 参数
         * @param response 参数
         * @param chain 参数
         */
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
            TargetEntry target = targetRegistry.lookup(targetId);
            if (target == null) {
                writeFilterError(response, 502, "target offline or not found: " + targetId);
                return;
            }
            if (target.getPort() <= 0 || target.getHost() == null || target.getHost().isBlank()) {
                writeFilterError(response, 502, "target address invalid: " + targetId);
                return;
            }
            String protocol = resolveProtocol(target);
            if (protocol == null) {
                writeFilterError(response, 400, "target is not HTTP service: " + targetId);
                return;
            }

            Discovery discovery = Discovery.builder()
                    .id(target.getTargetId())
                    .serverId(target.getAgentId())
                    .host(target.getHost())
                    .port(target.getPort())
                    .protocol(protocol)
                    .timeout(DEFAULT_TIMEOUT_MILLIS)
                    .metadata(target.getMetadata())
                    .build();
            request.setAttribute(ATTR_DISCOVERY, discovery);
            request.setAttribute(ATTR_SERVICE_PATH, "");
            request.setAttribute(ATTR_TIMEOUT, DEFAULT_TIMEOUT_MILLIS);
            request.setAttribute(ATTR_PROTOCOL, protocol);
            request.setAttribute(ATTR_PROTOCOL_TYPE, "http");
            request.setAttribute(ATTR_TARGET_ID, targetId);
            request.setAttribute("proxy.target.host", target.getHost());
            request.setAttribute("proxy.target.port", target.getPort());
            chain.doFilter(request, response);
        }

        /**
         * getFilterId
         * @return getFilterId结果
         */
        @Override
        public String getFilterId() {
            return "api-gateway-target-discovery";
        }

        /**
         * 获取过滤器名称
         * @return 获取过滤器名称结果
         */
        @Override
        public String getFilterName() {
            return "ApiGatewayTargetDiscoveryFilter";
        }

        /**
         * 获取执行顺序
         * @return 获取执行顺序结果
         */
        @Override
        public int getOrder() {
            return 10;
        }

        private static String resolveProtocol(TargetEntry target) {
            Protocol protocol = target.getProtocol();
            if (protocol == Protocol.HTTPS) {
                return "https";
            }
            if (protocol == null || protocol == Protocol.HTTP || protocol == Protocol.HTTP2) {
                return "http";
            }
            return null;
        }

        private static void writeFilterError(ServletResponse response, int status, String message) {
            response.setStatusCode(status);
            response.setStatusMessage(status >= 500 ? "Bad Gateway" : "Bad Request");
            response.setSuccess(false);
            response.setContentType("application/json; charset=utf-8");
            response.setBody(("{\"error\":\"" + escapeJson(message) + "\"}").getBytes(StandardCharsets.UTF_8));
            response.setTerminateEarly(true);
        }
    }

    private static final class TerminalHttpProxyFilter implements ServletFilter {
        private final HttpClient httpClient;

        private TerminalHttpProxyFilter(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        /**
         * doFilter
         * @param request 参数
         * @param response 参数
         * @param chain 参数
         */
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
            executeProxy(HttpServletRequest.from(request), HttpServletResponse.from(response));
        }

        /**
         * getFilterId
         * @return getFilterId结果
         */
        @Override
        public String getFilterId() {
            return "api-gateway-http-proxy-terminal";
        }

        /**
         * 获取过滤器名称
         * @return 获取过滤器名称结果
         */
        @Override
        public String getFilterName() {
            return "ApiGatewayHttpProxyTerminalFilter";
        }

        /**
         * 获取执行顺序
         * @return 获取执行顺序结果
         */
        @Override
        public int getOrder() {
            return Integer.MAX_VALUE;
        }

        private void executeProxy(HttpServletRequest request, HttpServletResponse response) throws Exception {
            URI targetUri = resolveTargetUri(request);
            if (targetUri == null) {
                writeFilterError(response, 400, "missing target host");
                return;
            }

            byte[] body = request.getBody() != null ? request.getBody() : new byte[0];
            String method = request.getMethod() == null ? "GET" : request.getMethod();
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(targetUri)
                    .timeout(Duration.ofMillis(resolveTimeout(request)))
                    .method(method, body.length > 0 ? BodyPublishers.ofByteArray(body) : BodyPublishers.noBody());

            copyRequestHeaders(request, builder);
            java.net.http.HttpResponse<byte[]> proxyResponse = httpClient.send(builder.build(), BodyHandlers.ofByteArray());
            applyProxyResponse(response, proxyResponse);
        }

        private URI resolveTargetUri(HttpServletRequest request) {
            Object discoveryObject = request.getAttribute(ATTR_DISCOVERY);
            if (!(discoveryObject instanceof Discovery discovery)) {
                return null;
            }
            String host = discovery.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            String path = request.getPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            String query = request.getQueryString();

            StringBuilder uri = new StringBuilder();
            if (host.startsWith("http://") || host.startsWith("https://")) {
                uri.append(host);
            }
 else {
                String protocol = discovery.getProtocol();
                if (protocol == null || protocol.isBlank()) {
                    protocol = discovery.getPort() == 443 ? "https" : "http";
                }
                uri.append(protocol).append("://").append(host);
                if (discovery.getPort() > 0) {
                    uri.append(":").append(discovery.getPort());
                }
            }
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            if (uri.charAt(uri.length() - 1) == '/' && path.startsWith("/")) {
                uri.append(path.substring(1));
            }
 else {
                uri.append(path);
            }
            if (query != null && !query.isBlank()) {
                uri.append("?").append(query);
            }
            return URI.create(uri.toString());
        }

        private void copyRequestHeaders(HttpServletRequest request, java.net.http.HttpRequest.Builder builder) {
            RequestHeaders headers = request.getHeaders();
            if (headers == null || headers.isEmpty()) {
                return;
            }
            for (String name : headers.getNames()) {
                if (name == null) {
                    continue;
                }
                String normalized = name.toLowerCase(Locale.ROOT);
                if (HOP_BY_HOP_HEADERS.contains(normalized) || RESTRICTED_REQUEST_HEADERS.contains(normalized)) {
                    continue;
                }
                for (String value : headers.getAll(name)) {
                    if (value != null) {
                        builder.header(name, value);
                    }
                }
            }
        }

        private long resolveTimeout(HttpServletRequest request) {
            Object timeout = request.getAttribute(ATTR_TIMEOUT);
            if (timeout instanceof Number number && number.longValue() > 0) {
                return number.longValue();
            }
            if (timeout instanceof String text) {
                try {
                    long value = Long.parseLong(text);
                    if (value > 0) {
                        return value;
                    }
                }
 catch (NumberFormatException ignored) {
                }
            }
            return DEFAULT_TIMEOUT_MILLIS;
        }

        private void applyProxyResponse(HttpServletResponse response, java.net.http.HttpResponse<byte[]> proxyResponse) {
            int status = proxyResponse.statusCode();
            response.setStatusCode(status);
            response.setStatusMessage(status >= 200 && status < 300 ? "OK" : "ERROR");
            response.setSuccess(status >= 200 && status < 400);

            RequestHeaders headers = response.getHeaders();
            if (headers == null) {
                headers = new RequestHeaders();
                response.setHeaders(headers);
            }
            headers.clear();

            for (Map.Entry<String, List<String>> entry : proxyResponse.headers().map().entrySet()) {
                String name = entry.getKey();
                List<String> values = entry.getValue();
                if (name == null || values == null) {
                    continue;
                }
                String normalized = name.toLowerCase(Locale.ROOT);
                if (HOP_BY_HOP_HEADERS.contains(normalized) || "content-length".equals(normalized)) {
                    continue;
                }
                if (RESPONSE_HEADERS_ALLOW_MULTIPLE.contains(normalized)) {
                    for (String value : values) {
                        if (value != null) {
                            headers.add(name, value);
                        }
                    }
                }
 else {
                    for (String value : values) {
                        if (value != null) {
                            headers.set(name, value);
                            break;
                        }
                    }
                }
                if ("content-type".equalsIgnoreCase(name) && !values.isEmpty() && values.get(0) != null) {
                    response.setContentType(values.get(0));
                }
            }

            byte[] body = proxyResponse.body();
            response.setBody(body == null ? new byte[0] : body);
            response.setContentLength(body == null ? 0L : (long) body.length);
        }

        private static void writeFilterError(HttpServletResponse response, int status, String message) {
            response.setStatusCode(status);
            response.setStatusMessage(status >= 500 ? "Bad Gateway" : "Bad Request");
            response.setSuccess(false);
            response.setContentType("application/json; charset=utf-8");
            response.setBody(("{\"error\":\"" + escapeJson(message) + "\"}").getBytes(StandardCharsets.UTF_8));
            response.setTerminateEarly(true);
        }
    }
}
