package com.chua.common.support.ai.bot;

import lombok.Builder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bot 群组信息。
 *
 * @author CH
 * @since 2026/07/18
 */
@Builder
public record BotGroupInfo(
        /**
         * 群组 ID
         */
        String groupId,
        /**
         * 群组名称
         */
        String groupName,
        /**
         * 成员 ID 列表
         */
        List<String> memberIds,
        /**
         * 成员数量
         */
        int memberCount,
        /**
         * 扩展字段
         */
        Map<String, Object> extra
) {

    /**
     * 规范构造器：对集合组件做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * {@code groupName} 与 {@code extra} 在现有调用点均不赋值（业务上就是缺省 null），
     * 故保留 null 语义，只在非 null 时做不可变拷贝。</p>
     *
     * @param groupId     群组 ID，不允许为 null
     * @param groupName   群组名称，允许为 null
     * @param memberIds   成员 ID 列表，允许为 null
     * @param memberCount 成员数量
     * @param extra       扩展字段，允许为 null
     */
    public BotGroupInfo {
        groupId = Objects.requireNonNull(groupId, "groupId 不能为 null");
        memberIds = memberIds == null ? null : List.copyOf(memberIds);
        extra = extra == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }
}
