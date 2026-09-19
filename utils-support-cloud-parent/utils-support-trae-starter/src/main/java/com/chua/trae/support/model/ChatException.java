package com.chua.trae.support.model;

import java.util.Objects;

/**
 * 聊天异常，承载 HTTP 状态码与错误消息。
 * 在请求失败（认证过期、限流、模型排队、SSE 错误）时抛出。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ChatException extends Exception {
    /**
     * HTTP 状态码，0 表示非 HTTP 错误（如 SSE 内错误事件）
    */
    private final int statusCode;

    /**
     * 创建聊天异常（无状态码）。
     *
     * @param message 错误消息，不可为 空
     */
    public ChatException(String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.statusCode = 0;
    }

    /**
     * 创建带原因的聊天异常（无状态码）。
     *
     * @param message 错误消息，不可为 空
     * @param cause 原因
     */
    public ChatException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.statusCode = 0;
    }

    /**
     * 创建带状态码的聊天异常。
     *
     * @param statusCode HTTP 状态码（如 401/429/4011）
     * @param message 错误消息，不可为 空
     */
    public ChatException(int statusCode, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.statusCode = statusCode;
    }

    /**
     * 创建带状态码和原因的聊天异常。
     *
     * @param statusCode HTTP 状态码
     * @param message 错误消息，不可为 空
     * @param cause 原因
     */
    public ChatException(int statusCode, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.statusCode = statusCode;
    }

    /**
     * HTTP 状态码。
     *
     * @return 状态码，0 表示非 HTTP 错误
     */
    public int statusCode() { return statusCode; }
}
