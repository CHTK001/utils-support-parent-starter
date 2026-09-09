package com.chua.common.support.network.http.invoke;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.http.BodyData;
import com.chua.common.support.network.http.EventSourceListener;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.http.HttpRequest;
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
 * 基于 JDK HttpClient 的 SSE 客户端实现
 *
 * @author CH
 */
@Slf4j
@Spi(value = "jdk", order = 0)
public class JdkSseClientInvoker extends AbstractSseClientInvoker implements AutoCloseable {
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String ACCEPT = "Accept";
    private static final String DATA_PREFIX = "data:";
    private static final String ID_PREFIX = "id:";
    private static final String EVENT_PREFIX = "event:";
    private static final String RETRY_PREFIX = "retry:";

    private CompletableFuture<Void> responseFuture;
    private ExecutorService executorService;
    private volatile boolean closed = false;
    private EventSourceListener listener;

    public JdkSseClientInvoker(HttpRequest request, HttpMethod httpMethod) {
        super(request, httpMethod);
    }

    @Override
    public void execute(EventSourceListener listener) {
        this.listener = listener;
        try {
            var client = createHttpClient();
            var httpRequest = buildHttpRequest(client);
            
            listener.onOpen();
            
            executorService = Executors.newVirtualThreadPerTaskExecutor();
            responseFuture = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                .thenAcceptAsync(response -> {
                    if (closed) {
                        return;
                    }
                    
                    if (response.statusCode() >= 400) {
                        var errorMsg = "HTTP错误: " + response.statusCode();
                        try {
                            if (response.body() != null) {
                                var errorBody = readErrorBody(response.body());
                                errorMsg = errorBody.isEmpty() ? errorMsg : errorBody;
                            }
                        } catch (Exception ignored) {
                            // 忽略读取错误体的异常
                        }
                        listener.onFailure(new RuntimeException(errorMsg));
                        return;
                    }
                    
                    var contentType = response.headers().firstValue("Content-Type").orElse("");
                    if (!contentType.contains(TEXT_EVENT_STREAM)) {
                        log.warn("[SSE客户端][JDK]响应Content-Type不是text/event-stream: {}", contentType);
                    }
                    
                    parseSseStream(response.body(), listener);
                }, executorService)
                .exceptionally(throwable -> {
                    if (!closed) {
                        listener.onFailure(throwable);
                    }
                    return null;
                });
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.cancel(true);
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        if (listener != null) {
            listener.onClosed();
        }
    }

    /**
     * 创建 HttpClient
     *
     * @return HttpClient 实例
     */
    private HttpClient createHttpClient() {
        var builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(request.getConnectTimeoutMill()))
            .executor(Executors.newVirtualThreadPerTaskExecutor());
        
        // 如果请求中指定了自定义客户端，尝试使用
        if (request.getClient() instanceof HttpClient) {
            return (HttpClient) request.getClient();
        }
        
        return builder.build();
    }

    /**
     * 构建 HTTP 请求
     *
     * @param client HttpClient 实例
     * @return HttpRequest 实例
     */
    private java.net.http.HttpRequest buildHttpRequest(HttpClient client) {
        var uri = URI.create(request.getUrl());
        var builder = java.net.http.HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofMillis(request.getReadTimeoutMill()));
        
        // 设置请求方法
        var method = request.getMethod();
        BodyPublisher bodyPublisher = BodyPublishers.noBody();
        
        if (method == HttpMethod.POST || method == HttpMethod.PUT) {
            var bodyData = request.getBodyData();
            if (!bodyData.isEmpty()) {
                var bodyContent = getRequestBody(bodyData);
                bodyPublisher = BodyPublishers.ofString(bodyContent, StandardCharsets.UTF_8);
            }
        }
        
        switch (method) {
            case GET:
                builder = builder.GET();
                break;
            case POST:
                builder = builder.POST(bodyPublisher);
                break;
            case PUT:
                builder = builder.PUT(bodyPublisher);
                break;
            case DELETE:
                builder = builder.DELETE();
                break;
            default:
                builder = builder.method(method.name(), bodyPublisher);
                break;
        }
        
        // 设置请求头
        var headers = request.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.asSimpleMap().entrySet()) {
                builder = builder.header(entry.getKey(), entry.getValue());
            }
        }
        
        // 确保 Accept 头包含 text/event-stream
        if (headers == null || !headers.hasHeader(ACCEPT)) {
            builder = builder.header(ACCEPT, TEXT_EVENT_STREAM);
        }
        
        return builder.build();
    }

    /**
     * 获取请求体内容
     *
     * @param bodyData 请求体数据
     * @return 请求体字符串
     */
    private String getRequestBody(BodyData bodyData) {
        if (bodyData.isJson()) {
            return bodyData.toJson();
        }
        var bodyBytes = bodyData.toByteArray();
        if (bodyBytes != null && bodyBytes.length > 0) {
            return new String(bodyBytes, StandardCharsets.UTF_8);
        }
        return "";
    }

    /**
     * 解析 SSE 流
     *
     * @param inputStream 输入流
     * @param listener 事件监听器
     */
    private void parseSseStream(InputStream inputStream, EventSourceListener listener) {
        try (var reader = new BufferedReader(
            new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            
            String line;
            var currentEvent = new SseEvent();
            
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // 空行表示一个事件结束
                    if (currentEvent.hasData()) {
                        listener.onEvent(currentEvent.id, currentEvent.type, currentEvent.data);
                        currentEvent = new SseEvent();
                    }
                    continue;
                }
                
                if (line.startsWith(DATA_PREFIX)) {
                    var data = line.substring(DATA_PREFIX.length()).trim();
                    if (currentEvent.data == null) {
                        currentEvent.data = data;
                    } else {
                        // 多行数据，追加换行符
                        currentEvent.data += "\n" + data;
                    }
                } else if (line.startsWith(ID_PREFIX)) {
                    currentEvent.id = line.substring(ID_PREFIX.length()).trim();
                } else if (line.startsWith(EVENT_PREFIX)) {
                    currentEvent.type = line.substring(EVENT_PREFIX.length()).trim();
                } else if (line.startsWith(RETRY_PREFIX)) {
                    // 重试间隔，暂时忽略
                    var retry = line.substring(RETRY_PREFIX.length()).trim();
                    try {
                        var retryMs = Long.parseLong(retry);
                        log.debug("[SSE客户端][JDK]服务器建议重试间隔: {}ms", retryMs);
                    } catch (NumberFormatException ignored) {
                        // 忽略无效的重试值
                    }
                } else if (line.startsWith(":")) {
                    // 注释行，忽略
                    continue;
                } else {
                    // 未知格式，作为数据处理
                    if (currentEvent.data == null) {
                        currentEvent.data = line;
                    } else {
                        currentEvent.data += "\n" + line;
                    }
                }
            }
            
            // 处理最后一个事件（如果没有空行结尾）
            if (currentEvent.hasData()) {
                listener.onEvent(currentEvent.id, currentEvent.type, currentEvent.data);
            }
        } catch (Exception e) {
            if (!closed) {
                listener.onFailure(e);
            }
        } finally {
            if (!closed) {
                listener.onClosed();
            }
        }
    }

    /**
     * 读取错误响应体
     *
     * @param inputStream 输入流
     * @return 错误消息
     */
    private String readErrorBody(InputStream inputStream) {
        try (var reader = new BufferedReader(
            new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return reader.lines().reduce("", (a, b) -> a + b + "\n").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * SSE 事件数据类
     */
    private static class SseEvent {
        String id;
        String type;
        String data;

        boolean hasData() {
            return data != null && !data.isEmpty();
        }
    }
}

