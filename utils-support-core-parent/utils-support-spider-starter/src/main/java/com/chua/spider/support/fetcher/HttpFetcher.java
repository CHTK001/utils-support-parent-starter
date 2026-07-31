package com.chua.spider.support.fetcher;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.spider.support.SpiderFetcher;
import com.chua.spider.support.model.SpiderRequest;
import com.chua.spider.support.model.SpiderResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP 爬虫抓取器。
 *
 * <p>基于 JDK 内置的 {@link HttpClient} 实现 Web 页面抓取。
 * 支持 GET/POST 请求、自定义请求头、Cookie、超时控制等功能。
 * 当 OkHttp 不在类路径上时作为默认 HTTP 抓取实现。
 *
 * <p>SPI 名称：{@code fetcher:http}
 *
 * @author CH
 * @since 2026/07/17
 */
@Slf4j
@Spi(value = "http", order = 0)
public class HttpFetcher implements SpiderFetcher {

    /**
     * 默认请求超时时间，30 秒
     */
    private static final int DEFAULT_TIMEOUT = 30_000;

    /**
     * 默认 User-Agent
     */
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * JDK HttpClient 实例
     */
    private final HttpClient httpClient;

    /**
     * 默认构造器，使用默认超时和 User-Agent。
     */
    public HttpFetcher() {
        this(DEFAULT_TIMEOUT);
    }

    /**
     * 构造器，指定超时时间。
     *
     * @param timeoutMs 请求超时毫秒数
     */
    public HttpFetcher(int timeoutMs) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public SpiderResponse fetch(SpiderRequest request) {
        long startTime = System.currentTimeMillis();
        SpiderResponse.SpiderResponseBuilder builder = SpiderResponse.builder()
                .request(request);

        try {
            // 构建 HTTP 请求
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(request.getUrl()))
                    .timeout(Duration.ofMillis(DEFAULT_TIMEOUT))
                    .header("User-Agent", DEFAULT_USER_AGENT);

            // 设置自定义请求头
            Map<String, String> headers = request.getHeaders();
            if (headers != null && !headers.isEmpty()) {
                headers.forEach(reqBuilder::header);
            }

            // 设置 Cookie
            String cookies = request.getCookies();
            if (cookies != null && !cookies.isEmpty()) {
                reqBuilder.header("Cookie", cookies);
            }

            // 设置请求方法
            String method = request.getMethod() != null ? request.getMethod().toUpperCase() : "GET";
            switch (method) {
                case "POST":
                    String body = request.getBody();
                    reqBuilder.POST(body != null ?
                            HttpRequest.BodyPublishers.ofString(body) :
                            HttpRequest.BodyPublishers.noBody());
                    break;
                case "PUT":
                    String putBody = request.getBody();
                    reqBuilder.PUT(putBody != null ?
                            HttpRequest.BodyPublishers.ofString(putBody) :
                            HttpRequest.BodyPublishers.noBody());
                    break;
                case "DELETE":
                    reqBuilder.DELETE();
                    break;
                default:
                    reqBuilder.GET();
                    break;
            }

            // 发送请求
            HttpResponse<String> response = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            long elapsed = System.currentTimeMillis() - startTime;

            // 构建响应
            builder.statusCode(response.statusCode())
                    .content(response.body())
                    .rawContent(response.body().getBytes())
                    .contentType(response.headers().firstValue("Content-Type").orElse(""))
                    .fetchTimeMs(elapsed);

            // 记录日志
            if (log.isDebugEnabled()) {
                log.debug("HTTP {} {} → {} ({}ms)",
                        method, request.getUrl(), response.statusCode(), elapsed);
            }

        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            builder.statusCode(0)
                    .error("IO 异常: " + e.getMessage())
                    .fetchTimeMs(elapsed);
            log.warn("HTTP 抓取失败: {} - {}", request.getUrl(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            builder.statusCode(0)
                    .error("请求被中断: " + e.getMessage());
            log.warn("HTTP 请求被中断: {}", request.getUrl());
        } catch (Exception e) {
            builder.statusCode(0)
                    .error("未知异常: " + e.getMessage());
            log.error("HTTP 抓取异常: {}", request.getUrl(), e);
        }

        return builder.build();
    }
}
