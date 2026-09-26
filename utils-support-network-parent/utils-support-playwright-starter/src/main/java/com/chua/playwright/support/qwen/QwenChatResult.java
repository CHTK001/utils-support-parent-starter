package com.chua.playwright.support.qwen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通义千问聊天结果。
 *
 * <p>封装一次完整的通义千问聊天响应，包含回答文本、思考链和会话 ID。
 *
 * @param text            最终回答文本
 * @param thinkingContent 思考链内容（仅思考模式有值）
 * @param conversationId  会话 标识，用于多轮对话
 * @param errorMessage    错误消息（请求失败时非空）
 * @param rawEvents       原始 SSE 事件列表（供图像/视频生成等解析）
 * @param done            是否正常结束
 * @author CH
 * @since 4.0.0.42
 */
public record QwenChatResult(
        String text,
        String thinkingContent,
        String conversationId,
        String errorMessage,
        List<Map<String, Object>> rawEvents,
        boolean done
) {

    /**
     * 规范构造器：对原始事件列表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。原始 SSE 事件由解析过程逐条累积，
     * 若直接持有该列表，解析缓冲区后续追加会改写已发布的结果。</p>
     *
     * <p>事件元素来自 SSE 解析，允许为 {@code null}，故采用可空安全的不可变包装而非
     * {@link List#copyOf(List)}（后者遇 null 元素会抛 {@link NullPointerException}）；
     * 本类的成功/失败工厂方法显式传 {@code null} 表示「无原始事件」，该语义同样保留。</p>
     *
     * @param text            最终回答文本
     * @param thinkingContent 思考链内容
     * @param conversationId  会话 标识
     * @param errorMessage    错误消息
     * @param rawEvents       原始 SSE 事件列表
     * @param done            是否正常结束
     */
    public QwenChatResult {
        rawEvents = rawEvents == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(rawEvents));
    }

    /**
     * 判断是否成功。
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return done && errorMessage == null;
    }

    /**
     * 创建成功结果。
     *
     * @param text            回答文本
     * @param thinkingContent 思考链文本
     * @param conversationId  会话 标识
     * @return 成功结果
     */
    public static QwenChatResult ok(String text, String thinkingContent, String conversationId) {
        return new QwenChatResult(text, thinkingContent, conversationId, null, null, true);
    }

    /**
     * 创建成功结果（含原始事件）。
     *
     * @param text            回答文本
     * @param thinkingContent 思考链文本
     * @param conversationId  会话 标识
     * @param rawEvents       原始 SSE 事件列表
     * @return 成功结果
     */
    public static QwenChatResult ok(String text, String thinkingContent, String conversationId,
                                    List<Map<String, Object>> rawEvents) {
        return new QwenChatResult(text, thinkingContent, conversationId, null, rawEvents, true);
    }

    /**
     * 创建错误结果。
     *
     * @param errorMessage 错误消息
     * @return 错误结果
     */
    public static QwenChatResult error(String errorMessage) {
        return new QwenChatResult(null, null, null, errorMessage, null, false);
    }
}
