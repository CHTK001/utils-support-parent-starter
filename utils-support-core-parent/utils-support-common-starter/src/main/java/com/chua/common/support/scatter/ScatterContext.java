package com.chua.common.support.scatter;

import com.chua.common.support.lang.json.Json;

import java.util.Map;
import java.util.Objects;

/**
 * 一次 Scatter 查询的上下文。
 * <p>封装单次查询的元数据，包括请求ID、路径、超时及属性。</p>
 *
 * @since 4.0.0.42
 */
public final class ScatterContext {

    /**
     * JSON 字段名：请求ID
     */
    private static final String FIELD_REQUEST_ID = "requestId";

    /**
     * JSON 字段名：服务路径
     */
    private static final String FIELD_PATH = "path";

    /**
     * JSON 字段名：超时时间
     */
    private static final String FIELD_TIMEOUT_MILLIS = "timeoutMillis";

    /**
     * JSON 字段名：最小成功数
     */
    private static final String FIELD_MIN_SUCCESS_COUNT = "minSuccessCount";

    /**
     * 请求唯一标识
     */
    private final String requestId;

    /**
     * 服务路径
     */
    private final String path;

    /**
     * 超时时间（毫秒）
     */
    private final long timeoutMillis;

    /**
     * 最小成功次数
     */
    private final int minSuccessCount;

    /**
     * 扩展属性
     */
    private final Map<String, Object> attributes;

    /**
     * 构造上下文。
     *
     * @param requestId       请求ID
     * @param path            路径
     * @param timeoutMillis   超时时间
     * @param minSuccessCount 最小成功数
     * @param attributes      属性
     */
    public ScatterContext(String requestId, String path, long timeoutMillis, int minSuccessCount,
                          Map<String, Object> attributes) {
        this.requestId = Objects.requireNonNull(requestId, "请求ID不能为空");
        this.path = Objects.requireNonNull(path, "路径不能为空");
        this.timeoutMillis = timeoutMillis;
        this.minSuccessCount = Math.max(1, minSuccessCount);
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getPath() {
        return path;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public int getMinSuccessCount() {
        return minSuccessCount;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * 按名称获取属性。
     *
     * @param name 属性名
     * @return 属性值
     */
    public Object attribute(String name) {
        return attributes.get(name);
    }

    /**
     * 可解析的序列化形式(sync 文本协议经 topic:payload 传输,需 toString 携带关键信息)。
     *
     * @return JSON 风格字符串
     */
    @Override
    public String toString() {
        return "{\"requestId\":\"" + requestId + "\",\"path\":\"" + path
                + "\",\"timeoutMillis\":" + timeoutMillis
                + ",\"minSuccessCount\":" + minSuccessCount + "}";
    }

    /**
     * 从 sync 文本协议的 payload（{@link #toString()} 输出的 JSON）反序列化上下文。
     * <p>替代各处手工 indexOf 字符串解析，避免转义/边界问题。</p>
     *
     * @param line 线格式 payload
     * @return 上下文，无法解析时返回 null
     */
    public static ScatterContext fromLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = Json.fromJson(line, Map.class);
            if (map == null) {
                return null;
            }
            String requestId = Objects.toString(map.get(FIELD_REQUEST_ID), null);
            String path = Objects.toString(map.get(FIELD_PATH), null);
            if (requestId == null || requestId.isBlank() || path == null || path.isBlank()) {
                return null;
            }
            Number timeout = map.get(FIELD_TIMEOUT_MILLIS) instanceof Number n ? n : null;
            Number minCount = map.get(FIELD_MIN_SUCCESS_COUNT) instanceof Number n ? n : null;
            long timeoutMillis = timeout == null ? 0L : timeout.longValue();
            int minSuccessCount = minCount == null ? 1 : Math.max(1, minCount.intValue());
            return new ScatterContext(requestId, path, timeoutMillis, minSuccessCount, Map.of());
        } catch (Exception e) {
            return null;
        }
    }
}
