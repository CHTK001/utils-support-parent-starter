package com.chua.openai.support.inception;

import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vercel AI SDK v5「UI Message Stream」解析器
 *
 * <p>上游 {@code POST /api/chat} 返回 {@code text/event-stream}，并在响应头
 * {@code x-vercel-ai-ui-message-stream: v1} 中声明协议版本。每个 {@code data:}
 * 行是一个 JSON 事件：</p>
 * <pre>
 * data: {"type":"reasoning-start","id":"reasoning-0"}
 * data: {"type":"text-start","id":"text-0"}
 * data: {"type":"text-delta","id":"text-0","delta":"收到"}
 * data: {"type":"text-end","id":"text-0"}
 * data: [DONE]
 * </pre>
 *
 * <p>解析器只关心正文增量、推理增量和用量；其余事件（start / step / tool-*）
 * 按需忽略。分片可能把一行拆成两半，因此内部维护行缓冲。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
final class InceptionStreamParser {

    /**
     * SSE 数据前缀
     */
    private static final String DATA_PREFIX = "data:";

    /**
     * 流结束标记
     */
    private static final String DONE_MARK = "[DONE]";

    /**
     * 流式回调
     */
    private final InceptionTransport.InceptionStreamListener listener;

    /**
     * 行缓冲
     */
    private final StringBuilder buffer = new StringBuilder();

    /**
     * 上游错误信息
     */
    private String error;

    /**
     * 是否已收到结束标记
     */
    private boolean completed;

    /**
     * 构造解析器
     * @param listener 流式回调
     * @return 返回值
    */
    InceptionStreamParser(InceptionTransport.InceptionStreamListener listener) {
        this.listener = listener;
    }

    /**
     * 喂入一段原始文本（可能截断在某一行中间）
     *
     * @param chunk 原始分片
     */
    void feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        buffer.append(chunk);
        int index;
        while ((index = buffer.indexOf("\n")) >= 0) {
            String line = buffer.substring(0, index);
            buffer.delete(0, index + 1);
            handleLine(line);
        }
    }

    /**
     * 处理残留的最后一行（上游未以换行结尾时）
     */
    void flush() {
        if (buffer.length() > 0) {
            String line = buffer.toString();
            buffer.setLength(0);
            handleLine(line);
        }
    }

    /**
     * 上游错误信息
     *
     * @return 错误文本，无错误返回 null
     */
    String error() {
        return error;
    }

    /**
     * 是否收到 [DONE]
     *
     * @return true 表示流已正常结束
     */
    boolean completed() {
        return completed;
    }

    /**
     * 处理单行 SSE
     *
     * @param rawLine 原始行（可能带 \r）
     */
    private void handleLine(String rawLine) {
        if (rawLine == null) {
            return;
        }
        String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
        if (line.isEmpty() || line.startsWith(":")) {
            return;
        }
        if (!line.startsWith(DATA_PREFIX)) {
            return;
        }
        String data = line.substring(DATA_PREFIX.length()).trim();
        if (data.isEmpty()) {
            return;
        }
        if (DONE_MARK.equals(data)) {
            completed = true;
            return;
        }

        Map<String, Object> event;
        try {
            event = Json.fromJson(data);
        } catch (Exception e) {
            log.debug("[inception] 忽略无法解析的流事件: {}", e.getMessage());
            return;
        }
        if (event == null) {
            return;
        }

        String type = asString(event.get("type"));
        if (type == null) {
            return;
        }
        switch (type) {
            case "text-delta" -> {
                String delta = asString(event.get("delta"));
                if (delta == null) {
                    delta = asString(event.get("textDelta"));
                }
                if (delta != null && !delta.isEmpty()) {
                    listener.onText(delta);
                }
            }
            case "reasoning-delta" -> {
                String delta = asString(event.get("delta"));
                if (delta == null) {
                    delta = asString(event.get("textDelta"));
                }
                if (delta != null && !delta.isEmpty()) {
                    listener.onReasoning(delta);
                }
            }
            case "error" -> {
                String text = asString(event.get("errorText"));
                if (text == null) {
                    text = asString(event.get("error"));
                }
                if (text == null) {
                    text = asString(event.get("message"));
                }
                error = text == null ? data : text;
            }
            default -> {
                Map<String, Object> usage = asMap(event.get("usage"));
                if (usage != null) {
                    applyUsage(usage);
                }
            }
        }
    }

    /**
     * 解析并回调用量
     *
     * @param usage 用量字段
     */
    private void applyUsage(Map<String, Object> usage) {
        Integer input = asInteger(usage.get("inputTokens"));
        Integer output = asInteger(usage.get("outputTokens"));
        Integer total = asInteger(usage.get("totalTokens"));
        if (input == null) {
            input = asInteger(usage.get("promptTokens"));
        }
        if (output == null) {
            output = asInteger(usage.get("completionTokens"));
        }
        if (input == null && output == null && total == null) {
            return;
        }
        if (total == null && input != null && output != null) {
            total = input + output;
        }
        listener.onUsage(new InceptionTransport.InceptionUsage(input, output, total));
    }

    /**
     * 安全转换字符串
     *
     * @param value 原始值
     * @return 字符串，类型不符返回 null
     */
    private static String asString(Object value) {
        return value instanceof String str ? str : null;
    }

    /**
     * 安全转换整数
     *
     * @param value 原始值
     * @return 整数，类型不符返回 null
     */
    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.valueOf(str.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 安全转换 Map
     *
     * @param value 原始值
     * @return 字符串键 Map，类型不符返回 null
     */
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
