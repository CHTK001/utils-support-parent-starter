package com.chua.common.support.ai.bot;

import lombok.Data;

/**
 * Bot 发送结果
 * <p>
 * 封装 Bot API 调用结果，包含消息 ID 或错误信息。
 * </p>
 *
 * @author CH
 * @since 2026/07/18
 */
@Data
public class BotSendResult {

    /** 是否发送成功 */
    /**
    * 是否成功
    */
    private boolean success;

    /** 返回的消息 ID */
    private String msgId;

    /** 错误码 */
    private int errorCode;

    /** 错误信息 */
    private String errorMessage;

    /** 原始响应内容 */
    private String rawResponse;

    /**
     * 创建成功结果
     *
     * @param msgId 消息 ID
     * @return 发送结果
     */
    public static BotSendResult ok(String msgId) {
        BotSendResult result = new BotSendResult();
        result.success = true;
        result.msgId = msgId;
        return result;
    }

    /**
     * 创建失败结果
     *
     * @param errorCode    错误码
     * @param errorMessage 错误信息
     * @return 发送结果
     */
    public static BotSendResult fail(int errorCode, String errorMessage) {
        BotSendResult result = new BotSendResult();
        result.success = false;
        result.errorCode = errorCode;
        result.errorMessage = errorMessage;
        return result;
    }
}
