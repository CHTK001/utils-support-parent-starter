package com.chua.common.support.network.http;


/**
* HTTP 请求方法枚举，定义标准的 RESTful 方法。
*
* @author CH
* @since 4.0.0.42
 */
public enum HttpMethod {

    /**
    * GET 请求，获取资源
     */
    GET,
    /**
    * POST 请求，创建资源
     */
    POST,
    /**
    * PUT 请求，更新资源
     */
    PUT,
    /**
    * DELETE 请求，删除资源
     */
    DELETE,
    /**
    * PATCH 请求，部分更新资源
     */
    PATCH,
    /**
    * HEAD 请求，获取响应头
     */
    HEAD,
    /**
    * OPTIONS 请求，获取支持的请求方法
     */
    OPTIONS
}
