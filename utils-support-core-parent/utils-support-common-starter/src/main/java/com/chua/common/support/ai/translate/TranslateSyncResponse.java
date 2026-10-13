package com.chua.common.support.ai.translate;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;

/**
 * AI 翻译同步响应。
 *
 * <p>封装同步翻译调用的返回结果，包含完整译文和用量信息。
 * 通过 {@link TranslateClient#translateSyncWithResponse(String)} 获取。
 *
 * <p>与 {@link TranslateClient#translateSync(String)} 仅返回文本不同，
 * 本对象额外携带 Token 用量等计量信息，便于业务侧进行成本统计和配额管理。
 *
 * @param text  完整译文，允许为 {@code null}。取值来源为各 SPI 客户端对
 *              {@code translateSyncWithResponse} 的实现，底层返回空响应时此处为
 *              {@code null}，调用方需先判空
 * @param usage 用量信息，允许为 {@code null}。取值来源为客户端上报的
 *              {@link AiUsage}（Token 数、性能指标等）；并非所有客户端都上报，未上报时为 {@code null}
 * @author CH
 * @since 2026/10/12
 */
@Builder
public record TranslateSyncResponse(
        /**
         * 完整译文。
         *
         * <p>AI 翻译模型返回的全部译文内容。
         */
        String text,
        /**
         * 用量信息。
         *
         * <p>包含本次调用的 Token 用量信息。
         */
        AiUsage usage
) {

    /**
     * 获取完整译文。
     *
     * @return 译文文本
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
