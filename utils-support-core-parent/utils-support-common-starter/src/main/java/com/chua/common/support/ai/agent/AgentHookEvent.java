package com.chua.common.support.ai.agent;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * Agent Hook 事件
 *
 * <p>统一封装调试 / 规划等 Hook 回调载荷。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class AgentHookEvent implements Serializable {

    /** 序列化版本号 */
    /** Serial版本UID */
    private static final long serialVersionUID = 1L;

    /**
     * 事件类型
     *
     * <p>常见值：PRE_CALL / POST_CALL / PRE_REASONING / POST_REASONING /
     * PRE_ACTING / POST_ACTING / ERROR / PLAN_ENTER / PLAN_WRITE / PLAN_EXIT 等
     */
    private String type;

    /** Agent 标识 */
    /** AgentID */
    private String agentId;

    /** 事件描述或提示消息 */
    /** 消息 */
    private String message;

    /** 事件时间戳（毫秒） */
    /** 时间戳 */
    private long timestamp;

    /** 扩展属性（工具名、输入摘要、错误信息等） */
    private Map<String, Object> attributes;

    /**
     * 创建简单事件
     *
     * @param type    事件类型
     * @param agentId Agent 标识
     * @param message 事件描述
     * @return 事件实例
     */
    public static AgentHookEvent of(String type, String agentId, String message) {
        return AgentHookEvent.builder()
                .type(type)
                .agentId(agentId)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
