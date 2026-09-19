package com.chua.common.support.ai.bot;

import lombok.Builder;

import java.util.List;
import java.util.Map;

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
}
