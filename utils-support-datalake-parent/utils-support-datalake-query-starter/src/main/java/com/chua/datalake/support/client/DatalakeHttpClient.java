package com.chua.datalake.support.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 数据湖-启动 提供的查询 API 客户端。
 *
 * <p>查询语句以 UTF-8 文本作为请求体 {@code POST {baseUrl}/query}，响应体原样返回。
 * 非 2xx 一律抛 {@link IOException}，避免把服务端的错误页当成查询结果交给上层。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DatalakeHttpClient {
    /**
     * 默认查询路径
     */
    private static final String DEFAULT_QUERY_PATH = "/query";

    /**
     * 请求超时（秒）
     */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    /**
     * 错误响应体回显上限（字符），防止把整页 HTML 塞进异常消息
     */
    private static final int ERROR_BODY_PREVIEW = 512;

    /**
     * 远程服务地址
     */
    private final String baseUrl;

    /**
     * HTTP客户端 实例
     */
    private final HttpClient client;

    /**
     * 构造
     *
     * @param baseUrl 数据湖-启动 提供的 API 服务地址（如 http://localhost:8700）
     */
    public DatalakeHttpClient(String baseUrl) {
        String trimmed = requireBaseUrl(baseUrl);
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build();
    }

    /**
     * 发送查询请求
     *
     * @param sdl 查询 SQL
     * @return 响应体字符串
     * @throws Exception 异常
     */
    public String query(String sdl) throws Exception {
        if (sdl == null || sdl.isEmpty()) {
            throw new IllegalArgumentException("查询语句不能为空");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + DEFAULT_QUERY_PATH))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sdl, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("查询失败: status=" + response.statusCode()
                    + ", url=" + baseUrl + DEFAULT_QUERY_PATH
                    + ", body=" + preview(response.body()));
        }
        return response.body();
    }

    /**
     * 关闭资源
     */
    public void close() {
 // Java.net.http.HTTP客户端 不需要关闭
    }

    /**
     * 校验服务地址必须是带 http/https scheme 的绝对地址。
     *
     * @param baseUrl 原始地址
     * @return 去除首尾空白的地址
     */
    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl 不是合法 URL: " + baseUrl);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("baseUrl 必须是 http/https 地址: " + baseUrl);
        }
        return baseUrl.trim();
    }

    /**
     * 截断响应体用于异常回显。
     *
     * @param body 响应体
     * @return 预览文本
     */
    private static String preview(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= ERROR_BODY_PREVIEW ? body : body.substring(0, ERROR_BODY_PREVIEW) + "...";
    }
}
