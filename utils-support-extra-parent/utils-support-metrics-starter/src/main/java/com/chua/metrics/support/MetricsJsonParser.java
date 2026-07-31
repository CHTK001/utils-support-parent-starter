package com.chua.metrics.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON 解析器，负责将字节数组解析为 MetricsSnapshot 对象。
 *
 * <p>Rust 侧使用 serde_json 序列化，Java 侧使用 Jackson 解析。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MetricsJsonParser {

    /**
     * ObjectMapper 实例
     */
    private final ObjectMapper objectMapper;

    /**
     * 构造 JSON 解析器。
     */
    public MetricsJsonParser() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 解析 JSON 字符串为 MetricsSnapshot 对象。
     *
     * @param json JSON 字符串
     * @return MetricsSnapshot 对象，解析失败返回 null
     */
    public MetricsSnapshot parse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MetricsSnapshot.class);
        } catch (Exception e) {
            log.warn("解析 JSON 数据异常", e);
            return null;
        }
    }
}