// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.sse;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.chua.common.support.utils.StringUtils;

/**
 * 基于 JDK {@link HttpClient} 的 SSE 客户端实现
 *
 * <p>使用 Java 标准库中的 {@link HttpClient} 建立 HTTP 连接，
 * 通过 {@link HttpResponse.BodyHandlers#ofInputStream()} 逐行读取 SSE 事件流，
 * 使用 {@link SseProtocolParser}（W3C 规范状态机）解析事件并回调 {@link SseListener}。
 *
 * <p>支持断线自动重连（{@link SseRequest#isReconnect()}）：
 * <ul>
 *   <li>重连请求自动携带 {@code Last-Event-ID} 头，服务端可据此断点续传</li>
 *   <li>重连间隔优先采用服务端 {@code retry:} 字段建议值，否则按指数退避（3s 起步、上限 30s）</li>
 *   <li>仅重试 5xx 与 429；4xx（除 429）视为不可恢复，直接结束</li>
 *   <li>每次重连触发 {@link SseListener#onReconnect(int, long)}</li>
 * </ul>
 *
 * <p>无需任何第三方依赖，作为 {@link SseClient} SPI 的默认实现。
 *
 * @author CH
 * @since 2026/07/21
 */
@Slf4j
@Spi("jdk")
public class JdkSseClient implements SseClient {

    /**
     * SSE 结束标记
     */
    private static final String DONE_MARKER = "[DONE]";

    /**
     * 共享的 JDK HttpClient 实例（线程安全）
     */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 自定义连接超时对应的 HttpClient 缓存（键：connectTimeout 毫秒值）。
     *
     * <p>HttpClient 线程安全且各自持有连接池；重连模式下每次 openStream 都新建实例，
     * 会反复丢弃已建立的连接池并重新握手。按超时值缓存后，除进程级共享的
     * {@link #SHARED_HTTP_CLIENT}（30000ms）外，自定义超时也只构建一次。</p>
     */
    private static final Map<Long, HttpClient> CLIENTS_BY_CONNECT_TIMEOUT = new ConcurrentHashMap<>();

    /**
     * 共享的 InputStream 响应体 handler（无状态，所有建流复用）
     */
    private static final HttpResponse.BodyHandler<InputStream> INPUT_STREAM_HANDLER =
            HttpResponse.BodyHandlers.ofInputStream();

    /**
     * 默认重连间隔（毫秒）
     */
    private static final long DEFAULT_RECONNECT_INTERVAL_MILLIS = 3000L;

    /**
     * 指数退避重连间隔上限（毫秒）
     */
    private static final long MAX_RECONNECT_INTERVAL_MILLIS = 30000L;

    /**
     * 服务端 retry 字段重连间隔上限（毫秒），防止异常值导致无限期挂起
     */
    private static final long MAX_SERVER_RETRY_MILLIS = 60000L;

    /**
     * 连接
     */
    @Override
    public SseConnection connect(SseRequest request, SseListener listener) {
        if (request.isReconnect()) {
            // 重连模式：连接在后台虚拟线程中建立与维护，立即返回可关闭的句柄
            JdkSseConnection connection = new JdkSseConnection();
            Thread readerThread = Thread.ofVirtual().name("sse-reader")
                    .start(() -> readLoop(request, listener, connection));
            connection.readerThread = readerThread;
            return connection;
        }
        return connectOnce(request, listener);
    }

    /**
     * 单次连接（不重连）：同步建立连接，失败立即回调 onError 并返回已关闭连接。
     *
     * <p>保持既有行为：调用方拿到返回值时，HTTP 连接已建立（或已失败）。</p>
     */
    private SseConnection connectOnce(SseRequest request, SseListener listener) {
        try {
            HttpResponse<InputStream> response = openStream(request, null);
            if (response.statusCode() != 200) {
                String errorBody = readErrorBody(response.body());
                listener.onError(new RuntimeException(
                        "SSE 连接失败，状态码: " + response.statusCode() + ", body: " + errorBody));
                return ClosedSseConnection.INSTANCE;
            }

            JdkSseConnection connection = new JdkSseConnection();
            var ignoredEventId = new AtomicReference<String>();
            AtomicLong ignoredRetryHint = new AtomicLong(-1);
            Thread readerThread = Thread.ofVirtual().name("sse-reader").start(() -> {
                try {
                    readEvents(response.body(), listener, connection, ignoredEventId, ignoredRetryHint);
                    if (connection.isConnected()) {
                        listener.onComplete();
                    }
                } catch (Exception e) {
                    if (connection.isConnected()) {
                        listener.onError(e);
                    }
                }
            });
            connection.readerThread = readerThread;
            return connection;
        } catch (Exception e) {
            listener.onError(e);
            return ClosedSseConnection.INSTANCE;
        }
    }

    /**
     * 重连模式主循环：断线后按退避策略自动重连，重连时携带 Last-Event-ID 断点续传。
     */
    private void readLoop(SseRequest request, SseListener listener, JdkSseConnection connection) {
        AtomicReference<String> lastEventId = new AtomicReference<>();
        AtomicLong retryHint = new AtomicLong(-1);
        long backoff = DEFAULT_RECONNECT_INTERVAL_MILLIS;
        int attempt = 0;
        while (connection.isConnected()) {
            try {
                HttpResponse<InputStream> response = openStream(request, lastEventId.get());
                if (response.statusCode() != 200) {
                    String errorBody = readErrorBody(response.body());
                    listener.onError(new RuntimeException(
                            "SSE 连接失败，状态码: " + response.statusCode() + ", body: " + errorBody));
                    if (!isRetryableStatus(response.statusCode())) {
                        // 4xx（除 429）等不可恢复状态：放弃重连
                        connection.close();
                        return;
                    }
                } else {
                    backoff = DEFAULT_RECONNECT_INTERVAL_MILLIS;
                    attempt = 0;
                    readEvents(response.body(), listener, connection, lastEventId, retryHint);
                    // 正常返回 = 流结束（EOF），重连模式下继续下一轮
                }
            } catch (Exception e) {
                if (!connection.isConnected()) {
                    return;
                }
                listener.onError(e);
            }
            if (!connection.isConnected() || !request.isReconnect()) {
                return;
            }
            attempt++;
            long delay = retryHint.get() > 0
                    ? Math.min(retryHint.get(), MAX_SERVER_RETRY_MILLIS)
                    : backoff;
            listener.onReconnect(attempt, delay);
            if (!sleepQuietly(delay)) {
                return;
            }
            backoff = Math.min(backoff * 2, MAX_RECONNECT_INTERVAL_MILLIS);
        }
    }

    /**
     * 建立 HTTP 流连接。
     *
     * @param request     SSE 请求参数
     * @param lastEventId 断点续传的事件 ID，null 表示首次连接
     * @return JDK 响应（InputStream 体）
     * @throws Exception 连接或发送失败
     */
    private HttpResponse<InputStream> openStream(SseRequest request, String lastEventId) throws Exception {
        // 选择客户端：按连接超时值复用（30000ms 命中进程级共享实例），避免重连时反复建池
        HttpClient httpClient = CLIENTS_BY_CONNECT_TIMEOUT.computeIfAbsent(
                request.getConnectTimeout(), timeout -> timeout == 30000L
                        ? SHARED_HTTP_CLIENT
                        : HttpClient.newBuilder()
                                .connectTimeout(Duration.ofMillis(timeout))
                                .build());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(request.getUrl()))
                .timeout(readTimeout(request));

        Map<String, String> headers = request.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        // 断点续传：携带最后收到的事件 ID
        if (StringUtils.isNotEmpty(lastEventId)) {
            builder.header("Last-Event-ID", lastEventId);
        }

        switch (request.getMethod()) {
            case POST:
                builder.POST(bodyPublisher(request));
                break;
            case PUT:
                builder.PUT(bodyPublisher(request));
                break;
            case DELETE:
                builder.DELETE();
                break;
            default:
                builder.GET();
                break;
        }

        return httpClient.send(builder.build(), INPUT_STREAM_HANDLER);
    }

    /**
     * 读取并解析 SSE 事件流（W3C 规范：data/event/id/retry/注释行）。
     *
     * <p>正常返回表示流已结束（EOF）；异常向上抛出由调用方处理。</p>
     */
    private void readEvents(InputStream stream, SseListener listener, JdkSseConnection connection,
            AtomicReference<String> lastEventId, AtomicLong retryHint) throws Exception {
        SseProtocolParser parser = new SseProtocolParser(event -> {
            if (StringUtils.isNotEmpty(event.getId())) {
                lastEventId.set(event.getId());
            }
            if (event.getRetry() != null && event.getRetry() > 0) {
                retryHint.set(event.getRetry());
            }
            String data = event.getData();
            if (StringUtils.isEmpty(data) || DONE_MARKER.equals(data.trim())) {
                return;
            }
            try {
                listener.onData(data);
            } catch (Exception e) {
                log.warn("SSE onData 回调异常: {}", e.getMessage(), e);
            }
        });
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while (connection.isConnected() && (line = reader.readLine()) != null) {
                parser.parseLine(line);
            }
            parser.flush();
        }
    }

    /**
     * 判断该状态码在重连模式下是否值得重试：仅 5xx 与 429。
     */
    private static boolean isRetryableStatus(int statusCode) {
        return statusCode >= 500 || statusCode == 429;
    }

    /**
     * 休眠（可中断）：线程被中断时恢复中断标记并返回 false。
     */
    private static boolean sleepQuietly(long delayMillis) {
        if (delayMillis <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 读取错误响应体（安全：读取失败返回空串）。
     */
    private static String readErrorBody(InputStream body) {
        try (InputStream in = body) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * JDK SSE 连接实现，跟踪连接状态
     */
    private static class JdkSseConnection implements SseConnection {

        /**
         * 读取线程
         */
        private volatile Thread readerThread;

        /**
         * 连接是否已关闭
         */
        private volatile boolean closed;

        /**
         * 关闭
         */
        @Override
        public void close() {
            closed = true;
            if (readerThread != null) {
                readerThread.interrupt();
            }
        }

        /**
         * 是否Connected
         */
        @Override
        public boolean isConnected() {
            return !closed;
        }
    }

    /**
     * 已关闭的空连接（用于错误路径）
     */
    private static final class ClosedSseConnection implements SseConnection {

        /**
         * 单例实例
         */
        static final ClosedSseConnection INSTANCE = new ClosedSseConnection();

        /**
         * 关闭
         */
        @Override
        public void close() {
        }

        /**
         * 是否Connected
         */
        @Override
        public boolean isConnected() {
            return false;
        }
    }

    /**
     * 根据请求体构建 BodyPublisher
     *
     * @param request SSE 请求参数
     * @return BodyPublisher
     */
    private static HttpRequest.BodyPublisher bodyPublisher(SseRequest request) {
        String body = request.getBody();
        if (StringUtils.isEmpty(body)) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofString(body);
    }

    /**
     * 计算读取超时
     *
     * <p>SSE 长连接通常不应设置读取超时（0 = 不超时），
     * 但为避免死连接，可配置一个较大的值。
     *
     * @param request SSE 请求参数
     * @return Duration，0 返回 null（JDK HttpClient null = 不超时）
     */
    private static Duration readTimeout(SseRequest request) {
        long timeout = request.getReadTimeout();
        return timeout > 0 ? Duration.ofMillis(timeout) : null;
    }
}
