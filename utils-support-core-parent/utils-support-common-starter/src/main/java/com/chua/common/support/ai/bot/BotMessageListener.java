package com.chua.common.support.ai.bot;

import org.jspecify.annotations.NullUnmarked;

/**
 * Bot 消息监听器
 * <p>
 * 通过 {@link BotClient#addMessageListener(BotMessageListener)} 注册，
 * 在接收到用户消息时回调。
 * </p>
 * <p>回调中可通过 {@link BotInboundMessage#getFromUser()} 获取用户 ID，
 * 使用 {@link BotClient#sendText(String, String)} 回复。</p>
 *
 * @author CH
 * @since 2026/07/18
 */
@NullUnmarked
public interface BotMessageListener {

    /**
     * 收到消息回调
     *
     * @param message 入站消息
     */
    void onMessage(BotInboundMessage message);
}
