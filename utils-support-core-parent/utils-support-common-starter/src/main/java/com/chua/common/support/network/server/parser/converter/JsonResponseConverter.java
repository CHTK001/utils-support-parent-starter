package com.chua.common.support.network.server.parser.converter;

import com.chua.common.support.network.annotations.ResponseConverter;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.lang.json.Json;

import java.nio.charset.StandardCharsets;

/**
 * JSON 响应转化器，将数据对象转换为 JSON 格式文本。
 *
 * <p>Content-Type 为 {@code application/json}。支持以下数据类型：
 * <ul>
 *   <li>{@link String} — 直接作为 JSON 输出</li>
 *   <li>{@code byte[]} — 按 UTF-8 解码为字符串</li>
 *   <li>其他对象 — 调用 {@link Object#toString()} 输出</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
*/
public class JsonResponseConverter implements ResponseConverter {

    @Override
    /** 转换 */
    public void convert(ServerResponse response, Object data) throws Exception {
        response.setContentType(contentType());
        if (data instanceof String s) {
            response.setBody(s);
        } else if (data instanceof byte[] b) {
            response.setBody(new String(b, StandardCharsets.UTF_8));
        } else if (data != null) {
            response.setBody(Json.toJson(data));
        }
    }

    @Override
    /** ContentType */
    public String contentType() {
        return "application/json; charset=utf-8";
    }

    @Override
    /** Support */
    public boolean support(Object data) {
        return true;
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return 100;
    }
}
