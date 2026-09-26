package com.chua.common.support.ai.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 工具调用数据
 *
 * <p>统一承载函数调用（function call）信息，同时用于两类场景：
 * <ul>
 *   <li><b>历史消息</b>：作为 assistant 消息中的 {@code tool_calls}（{@link ChatMessage#getToolCalls()}），
 *       此时 {@link #index} 无意义（取 0），{@link #arguments} 为完整入参 JSON；</li>
 *   <li><b>流式增量</b>：作为 {@link ChatResponse#getToolCalls()} 中的一帧，
 *       {@link #index} 标识工具调用序号，{@link #id}/{@link #name} 通常仅首帧给出，
 *       {@link #arguments} 为入参 JSON 的增量片段。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatToolCallData {

    /**
     * 工具调用序号（仅流式增量使用，标识同一轮中的第几个工具调用）
     */
    private long index;

    /**
     * 工具调用 ID（用于关联后续的 tool 结果消息）
     */
    private String id;

    /**
     * 被调用的工具名称
     */
    private String name;

    /**
     * 工具入参：历史消息中为完整 JSON 字符串；流式中为 JSON 增量片段
     */
    private String arguments;
}
