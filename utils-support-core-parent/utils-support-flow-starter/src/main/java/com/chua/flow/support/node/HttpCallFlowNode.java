package com.chua.flow.support.node;

import com.chua.common.support.task.flow.FlowContext;
import com.chua.common.support.task.flow.FlowProps;
import com.chua.common.support.task.flow.HttpCallNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP 调用节点。
 *
 * <p>使用 JDK HttpClient 发起 HTTP 请求，响应结果写入流程上下文，
 * 供下游节点消费。支持 GET / POST / PUT / DELETE 等常用方法及自定义请求头。</p>
 *
 * <p>节点属性说明：</p>
 * <ul>
 *   <li>{@code url} — 请求地址（必填）</li>
 *   <li>{@code method} — 请求方法，默认 GET</li>
 *   <li>{@code headers} — 请求头映射（可选）</li>
 *   <li>{@code body} — 请求体（可选，用于 POST/PUT）</li>
 * </ul>
 *
 * <p>执行后写入上下文：</p>
 * <ul>
 *   <li>{@code http.result} — 响应体字符串</li>
 *   <li>{@code http.status} — 响应状态码</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HttpCallFlowNode implements HttpCallNode {

    /**
     * 结果上下文属性键：响应体
     */
    private static final String RESULT_KEY = "http.result";

    /**
     * 结果上下文属性键：响应状态码
     */
    private static final String STATUS_KEY = "http.status";

    /**
     * 默认请求方法
     */
    private static final String DEFAULT_METHOD = "GET";

    /**
     * 请求超时时间（秒）
     */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    /**
     * 执行 HTTP 调用节点。
     *
     * <p>根据节点属性构造请求并发送，响应结果写入实例上下文。
     * 请求失败时抛出运行时异常，由引擎标记实例失败。</p>
     *
     * @param context 当前流程上下文
     */
    @Override
    public void execute(FlowContext context) {
        FlowProps props = context.currentNodeProps();
        String url = props.getString("url");
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("httpCall 节点缺少 url 属性");
        }
        String method = props.getString("method", DEFAULT_METHOD).toUpperCase();
        Map<String, Object> headers = props.getMap("headers");
        String body = props.getString("body");

        HttpResponse<String> response = send(url, method, headers, body);
        context.setAttribute(RESULT_KEY, response.body());
        context.setAttribute(STATUS_KEY, response.statusCode());
    }

    /**
     * 发送 HTTP 请求。
     *
     * @param url     请求地址
     * @param method  请求方法
     * @param headers 请求头映射
     * @param body    请求体
     * @return 响应结果
     */
    private HttpResponse<String> send(String url, String method,
                                      Map<String, Object> headers, String body) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
            for (Map.Entry<String, Object> entry : headers.entrySet()) {
                builder.header(entry.getKey(), String.valueOf(entry.getValue()));
            }
            switch (method) {
                case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(
                        body != null ? body : ""));
                case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(
                        body != null ? body : ""));
                case "DELETE" -> builder.DELETE();
                default -> builder.GET();
            }
            HttpRequest request = builder.build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP 请求被中断: " + url, e);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 请求失败: " + url, e);
        }
    }
}
