package com.chua.kimi.support;

/**
 * Kimi 聊天结果。
 *
 * @param text                   最终回答文本
 * @param thinkingContent        思考链内容（仅思考模式有值）
 * @param remoteChatId           远程会话 ID，用于多轮上下文
 * @param lastAssistantMessageId 最后一条 assistant 消息 ID，用于多轮上下文
 * @param errorMessage           错误消息（请求失败时非空）
 * @author CH
 * @since 4.0.0.42
 */
public record KimiChatResult(
        String text,
        String thinkingContent,
        String remoteChatId,
        String lastAssistantMessageId,
        String errorMessage
) {

    /**
     * 判断是否成功。
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return errorMessage == null;
    }
}