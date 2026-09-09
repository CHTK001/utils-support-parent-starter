package com.chua.common.support.network.protocol.server.impl;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.request.HttpServletRequest;
import com.chua.common.support.network.protocol.request.HttpServletResponse;
import com.chua.common.support.network.protocol.request.FormPart;
import com.chua.common.support.network.protocol.request.RequestParameters;
import com.chua.common.support.network.protocol.request.RequestHeaders;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.server.AbstractProtocolServer;
import com.chua.common.support.network.protocol.server.ProtocolServer;
import com.chua.common.support.network.protocol.view.ContentConverter;
import com.chua.common.support.network.protocol.view.ModelViewManager;
import lombok.extern.slf4j.Slf4j;
import org.smartboot.http.common.enums.HttpStatus;
import org.smartboot.http.common.multipart.MultipartConfig;
import org.smartboot.http.common.multipart.Part;
import org.smartboot.http.server.HttpBootstrap;
import org.smartboot.http.server.HttpRequest;
import org.smartboot.http.server.HttpResponse;
import org.smartboot.http.server.HttpServerHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;


/**
 * 基于 smart-http 的 HTTP 服务器实现。
 */
@Slf4j
@Spi({"http", "httpd"})
@SpiDescribe(value = "HTTP协议服务器")
public class HttpProtocolServer extends AbstractProtocolServer {

    private static final MultipartConfig MULTIPART_CONFIG = new MultipartConfig();

    private final boolean useHttps;
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private HttpBootstrap bootstrap;

    public HttpProtocolServer(ServerSetting serverSetting) {
        super(serverSetting);
        this.useHttps = serverSetting.isSslEnabled() || serverSetting.isUseSsl();
        this.modelViewManager = ModelViewManager.getInstance();
    }

    @Override
    protected void doStart() throws Exception {
        if (useHttps) {
            throw new UnsupportedOperationException("smart-http 当前集成未开放 HTTPS 配置，请先关闭 SSL 或补充证书适配");
        }

        bootstrap = new HttpBootstrap()
                .setPort(serverSetting.getPort())
                .httpHandler(new SmartHttpServerHandler());

        configureBootstrap();
        bootstrap.start();

        log.info("HTTP服务器启动成功 [smart-http] - {}:{} - 自动优化: {} - 工作线程: {} - 读取超时: {}ms",
                serverSetting.getHost(),
                serverSetting.getPort(),
                serverSetting.isAutomaticOptimization(),
                resolveThreadNum(),
                resolveIdleTimeout());
    }

    private void configureBootstrap() {
        var configuration = bootstrap.configuration()
                .host(serverSetting.getHost())
                .threadNum(resolveThreadNum())
                .readBufferSize(resolveReadBufferSize())
                .writeBufferSize(resolveWriteBufferSize())
                .setHttpIdleTimeout(resolveIdleTimeout())
                .bannerEnabled(false)
                .serverName("utils-support-common-starter/smart-http");
        configuration.setMaxRequestSize(resolveMaxRequestSize());
    }

    private int resolveThreadNum() {
        if (serverSetting.isAutomaticOptimization()) {
            return Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        }
        return serverSetting.getWorkerThreads() > 0
                ? serverSetting.getWorkerThreads()
                : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    }

    private int resolveReadBufferSize() {
        if (serverSetting.getReceiveBufferSize() > 0) {
            return serverSetting.getReceiveBufferSize();
        }
        return serverSetting.isAutomaticOptimization() ? 16 * 1024 : 8 * 1024;
    }

    private int resolveWriteBufferSize() {
        if (serverSetting.getSendBufferSize() > 0) {
            return serverSetting.getSendBufferSize();
        }
        return serverSetting.isAutomaticOptimization() ? 16 * 1024 : 8 * 1024;
    }

    private long resolveIdleTimeout() {
        if (serverSetting.getReadTimeoutMillis() > 0) {
            return serverSetting.getReadTimeoutMillis();
        }
        return serverSetting.isAutomaticOptimization() ? 5000L : 30000L;
    }

    private long resolveMaxRequestSize() {
        if (serverSetting.getMaxRequestSize() > 0) {
            return serverSetting.getMaxRequestSize();
        }
        return serverSetting.isAutomaticOptimization() ? 16L * 1024 * 1024 : 8L * 1024 * 1024;
    }

    @Override
    protected void doStop() throws Exception {
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
        log.info("HTTP服务器停止 [smart-http] - 总请求数: {}", totalRequests.get());
    }

    public long getActiveConnections() {
        return activeConnections.get();
    }

    public long getTotalRequests() {
        return totalRequests.get();
    }

    @Override
    public ProtocolServer addModelView(ContentConverter contentConverter) {
        modelViewManager.addContentConverter(contentConverter);
        return this;
    }

    @Override
    public String getServerInfo() {
        return String.format("smart-http/%s (%s:%d) - 活跃连接: %d, 总请求: %d",
                "2.5",
                serverSetting.getHost(),
                serverSetting.getPort(),
                activeConnections.get(),
                totalRequests.get());
    }

    @Override
    public ProtocolType getProtocolType() {
        return useHttps ? ProtocolType.HTTPS : ProtocolType.HTTP;
    }

    @Override
    protected HttpServletResponse createServletResponse() {
        return HttpServletResponse.builder()
                .statusCode(200)
                .statusMessage("OK")
                .success(true)
                .build();
    }

    private final class SmartHttpServerHandler extends HttpServerHandler {
        @Override
        public void handle(HttpRequest request, HttpResponse response, CompletableFuture<Object> future) throws Throwable {
            totalRequests.incrementAndGet();
            activeConnections.incrementAndGet();
            try {
                ServletRequest servletRequest = fromSmartHttpRequest(request);
                HttpServletResponse servletResponse = createServletResponse();
                try {
                    doHandle(servletRequest, servletResponse);
                    writeSmartHttpResponse(response, servletResponse);
                } catch (Exception e) {
                    log.error("处理HTTP请求失败", e);
                    writeErrorResponse(response, e);
                } finally {
                    try {
                        servletRequest.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        servletResponse.close();
                    } catch (Exception ignored) {
                    }
                }
                future.complete(null);
            } finally {
                activeConnections.decrementAndGet();
            }
        }
    }

    private ServletRequest fromSmartHttpRequest(HttpRequest request) throws IOException {
        RequestHeaders headers = new RequestHeaders();
        Collection<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            for (String headerName : headerNames) {
                Collection<String> values = request.getHeaders(headerName);
                if (values != null) {
                    for (String value : values) {
                        headers.add(headerName, value);
                    }
                }
            }
        }

        byte[] body = readRequestBody(request);
        String queryString = request.getQueryString();
        String requestUri = request.getRequestURI();
        String requestUrl = request.getRequestURL();

        HttpServletRequest httpServletRequest = HttpServletRequest.builder()
                .requestId(generateRequestId())
                .method(request.getMethod())
                .url(requestUrl != null ? requestUrl : requestUri)
                .path(requestUri)
                .queryString(queryString)
                .protocolVersion(request.getProtocol() != null ? request.getProtocol().name() : "HTTP/1.1")
                .requestTime(LocalDateTime.now())
                .serverIp(resolveHost(request.getLocalAddress()))
                .serverPort(request.getLocalAddress() != null ? request.getLocalAddress().getPort() : serverSetting.getPort())
                .clientIp(request.getRemoteAddr())
                .clientPort(request.getRemoteAddress() != null ? request.getRemoteAddress().getPort() : 0)
                .secure(request.isSecure())
                .headers(headers)
                .contentType(request.getContentType())
                .contentLength(request.getContentLength())
                .userAgent(request.getHeader("User-Agent"))
                .referer(request.getHeader("Referer"))
                .body(body)
                .build();

        populateParameters(httpServletRequest, request.getParameters());
        populateMultipart(httpServletRequest, request);
        return httpServletRequest;
    }

    private void populateParameters(HttpServletRequest request, Map<String, String[]> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return;
        }
        RequestParameters target = request.getParameters();
        if (target == null) {
            target = new RequestParameters();
            request.setParameters(target);
        }
        for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                target.add(entry.getKey(), value);
            }
        }
    }

    private void populateMultipart(HttpServletRequest request, HttpRequest smartRequest) {
        try {
            Collection<Part> parts = smartRequest.getParts(MULTIPART_CONFIG);
            if (parts == null || parts.isEmpty()) {
                return;
            }
            request.setMultipart(true);
            for (Part part : parts) {
                try (InputStream in = part.getInputStream()) {
                    byte[] content = in.readAllBytes();
                    if (part.getSubmittedFileName() != null && !part.getSubmittedFileName().isEmpty()) {
                        request.addFormPart(FormPart.createFilePart(
                                part.getName(),
                                part.getSubmittedFileName(),
                                part.getContentType(),
                                content
                        ));
                    } else {
                        request.addFormPart(FormPart.createTextPart(
                                part.getName(),
                                new String(content, resolveCharset(request.getCharset()))
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析smart-http multipart数据失败: {}", e.getMessage());
        }
    }

    private byte[] readRequestBody(HttpRequest request) throws IOException {
        try (InputStream inputStream = request.getInputStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (inputStream == null) {
                return new byte[0];
            }
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            return outputStream.toByteArray();
        }
    }

    private void writeSmartHttpResponse(HttpResponse response, HttpServletResponse servletResponse) throws IOException {
        response.setHttpStatus(resolveStatus(servletResponse.getStatusCode(), servletResponse.getStatusMessage()));
        if (servletResponse.getContentType() != null) {
            response.setContentType(servletResponse.getContentType());
        }

        RequestHeaders headers = servletResponse.getHeaders();
        if (headers != null) {
            headers.toMap().forEach((key, values) -> {
                if (values != null) {
                    for (String value : values) {
                        response.addHeader(key, value);
                    }
                }
            });
        }

        byte[] body = servletResponse.getBody();
        if (body != null && body.length > 0) {
            response.setContentLength(body.length);
            response.write(body);
        } else {
            response.setContentLength(0);
        }
        response.close();
    }

    private void writeErrorResponse(HttpResponse response, Exception exception) throws IOException {
        response.setHttpStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        response.setContentType("text/plain; charset=UTF-8");
        byte[] body = ("Internal Server Error: " + exception.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.write(body);
        response.close();
    }

    private HttpStatus resolveStatus(int statusCode, String statusMessage) {
        HttpStatus status = HttpStatus.valueOf(statusCode);
        if (status != null) {
            return status;
        }
        return new HttpStatus(statusCode > 0 ? statusCode : 200,
                statusMessage != null && !statusMessage.isEmpty() ? statusMessage : "OK");
    }

    private String resolveHost(java.net.InetSocketAddress address) {
        if (address == null || address.getAddress() == null) {
            return serverSetting.getHost();
        }
        return address.getAddress().getHostAddress();
    }

    private java.nio.charset.Charset resolveCharset(String charset) {
        if (charset == null || charset.isEmpty()) {
            return java.nio.charset.StandardCharsets.UTF_8;
        }
        try {
            return java.nio.charset.Charset.forName(charset);
        } catch (Exception e) {
            return java.nio.charset.StandardCharsets.UTF_8;
        }
    }
}
