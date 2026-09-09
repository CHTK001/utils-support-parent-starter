package com.chua.common.support.network.http.invoke;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.http.*;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 Java 9+ {@link java.net.http.HttpClient} 的 SSE 客户端实现
 * <p>
 * SPI key: {@code "httpclient-sse"}，与 {@link JdkSseClientInvoker}（key="jdk"）并存，
 * 本实现专注于标准 text/event-stream 协议，支持虚拟线程、异步流式读取。
 * </p>
 *
 * @author CH
 * @since 2026-03-16
 */
@Slf4j
@Spi(value = "httpclient-sse", order = 0)
public class HttpClientSseClientInvoker extends AbstractSseClientInvoker implements AutoCloseable {

    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String DATA_PREFIX  = "data:";
    private static final String ID_PREFIX    = "id:";
    private static final String EVENT_PREFIX = "event:";
    private static final String RETRY_PREFIX = "retry:";

    private final HttpClient httpClient;
    private volatile boolean closed = false;
    private CompletableFuture<Void> future;
    private ExecutorService executor;
    private EventSourceListener activeListener;

    public HttpClientSseClientInvoker(HttpRequest request, HttpMethod httpMethod) {
        super(request, httpMethod);
        this.httpClient = buildHttpClient();
    }

    // ---- SseClientInvoker ----

    @Override
    public void execute(EventSourceListener listener) {
        this.activeListener = listener;
        executor = Executors.newVirtualThreadPerTaskExecutor();

        java.net.http.HttpRequest httpRequest;
        try {
            httpRequest = buildRequest();
        } catch (Exception e) {
            listener.onFailure(e);
            return;
        }

        listener.onOpen();

        future = httpClient
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                .thenAcceptAsync(response -> {
                    if (closed) return;
                    int status = response.statusCode();
                    if (status >= 400) {
                        listener.onFailure(new RuntimeException("HTTP " + status));
                        return;
                    }
                    String ct = response.headers().firstValue("Content-Type").orElse("");
                    if (!ct.contains(TEXT_EVENT_STREAM)) {
                        log.warn("[HttpClientSse] Content-Type 不是 text/event-stream: {}", ct);
                    }
                    parseSse(response.body(), listener);
                }, executor)
                .exceptionally(t -> {
                    if (!closed) listener.onFailure(t);
                    return null;
                });
    }

    @Override
    public void close() {
        closed = true;
        if (future != null && !future.isDone()) future.cancel(true);
        if (executor != null) executor.shutdown();
        if (activeListener != null) activeListener.onClosed();
    }

    // ---- 私有方法 ----

    private HttpClient buildHttpClient() {
        if (request.getClient() instanceof HttpClient c) return c;
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(request.getConnectTimeoutMill()))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private java.net.http.HttpRequest buildRequest() throws Exception {
        var builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.getUrl()))
                .timeout(Duration.ofMillis(request.getReadTimeoutMill()))
                .header("Accept", TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache");

        // 追加自定义请求头
        HttpHeader headers = request.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.asSimpleMap().entrySet()) {
                // 避免重复设置 Accept
                if (!"Accept".equalsIgnoreCase(e.getKey())) {
                    builder.header(e.getKey(), e.getValue());
                }
            }
        }

        // 请求体
        BodyPublisher body = resolveBody();
        switch (httpMethod) {
            case GET    -> builder.GET();
            case POST   -> builder.POST(body);
            case PUT    -> builder.PUT(body);
            case DELETE -> builder.DELETE();
            default     -> builder.method(httpMethod.name(), body);
        }

        return builder.build();
    }

    private BodyPublisher resolveBody() {
        BodyData bd = request.getBodyData();
        if (bd == null || bd.isEmpty()) return BodyPublishers.noBody();
        byte[] bytes = bd.toByteArray();
        return (bytes != null && bytes.length > 0)
                ? BodyPublishers.ofByteArray(bytes)
                : BodyPublishers.noBody();
    }

    private void parseSse(InputStream is, EventSourceListener listener) {
        try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            SseEvent current = new SseEvent();
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (current.hasData()) {
                        listener.onEvent(current.id, current.type, current.data);
                        current = new SseEvent();
                    }
                    continue;
                }
                if (line.startsWith(DATA_PREFIX)) {
                    String d = line.substring(DATA_PREFIX.length()).trim();
                    current.data = current.data == null ? d : current.data + "\n" + d;
                } else if (line.startsWith(ID_PREFIX)) {
                    current.id = line.substring(ID_PREFIX.length()).trim();
                } else if (line.startsWith(EVENT_PREFIX)) {
                    current.type = line.substring(EVENT_PREFIX.length()).trim();
                } else if (line.startsWith(RETRY_PREFIX)) {
                    // 忽略重试提示
                } else if (!line.startsWith(":")) {
                    current.data = current.data == null ? line : current.data + "\n" + line;
                }
            }
            if (current.hasData()) {
                listener.onEvent(current.id, current.type, current.data);
            }
        } catch (Exception e) {
            if (!closed) listener.onFailure(e);
        } finally {
            if (!closed) listener.onClosed();
        }
    }

    private static class SseEvent {
        String id, type, data;
        boolean hasData() { return data != null && !data.isEmpty(); }
    }
}
