package com.chua.protocol.support.network.protocol.request;

/**
 * 协议服务器响应抽象。
 *
 * <p>屏蔽底层协议差异，为业务提供统一的响应写入 API。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ServletResponse {

    /**
     * 设置响应 Content-Type。
     *
     * @param contentType MIME 类型，如 application/json;charset=UTF-8
     */
    void setContentType(String contentType);

    /**
     * 设置响应状态码。
     *
     * @param status HTTP 状态码
     */
    void setStatus(int status);

    /**
     * 写入响应体字符串。
     *
     * @param body 响应体内容
     */
    void setBodyString(String body);
}