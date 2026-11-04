package com.chua.deeplearning.support.agent.opencode;

import lombok.Getter;

/**
 * opencode server HTTP 接口异常。
 *
 * <p>携带 HTTP 状态码与响应体原文，便于上层区分不同失败场景：</p>
 * <ul>
 *   <li>{@code 404} — 会话不存在（{@code SessionNotFoundError}），可用于实现“先查后建”</li>
 *   <li>{@code 400} — 请求体不符合 OpenAPI 契约（{@code InvalidRequestError}）</li>
 *   <li>{@code 401} — Basic Auth 凭据不匹配</li>
 *   <li>{@code 409} — 同一会话已有进行中的一轮对话（{@code ConflictError}）</li>
 * </ul>
 *
 * <p>非 HTTP 阶段（连接失败、序列化失败、线程中断）的失败状态码记为 {@code 0}。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Getter
public class OpencodeApiException extends RuntimeException {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * HTTP 状态码；非 HTTP 阶段失败时为 0
     */
    private final int statusCode;

    /**
     * 响应体原文（可能为空）
     */
    private final String responseBody;

    /**
     * 构造 HTTP 状态码异常。
     *
     * @param statusCode   HTTP 状态码
     * @param responseBody 响应体原文
     * @param message      异常说明
     */
    public OpencodeApiException(int statusCode, String responseBody, String message) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * 构造非 HTTP 阶段异常。
     *
     * @param message 异常说明
     * @param cause   根因
     */
    public OpencodeApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
    }
}
