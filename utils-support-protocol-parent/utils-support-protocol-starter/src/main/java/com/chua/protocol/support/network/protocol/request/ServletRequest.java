package com.chua.protocol.support.network.protocol.request;

/**
 * 协议服务器请求抽象。
 *
 * <p>屏蔽底层协议差异，为业务提供统一的请求读取 API。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ServletRequest {

    /**
     * 获取请求体字节。
     *
     * @return 请求体字节数组，可能为空数组
     */
    byte[] getBody();

    /**
     * 获取查询字符串。
     *
     * @return 查询字符串，无则为 {@code null}
     */
    String getQueryString();
}