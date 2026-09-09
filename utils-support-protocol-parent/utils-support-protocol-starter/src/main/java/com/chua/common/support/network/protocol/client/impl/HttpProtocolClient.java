package com.chua.common.support.network.protocol.client.impl;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.network.protocol.client.AbstractProtocolClient;
import com.chua.common.support.network.protocol.request.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.chua.common.support.network.http.HttpConstant.APPLICATION_X_WWW_FORM_URLENCODED;
import static com.chua.common.support.network.http.HttpConstant.FORM_DATA;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * HTTP协议客户端实现
 * <p>
 * 提供HTTP协议的客户端实现，使用java.net.http.HttpClient
 * 1. 支持异步和同步HTTP请求
 * 2. 支持HTTP/1.1和HTTP/2协议
 * 3. 支持SSL/TLS加密连接
 * 4. 支持连接池和超时控制
 * 5. 注意：HTTP协议不支持监听器功能，因为它是无状态的请求-响应协议
 *
 * @author CH
 * @since 2024/7/8
 */
@Slf4j
@Spi("HTTP")
public class HttpProtocolClient extends AbstractProtocolClient {

    private java.net.http.HttpClient httpClient;

    public HttpProtocolClient(ClientSetting clientSetting) {
        super(clientSetting);
    }

    @Override
    protected boolean doConnect() {
        try {
            // 创建HttpClient实例
            java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(clientSetting.getConnectTimeoutMillis()))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL);

            // 如果启用SSL，配置SSL上下文
            if (clientSetting.ssl()) {
                if (log.isDebugEnabled()) {
                    log.debug("启用SSL连接");
                }
            }

            httpClient = builder.build();
            if (log.isDebugEnabled()) {
                log.debug("HTTP客户端连接建立成功");
            }
            return true;
        } catch (Exception e) {
            log.error("HTTP客户端连接失败", e);
            return false;
        }
    }

    @Override
    protected void doDisconnect() {
        // HttpClient会自动管理连接池，不需要显式关闭
        httpClient = null;
        if (log.isDebugEnabled()) {
            log.debug("HTTP客户端连接断开");
        }
    }

    @Override
    protected CompletableFuture<ServletResponse> doSendAsync(ServletRequest request) {
        if (httpClient == null) {
            return CompletableFuture.completedFuture(
                ServletResponse.error("HTTP客户端未连接"));
        }

        try {
            HttpRequest httpRequest = buildHttpRequest(request);

            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(this::convertToServletResponse)
                    .exceptionally(throwable -> {
                        log.error("HTTP异步请求失败", throwable);
                        return ServletResponse.error("异步请求失败: " + throwable.getMessage());
                    });
        } catch (Exception e) {
            log.error("构建HTTP异步请求失败", e);
            return CompletableFuture.completedFuture(
                ServletResponse.error("构建请求失败: " + e.getMessage()));
        }
    }

    @Override
    protected ServletResponse doSendSync(ServletRequest request) {
        if (httpClient == null) {
            return ServletResponse.error("HTTP客户端未连接");
        }

        try {
            HttpRequest httpRequest = buildHttpRequest(request);

            HttpResponse<byte[]> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofByteArray());

            return convertToServletResponse(response);
        } catch (IOException e) {
            log.error("HTTP同步请求IO失败", e);
            return ServletResponse.error("请求IO失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("HTTP同步请求被中断", e);
            return ServletResponse.error("请求被中断");
        } catch (Exception e) {
            log.error("HTTP同步请求失败", e);
            return ServletResponse.error("请求失败: " + e.getMessage());
        }
    }

    @Override
    protected void doSendOneWay(ServletRequest request) {
        // HTTP协议不支持真正的单向发送，但可以异步发送并忽略响应
        doSendAsync(request).thenAccept(response -> {
            if (log.isDebugEnabled()) {
                log.debug("HTTP单向请求完成，状态码: {}", response.getStatusCode());
            }
        }).exceptionally(throwable -> {
            log.warn("HTTP单向请求失败", throwable);
            return null;
        });
    }

    @Override
    public boolean isConnected() {
        return httpClient != null;
    }

    @Override
    public String getProtocolName() {
        return "HTTP";
    }

    @Override
    protected void doHeartbeat() {
        // HTTP协议不需要心跳
        if (log.isDebugEnabled()) {
            log.debug("HTTP协议不需要心跳");
        }
    }

    @Override
    protected void doStartHeartbeat() {
        // HTTP协议不需要心跳
        if (log.isDebugEnabled()) {
            log.debug("HTTP协议不需要心跳");
        }
    }

    @Override
    protected void doStopHeartbeat() {
        // HTTP协议不需要心跳
        if (log.isDebugEnabled()) {
            log.debug("HTTP协议不需要心跳");
        }
    }

    @Override
    protected void doClose() {
        doDisconnect();
    }

    /**
     * 构建HttpRequest
     * <p>
     * 支持多种参数处理方式：
     * 1. Query参数：添加到URL查询字符串中
     * 2. Parameter参数：根据请求方法和Content-Type决定处理方式
     *    - GET请求：所有参数作为查询参数
     *    - POST/PUT表单请求：查询参数在URL中，表单参数在请求体中
     *    - 其他请求：参数处理由具体实现决定
     */
    private HttpRequest buildHttpRequest(ServletRequest request) throws Exception {
        String protocol = clientSetting.ssl() ? "https" : "http";
        String baseUrl = protocol + "://" + clientSetting.host() + ":" + clientSetting.port() + request.getUrl();

        // 构建完整URL（包含查询参数）
        String url = buildCompleteUrl(baseUrl, request);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(clientSetting.getReadTimeoutMillis()));

        // 设置请求头（在构建请求体之前，因为可能需要设置Content-Type）
        setRequestHeaders(builder, request);

        // 设置请求方法和body
        byte[] body = buildRequestBody(request);
        if (body != null && body.length > 0) {
            builder.method(request.getMethod(), HttpRequest.BodyPublishers.ofByteArray(body));

            // 自动设置Content-Type（如果没有手动设置）
            setAutoContentType(builder, request);
        } else {
            builder.method(request.getMethod(), HttpRequest.BodyPublishers.noBody());
        }

        // 添加客户端标识header
        builder.header("X-Protocol-Client", "true");
        builder.header("X-Client-Id", clientSetting.getClientId());

        // 添加自定义header
        addCustomHeaders(builder);

        return builder.build();
    }

    /**
     * 构建完整URL
     * <p>
     * 根据请求方法和参数类型构建完整的URL：
     * 1. 优先使用query参数构建查询字符串
     * 2. 对于GET请求，如果没有query参数但有parameter参数，将parameter作为查询参数
     * 3. 对于POST/PUT请求，只将query参数和非表单parameter参数添加到URL中
     *
     * @param baseUrl 基础URL
     * @param request 请求对象
     * @return 完整URL
     */
    private String buildCompleteUrl(String baseUrl, ServletRequest request) {
        StringBuilder url = new StringBuilder(baseUrl);

        // 1. 首先处理query参数（如果存在）
        String queryString = buildQueryStringFromRequest(request);

        // 2. 对于GET请求，如果没有query参数，尝试使用parameter参数
        if ((queryString == null || queryString.isEmpty()) && "GET".equalsIgnoreCase(request.getMethod())) {
            queryString = buildQueryStringFromParameters(request);
        }

        // 3. 添加查询字符串到URL
        if (queryString != null && !queryString.isEmpty()) {
            url.append(url.toString().contains("?") ? "&" : "?").append(queryString);
        }

        return url.toString();
    }

    /**
     * 从请求中构建查询字符串
     * <p>
     * 优先级：
     * 1. 使用原始查询字符串
     * 2. 从查询参数映射构建
     *
     * @param request 请求对象
     * @return 查询字符串
     */
    private String buildQueryStringFromRequest(ServletRequest request) {
        // 1. 优先使用原始查询字符串
        String existingQueryString = request.getQueryString();
        if (existingQueryString != null && !existingQueryString.trim().isEmpty()) {
            return existingQueryString;
        }

        // 2. 从查询参数映射构建
        Map<String, String[]> queryParams = request.getQueryParameterMap();
        if (queryParams != null && !queryParams.isEmpty()) {
            return buildQueryStringFromMap(queryParams);
        }

        return null;
    }

    /**
     * 从请求参数构建查询字符串
     * <p>
     * 当没有专门的查询参数时，使用请求参数构建查询字符串
     * 主要用于GET请求的向后兼容
     *
     * @param request 请求对象
     * @return 查询字符串
     */
    private String buildQueryStringFromParameters(ServletRequest request) {
        // 获取所有请求参数
        Map<String, String[]> allParams = request.getParameterMap();
        if (allParams == null || allParams.isEmpty()) {
            return null;
        }

        return buildQueryStringFromMap(allParams);
    }

    /**
     * 从参数映射构建查询字符串
     *
     * @param paramMap 参数映射
     * @return 查询字符串
     */
    private String buildQueryStringFromMap(Map<String, String[]> paramMap) {
        if (paramMap == null || paramMap.isEmpty()) {
            return null;
        }

        StringBuilder queryString = new StringBuilder();
        for (Map.Entry<String, String[]> entry : paramMap.entrySet()) {
            String key = entry.getKey();
            String[] values = entry.getValue();

            if (values != null) {
                for (String value : values) {
                    if (!queryString.isEmpty()) {
                        queryString.append("&");
                    }
                    queryString.append(urlEncode(key)).append("=").append(urlEncode(value != null ? value : ""));
                }
            }
        }

        return queryString.length() > 0 ? queryString.toString() : null;
    }


    /**
     * 构建请求体
     * <p>
     * 处理不同类型的请求体：
     * 1. 如果已有请求体数据，直接使用
     * 2. 对于POST/PUT请求，如果是表单类型，从参数构建请求体
     * 3. 支持从parameter参数构建表单数据
     *
     * @param request 请求对象
     * @return 请求体字节数组
     */
    private byte[] buildRequestBody(ServletRequest request) throws Exception {
        // 1. 如果已有请求体，直接使用
        byte[] existingBody = request.getBody();
        if (existingBody != null && existingBody.length > 0) {
            return existingBody;
        }

        // 2. 对于POST/PUT请求，处理表单数据
        String method = request.getMethod();
        String contentType = request.getContentType();

        if (("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {

            // 2.1 优先处理HttpServletRequest的表单数据
            if (request instanceof HttpServletRequest httpRequest) {

                // 检查是否有表单部分数据
                if (hasFormParts(httpRequest)) {
                    return buildFormDataFromParts(httpRequest, contentType);
                }
            }

            // 2.2 处理传统的parameter参数
            if ((contentType != null && contentType.contains(APPLICATION_X_WWW_FORM_URLENCODED)) ||
                    (contentType == null && hasFormParameters(request))) {

                String formData = buildFormDataFromRequest(request);
                if (formData != null && !formData.isEmpty()) {
                    return formData.getBytes(StandardCharsets.UTF_8);
                }
            }
        }

        return null;
    }

    /**
     * 检查是否有表单参数
     * <p>
     * 判断请求是否包含可以作为表单数据的参数
     *
     * @param request 请求对象
     * @return 如果有表单参数返回true
     */
    private boolean hasFormParameters(ServletRequest request) {
        // 检查是否有请求参数（排除查询参数）
        Map<String, String[]> allParams = request.getParameterMap();
        Map<String, String[]> queryParams = request.getQueryParameterMap();

        if (allParams == null || allParams.isEmpty()) {
            return false;
        }

        // 如果所有参数都是查询参数，则没有表单参数
        if (queryParams != null && queryParams.size() == allParams.size()) {
            for (String key : allParams.keySet()) {
                if (!queryParams.containsKey(key)) {
                    return true; // 找到非查询参数
                }
            }
            return false; // 所有参数都是查询参数
        }

        return true; // 有参数且不全是查询参数
    }

    /**
     * 从请求构建表单数据
     * <p>
     * 智能选择参数来源构建表单数据：
     * 1. 优先从请求参数中排除查询参数来构建
     * 2. 如果没有区分，则使用所有请求参数
     *
     * @param request 请求对象
     * @return 表单数据字符串
     */
    private String buildFormDataFromRequest(ServletRequest request) {
        // 获取所有请求参数
        Map<String, String[]> allParams = request.getParameterMap();
        if (allParams == null || allParams.isEmpty()) {
            return null;
        }

        // 获取查询参数（需要排除）
        Map<String, String[]> queryParams = request.getQueryParameterMap();

        StringBuilder formData = new StringBuilder();

        for (Map.Entry<String, String[]> entry : allParams.entrySet()) {
            String key = entry.getKey();
            String[] values = entry.getValue();

            // 如果有查询参数映射，跳过查询参数，只处理表单参数
            if (queryParams != null && !queryParams.isEmpty() && queryParams.containsKey(key)) {
                continue;
            }

            if (values != null) {
                for (String value : values) {
                    if (!formData.isEmpty()) {
                        formData.append("&");
                    }
                    formData.append(urlEncode(key)).append("=").append(urlEncode(value != null ? value : ""));
                }
            }
        }

        return !formData.isEmpty() ? formData.toString() : null;
    }


    /**
     * 设置请求头
     *
     * @param builder HTTP请求构建器
     * @param request 请求对象
     */
    private void setRequestHeaders(HttpRequest.Builder builder, ServletRequest request) {
        // 设置请求头
        for (String name : request.getHeaders().keySet()) {
            List<String> values = request.getHeaders().getAll(name);
            for (String value : values) {
                builder.header(name, value);
            }
        }
    }

    /**
     * 自动设置Content-Type头
     *
     * @param builder HTTP请求构建器
     * @param request 请求对象
     */
    private void setAutoContentType(HttpRequest.Builder builder, ServletRequest request) {
        // 如果已经手动设置了Content-Type，则不自动设置
        if (request.getContentType() != null && !request.getContentType().isEmpty()) {
            return;
        }

        // 检查是否是HttpServletRequest且有表单数据
        if (request instanceof HttpServletRequest httpRequest) {
            if (hasFormParts(httpRequest)) {
                // 检查是否有文件上传
                boolean hasFiles = httpRequest.getAllFileParts().stream()
                        .anyMatch(part -> part != null && part.isFile());

                if (hasFiles) {
                    // 有文件上传，使用multipart/form-data
                    String boundary = generateBoundary();
                    builder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
                } else {
                    // 只有文本字段，使用application/x-www-form-urlencoded
                    builder.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                }
                return;
            }
        }

        // 对于POST/PUT请求，如果有参数但没有请求体，默认使用form-urlencoded
        String method = request.getMethod();
        if (("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) &&
                hasFormParameters(request)) {
            builder.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        }
    }

    /**
     * URL编码
     *
     * @param value 需要编码的值
     * @return 编码后的值
     */
    private String urlEncode(String value) {
        if (value == null) {
            return "";
        }

        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 添加自定义HTTP头
     */
    private void addCustomHeaders(HttpRequest.Builder builder) {
        String customHeaders = clientSetting.getCustomHeaders();
        if (customHeaders != null && !customHeaders.trim().isEmpty()) {
            String[] headers = customHeaders.split(";");
            for (String header : headers) {
                String[] parts = header.split("=", 2);
                if (parts.length == 2) {
                    String name = parts[0].trim();
                    String value = parts[1].trim();
                    if (!name.isEmpty() && !value.isEmpty()) {
                        builder.header(name, value);
                        if (log.isDebugEnabled()) {
                            log.debug("添加自定义HTTP头: {} = {}", name, value);
                        }
                    }
                }
            }
        }
    }

    /**
     * 转换HttpResponse为ServletResponse
     */
    private ServletResponse convertToServletResponse(HttpResponse<byte[]> httpResponse) {
        byte[] responseBody = httpResponse.body();

        // 构建响应头
        RequestHeaders responseHeaders = new RequestHeaders();
        for (Map.Entry<String, List<String>> entry : httpResponse.headers().map().entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue();
            for (String value : values) {
                responseHeaders.add(name, value);
            }
        }

        HttpServletResponse response = HttpServletResponse.builder()
                .statusCode(httpResponse.statusCode())
                .headers(responseHeaders)
                .build();
        response.setBody(responseBody);

        return response;
    }

    /**
     * 检查HttpServletRequest是否有表单部分数据
     *
     * @param httpRequest HttpServletRequest对象
     * @return 如果有表单部分数据返回true
     */
    private boolean hasFormParts(HttpServletRequest httpRequest) {
        java.util.Map<String, FormPart[]> formParts =
                httpRequest.getAllFormParts();
        return formParts != null && !formParts.isEmpty();
    }

    /**
     * 从HttpServletRequest的表单部分构建请求体数据
     *
     * @param httpRequest HttpServletRequest对象
     * @param contentType 内容类型
     * @return 请求体字节数组
     */
    private byte[] buildFormDataFromParts(HttpServletRequest httpRequest,
                                          String contentType) throws Exception {

        // 判断表单类型
        if (contentType != null && contentType.contains(FORM_DATA)) {
            return buildMultipartFormData(httpRequest, contentType);
        } else {
            // 默认使用application/x-www-form-urlencoded
            return buildUrlEncodedFormData(httpRequest);
        }
    }

    /**
     * 构建multipart/form-data格式的请求体
     *
     * @param httpRequest HttpServletRequest对象
     * @param contentType 内容类型（包含boundary信息）
     * @return 请求体字节数组
     */
    private byte[] buildMultipartFormData(HttpServletRequest httpRequest,
                                          String contentType) throws Exception {

        // 提取boundary，如果没有则生成一个
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            boundary = generateBoundary();
            // 注意：这里生成的boundary需要与Content-Type头中的保持一致
            // 在实际使用中，应该在setAutoContentType中统一处理
        }

        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        String boundaryLine = "--" + boundary + "\r\n";
        String endBoundary = "--" + boundary + "--\r\n";

        // 处理所有表单部分
        java.util.Map<String, FormPart[]> allParts =
                httpRequest.getAllFormParts();

        for (java.util.Map.Entry<String, FormPart[]> entry : allParts.entrySet()) {
            FormPart[] parts = entry.getValue();
            if (parts != null) {
                for (FormPart part : parts) {
                    if (part != null) {
                        writeMultipartPart(outputStream, part, boundaryLine);
                    }
                }
            }
        }

        // 写入结束边界
        outputStream.write(endBoundary.getBytes(StandardCharsets.UTF_8));

        return outputStream.toByteArray();
    }

    /**
     * 构建application/x-www-form-urlencoded格式的请求体
     *
     * @param httpRequest HttpServletRequest对象
     * @return 请求体字节数组
     */
    private byte[] buildUrlEncodedFormData(HttpServletRequest httpRequest)
            throws Exception {

        StringBuilder formData = new StringBuilder();
        java.util.Map<String, FormPart[]> allParts =
                httpRequest.getAllFormParts();

        for (java.util.Map.Entry<String, FormPart[]> entry : allParts.entrySet()) {
            FormPart[] parts = entry.getValue();
            if (parts != null) {
                for (FormPart part : parts) {
                    if (part != null && part.isText()) {
                        if (!formData.isEmpty()) {
                            formData.append("&");
                        }
                        formData.append(urlEncode(part.getName()))
                                .append("=")
                                .append(urlEncode(part.getValue()));
                    }
                }
            }
        }

        return !formData.isEmpty() ?
                formData.toString().getBytes(StandardCharsets.UTF_8) : null;
    }

    /**
     * 写入multipart部分数据
     */
    private void writeMultipartPart(java.io.ByteArrayOutputStream outputStream,
                                    FormPart part,
                                    String boundaryLine) throws Exception {

        // 写入边界
        outputStream.write(boundaryLine.getBytes(StandardCharsets.UTF_8));

        // 写入Content-Disposition头
        String disposition = "Content-Disposition: form-data; name=\"" + part.getName() + "\"";
        if (part.isFile() && part.getFilename() != null) {
            disposition += "; filename=\"" + part.getFilename() + "\"";
        }
        disposition += "\r\n";
        outputStream.write(disposition.getBytes(StandardCharsets.UTF_8));

        // 写入Content-Type头（如果有）
        if (part.getContentType() != null) {
            String contentTypeHeader = "Content-Type: " + part.getContentType() + "\r\n";
            outputStream.write(contentTypeHeader.getBytes(StandardCharsets.UTF_8));
        }

        // 写入空行
        outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));

        // 写入内容
        if (part.isText()) {
            outputStream.write(part.getValue().getBytes(StandardCharsets.UTF_8));
        } else if (part.getContent() != null) {
            outputStream.write(part.getContent());
        }

        // 写入换行
        outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从Content-Type中提取boundary
     */
    private String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }

        String[] parts = contentType.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                return part.substring("boundary=".length());
            }
        }

        return null;
    }

    /**
     * 生成随机boundary
     */
    private String generateBoundary() {
        return "----HttpProtocolClientBoundary" + System.currentTimeMillis();
    }

}