package com.chua.common.support.network.protocol.proxy;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.text.json.Json;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * JSON 响应处理器
 * <p>
 * 对 JSON 响应进行处理，支持：
 * - 添加包装层
 * - 字段重命名
 * - 字段过滤
 * - 数据脱敏
 *
 * @author CH
 * @since 2025/12/06
 */
@Slf4j
@Spi("json")
public class JsonProxyResponseHandler implements ProxyResponseHandler {

    /**
     * 是否包装响应
     */
    private boolean wrapResponse = false;

    /**
     * 包装字段名
     */
    private String wrapFieldName = "data";

    /**
     * 需要脱敏的字段
     */
    private String[] sensitiveFields = {};

    @Override
    public String getName() {
        return "json";
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public boolean supports(ServletRequest request, ServletResponse response) {
        String contentType = response.getContentType();
        return contentType != null && contentType.toLowerCase().contains("json");
    }

    @Override
    public ServletResponse handle(ServletRequest request, ServletResponse response) {
        try {
            String bodyString = response.getBodyString();
            if (bodyString == null || bodyString.isEmpty()) {
                return response;
            }

            // 解析 JSON
            Object jsonObj = Json.fromJson(bodyString, Object.class);
            if (jsonObj == null) {
                return response;
            }

            // 脱敏处理
            if (sensitiveFields.length > 0 && jsonObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> jsonMap = (Map<String, Object>) jsonObj;
                desensitize(jsonMap);
                jsonObj = jsonMap;
            }

            // 包装响应
            if (wrapResponse) {
                jsonObj = Map.of(
                        wrapFieldName, jsonObj,
                        "success", response.getStatusCode() >= 200 && response.getStatusCode() < 300,
                        "code", response.getStatusCode()
                );
            }

            // 转回 JSON
            response.setBodyString(Json.toJson(jsonObj));

        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("JSON 处理失败: {}", e.getMessage());
            }
        }

        return response;
    }

    /**
     * 数据脱敏
     */
    @SuppressWarnings("unchecked")
    private void desensitize(Map<String, Object> jsonMap) {
        for (String field : sensitiveFields) {
            if (jsonMap.containsKey(field)) {
                Object value = jsonMap.get(field);
                if (value instanceof String) {
                    jsonMap.put(field, mask((String) value));
                }
            }
        }

        // 递归处理嵌套对象
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            if (entry.getValue() instanceof Map) {
                desensitize((Map<String, Object>) entry.getValue());
            }
        }
    }

    /**
     * 掩码处理
     */
    private String mask(String value) {
        if (value == null || value.length() <= 2) {
            return "***";
        }
        int len = value.length();
        int showLen = Math.max(1, len / 4);
        return value.substring(0, showLen) + "***" + value.substring(len - showLen);
    }

    // ========== 配置方法 ==========

    public JsonProxyResponseHandler setWrapResponse(boolean wrapResponse) {
        this.wrapResponse = wrapResponse;
        return this;
    }

    public JsonProxyResponseHandler setWrapFieldName(String wrapFieldName) {
        this.wrapFieldName = wrapFieldName;
        return this;
    }

    public JsonProxyResponseHandler setSensitiveFields(String... fields) {
        this.sensitiveFields = fields;
        return this;
    }
}
