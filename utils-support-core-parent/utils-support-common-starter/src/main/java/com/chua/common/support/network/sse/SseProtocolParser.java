package com.chua.common.support.network.sse;

import java.util.function.Consumer;

/**
 * SSE 协议行级解析器
 *
 * <p>基于 <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation">W3C SSE 规范</a>
 * 实现的状态机解析器，逐行处理 SSE 事件流并正确组装事件。
 *
 * <h3>处理的字段</h3>
 * <table>
 *   <tr><th>字段</th><th>说明</th></tr>
 *   <tr><td>{@code data:}</td><td>事件数据，多条连续 data 行以 {@code \n} 合并</td></tr>
 *   <tr><td>{@code event:}</td><td>事件类型，默认 {@code "message"}</td></tr>
 *   <tr><td>{@code id:}</td><td>事件 ID，用于断点重连</td></tr>
 *   <tr><td>{@code retry:}</td><td>重试间隔（毫秒）</td></tr>
 *   <tr><td>{@code :}</td><td>注释行，传递给 commentHandler</td></tr>
 * </table>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * SseProtocolParser parser = new SseProtocolParser(
 *     event -> listener.onEvent(event),
 *     comment -> log.debug("SSE comment: {}", comment)
 * );
 *
 * try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
 *     String line;
 *     while ((line = reader.readLine()) != null) {
 *         parser.parseLine(line);
 *     }
 *     parser.flush(); // 处理末尾未以空行结束的事件
 * }
 * }</pre>
 *
 * <p><b>线程安全性：</b>本类不是线程安全的，应在单个线程中顺序调用 {@link #parseLine(String)}。</p>
 *
 * @author CH
 * @since 4.0
 * @see SseEvent
 */
public class SseProtocolParser {

    /** {@code data} 字段名 */
    private static final String FIELD_DATA = "data";
    /** {@code event} 字段名 */
    private static final String FIELD_EVENT = "event";
    /** {@code id} 字段名 */
    private static final String FIELD_ID = "id";
    /** {@code retry} 字段名 */
    private static final String FIELD_RETRY = "retry";

    // ==================== 当前事件缓冲区 ====================

    /** 数据缓冲区 */
    private StringBuilder dataBuffer;
    /** 事件类型 */
    private String eventType;
    /** 事件ID */
    private String eventId;
    /** 重试MS */
    private Long retryMs;
    /** HAS数据 */
    private boolean hasData;

    // ==================== 回调 ====================

    /** 事件处理器 */
    private final Consumer<SseEvent> eventHandler;
    /** Comment处理器 */
    private final Consumer<String> commentHandler;

    /**
     * 创建 SSE 协议解析器
     *
     * @param eventHandler   事件分发回调（空行触发），不能为 null
     * @param commentHandler 注释行回调（{@code :} 前缀触发），可为 null
     */
    public SseProtocolParser(Consumer<SseEvent> eventHandler, Consumer<String> commentHandler) {
        this.eventHandler = eventHandler;
        this.commentHandler = commentHandler;
        resetBuffer();
    }

    /**
     * 创建 SSE 协议解析器（无注释回调）
     *
     * @param eventHandler 事件分发回调
     */
    public SseProtocolParser(Consumer<SseEvent> eventHandler) {
        this(eventHandler, null);
    }

    /**
     * 解析单行输入
     *
     * <p>按照 SSE 规范逐行处理：</p>
     * <ul>
     *   <li>空行 → 分发当前缓冲的事件</li>
     *   <li>{@code :} 开头 → 注释行</li>
     *   <li>{@code field: value} → 解析字段</li>
     *   <li>{@code field:value}（无空格）→ 解析字段</li>
     *   <li>{@code field:}（无值）→ 字段值为空字符串</li>
     * </ul>
     *
     * @param line 从流中读取的一行文本（不含换行符）
     */
    public void parseLine(String line) {
        // 空行 → 分发事件
        if (line.isEmpty()) {
            dispatchEvent();
            return;
        }

        // 注释行
        if (line.charAt(0) == ':') {
            if (commentHandler != null) {
                commentHandler.accept(line.length() > 1 ? line.substring(1).trim() : "");
            }
            return;
        }

        // 解析字段名和值
        int colonIndex = line.indexOf(':');
        String field;
        String value;

        if (colonIndex == -1) {
            // 无冒号 → 整行作为字段名，值为空
            field = line;
            value = "";
        } else {
            field = line.substring(0, colonIndex);
            value = line.substring(colonIndex + 1);
            // 去除前导单个空格（SSE 规范）
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
        }

        // 按字段名处理
        switch (field) {
            case FIELD_DATA:
                appendData(value);
                break;
            case FIELD_EVENT:
                this.eventType = value;
                break;
            case FIELD_ID:
                // SSE 规范：id 字段值不能包含空字符（U+0000）
                if (!value.contains("\0")) {
                    this.eventId = value;
                }
                break;
            case FIELD_RETRY:
                try {
                    long retry = Long.parseLong(value.trim());
                    if (retry >= 0) {
                        this.retryMs = retry;
                    }
                } catch (NumberFormatException ignored) {
                    // 非数字值忽略
                }
                break;
            default:
                // 未知字段忽略（SSE 规范）
                break;
        }
    }

    /**
     * 刷新缓冲区，分发尚未以空行结尾的最后一个事件
     *
     * <p>在流结束时调用，确保最后一条不以空行结尾的事件也能被分发。</p>
     */
    public void flush() {
        if (hasData || eventType != null) {
            dispatchEvent();
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 追加 data 行内容
     */
    private void appendData(String value) {
        if (dataBuffer == null) {
            dataBuffer = new StringBuilder();
        } else {
            dataBuffer.append('\n');
        }
        dataBuffer.append(value);
        hasData = true;
    }

    /**
     * 分发当前缓冲的事件并重置缓冲区
     */
    private void dispatchEvent() {
        if (!hasData) {
            // 空事件（无任何 data 行）不分发
            resetBuffer();
            return;
        }

        SseEvent event = SseEvent.builder()
                .data(dataBuffer != null ? dataBuffer.toString() : null)
                .event(eventType != null ? eventType : "message")
                .id(eventId)
                .retry(retryMs)
                .build();

        resetBuffer();
        eventHandler.accept(event);
    }

    /**
     * 重置事件缓冲区
     */
    private void resetBuffer() {
        dataBuffer = null;
        eventType = null;
        eventId = null;
        retryMs = null;
        hasData = false;
    }
}
