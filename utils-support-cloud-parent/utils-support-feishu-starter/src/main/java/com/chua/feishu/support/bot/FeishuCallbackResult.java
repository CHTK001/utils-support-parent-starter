package com.chua.feishu.support.bot;

import lombok.Data;

/**
 * 飞书 Webhook 回调的应答
 * <p>
 * 由 {@link FeishuBotClient#handleCallback(String, java.util.Map)} 返回，
 * 调用方需把 {@link #getStatusCode()} 与 {@link #getBody()} 原样写回 HTTP 响应，
 * 飞书据此判定回调已被受理；URL 验证握手也依赖回显 {@code challenge}。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class FeishuCallbackResult {

    /**
     * JSON 响应内容类型
     */
    private static final String JSON_CONTENT_TYPE
            = "application/json; charset=utf-8";

    /**
     * HTTP 状态码
     */
    private int statusCode;

    /**
     * 响应内容类型
     */
    private String contentType = JSON_CONTENT_TYPE;

    /**
     * 响应体
     */
    private String body;

    /**
     * 创建应答
     *
     * @param statusCode HTTP 状态码
     * @param body       响应体
     * @return 应答
     */
    public static FeishuCallbackResult of(int statusCode, String body) {
        FeishuCallbackResult result = new FeishuCallbackResult();
        result.setStatusCode(statusCode);
        result.setBody(body);
        return result;
    }

    /**
     * 飞书是否已受理本次回调
     *
     * @return true 表示 2xx
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
