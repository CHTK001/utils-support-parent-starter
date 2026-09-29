package com.chua.common.support.ai.bot;

import lombok.Builder;

/**
 * Bot 用户实体（数据库持久化用）。
 *
 * <p>与运行时值对象 {@link BotUserInfo} 一一对应，组件语义完全相同，区别只在于落库形态：
 * {@link BotUserInfo#extra()} 是 {@code Map<String, Object>}，而本类的 {@link #extra()} 是
 * 序列化后的 JSON 字符串，便于直接映射到数据库文本列。本类不定义紧凑构造器，
 * 不做非空校验与防御性拷贝，5 个组件均允许为 {@code null}；
 * 实际写入前应把 {@link BotUserInfo} 的字段逐一映射过来。</p>
 *
 * @param userId    用户 ID，即用户在 Bot 平台侧的全局唯一标识，是本实体的主键与 upsert 依据。
 *                  取值来自各渠道入站消息的发送者标识（飞书为 {@code open_id}，钉钉为 sender 的
 *                  {@code userId}，QQ 为 {@code openid}）；本类不做非空校验，但为 {@code null} 时无法定位记录
 * @param username  用户名 / login name，允许为 {@code null}。取值来自渠道返回的登录名；
 *                  部分渠道（如飞书）不解析用户资料，调用方会直接用 {@link #userId} 兜底填入
 * @param nickname  昵称 / display name / alias，允许为 {@code null}。取值来自入站消息中的发送者昵称，
 *                  用于前端展示；未解析到用户资料时同样可能与 {@link #userId} 相同
 * @param avatarUrl 头像 URL，允许为 {@code null}。取值来自渠道返回的头像地址；
 *                  多数渠道的入站消息不携带头像，需另行调用用户资料接口补齐，故当前调用点常为空
 * @param extra     扩展字段，允许为 {@code null}。以 JSON 字符串形式承载渠道私有的额外元数据
 *                  （对应 {@link BotUserInfo#extra()} 的序列化结果），无扩展内容时不填
 * @author CH
 * @since 2026/07/18
 */
@Builder
public record BotUserEntity(
        /**
         * 用户 ID
         */
        String userId,
        /**
         * 用户名
         */
        String username,
        /**
         * 昵称
         */
        String nickname,
        /**
         * 头像 URL
         */
        String avatarUrl,
        /** 扩展字段（JSON 字符串）*/
        String extra
) {
}
