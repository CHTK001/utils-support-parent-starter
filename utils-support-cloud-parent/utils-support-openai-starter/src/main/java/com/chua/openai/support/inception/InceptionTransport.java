package com.chua.openai.support.inception;

/**
 * Inception 上游传输通道
 *
 * <p>{@code chat.inceptionlabs.ai} 的接口被 Vercel 安全网关保护：网关按
 * TLS / HTTP2 指纹判定客户端，纯 HTTP 客户端即使携带有效 Cookie 也会收到
 * HTTP 429「This request requires a challenge to be completed.」。
 * 因此本包提供两种通道：</p>
 * <ul>
 *   <li>{@link InceptionBrowserTransport}：驱动真实 Chromium，在页面上下文里
 *       发起同源 {@code fetch}，天然带浏览器指纹，默认通道；</li>
 *   <li>{@link InceptionHttpTransport}：纯 JDK HttpClient，适合未被网关拦截的
 *       自建 / 反代部署，或已在链路上做了指纹伪造的场景。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface InceptionTransport extends AutoCloseable {

    /**
     * 发起一轮对话，按上游 SSE 分片回调
     *
     * @param payloadJson 上游 {@code POST /api/chat} 的请求体 JSON
     * @param listener    流式回调
     * @throws Exception 网络异常或上游错误
     */
    void chat(String payloadJson, InceptionStreamListener listener) throws Exception;

    /**
     * 释放通道资源（浏览器进程 / 连接池）
     */
    @Override
    default void close() {
    }

    /**
     * 流式回调
     *
     * <p>上游只产出正文与推理两类增量，用量信息在 {@code finish} 事件里
     * 出现时才会回调。</p>
     */
    interface InceptionStreamListener {

        /**
         * 正文增量
         *
         * @param delta 增量文本
         */
        void onText(String delta);

        /**
         * 推理（思维链）增量
         *
         * @param delta 增量文本
         */
        default void onReasoning(String delta) {
        }

        /**
         * 上游返回的用量信息
         *
         * @param usage 用量
         */
        default void onUsage(InceptionUsage usage) {
        }
    }

    /**
     * 上游用量
     *
     * @param inputTokens  输入 Token
     * @param outputTokens 输出 Token
     * @param totalTokens  合计 Token
     */
    record InceptionUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
    }
}
