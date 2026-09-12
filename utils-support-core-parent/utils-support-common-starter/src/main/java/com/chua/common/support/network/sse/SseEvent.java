package com.chua.common.support.network.sse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 事件数据模型
 *
 * <p>封装一个完整的 SSE 事件，包含数据、事件类型、事件 ID 和重试间隔。
 * 符合 <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html">W3C SSE 规范</a>。
 *
 * <p><b>事件字段说明：</b></p>
 * <ul>
 *   <li>{@link #getData()} — {@code data:} 行内容，多条 data 行以 {@code \n} 合并</li>
 *   <li>{@link #getEvent()} — {@code event:} 行内容，默认为 {@code "message"}</li>
 *   <li>{@link #getId()} — {@code id:} 行内容，用于断点重连（Last-Event-ID）</li>
 *   <li>{@link #getRetry()} — {@code retry:} 行内容，服务端建议的重连间隔（毫秒）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0
 * @see SseListener
 * @see SseProtocolParser
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SseEvent {

    /**
     * 事件数据内容
     *
     * <p>多条连续 {@code data:} 行合并后的字符串，以 {@code \n} 分隔。
     * 空事件（无 data 行）时为 {@code null}。
     */
    private String data;

    /**
     * 事件类型
     *
     * <p>来自 {@code event:} 行。如果服务端未指定，默认为 {@code "message"}。
     */
    @Builder.Default
    /** 事件 */
    private String event = "message";

    /**
     * 事件 ID
     *
     * <p>来自 {@code id:} 行。用于断点重连时设置 {@code Last-Event-ID} 请求头。
     */
    private String id;

    /**
     * 重试间隔（毫秒）
     *
     * <p>来自 {@code retry:} 行。服务端建议客户端在断线后等待多久再重连。
     * {@code null} 表示服务端未指定。
     */
    private Long retry;

    /**
     * 判断是否有数据内容
     *
     * @return true 表示 data 非空且非 null
     */
    public boolean hasData() {
        return data != null && !data.isEmpty();
    }
}
