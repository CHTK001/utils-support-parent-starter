package com.chua.common.support.ai.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI 对话消息
 *
 * <p>表示一次 AI 对话中的单条消息记录，包含消息角色和文本内容。
 * 用于构建多轮对话的上下文历史。
 *
 * @author CH
 * @since 2026/07/15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /**
     * 消息角色
     *
     * <p>标识消息的发送方，可选值：
     * <ul>
     *   <li>system — 系统提示词，设定 AI 的行为和角色</li>
     *   <li>user — 用户输入</li>
     *   <li>assistant — AI 助手的回复</li>
     * </ul>
     */
    private String role;

    /**
     * 消息内容
     *
     * <p>该条消息的文本内容。
     */
    private String content;

    /**
     * 工具调用列表（仅 role=assistant 的消息携带）
     *
     * <p>当助手本轮决定调用工具时，记录一个或多个函数调用（id/名称/入参 JSON）。
     * 后续每个工具的执行结果以 role=tool、{@link #toolCallId} 关联的消息回传。
     */
    private List<ChatToolCallData> toolCalls;

    /**
     * 工具调用关联 ID（仅 role=tool 的消息携带）
     *
     * <p>对应此前 assistant 发起的某个工具调用 id，用于把工具执行结果与调用配对。
     */
    private String toolCallId;
}
