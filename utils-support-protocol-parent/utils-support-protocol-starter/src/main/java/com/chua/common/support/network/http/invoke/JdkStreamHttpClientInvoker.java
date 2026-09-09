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
 * 基于 JDK HttpClient 的 Stream-http 客户端实现
 *
 * @author CH
 */
@Slf4j
@Spi(value = "jdk", order = 0)
public class JdkStreamHttpClientInvoker extends AbstractStreamHttpClientInvoker implements AutoCloseable {
    private CompletableFuture<Void> responseFuture;
    private ExecutorService executorService;
    private volatile boolean closed = false;
    private StreamListener listener;

    public JdkStreamHttpClientInvoker() {
        super();
    }

    public JdkStreamHttpClientInvoker(HttpRequest request, HttpMethod httpMethod) {
        super(request, httpMethod);
    }

    @Override
    public void execute(StreamListener listener) {
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
                    
                    parseStream(response.body(), listener);
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
     * 解析流式响应
     *
     * @param inputStream 输入流
     * @param listener 监听器
     */
    private void parseStream(InputStream inputStream, StreamListener listener) {
        try (var reader = new BufferedReader(
            new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            
            var buffer = new char[8192];
            int bytesRead;
            var stringBuilder = new StringBuilder();
            
            while (!closed && (bytesRead = reader.read(buffer)) != -1) {
                var chunk = new String(buffer, 0, bytesRead);
                listener.onData(chunk.getBytes(StandardCharsets.UTF_8));
                stringBuilder.append(chunk);
                
                // 按行处理文本数据
                var text = stringBuilder.toString();
                var lines = text.split("\n", -1);
                stringBuilder = new StringBuilder(lines[lines.length - 1]);
                
                for (int i = 0; i < lines.length - 1; i++) {
                    if (!lines[i].isEmpty()) {
                        listener.onText(lines[i]);
                    }
                }
            }
            
            // 处理剩余数据
            if (stringBuilder.length() > 0) {
                listener.onText(stringBuilder.toString());
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
}

