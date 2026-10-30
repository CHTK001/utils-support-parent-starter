package com.chua.playwright.support;

import com.chua.playwright.support.engine.Engine;

/**
 * 双模式 {@code APIRequestContext}。
 * 封装 API 请求上下文，按引擎句柄转发具体调用。
 *
 * @author CH
 * @since 4.0.0
 */
public class APIRequestContext {

    private final Engine engine; // engine
    private final long handle; // 处理

    /**
     * 创建 API 请求上下文句柄包装。
     * @param engine 驱动引擎实例，不允许为 null
     * @param handle 引擎侧的请求上下文句柄标识
     * @return 返回值
    */
    APIRequestContext(Engine engine, long handle) {
        this.engine = engine;
        this.handle = handle;
    }

    /**
     * 发起 GET 请求。
     *
     * @param url 请求地址
     * @return 响应结果
     */
    public ApiResponse get(String url) {
        return fetch("apiGet", url, null);
    }

    /**
     * 发起 POST 请求。
     *
     * @param url  请求地址
     * @param body 请求体，可为 null
     * @return 响应结果
     */
    public ApiResponse post(String url, Object body) {
        return fetch("apiPost", url, body);
    }

    /**
     * 发起 PUT 请求。
     *
     * @param url  请求地址
     * @param body 请求体，可为 null
     * @return 响应结果
     */
    public ApiResponse put(String url, Object body) {
        return fetch("apiPut", url, body);
    }

    /**
     * 发起 DELETE 请求。
     *
     * @param url 请求地址
     * @return 响应结果
     */
    public ApiResponse delete(String url) {
        return fetch("apiDelete", url, null);
    }

    /**
     * 按动作名转发到引擎侧的具体 API 请求实现。
     *
     * @param action 动作名（apiGet/apiPost/apiPut/apiDelete）
     * @param url    请求地址
     * @param body   请求体，可为 null
     * @return 响应结果
     */
    private ApiResponse fetch(String action, String url, Object body) {
        Engine.ApiResponseData d = engine.apiRequest(action, url, body);
        return new ApiResponse(d);
    }

    /**
     * API 响应结果的只读包装。
     */
    public static class ApiResponse {
        private final int status;
        private final String body;

        /**
         * 基于引擎侧响应数据创建包装对象。
         *
         * @param d 引擎侧响应数据
         */
        public ApiResponse(Engine.ApiResponseData d) {
            this.status = d.status;
            this.body = d.body;
        }

        /**
         * 获取 HTTP 状态码。
         *
         * @return 状态码
         */
        public int status() {
            return status;
        }

        /**
         * 获取响应体文本。
         *
         * @return 响应体
         */
        public String body() {
            return body;
        }
    }
}
