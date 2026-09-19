package com.chua.common.support.ai.bot;

import lombok.Builder;

import java.util.Map;

/**
 * Bot 用户信息。
 *
 * @author CH
 * @since 2026/07/18
 */
@Builder
public record BotUserInfo(
        /** 用户在 Bot 平台中的唯一 ID */
        String userId,
        /** 用户名 / login name */
        String username,
        /** 昵称 / display name / alias */
        String nickname,
        /** 头像 URL */
        String avatarUrl,
        /** 扩展字段 */
        Map<String, Object> extra
) {
    /**
        * 获取用户在 Bot 平台中的唯一 ID。
        *
        * @return 用户 ID
        */
    public String getUserId() {
        return userId;
    }
}
