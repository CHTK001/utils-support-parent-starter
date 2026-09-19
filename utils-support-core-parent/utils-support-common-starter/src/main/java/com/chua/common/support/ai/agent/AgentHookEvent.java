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
    private static final long serialVersionUID = 1L;

    /**
     * 事件类型
     *
     * <p>常见值：PRE_CALL / POST_CALL / PRE_REASONING / POST_REASONING /
     * PRE_ACTING / POST_ACTING / ERROR / PLAN_ENTER / PLAN_WRITE / PLAN_EXIT 等
     */
    private String type;

    /** Agent 标识 */
    private String agentId;

    /** 事件描述或提示消息 */
    private String message;

    /** 事件时间戳（毫秒） */
    private long timestamp;

    /** 扩展属性（工具名、输入摘要、错误信息等） */
    private Map<String, Object> attributes;

    /** 当前执行轮次（从 1 开始，每完成一次完整 LLM 调用循环递增） */
    private Integer iteration;

    /** 本次轮次已调用的工具次数 */
    private Integer toolCallCount;

    /** 累计输入 Token 数（所有轮次合计） */
    private Long totalInputTokens;

    /** 累计输出 Token 数（所有轮次合计） */
    private Long totalOutputTokens;

    /** 累计总 Token 数（所有轮次合计） */
    private Long totalTokens;

    /** 从 Agent 启动到本事件时刻的耗时（毫秒） */
    private Long elapsedMillis;

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

    /**
     * 创建简单事件（含执行轮次）
     *
     * @param type      事件类型
     * @param agentId   Agent 标识
     * @param message   事件描述
     * @param iteration 当前执行轮次（从 1 开始）
     * @return 事件实例
     */
    public static AgentHookEvent of(String type, String agentId, String message, int iteration) {
        return AgentHookEvent.builder()
                .type(type)
                .agentId(agentId)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .iteration(iteration)
                .build();
    }
}
