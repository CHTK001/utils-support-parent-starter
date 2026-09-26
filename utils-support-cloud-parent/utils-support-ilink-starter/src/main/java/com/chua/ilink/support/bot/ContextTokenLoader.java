package com.chua.ilink.support.bot;

/**
 * 会话上下文令牌加载器。
 *
 * <p>微信 iLink 入站消息携带 {@code context_token}，回复 / 主动发送时需原样回传。
 * 该令牌仅在客户端内存中会随后端重启丢失，故由上层持久化，并在客户端内存缺失时
 * （如重启后首次发送）通过本接口回查最近一条令牌回填。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@FunctionalInterface
public interface ContextTokenLoader {

    /**
     * 按 机器人 与目标用户/会话标识加载最近的上下文令牌。
     *
     * @param botId  机器人唯一标识
     * @param userId 目标用户标识（私聊=用户 openid）
     * @return 上下文令牌，无可用令牌时返回 {@code null}
     */
    String load(String botId, String userId);
}
