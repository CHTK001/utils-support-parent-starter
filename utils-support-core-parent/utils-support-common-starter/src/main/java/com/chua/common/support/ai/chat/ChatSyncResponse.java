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
