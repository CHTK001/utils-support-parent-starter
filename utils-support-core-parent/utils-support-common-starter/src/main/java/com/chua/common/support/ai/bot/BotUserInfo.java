package com.chua.common.support.ai.bot;

import lombok.Builder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bot 用户信息。
 *
 * @author CH
 * @since 2026/07/18
 */
@Builder
public record BotUserInfo(
        /**
         * 用户在 Bot 平台中的唯一 ID
         */
        String userId,
        /**
         * 用户名 / login name
         */
        String username,
        /**
         * 昵称 / display name / alias
         */
        String nickname,
        /**
         * 头像 URL
         */
        String avatarUrl,
        /**
         * 扩展字段
         */
        Map<String, Object> extra
) {

    /**
     * 规范构造器：对集合组件做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * {@code username} / {@code nickname} / {@code avatarUrl} / {@code extra}
     * 在现有调用点均不赋值（业务上就是缺省 null），故保留 null 语义。</p>
     *
     * @param userId    用户 ID，不允许为 null
     * @param username  用户名，允许为 null
     * @param nickname  昵称，允许为 null
     * @param avatarUrl 头像 URL，允许为 null
     * @param extra     扩展字段，允许为 null
     */
    public BotUserInfo {
        userId = Objects.requireNonNull(userId, "userId 不能为 null");
        extra = extra == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    /**
     * 获取用户在 Bot 平台中的唯一 ID。
     *
     * @return 用户 ID
     */
    public String getUserId() {
        return userId;
    }
}
