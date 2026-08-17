package com.chua.common.support.scatter;

import com.chua.common.support.lang.json.Json;

import java.util.Objects;

/**
 * 带请求ID的结果包装，用于远程响应定位（requestId -> Future）。
 *
 * <p>sync 协议为文本行(topic:payload)，故本类提供线格式序列化/反序列化：
 * {@code requestId|S|<dataJson>}（成功）、{@code requestId|F|<error>}（失败）、
 * {@code requestId|T|<error>}（超时）、{@code requestId|N|}（空结果）。</p>
 *
 * @param requestId 请求ID
 * @param result    查询结果
 * @param <T>       数据类型
 * @author CH
 * @since 4.0.0.42
 */
public record ScatterResultWithRequestId<T>(String requestId, ScatterResult<T> result) {

    /**
     * 线格式字段分隔符
     */
    private static final char SEP = '|';

    /**
     * 成功标记
     */
    private static final char SUCCESS = 'S';

    /**
     * 失败标记
     */
    private static final char FAILURE = 'F';

    /**
     * 超时标记
     */
    private static final char TIMEOUT = 'T';

    /**
     * 空结果标记
     */
    private static final char NONE = 'N';

    /**
     * 序列化为文本线格式（sync 协议经 topic:payload 传输，payload 即本方法输出）。
     *
     * @return 线格式字符串
     */
    @Override
    public String toString() {
        if (result == null) {
            return requestId + SEP + NONE + SEP;
        }
        if (result.isSuccess()) {
            return requestId + SEP + SUCCESS + SEP + Json.toJson(result.getData());
        }
        char mark = result.isTimeout() ? TIMEOUT : FAILURE;
        String error = result.getErrorMessage() == null ? "" : result.getErrorMessage();
        return requestId + SEP + mark + SEP + error;
    }

    /**
     * 从文本线格式反序列化。
     *
     * @param line 线格式字符串
     * @param type 数据类型
     * @param <T>  数据类型
     * @return 包装对象，无法解析时返回 null
     */
    public static <T> ScatterResultWithRequestId<T> fromLine(String line, Class<T> type) {
        if (line == null || line.isBlank()) {
            return null;
        }
        int first = line.indexOf(SEP);
        if (first <= 0 || first + 2 > line.length()) {
            return null;
        }
        String requestId = line.substring(0, first);
        char mark = line.charAt(first + 1);
        String payload = first + 2 < line.length() ? line.substring(first + 2) : "";
        return switch (mark) {
            case SUCCESS -> new ScatterResultWithRequestId<>(requestId,
                    ScatterResult.success(requestId, deserialize(payload, type)));
            case FAILURE -> new ScatterResultWithRequestId<>(requestId,
                    ScatterResult.failure(requestId, payload));
            case TIMEOUT -> new ScatterResultWithRequestId<>(requestId,
                    ScatterResult.timeout(requestId, payload));
            case NONE -> new ScatterResultWithRequestId<>(requestId,
                    ScatterResult.failure(requestId, "无数据"));
            default -> null;
        };
    }

    /**
     * 反序列化数据（空串返回 null）。
     *
     * @param payload 数据 JSON
     * @param type    目标类型
     * @param <T>     数据类型
     * @return 数据
     */
    @SuppressWarnings("unchecked")
    private static <T> T deserialize(String payload, Class<T> type) {
        if (payload == null || payload.isBlank() || type == null || type == Object.class) {
            return (T) payload;
        }
        try {
            return Json.fromJson(payload, type);
        } catch (Exception e) {
            return (T) payload;
        }
    }

    /**
     * 便捷校验：结果与请求ID均非空。
     *
     * @return true 合法
     */
    public boolean isValid() {
        return Objects.nonNull(requestId) && Objects.nonNull(result);
    }
}
