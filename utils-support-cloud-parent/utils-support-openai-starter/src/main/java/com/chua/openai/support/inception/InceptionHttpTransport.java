// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.openai.support.inception;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.sse.SseClient;
import com.chua.common.support.network.sse.SseConnection;
import com.chua.common.support.network.sse.SseListener;
import com.chua.common.support.network.sse.SseRequest;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 纯 HTTP 版 Inception 传输通道
 *
 * <p>通过仓库统一的 {@link HttpClientFactory} 访问 {@code /api/session}，并通过
 * {@link SseClient} SPI 订阅 {@code /api/chat} 的事件流。
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
     * 连接超时（毫秒）
     */
    private static final long CONNECT_TIMEOUT_MILLIS = 30000L;

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
     * 代理地址（{@code host:port}），可为空
     */
    private final String proxy;

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
        this.proxy = normalizeProxy(proxy);
    }

    @Override
    public void chat(String payloadJson, InceptionStreamListener listener) throws Exception {
        String token = readSessionToken();
        InceptionStreamParser parser = new InceptionStreamParser(listener);
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        SseConnection[] holder = new SseConnection[1];

        Map<String, String> headers = new LinkedHashMap<>(commonHeaders());
        headers.put("Accept", "text/event-stream");
        headers.put("x-session-token", token);

        SseRequest request = SseRequest.builder()
                .url(baseUrl + CHAT_PATH)
                .method(HttpMethod.POST)
                .headers(headers)
                .body(payloadJson)
                .connectTimeout(CONNECT_TIMEOUT_MILLIS)
                .readTimeout(timeoutMillis)
                .build();

        SseConnection connection = SseClient.create().connect(request, new SseListener() {
            /**
             * 收到 SSE 数据帧
             *
             * @param data data 内容
             */
            @Override
            public void onData(String data) {
                // 复用既有行解析器：把负载还原成一行 SSE 再喂入
                parser.feed("data: " + data + "\n");
            }

            /**
             * 流式连接正常结束
             */
            @Override
            public void onComplete() {
                done.countDown();
            }

            /**
             * 流式连接异常结束
             *
             * @param error 异常信息
             */
            @Override
            public void onError(Throwable error) {
                failure[0] = error;
                done.countDown();
            }
        });
        holder[0] = connection;

        long waitMillis = timeoutMillis > 0L ? timeoutMillis + 60_000L : 0L;
        if (waitMillis > 0L) {
            if (!done.await(waitMillis, TimeUnit.MILLISECONDS)) {
                connection.close();
                throw new IllegalStateException("Inception 流式响应超时（" + waitMillis + "ms 未结束）");
            }
        } else {
            done.await();
        }
        parser.flush();
        if (failure[0] != null) {
            throw new IllegalStateException("Inception 流式请求失败: " + failure[0].getMessage(), failure[0]);
        }
        if (parser.error() != null) {
            throw new IllegalStateException("Inception 上游返回错误: " + parser.error());
        }
    }

    /**
     * 读取会话 token
     *
     * @return 会话 token，可能为空串
     */
    private String readSessionToken() {
        ClientResponse response = newBuilder(SESSION_PATH)
                .header("Accept", "application/json")
                .readTimeout(timeoutMillis > 0L ? timeoutMillis : 60000L)
                .get();
        String body = response.getBodyString();
        if (response.getStatusCode() != 200) {
            throw new IllegalStateException(describeFailure(response.getStatusCode(), body));
        }
        Map<String, Object> parsed;
        try {
            parsed = Json.fromJson(body);
        } catch (Exception e) {
            throw new IllegalStateException("解析 /api/session 响应失败: " + truncate(body), e);
        }
        if (parsed == null) {
            return "";
        }
        Object token = parsed.get("token");
        return token instanceof String str ? str : "";
    }

    /**
     * 创建带公共配置的请求构建器
     *
     * @param path 请求路径（含前导斜杠）
     * @return 请求构建器
     */
    private HttpClientBuilder newBuilder(String path) {
        HttpClientBuilder builder = HttpClientFactory.of(baseUrl)
                .path(path)
                .connectTimeout(CONNECT_TIMEOUT_MILLIS);
        commonHeaders().forEach(builder::header);
        if (proxy != null) {
            int idx = proxy.lastIndexOf(':');
            try {
                builder.proxy(proxy.substring(0, idx), Integer.parseInt(proxy.substring(idx + 1)));
            } catch (RuntimeException e) {
                log.warn("[inception] 代理端口非法，已忽略: {}", proxy);
            }
        }
        return builder;
    }

    /**
     * 组装公共请求头
     *
     * @return 请求头
     */
    private Map<String, String> commonHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Origin", baseUrl);
        headers.put("Referer", baseUrl + "/");
        headers.put("User-Agent", userAgent);
        if (StringUtils.isNotBlank(cookie)) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    /**
     * 规范化代理地址：剥离协议前缀，保留 {@code host:port}
     *
     * @param proxyStr 原始代理地址
     * @return {@code host:port}；未配置返回 null
     */
    private static String normalizeProxy(String proxyStr) {
        if (StringUtils.isBlank(proxyStr)) {
            return null;
        }
        String hostPort = proxyStr.trim();
        int sep = hostPort.indexOf("://");
        if (sep >= 0) {
            hostPort = hostPort.substring(sep + 3);
        }
        int idx = hostPort.lastIndexOf(':');
        return idx > 0 && idx < hostPort.length() - 1 ? hostPort : null;
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
}