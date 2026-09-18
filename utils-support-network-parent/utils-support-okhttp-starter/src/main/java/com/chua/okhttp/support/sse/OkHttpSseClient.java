package com.chua.okhttp.support.sse;

import com.chua.common.support.network.sse.SseClient;
import com.chua.common.support.network.sse.SseConnection;
import com.chua.common.support.network.sse.SseListener;
import com.chua.common.support.network.sse.SseRequest;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
* 基于 OkHttp3 的 SSE 客户端实现
*
* <p>使用 OkHttp3 建立 HTTP 连接，通过响应体的 {@link InputStream} 逐行读取 SSE 事件流，
* 解析 {@code data:} 前缀并回调 {@link SseListener}。
*
* <p>使用 OkHttp3 的优势：
* <ul>
*   <li>连接池复用 — 减少重复建连开销</li>
*   <li>HTTP/2 支持 — 多路复用提升并发性能</li>
*   <li>完善的拦截器机制 — 便于日志、重试、鉴权等扩展</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("okhttp")
@ConditionalOnClass("okhttp3.OkHttpClient")
public class OkHttpSseClient implements SseClient {

    /**
    * SSE 数据行前缀
    */
    private static final String DATA_PREFIX = "data: ";

    /**
    * SSE 结束标记
    */
    private static final String DONE_MARKER = "[DONE]";

    /**
    * 默认 OkHttp 客户端
    */
    private final OkHttpClient defaultClient;

    /**
    * 构造 OkHttp SSE 客户端
    *
    * <p>使用默认配置创建 OkHttpClient 实例。
    */
    public OkHttpSseClient() {
        this.defaultClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                // Unit.SECONDS) // SSE 长连接不超时
                .readTimeout(0, TimeUnit.SECONDS)
                .build();
    }

    @Override
    /** 连接 */
    public SseConnection connect(SseRequest request, SseListener listener) {
        try {
            // 构建 OkHttp 请求
            Request.Builder builder = new Request.Builder()
                    .url(request.getUrl());

            // 设置请求头
            Map<String, String> headers = request.getHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }

            // 设置请求体和 HTTP 方法
            RequestBody body = null;
            String requestBody = request.getBody();
            if (requestBody != null && !requestBody.isEmpty()) {
                body = RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8"));
            }
            builder.method(request.getMethod().name(), body);

            // 构建自定义客户端（按请求配置调整超时）
            OkHttpClient client = defaultClient.newBuilder()
                    .connectTimeout(request.getConnectTimeout(), TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeout(request), TimeUnit.MILLISECONDS)
                    .build();

            Request okRequest = builder.build();

            // 执行请求并获取响应
            Response response = client.newCall(okRequest).execute();

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null
                        ? new String(response.body().bytes(), StandardCharsets.UTF_8)
                        : "";
                response.close();
                listener.onError(new RuntimeException(
                        "SSE 连接失败，状态码: " + response.code() + ", body: " + errorBody));
                return ClosedSseConnection.INSTANCE;
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                response.close();
                listener.onError(new RuntimeException("SSE 响应体为空"));
                return ClosedSseConnection.INSTANCE;
            }

            // 在虚拟线程中读取 SSE 事件流
            InputStream inputStream = responseBody.byteStream();
            OkHttpSseConnection connection = new OkHttpSseConnection(response);
            Thread readerThread = Thread.ofVirtual().name("sse-reader-okhttp").start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
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
                } finally {
                    response.close();
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
    * OkHttp SSE 连接实现，跟踪连接状态
    * @author CH
    * @since 4.0.0
    */
    private static class OkHttpSseConnection implements SseConnection {

        /**
        * 读取线程
        */
        private volatile Thread readerThread;

        /**
        * 连接是否已关闭
        */
        private volatile boolean closed;

        /**
        * OkHttp 响应（用于关闭底层连接）
        */
        private final Response response;

        /**
        * 构造 OkHttp SSE 连接
        *
        * @param response OkHttp 响应
        */
        OkHttpSseConnection(Response response) {
            this.response = response;
        }

        @Override
        /** 关闭 */
        public void close() {
            closed = true;
            if (readerThread != null) {
                readerThread.interrupt();
            }
            response.close();
        }

        @Override
        /** 是否连接 */
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
        /** 是否连接 */
        public boolean isConnected() {
            return false;
        }
    }

    /**
        * 计算读取超时
        *
        * <p>SSE 长连接通常不应设置读取超时（0 = 不超时）。
        *
        * @param request SSE 请求参数
        * @return 超时毫秒数，0 返回 0（不超时）
        */
    private static long readTimeout(SseRequest request) {
        return request.getReadTimeout();
    }
}
