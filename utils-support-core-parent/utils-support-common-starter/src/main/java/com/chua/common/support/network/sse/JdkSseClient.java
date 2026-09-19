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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 JDK {@link HttpClient} 的 SSE 客户端实现
 *
 * <p>使用 Java 标准库中的 {@link HttpClient} 建立 HTTP 连接，
 * 通过 {@link HttpResponse.BodyHandlers#ofInputStream()} 逐行读取 SSE 事件流，
 * 解析 {@code data:} 前缀并回调 {@link SseListener}。
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
     * SSE 数据行前缀
     */
    private static final String DATA_PREFIX = "data: ";

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

    @Override
    /** 连接 */
    public SseConnection connect(SseRequest request, SseListener listener) {
        // 选择客户端：默认超时用共享实例，自定义超时新建
        HttpClient httpClient = request.getConnectTimeout() == 30000
                ? SHARED_HTTP_CLIENT
                : HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(request.getConnectTimeout()))
                        .build();

        try {
            // 构建 HTTP 请求
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.getUrl()))
                    .timeout(readTimeout(request));

            // 设置请求头
            Map<String, String> headers = request.getHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }

            // 设置请求体和 HTTP 方法
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

            HttpRequest httpRequest = builder.build();

            // 发送请求并获取响应流
            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                listener.onError(new RuntimeException(
                        "SSE 连接失败，状态码: " + response.statusCode() + ", body: " + errorBody));
                return ClosedSseConnection.INSTANCE;
            }

            // 在虚拟线程中读取 SSE 事件流
            JdkSseConnection connection = new JdkSseConnection();
            Thread readerThread = Thread.ofVirtual().name("sse-reader").start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while (connection.isConnected() && (line = reader.readLine()) != null) {
                        if (!line.startsWith(DATA_PREFIX)) {
                            continue;
                        }
                        String data = line.substring(DATA_PREFIX.length()).trim();
                        if (data.isEmpty() || DONE_MARKER.equals(data)) {
                            continue;
                        }
                        try {
                            listener.onData(data);
                        } catch (Exception e) {
                            log.warn("SSE onData 回调异常: {}", e.getMessage());
                        }
                    }
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

        @Override
        /** 关闭 */
        public void close() {
            closed = true;
            if (readerThread != null) {
                readerThread.interrupt();
            }
        }

        @Override
        /** 是否Connected */
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

        @Override
        /** 关闭 */
        public void close() {
        }

        @Override
        /** 是否Connected */
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
        if (body == null || body.isEmpty()) {
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
