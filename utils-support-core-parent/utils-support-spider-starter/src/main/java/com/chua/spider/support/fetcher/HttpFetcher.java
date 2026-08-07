package com.chua.spider.support.fetcher;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.spider.support.SpiderFetcher;
import com.chua.spider.support.model.SpiderCookie;
import com.chua.spider.support.model.SpiderProxyConfig;
import com.chua.spider.support.model.SpiderRequest;
import com.chua.spider.support.model.SpiderResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP 爬虫抓取器。
 *
 * <p>基于 JDK 内置的 {@link HttpClient} 实现 Web 页面抓取。
 * 支持 GET/POST 请求、自定义请求头、Cookie、代理、超时控制等功能。
 * 当 OkHttp 不在类路径上时作为默认 HTTP 抓取实现。</p>
 *
 * <p>SPI 名称：{@code fetcher:http}</p>
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
     * JDK HttpClient 实例（无代理时的共享实例）
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
            HttpRequest.Builder reqBuilder = buildHttpRequest(request);

            // 每次请求独立 HttpClient：带代理时新建，不污染共享实例
            HttpClient client = chooseClient(request);

            HttpResponse<String> response = client.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            long elapsed = System.currentTimeMillis() - startTime;
            builder.statusCode(response.statusCode())
                    .content(response.body())
                    .rawContent(response.body().getBytes())
                    .contentType(response.headers().firstValue("Content-Type").orElse(""))
                    .fetchTimeMs(elapsed);

            if (log.isDebugEnabled()) {
                log.debug("HTTP {} {} → {} ({}ms)",
                        request.getMethod(), request.getUrl(), response.statusCode(), elapsed);
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

    /**
     * 构建 JDK HttpRequest.Builder：URL + 方法 + 头 + Cookie + body。
     *
     * @param request 爬虫请求
     * @return JDK 请求构造器
     */
    private HttpRequest.Builder buildHttpRequest(SpiderRequest request) {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(request.getUrl()))
                .timeout(Duration.ofMillis(DEFAULT_TIMEOUT))
                .header("User-Agent", DEFAULT_USER_AGENT);

        Map<String, String> headers = request.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            headers.forEach(reqBuilder::header);
        }

        String cookieHeader = buildCookieHeader(request);
        if (cookieHeader != null) {
            reqBuilder.header("Cookie", cookieHeader);
        }

        String method = request.getMethod() != null ? request.getMethod().toUpperCase() : "GET";
        switch (method) {
            case "POST": {
                String body = request.getBody();
                reqBuilder.POST(body != null ?
                        HttpRequest.BodyPublishers.ofString(body) :
                        HttpRequest.BodyPublishers.noBody());
                break;
            }
            case "PUT": {
                String body = request.getBody();
                reqBuilder.PUT(body != null ?
                        HttpRequest.BodyPublishers.ofString(body) :
                        HttpRequest.BodyPublishers.noBody());
                break;
            }
            case "DELETE":
                reqBuilder.DELETE();
                break;
            default:
                reqBuilder.GET();
                break;
        }
        return reqBuilder;
    }

    /**
     * 选择 HTTP 客户端：无代理时复用共享实例，有代理时为本次请求新建。
     *
     * @param request 爬虫请求
     * @return HttpClient 实例
     */
    private HttpClient chooseClient(SpiderRequest request) {
        SpiderProxyConfig proxy = request.getProxy();
        if (proxy == null || proxy.getProxyHost() == null || proxy.getProxyHost().isEmpty()) {
            return httpClient;
        }
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(DEFAULT_TIMEOUT))
                .followRedirects(HttpClient.Redirect.NORMAL);
        builder.proxy(buildProxySelector(proxy));
        return builder.build();
    }

    /**
     * 根据代理配置构建 JDK ProxySelector。
     *
     * <p>JDK 17 中 {@code ProxySelector.of(InetSocketAddress)} 是带默认值的便捷方法。
     * 本方法直接返回该实例，HttpClient 会自动应用代理。</p>
     *
     * @param proxy 代理配置
     * @return ProxySelector 实例
     */
    private ProxySelector buildProxySelector(SpiderProxyConfig proxy) {
        InetSocketAddress address = new InetSocketAddress(proxy.getProxyHost(), proxy.getProxyPort());
        // 注：ProxySelector.of(InetSocketAddress) 构造的是"该地址作为所有 URI 的默认代理"的 selector。
        // HTTP/HTTPS 协议的代理直接返回此地址即可；SOCKS 协议 JDK HttpClient 暂不支持，
        // 这里仍按 HTTP 代理地址传递（JDK 21+ HttpClient 自动处理）。
        return ProxySelector.of(address);
    }

    /**
     * 合并 Cookie 字符串与结构化 cookieList 为标准 Cookie 请求头。
     *
     * @param request 爬虫请求
     * @return Cookie 请求头值；都为空时返回 null
     */
    private String buildCookieHeader(SpiderRequest request) {
        StringBuilder builder = new StringBuilder();
        String raw = request.getCookies();
        if (raw != null && !raw.isEmpty()) {
            builder.append(raw);
        }
        List<SpiderCookie> list = request.getCookieList();
        if (list != null && !list.isEmpty()) {
            for (SpiderCookie cookie : list) {
                if (cookie == null || cookie.getName() == null || cookie.getValue() == null) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append("; ");
                }
                builder.append(cookie.getName()).append('=').append(cookie.getValue());
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }
}