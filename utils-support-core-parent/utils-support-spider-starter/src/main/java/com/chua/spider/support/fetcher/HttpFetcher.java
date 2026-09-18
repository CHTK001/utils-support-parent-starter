package com.chua.spider.support.fetcher;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.spider.support.SpiderFetcher;
import com.chua.spider.support.model.SpiderCookie;
import com.chua.spider.support.model.SpiderProxyConfig;
import com.chua.spider.support.model.SpiderRequest;
import com.chua.spider.support.model.SpiderResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
* HTTP 爬虫抓取器。
*
* <p>基于 JDK 内置的 {@link HttpClient} 实现 Web 页面抓取。
* 支持 获取/POST 请求、自定义请求头、Cookie、代理、超时控制等功能。
* 当 OkHttp 不在类路径上时作为默认 HTTP 抓取实现。</p>
*
* <p>SPI 名称：{@code fetcher:http}</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi(value = "http", order = 0)
public class HttpFetcher implements SpiderFetcher {

    /**
    * 默认请求超时时间，30 秒
    */
    private static final int DEFAULT_TIMEOUT = 30_000;

    /**
    * 请求属性键：超时时间（毫秒）。可写整数。
    */
    private static final String ATTR_TIMEOUT = "timeoutMs";

    /**
    * 请求属性键：用户-Agent。
    */
    private static final String ATTR_USER_AGENT = "userAgent";

    /**
    * 默认 用户-Agent
    */
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
    * JDK HTTP客户端 实例（无代理时的共享实例）
    */
    private final HttpClient httpClient;

    /**
    * 默认构造器，使用默认超时和 用户-Agent。
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
    /** 获取 */
    public SpiderResponse fetch(SpiderRequest request) {
        long startTime = System.currentTimeMillis();
        SpiderResponse.SpiderResponseBuilder builder = SpiderResponse.builder()
                .request(request);

        try {
            HttpRequest.Builder reqBuilder = buildHttpRequest(request);

 // 每次请求独立 HTTP客户端：带代理时新建，不污染共享实例
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
                log.debug("[spider-fetcher] HTTP {} {} → {} ({}ms)",
                        request.getMethod(), request.getUrl(), response.statusCode(), elapsed);
            }
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            builder.statusCode(0)
                    .error("IO 异常: " + e.getMessage())
                    .fetchTimeMs(elapsed);
            log.warn("[spider-fetcher] HTTP 抓取失败: {} - {}", request.getUrl(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            builder.statusCode(0)
                    .error("请求被中断: " + e.getMessage());
            log.warn("[spider-fetcher] HTTP 请求被中断: {}", request.getUrl());
        } catch (Exception e) {
            builder.statusCode(0)
                    .error("未知异常: " + e.getMessage());
            log.error("[spider-fetcher] HTTP 抓取异常: {}", request.getUrl(), e);
        }

        return builder.build();
    }

    /**
    * 构建 JDK http请求.构建器：URL + 方法 + 头 + Cookie + 主体。
    *
    * @param request 爬虫请求
    * @return JDK 请求构造器
    */
    private HttpRequest.Builder buildHttpRequest(SpiderRequest request) {
        Map<String, Object> attributes = request.getAttributes() != null
                ? request.getAttributes() : java.util.Collections.emptyMap();
        Object timeoutObj = attributes.get(ATTR_TIMEOUT);
        long timeoutMs = timeoutObj instanceof Number
                ? ((Number) timeoutObj).longValue() : DEFAULT_TIMEOUT;
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT;
        }
        Object uaObj = attributes.get(ATTR_USER_AGENT);
        String userAgent = uaObj != null ? String.valueOf(uaObj) : DEFAULT_USER_AGENT;

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(request.getUrl()))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", userAgent);

        Map<String, String> headers = request.getHeaders();
        if (CollectionUtils.isNotEmpty(headers)) {
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
        if (proxy == null || StringUtils.isEmpty(proxy.getProxyHost())) {
            return httpClient;
        }
        Object timeoutObj = request.getAttributes() != null
                ? request.getAttributes().get(ATTR_TIMEOUT) : null;
        long timeoutMs = timeoutObj instanceof Number
                ? ((Number) timeoutObj).longValue() : DEFAULT_TIMEOUT;
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT;
        }
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL);
        builder.proxy(buildProxySelector(proxy));
        return builder.build();
    }

    /**
    * 根据代理配置构建 JDK 代理selector。
    *
    * <p>根据 {@link SpiderProxyConfig#getProxyProtocol()} 选择代理类型：
    * SOCKS/SOCKS5 使用 {@link Proxy.Type#SOCKS}，其余（HTTP/HTTPS）使用
    * {@link Proxy.Type#HTTP}。通过可配置的地址实现代理转发。</p>
    *
    * @param proxy 代理配置
    * @return ProxySelector 实例
    */
    private ProxySelector buildProxySelector(SpiderProxyConfig proxy) {
        InetSocketAddress address = new InetSocketAddress(proxy.getProxyHost(), proxy.getProxyPort());
        String protocol = proxy.getProxyProtocol() != null
                ? proxy.getProxyProtocol().toUpperCase() : "HTTP";
        boolean socks = "SOCKS".equals(protocol) || "SOCKS5".equals(protocol);
        Proxy proxyConfig = socks
                ? new Proxy(Proxy.Type.SOCKS, address)
                : new Proxy(Proxy.Type.HTTP, address);
        return new ProxySelector() {
            @Override
            /** 选择 */
            public java.util.List<Proxy> select(URI uri) {
                return java.util.Collections.singletonList(proxyConfig);
            }

            @Override
            /** 连接失败 */
            public void connectFailed(URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
                log.warn("[spider-fetcher] 代理连接失败: {} - {}", uri, ioe.getMessage());
            }
        };
    }

    /**
            * 合并 Cookie 字符串与结构化 Cookie列表 为标准 Cookie 请求头。
            *
            * @param request 爬虫请求
            * @return Cookie 请求头值；都为空时返回 空
            */
    private String buildCookieHeader(SpiderRequest request) {
        StringBuilder builder = new StringBuilder();
        String raw = request.getCookies();
        if (StringUtils.isNotEmpty(raw)) {
            builder.append(raw);
        }
        List<SpiderCookie> list = request.getCookieList();
        if (CollectionUtils.isNotEmpty(list)) {
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
