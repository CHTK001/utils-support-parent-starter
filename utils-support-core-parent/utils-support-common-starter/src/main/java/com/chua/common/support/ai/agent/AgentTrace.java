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
     * 所属 span 标识（可空）。
     *
     * <p>同一次模型调用 / 同一次工具执行产生的多条 trace 共享同一个 spanId，
     * 落库后据此配对成区间，甘特图的「时长」泳道才有依据。
     * 为空表示这是一条独立的点事件（如 agent_start / hint）。</p>
     */
    private String spanId;

    /**
     * 区间结束时间戳（毫秒）；0 表示这是点事件，没有结束时间。
     *
     * <p>与 {@link #timestamp} 一起构成 span：开始时间为 timestamp，结束时间为本字段，
     * 时长 = endTimestamp - timestamp。同一 span 内以结束时间最大的一条为准。</p>
     */
    private long endTimestamp;

    /**
     * 该条 trace 是否为一个区间的结束标记。
     *
     * <p>为 true 时 {@link #endTimestamp} 才有意义。AgentScope 的「工具结果」「Agent 结束」
     * 这类事件属于此类；纯增量的思考 / 正文 delta 不是。</p>
     */
    private boolean spanEnd;

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

    /**
     * 构建一个区间（span）的开始标记。
     *
     * <p>调用方负责持有 spanId，并在结束时用 {@link #end(String, long)} 产出结束标记；
     * 两者共享同一个 spanId，落库时即可配对成甘特图上的一条带时长的横条。</p>
     *
     * @param kind     类别
     * @param agentId  Agent 标识
     * @param spanId   区间标识（调用方生成，全局唯一）
     * @param toolName 工具名（可空）
     * @param text     文本（可空）
     * @return span 开始 trace
     */
    public static AgentTrace spanStart(String kind, String agentId, String spanId,
                                       String toolName, String text) {
        return AgentTrace.builder()
                .kind(kind)
                .agentId(agentId)
                .spanId(spanId)
                .toolName(toolName)
                .text(text)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 构建一个区间的结束标记：耗时从 trace 产生时刻算到此刻。
     *
     * @param kind    类别
     * @param agentId Agent 标识
     * @param spanId  与开始标记相同的区间标识
     * @param text    文本（可空）
     * @return span 结束 trace
     */
    public static AgentTrace end(String kind, String agentId, String spanId, String text) {
        long now = System.currentTimeMillis();
        return AgentTrace.builder()
                .kind(kind)
                .agentId(agentId)
                .spanId(spanId)
                .text(text)
                .timestamp(now)
                .endTimestamp(now)
                .spanEnd(true)
                .build();
    }

    /**
     * 生成一个区间标识：agentId + 类别 + 序号 + 当前毫秒。
     *
     * <p>不依赖 UUID 是为了让同一轮的同类 span 标识可读，便于排查；
     * 序号由调用方自增传入，避免这里出现可变状态。</p>
     *
     * @param agentId Agent 标识
     * @param kind    类别
     * @param seq     同类 span 的自增序号
     * @return 区间标识
     */
    public static String newSpanId(String agentId, String kind, int seq) {
        return agentId + "-" + kind + "-" + seq + "-" + System.currentTimeMillis();
    }
}
