package com.chua.common.support.ai.bot;

import lombok.Builder;
import org.jspecify.annotations.NullUnmarked;

/**
 * Bot 用户实体（数据库持久化用）。
 *
 * @author CH
 * @since 2026/07/18
 */
@NullUnmarked
@Builder
public record BotUserEntity(
        /** 用户 ID */
        String userId,
        /** 用户名 */
        String username,
        /** 昵称 */
        String nickname,
        /** 头像 URL */
        String avatarUrl,
        /** 扩展字段（JSON 字符串）*/
        String extra
) {
}
