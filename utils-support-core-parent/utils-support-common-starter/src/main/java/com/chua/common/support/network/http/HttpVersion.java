package com.chua.common.support.network.http;

/**
 * HTTP 协议版本枚举。
 *
 * <p>用于标识 HTTP 客户端请求所使用的协议版本，
 * 取值包括 HTTP/1.1、HTTP/2 与 HTTP/3。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum HttpVersion {

    /**
     * HTTP/1.1 协议版本
     */
    HTTP_1_1,

    /**
     * HTTP/2 协议版本
     */
    HTTP_2,

    /**
     * HTTP/3 协议版本
     */
    HTTP_3
}
