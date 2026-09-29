package com.chua.common.support.ai.bot;


/**
 * Bot 消息监听器
 * 通过 {@link BotClient#addMessageListener} 注册，
 * 在接收到用户消息时回调。
 * <p>回调中可通过 {@link BotInboundMessage#fromUser} 获取用户 ID
 * （读取方法由 Lombok 生成），
 * 使用 {@link BotClient#sendText} 回复。</p>
 *
 * @author CH
 * @since 2026/07/18
 */
public interface BotMessageListener {

    /**
     * 收到消息回调
     *
     * @param message 入站消息
     */
    void onMessage(BotInboundMessage message);
}
