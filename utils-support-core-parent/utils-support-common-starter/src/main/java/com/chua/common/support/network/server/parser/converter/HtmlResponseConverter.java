package com.chua.common.support.network.server.parser.converter;

import com.chua.common.support.network.annotations.ResponseConverter;
import com.chua.common.support.network.server.response.ServerResponse;

import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NullUnmarked;

/**
 * HTML 响应转化器，将数据对象转换为 HTML 格式文本。
 *
 * <p>Content-Type 为 {@code text/html}。支持以下数据类型：
 * <ul>
 *   <li>{@link String} — 直接作为 HTML 输出</li>
 *   <li>{@code byte[]} — 按 UTF-8 解码为字符串</li>
 *   <li>其他对象 — 调用 {@link Object#toString()} 输出</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
@NullUnmarked
public class HtmlResponseConverter implements ResponseConverter {

    @Override
    public void convert(ServerResponse response, Object data) throws Exception {
        response.setContentType(contentType());
        if (data instanceof String s) {
            response.setBody(s);
        } else if (data instanceof byte[] b) {
            response.setBody(new String(b, StandardCharsets.UTF_8));
        } else if (data != null) {
            response.setBody(data.toString());
        }
    }

    @Override
    public String contentType() {
        return "text/html; charset=utf-8";
    }

    @Override
    public boolean support(Object data) {
        return data instanceof String s && (s.trim().startsWith("<") || s.trim().startsWith("<!"));
    }

    @Override
    public int getOrder() {
        return 1;
    }
}