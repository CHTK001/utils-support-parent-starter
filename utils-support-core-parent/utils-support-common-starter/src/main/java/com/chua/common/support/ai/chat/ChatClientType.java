package com.chua.common.support.ai.chat;

/**
 * AI 对话客户端协议类型标识。
 *
 * <p>区分 {@link ChatClient} 实现所支撑的协议族，决定调用方（POPAPI 封装、协议网关、路由）
 * 与该客户端交互时使用的请求/响应契约：</p>
 * <ul>
 *   <li>{@link #OPENAI_COMPATIBLE} — OpenAI 兼容 Chat Completions 协议：以
 *       {@code POST /v1/chat/completions} 为端点，客户端显式维护 {@code history}、
 *       {@code tools}、{@code imageUrls} 等通用字段，请求者只需拼装 {@code prompt}；</li>
 *   <li>{@link #PROVIDER_NATIVE} — 服务商原生协议（如 Anthropic Messages 的
 *       {@code POST /v1/messages}、Gemini generateContent）：响应结构保留服务商扩展语义。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
public enum ChatClientType {

    /**
     * OpenAI 兼容 Chat Completions 协议。
     */
    OPENAI_COMPATIBLE,

    /**
     * 服务商原生协议（Anthropic Messages、Gemini generateContent 等）。
     */
    PROVIDER_NATIVE
}
