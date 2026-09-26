package com.chua.common.support.ai.chat;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * AI 对话流式响应
 *
 * <p>表示一次 AI 对话调用中的流式响应事件，包含状态、内容片段以及用量信息。
 * 调用方通过 {@link ChatClient#chat(String, java.util.function.Consumer)}
 * 接收此对象，根据 state 区分事件类型进行相应处理。
 *
 * @author CH
 * @since 2026/07/15
 */
@Data
@Builder
public class ChatResponse {

    /**
     * 响应状态
     *
     * <p>标识当前响应事件的类型：
     * <ul>
     *   <li>START — 流式响应开始，表示请求已发出</li>
     *   <li>STREAMING — 正在接收内容片段，每次回调携带一个 chunk</li>
     *   <li>STOP — 流式响应结束，携带完整的用量统计信息</li>
     *   <li>ERROR — 请求过程中发生错误</li>
     * </ul>
     */
    private State state;

    /**
     * 内容片段
     *
     * <p>当 state 为 {@link State#STREAMING} 时，该字段为本次回调携带的文本片段。
     * 其他状态下该字段为空。
     */
    private String content;

    /**
     * 完整响应文本
     *
     * <p>当 state 为 {@link State#STOP} 时，该字段为本次请求的完整响应内容。
     */
    private String fullContent;

    /**
     * 用量信息
     *
     * <p>当 state 为 {@link State#STOP} 时，该字段包含本次调用的 Token 用量和费用信息。
     * 包括输入/输出 Token 数、单价、费用等。
     * 其他状态下该字段为 null。
     */
    private AiUsage usage;

    /**
     * 错误消息
     *
     * <p>当 state 为 {@link State#ERROR} 时，该字段包含异常信息。
     */
    private String errorMessage;

    /**
     * 思考内容（深度思考模型）
     *
     * <p>当 state 为 {@link State#STREAMING} 或 {@link State#STOP} 时，
     * 该字段包含模型的推理/思考过程文本。</p>
     */
    private String reasoningContent;

    /**
     * 工具调用增量（函数调用）
     *
     * <p>当模型在流式过程中发起工具调用时，该字段携带本帧的工具调用数据
     * （序号/id/名称/入参 JSON 增量），与正文 {@link #content} 相互独立。
     * 协议适配层据此把 OpenAI 的 tool_calls 翻译为 Anthropic 的 tool_use 块。
     */
    private List<ChatToolCallData> toolCalls;

    /**
     * 执行轨迹日志（Agent 运行时）
     *
     * <p>当回调承载一条执行轨迹（thinking/输出/轮次/工具/用量/边缘事件）时，
     * 该字段非空；上层据此把 trace 作为会话内容推送到前端，不计入正文。</p>
     */
    private com.chua.common.support.ai.agent.AgentTrace trace;

    /**
     * 人工确认请求（human-in-the-loop）
     *
     * <p>当 Agent 运行到需要人工决策的工具（如规划模式 plan_exit）时，
     * 该字段非空，携带 replyId 与待确认项；上层据此渲染批准/拒绝卡片，
     * 用户决策回传后调用 resume 恢复 Agent。
     */
    private com.chua.common.support.ai.agent.ConfirmationRequest confirmationRequest;

    /**
     * 响应状态枚举
     *
     * <p>定义流式响应全生命周期的状态节点：
     * <ul>
     *   <li>START → 请求已发送，等待响应</li>
     *   <li>STREAMING → 持续接收内容片段</li>
     *   <li>STOP → 接收完毕，正常结束</li>
     *   <li>ERROR → 异常终止</li>
     * </ul>
     */
    public enum State {

        /**
         * 流式开始
         *
         * <p>表示请求已成功发出，服务端开始返回流式数据。
         */
        START,

        /**
         * 流式传输中
         *
         * <p>正在接收服务端返回的内容片段，每次回调携带一个 chunk。
         */
        STREAMING,

        /**
         * 流式结束
         *
         * <p>流式响应正常结束，携带用量统计信息。
         */
        STOP,

        /**
         * 请求出错
         *
         * <p>请求过程中发生异常，响应包含错误信息。
         */
        ERROR
    }
}
