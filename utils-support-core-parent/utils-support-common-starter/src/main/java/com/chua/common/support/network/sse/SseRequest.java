package com.chua.common.support.network.sse;

import com.chua.common.support.network.http.HttpMethod;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * SSE 请求参数
 *
 * <p>封装建立 SSE 连接所需的全部参数，通过 {@link Builder} 链式构建。
 *
 * <h3>构建示例</h3>
 * <pre>{@code
 *   SseRequest request = SseRequest.builder()
 *       .url("https://api.example.com/stream")
 *       .method(HttpMethod.POST)
 *       .header("Authorization", "Bearer sk-xxx")
 *       .header("Content-Type", "application/json")
 *       .body("{\"prompt\":\"hello\"}")
 *       .connectTimeout(15000)
 *       .build();
 * }</pre>
 *
 * @author CH
 * @since 2026/07/21
 */
@Data
@Builder
public class SseRequest {

    /**
     * 请求地址
     *
     * <p>完整的 SSE 端点 URL，包含协议、主机、端口和路径。
     * 例如：{@code "https://api.openai.com/v1/chat/completions"}。
     */
    private String url;

    /**
     * HTTP 请求方法
     *
     * <p>SSE 通常使用 GET 或 POST。AI 对话流式接口通常使用 POST。
     * 默认为 {@link HttpMethod#GET}。
     */
    @Builder.Default
    /**
     * 方法名
     */
    private HttpMethod method = HttpMethod.GET;

    /**
     * 请求头
     *
     * <p>需要透传给服务端的 HTTP 请求头，如 Authorization、Content-Type 等。
     * 默认为空 Map。
     */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    /**
     * 请求体
     *
     * <p>POST/PUT 请求的请求体内容。GET 请求该字段为 null。
     * 通常为 JSON 字符串。
     */
    private String body;

    /**
     * 连接超时时间（毫秒）
     *
     * <p>建立 TCP 连接的最大等待时间。默认 30 秒。
     */
    @Builder.Default
    /**
     * 连接超时时间（毫秒）
     */
    private long connectTimeout = 30000;

    /**
     * 读取超时时间（毫秒）
     *
     * <p>等待服务端返回数据的最大间隔。SSE 是长连接，服务端可能长时间不发送数据，
     * 因此默认 0（不超时）。
     */
    @Builder.Default
    /**
     * 读取超时时间（毫秒）
     */
    private long readTimeout = 0;

    /**
     * 是否自动重连。
     *
     * <p>SSE 连接断开后是否自动重新建立连接。默认 false。
     */
    @Builder.Default
    /** Reconnect */
    private boolean reconnect = false;

    /**
    * 添加单个请求头
    *
    * <p>便捷方法，用于在构建器之外修改请求头。
    *
    * @param name  请求头名称
    * @param value 请求头值
    * @return 当前实例
    */
    public SseRequest addHeader(String name, String value) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        headers.put(name, value);
        return this;
    }
}
