package com.chua.common.support.ai.bot;

import lombok.Builder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bot 用户信息。
 *
 * <p>Bot 平台侧的轻量用户画像，由各平台适配层从回调上下文解析后回填，
 * 供 {@code BotUserStore} 持久化（内存实现 {@code InMemoryBotUserStore} 以
 * {@link #userId} 为主键）。除 {@link #userId} 外全部组件允许为 {@code null}，
 * 表示该平台未提供对应信息。</p>
 *
 * @param userId     用户在 Bot 平台中的唯一 ID，是 {@code BotUserStore} 的主键
 *                   （{@code findByUserId(String)} 按它查找）；不允许为 {@code null}，
 *                   紧凑构造器用 {@code requireNonNull} 强校验。取值由平台侧原样透传，
 *                   不做大小写归一，不同平台的 ID 体系互不通用
 * @param username   用户名 / login name，即平台侧的登录名；对允许为 {@code null}
 *                   （平台未提供时留空，微信等场景本就没有该概念）
 * @param nickname   昵称 / display name / alias，即面向用户展示的名字；
 *                   允许为 {@code null}，展示时应回落到 {@link #username}
 * @param avatarUrl  头像 URL，通常是平台 CDN 的图片地址；允许为 {@code null}。
 *                   该字段只存字符串，不做下载与缓存，URL 过期由调用方负责处理
 * @param extra      平台私有的扩展字段，键为字段名、值为原始对象（如性别、关注状态、
 *                   平台原始 JSON 片段），键值均可为 {@code null}；允许整个组件为 {@code null}
 *                   （紧凑构造器保留空语义）。构造时会拷贝为不可变 {@code LinkedHashMap}
 *                   以保持插入顺序，外部无法再修改
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
