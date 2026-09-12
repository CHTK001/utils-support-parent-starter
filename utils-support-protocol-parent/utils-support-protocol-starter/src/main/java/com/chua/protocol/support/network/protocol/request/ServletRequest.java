package com.chua.protocol.support.network.protocol.request;

import java.util.Enumeration;
import java.util.Map;

/**
* 协议服务器请求抽象，屏蔽底层协议差异。
*
* @author CH
* @since 4.0.0.42
 */
public interface ServletRequest {

    /** 获取请求体字节 */
    byte[] getBody();

    /** 获取查询字符串 */
    String getQueryString();

    /** 获取远程客户端 IP */
    String getRemoteAddr();

    /** 获取远程客户端端口 */
    int getRemotePort();

    /**
    * 获取所有请求头（键 → 值）。
    *
    * @return 请求头映射，大小写不敏感
     */
    Map<String, String> getHeaders();

    /** 获取所有请求头名称枚举 */
    Enumeration<String> getHeaderNames();

    /**
    * 获取指定请求头的值。
    *
    * @param name 头名称
    * @return 头值，无则为 {@code null}
     */
    String getHeader(String name);

    /** 获取 HTTP 方法（如 获取 / POST） */
    String getMethod();

    /** 获取请求 URI 路径 */
    String getRequestURI();
}