package com.chua.openai.support.inception;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 纯 HTTP 版 Inception 传输通道
 *
 * <p>用 JDK {@link HttpClient} 直接访问 {@code /api/session} 与 {@code /api/chat}。
 * 由于站点前置了 Vercel 安全网关（按 TLS / HTTP2 指纹判定），在官方域名上
 * 直接使用通常会被 HTTP 429 拒绝；本通道适用于：</p>
 * <ul>
 *   <li>自建 / 反代部署（网关已关闭或已放行）；</li>
 *   <li>链路上已有指纹伪造代理（如 curl-impersonate、浏览器代理池）。</li>
 * </ul>
 *
 * <p>凭证为浏览器 Cookie 串，通过 {@code Cookie} 头携带；Cookie 与出口 IP、
 * User-Agent 绑定，所以必须传入抓取时使用的 UA。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class InceptionHttpTransport implements InceptionTransport {

    /**
     * 会话接口路径
     */
    private static final String SESSION_PATH = "/api/session";

    /**
     * 对话接口路径
     */
    private static final String CHAT_PATH = "/api/chat";

    /**
     * 默认浏览器 UA
     */
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36";

    /**
     * 站点根地址
     */
    private final String baseUrl;

    /**
     * Cookie 字符串
     */
    private final String cookie;

    /**
     * User-Agent
     */
    private final String userAgent;

    /**
     * 单轮超时（毫秒）
     */
    private final long timeoutMillis;

    /**
     * HTTP 客户端
     */
    private final HttpClient httpClient;

    /**
     * 构造 HTTP 通道
     *
     * @param baseUrl       站点根地址
     * @param cookie        Cookie 字符串
     * @param userAgent     User-Agent
     * @param timeoutMillis 单轮超时（毫秒）
     * @param proxy         代理地址，可为空
     */
    public InceptionHttpTransport(String baseUrl, String cookie, String userAgent,
                                  long timeoutMillis, String proxy) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.userAgent = StringUtils.isBlank(userAgent) ? DEFAULT_USER_AGENT : userAgent;
        this.timeoutMillis = timeoutMillis;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30));
        ProxySelector selector = proxySelector(proxy);
        if (selector != null) {
            builder.proxy(selector);
        }
        this.httpClient = builder.build();
    }

    @Override
    public void chat(String payloadJson, InceptionStreamListener listener) throws Exception {
        String token = readSessionToken();
        HttpRequest request = newRequestBuilder(URI.create(baseUrl + CHAT_PATH))
                .header("Accept", "text/event-stream")
                .header("x-session-token", token)
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<Stream<String>> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(describeFailure(response.statusCode(), response.body()));
        }

        InceptionStreamParser parser = new InceptionStreamParser(listener);
        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> parser.feed(line + "\n"));
        }
        parser.flush();
        if (parser.error() != null) {
            throw new IllegalStateException("Inception 上游返回错误: " + parser.error());
        }
    }

    /**
     * 读取会话 token
     *
     * @return 会话 token，可能为空串
     */
    private String readSessionToken() throws IOException, InterruptedException {
        HttpRequest request = newRequestBuilder(URI.create(baseUrl + SESSION_PATH))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException(describeFailure(response.statusCode(), Stream.of(response.body())));
        }
        Map<String, Object> body;
        try {
            body = Json.fromJson(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("解析 /api/session 响应失败: " + truncate(response.body()), e);
        }
        if (body == null) {
            return "";
        }
        Object token = body.get("token");
        return token instanceof String str ? str : "";
    }

    /**
     * 创建带公共请求头的构建器
     *
     * @param uri 目标地址
     * @return 请求构建器
     */
    private HttpRequest.Builder newRequestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Origin", baseUrl)
                .header("Referer", baseUrl + "/")
                .header("User-Agent", userAgent);
        if (StringUtils.isNotBlank(cookie)) {
            builder.header("Cookie", cookie);
        }
        return builder;
    }

    /**
     * 组装错误信息
     *
     * @param status 状态码
     * @param body   响应体
     * @return 错误描述
     */
    private static String describeFailure(int status, Stream<String> body) {
        StringBuilder snippet = new StringBuilder();
        try (Stream<String> lines = body) {
            lines.limit(20).forEach(line -> snippet.append(line).append('\n'));
        } catch (Exception e) {
            snippet.append("<读取响应体失败: ").append(e.getMessage()).append('>');
        }
        return describeFailure(status, snippet.toString());
    }

    /**
     * 组装错误信息
     *
     * @param status 状态码
     * @param body   响应体
     * @return 错误描述
     */
    private static String describeFailure(int status, String body) {
        String text = body == null ? "" : body.trim();
        if (status == 429 || text.contains("Security Checkpoint")
                || text.contains("requires a challenge")) {
            return "Inception 上游被 Vercel 安全网关拦截（HTTP " + status + "）：该网关按 TLS 指纹判定，"
                    + "纯 HTTP 客户端即使 Cookie 有效也会被拒绝，请改用浏览器通道"
                    + "（-Dinception.transport=browser，默认值）或让上游走自建反代。响应片段: " + truncate(text);
        }
        return "Inception 上游返回 HTTP " + status + "，响应片段: " + truncate(text);
    }

    /**
     * 截断文本
     *
     * @param text 原始文本
     * @return 截断后的文本
     */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(0, 400) + "...";
    }

    /**
     * 解析代理地址
     *
     * @param proxyStr 代理地址，支持 http://、socks5://
     * @return 代理选择器，未配置返回 null
     */
    private static ProxySelector proxySelector(String proxyStr) {
        if (StringUtils.isBlank(proxyStr)) {
            return null;
        }
        Proxy.Type proxyType;
        String hostPort;
        String text = proxyStr.trim();
        if (text.startsWith("socks5://") || text.startsWith("socks://")) {
            proxyType = Proxy.Type.SOCKS;
            hostPort = text.substring(text.indexOf("://") + 3);
        } else if (text.startsWith("http://")) {
            proxyType = Proxy.Type.HTTP;
            hostPort = text.substring(7);
        } else if (text.startsWith("https://")) {
            proxyType = Proxy.Type.HTTP;
            hostPort = text.substring(8);
        } else {
            proxyType = Proxy.Type.HTTP;
            hostPort = text;
        }
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        final Proxy proxy = new Proxy(proxyType, new InetSocketAddress(host, port));
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {
            }
        };
    }
}
