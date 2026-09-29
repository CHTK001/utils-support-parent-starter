package com.chua.common.support.ai.chat;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;

/**
 * AI 对话同步响应。
 *
 * <p>封装同步对话调用的返回结果，包含完整响应文本和用量信息。
 * 通过 {@link ChatClient#chatSyncWithResponse(String)} 获取。
 *
 * <p>与 {@link ChatClient#chatSync(String)} 仅返回文本不同，
 * 本对象额外携带 Token 用量、费用等计量信息，便于业务侧进行成本统计和配额管理。
 *
 * @param text  完整响应文本，允许为 {@code null}。取值来源为各 SPI 客户端对 {@code chatSyncWithResponse} 的实现（OpenAI、智谱、Ollama 等），底层返回空响应时此处为 {@code null}，调用方需先判空
 * @param usage 用量信息，允许为 {@code null}。取值来源为客户端上报的 {@link AiUsage}（Token 数、费用、首字延迟等）；并非所有客户端都上报，未上报时为 {@code null}，{@code UsagePersistChatClient} 亦按可空处理后再异步持久化
 * @author CH
 * @since 2026/07/15
 */
@Builder
public record ChatSyncResponse(
        /**
         * 完整响应文本。
         *
         * <p>AI 模型返回的全部文本内容。
         */
        String text,
        /**
         * 用量信息。
         *
         * <p>包含本次调用的 Token 用量和费用信息。
         */
        AiUsage usage
) {

    /**
     * 获取完整响应文本。
     *
     * @return 响应文本
     */
    public String getText() {
        return text;
    }

    /**
     * 获取用量信息。
     *
     * @return 用量信息
     */
    public AiUsage getUsage() {
        return usage;
    }
}
