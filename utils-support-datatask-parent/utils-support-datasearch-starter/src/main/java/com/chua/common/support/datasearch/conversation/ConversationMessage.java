package com.chua.common.support.datasearch.conversation;

import lombok.Builder;
import lombok.Data;

/**
 * AI 工具本地会话中的一条消息记录。
 *
 * <p>由 {@code ConversationParser} 从各工具的本地会话文件中解析得出，
 * 仅包含文本类内容；thinking、tool_use 等非文本块以类型标记保留，
 * 内容置空，避免敏感信息扩散。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder(toBuilder = true)
public class ConversationMessage {

    /** 来源工具标识（如 claude-编码、qoder） */
    private String provider;

    /** 会话 标识 */
    private String sessionId;

    /** 消息 标识 */
    private String messageId;

    /** 角色：用户 / assistant */
    private String role;

    /**
     * 内容块类型。
     *
     * <p>text = 正文文本；thinking = 思考块（内容为空）；
     * tool_use = 工具调用（内容为工具名）；tool_结果 = 工具结果（内容为空）。</p>
     */
    private String contentType;

    /** 文本内容（仅 文本 块有值） */
    private String content;

    /** assistant 消息的模型名 */
    private String model;

    /** 时间戳（轮次 毫秒） */
    private Long timestamp;

    /** 消息发生时的工作目录 */
    private String cwd;
}
