package com.chua.protocol.support.network.protocol.request;

/**
* 协议服务器响应抽象，屏蔽底层协议差异。
*
* @author CH
* @since 4.0.0.42
 */
public interface ServletResponse {

    /**
     * 设置响应 内容-类型
     * @param contentType 内容类型，不允许为 null
     */
    void setContentType(String contentType);

    /**
     * 设置响应状态码
     * @param status 状态，不允许为 null
     */
    void setStatus(int status);

    /**
    * 设置响应头。
    *
    * @param name  头名称
    * @param value 头值
    */
    void setHeader(String name, String value);

    /**
    * 添加响应头（同名可叠加）。
    *
    * @param name  头名称
    * @param value 头值
    */
    void addHeader(String name, String value);

    /**
     * 写入响应体字符串
     * @param body 请求体，不允许为 null
     */
    void setBodyString(String body);
}
