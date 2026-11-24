package com.chua.common.support.ai.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 内容护栏检查请求。
 *
 * @param kind       内容边界类型，不应为 null
 * @param agentId    Agent 标识，可为 null
 * @param sessionId  业务会话标识，可为 null
 * @param content    待检查文本，可为 null（视为空内容，直接放行）
 * @param attributes 扩展信息（工具名、消息序号等），可为 null
 * @author CH
 * @since 4.0.0.52
 */
public record AgentContentRequest(
        AgentContentKind kind,
        String agentId,
        String sessionId,
        String content,
        Map<String, Object> attributes) {

    /**
     * 紧凑构造：attributes 防御性拷贝。
     */
    public AgentContentRequest {
        attributes = attributes == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    /**
     * 最小请求（无会话信息与扩展属性）。
     *
     * @param kind    内容边界类型
     * @param content 待检查文本
     * @return 检查请求
     */
    public static AgentContentRequest of(AgentContentKind kind, String content) {
        return new AgentContentRequest(kind, null, null, content, null);
    }
}
