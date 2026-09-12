package com.chua.common.support.network.server.parser.converter;

import com.chua.common.support.network.annotations.ResponseConverter;
import com.chua.common.support.network.server.response.ServerResponse;

import java.nio.charset.StandardCharsets;

/**
* 二进制响应转化器，将数据对象转换为二进制字节数组。
*
* <p>Content-Type 为 {@code application/octet-stream}。支持以下数据类型：
* <ul>
*   <li>{@code byte[]} — 直接作为二进制输出</li>
*   <li>{@link String} — 按 UTF-8 编码为字节数组</li>
*   <li>其他对象 — 调用 {@link Object#toString()} 按 UTF-8 编码</li>
* </ul>
*
* @author CH
* @since 2026/07/16
 */
public class BinaryResponseConverter implements ResponseConverter {

    @Override
    /** 转换 */
    public void convert(ServerResponse response, Object data) throws Exception {
        response.setContentType(contentType());
        if (data instanceof byte[] b) {
            response.setBody(b);
        } else if (data instanceof String s) {
            response.setBody(s.getBytes(StandardCharsets.UTF_8));
        } else if (data != null) {
            response.setBody(data.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    /** ContentType */
    public String contentType() {
        return "application/octet-stream";
    }

    @Override
    /** Support */
    public boolean support(Object data) {
        return data instanceof byte[];
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return 300;
    }
}