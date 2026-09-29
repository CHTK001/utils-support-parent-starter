package com.chua.common.support.ai;

/**
 * 供应商（上游）接口调用失败。
 *
 * <p>与「本项目自己算错了」区分开：前者是外部依赖返回的错误（限流、队列满、
 * 鉴权失败、参数不被接受），应当把<em>上游原文</em>透给用户；后者是配置或代码
 * 问题，透出去只会误导排查方向。</p>
 *
 * <p>存在的理由：客户端此前一律抛裸 {@link RuntimeException}，控制器只捕获
 * {@code IllegalArgumentException} 与 {@code IllegalStateException}，于是上游原文
 * 落到统一异常处理器后被压成「系统繁忙，请稍后重试」——用户完全看不到
 * 「免费额度已用尽」「视频队列已满」这类真正可行动的信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AiProviderException extends RuntimeException {

    /**
     * 上游 HTTP 状态码；未拿到时为 null
     */
    private final Integer status;

    /**
     * 上游返回体，已截断到可读长度
     */
    private final String upstreamBody;

    /**
     * 构造异常。
     *
     * @param message      面向用户的中文描述
     * @param status       上游 HTTP 状态码，可为 null
     * @param upstreamBody 上游返回体原文，可为 null
     */
    public AiProviderException(String message, Integer status, String upstreamBody) {
        super(message);
        this.status = status;
        this.upstreamBody = upstreamBody;
    }

    /**
     * 构造异常（无状态码与返回体）。
     *
     * @param message 面向用户的中文描述
     */
    public AiProviderException(String message) {
        this(message, null, null);
    }

    /**
     * 上游 HTTP 状态码。
     *
     * @return 状态码；无则返回 null
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * 上游返回体原文。
     *
     * @return 返回体；无则返回 null
     */
    public String getUpstreamBody() {
        return upstreamBody;
    }
}
