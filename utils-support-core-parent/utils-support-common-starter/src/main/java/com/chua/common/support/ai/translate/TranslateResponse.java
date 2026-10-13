package com.chua.common.support.ai.translate;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;
import lombok.Data;

/**
 * AI 翻译响应
 *
 * <p>表示一次 AI 翻译调用中的响应事件，包含状态、
 * 译文片段以及用量信息。调用方通过
 * {@link TranslateClient#translate(String, java.util.function.Consumer)}
 * 接收此对象，根据 state 区分事件类型进行相应处理。
 *
 * @author CH
 * @since 2026/10/12
 */
@Data
@Builder
public class TranslateResponse {

    /**
     * 响应状态
     *
     * <p>标识当前响应事件的类型：
     * <ul>
     *   <li>START — 请求已发出，等待响应</li>
     *   <li>STREAMING — 正在接收译文片段</li>
     *   <li>STOP — 翻译完成，携带完整的用量统计信息</li>
     *   <li>ERROR — 请求过程中发生错误</li>
     * </ul>
     */
    private State state;

    /**
     * 译文片段
     *
     * <p>当 state 为 {@link State#STREAMING} 时，该字段为本次回调携带的译文片段。
     * 其他状态下该字段为空。
     */
    private String content;

    /**
     * 完整译文
     *
     * <p>当 state 为 {@link State#STOP} 时，该字段为本次翻译的完整结果。
     */
    private String fullContent;

    /**
     * 用量信息
     *
     * <p>当 state 为 {@link State#STOP} 时，该字段包含本次调用的 Token 用量信息。
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
     * 响应状态枚举
     *
     * <p>定义翻译响应全生命周期的状态节点：
     * <ul>
     *   <li>START → 请求已发送，等待响应</li>
     *   <li>STREAMING → 持续接收译文片段</li>
     *   <li>STOP → 接收完毕，正常结束</li>
     *   <li>ERROR → 异常终止</li>
     * </ul>
     */
    public enum State {

        /**
         * 翻译开始
         *
         * <p>表示请求已成功发出，服务端开始返回数据。
         */
        START,

        /**
         * 译文传输中
         *
         * <p>正在接收服务端返回的译文片段，每次回调携带一个 chunk。
         */
        STREAMING,

        /**
         * 翻译结束
         *
         * <p>翻译响应正常结束，携带用量统计信息。
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
