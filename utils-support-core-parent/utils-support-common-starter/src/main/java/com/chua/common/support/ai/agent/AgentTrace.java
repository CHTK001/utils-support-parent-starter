package com.chua.common.support.ai.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Agent 执行轨迹（Trace）单条日志。
 *
 * <p>承载 Agentscope 执行过程中的全部可观测信息：轮次、深度思考(thinking)、
 * 模型输出/命令、工具调用与参数、工具结果、token 用量，以及会导致“卡住”的
 * 边缘事件（等待用户确认 / 等待外部执行 / 工具被拒 / 超过最大迭代）。
 * 由 {@link Agent#runStream(String)} 以 {@link AgentStreamEvent.Type#TRACE}
 * 事件逐条下发，最终作为会话内容推送到前端执行日志面板。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTrace implements Serializable {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 日志类别
     */
    private String kind;

    /**
     * Agent 标识
     */
    private String agentId;

    /**
     * 工具名称（工具相关日志）
     */
    private String toolName;

    /**
     * 文本内容（思考 / 输出 / 工具参数与结果 / 边缘事件描述）
     */
    private String text;

    /**
     * 事件时间戳（毫秒）
     */
    private long timestamp;

    /**
     * 常用类别常量
     */
    public static final String KIND_AGENT_START = "agent_start";
    public static final String KIND_AGENT_END = "agent_end";
    public static final String KIND_TURN = "turn";
    public static final String KIND_THINKING = "thinking";
    public static final String KIND_TEXT = "text";
    public static final String KIND_TOOL_CALL = "tool_call";
    public static final String KIND_TOOL_ARGS = "tool_args";
    public static final String KIND_TOOL_RESULT = "tool_result";
    public static final String KIND_USAGE = "usage";
    public static final String KIND_HINT = "hint";
    public static final String KIND_EDGE = "edge";

    /**
     * 快速构建一条 trace。
     *
     * @param kind     类别
     * @param agentId  Agent 标识
     * @param toolName 工具名（可空）
     * @param text     文本（可空）
     * @return trace 实例
     */
    public static AgentTrace of(String kind, String agentId, String toolName, String text) {
        return AgentTrace.builder()
                .kind(kind)
                .agentId(agentId)
                .toolName(toolName)
                .text(text)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
