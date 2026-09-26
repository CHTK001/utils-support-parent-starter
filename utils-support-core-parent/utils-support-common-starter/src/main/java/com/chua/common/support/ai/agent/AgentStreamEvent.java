package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Agent 流式事件
 *
 * <p>provider 中立的 Agent 运行时流式事件，由 {@link Agent#runStream(String)} 产生。
 * 调用方（如 AgentChatClient）据此实现逐字流式、工具步骤展示与聚合用量回填。
 *
 * <ul>
 *   <li>{@link Type#REASONING_DELTA} — 正文文本增量，{@link #delta} 携带片段</li>
 *   <li>{@link Type#THINKING_DELTA} — 思考过程增量，{@link #delta} 携带片段</li>
 *   <li>{@link Type#TOOL_START} — 工具调用开始，{@link #toolName}/{@link #toolInput}</li>
 *   <li>{@link Type#TOOL_RESULT} — 工具调用结果，{@link #toolOutput}</li>
 *   <li>{@link Type#COMPLETE} — 本次执行完成，{@link #usage} 为聚合用量</li>
 *   <li>{@link Type#ERROR} — 执行异常，{@link #toolOutput} 携带错误信息</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class AgentStreamEvent implements Serializable {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 事件类型
     */
    private Type type;

    /**
     * Agent 标识
     */
    private String agentId;

    /**
     * 文本片段（REASONING_DELTA / THINKING_DELTA）
     */
    private String delta;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 工具输入（摘要）
     */
    private String toolInput;

    /**
     * 工具输出 / 错误信息（摘要）
     */
    private String toolOutput;

    /**
     * 聚合用量（仅 COMPLETE）
     */
    private AiUsage usage;

    /**
     * 执行轨迹日志（仅 TRACE）：thinking/输出/轮次/工具/用量/边缘事件
     */
    private AgentTrace trace;

    /**
     * 待确认请求的回复 ID（仅 CONFIRMATION_REQUEST）：回传决策时原样带回
     */
    private String replyId;

    /**
     * 待用户确认项列表（仅 CONFIRMATION_REQUEST）
     */
    private List<ConfirmationItem> confirmations;

    /**
     * 事件时间戳（毫秒）
     */
    private long timestamp;

    /**
     * 事件类型枚举
     */
    public enum Type {
        /**
         * 正文增量
         */
        REASONING_DELTA,
        /**
         * 思考增量
         */
        THINKING_DELTA,
        /**
         * 工具开始
         */
        TOOL_START,
        /**
         * 工具结果
         */
        TOOL_RESULT,
        /**
         * 执行完成
         */
        COMPLETE,
        /**
         * 执行轨迹日志（thinking/输出/轮次/工具/用量/边缘），{@link #trace} 携带
         */
        TRACE,
        /**
         * 等待用户确认（人工审批）：{@link #replyId} 与 {@link #confirmations} 携带，
         * 前端渲染批准/拒绝卡片，决策回传后 Agent 继续运行
         */
        CONFIRMATION_REQUEST,
        /**
         * 执行异常
         */
        ERROR
    }

    /**
     * 构建正文增量事件
     *
     * @param agentId Agent 标识
     * @param delta   文本片段
     * @return 事件实例
     */
    public static AgentStreamEvent reasoningDelta(String agentId, String delta) {
        return AgentStreamEvent.builder()
                .type(Type.REASONING_DELTA)
                .agentId(agentId)
                .delta(delta)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 构建思考增量事件
     *
     * @param agentId Agent 标识
     * @param delta   文本片段
     * @return 事件实例
     */
    public static AgentStreamEvent thinkingDelta(String agentId, String delta) {
        return AgentStreamEvent.builder()
                .type(Type.THINKING_DELTA)
                .agentId(agentId)
                .delta(delta)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 构建执行轨迹事件。
     *
     * @param trace 轨迹日志
     * @return 事件实例
     */
    public static AgentStreamEvent trace(AgentTrace trace) {
        return AgentStreamEvent.builder()
                .type(Type.TRACE)
                .agentId(trace != null ? trace.getAgentId() : null)
                .trace(trace)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 构建等待用户确认事件。
     *
     * @param agentId       Agent 标识
     * @param replyId       待确认请求回复 ID
     * @param confirmations 待确认项
     * @return 事件实例
     */
    public static AgentStreamEvent confirmationRequest(String agentId, String replyId,
                                                       List<ConfirmationItem> confirmations) {
        return AgentStreamEvent.builder()
                .type(Type.CONFIRMATION_REQUEST)
                .agentId(agentId)
                .replyId(replyId)
                .confirmations(confirmations)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
