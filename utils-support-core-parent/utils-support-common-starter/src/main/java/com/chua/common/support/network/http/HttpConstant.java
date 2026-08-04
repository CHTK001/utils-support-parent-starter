package com.chua.common.support.network.http;

import org.jspecify.annotations.NullUnmarked;

/**
 * HTTP 相关常量。
 *
 * @author CH
 */
@NullUnmarked
public class HttpConstant {

    /** 请求头：Content-Type */
    public static final String CONTENT_TYPE = "Content-Type";
    /** 请求头：Content-Length */
    public static final String CONTENT_LENGTH = "Content-Length";
    /** 请求头：Authorization */
    public static final String AUTHORIZATION = "Authorization";
    /** 请求头：Accept */
    public static final String ACCEPT = "Accept";

    /** Content-Type：application/json */
    public static final String APPLICATION_JSON = "application/json";
    /** Content-Type：application/x-www-form-urlencoded */
    public static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";
    /** Content-Type：multipart/form-data */
    public static final String MULTIPART_FORM_DATA = "multipart/form-data";
    /** Content-Type：text/plain */
    public static final String TEXT_PLAIN = "text/plain";
    /** Content-Type：text/html */
    public static final String TEXT_HTML = "text/html";
    /** Content-Type：application/octet-stream */
    public static final String OCTET_STREAM = "application/octet-stream";
    /** Content-Type：text/event-stream（SSE） */
    public static final String TEXT_EVENT_STREAM = "text/event-stream";
    /** Content-Type：application/xml */
    public static final String APPLICATION_XML = "application/xml";

    /** HTTP 方法：GET */
    public static final String GET = "GET";
    /** HTTP 方法：POST */
    public static final String POST = "POST";
    /** HTTP 方法：PUT */
    public static final String PUT = "PUT";
    /** HTTP 方法：DELETE */
    public static final String DELETE = "DELETE";
}