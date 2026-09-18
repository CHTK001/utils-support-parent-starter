package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

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
     *
     * @param engine 驱动引擎实例，不允许为 null
     * @param handle 引擎侧的请求上下文句柄标识
     */
    APIRequestContext(Engine engine, long handle) {
        this.engine = engine;
        this.handle = handle;
    }
/**
* 获取。
* @param url url
* @return 获取的结果
 */

    /**
    * post。
    * @param url url
    * @param body 主体
    * @return post的结果
    */
    public ApiResponse get(String url) { return fetch("apiGet", url, null); }
    /**
    * 放入。
    * @param url url
    * @param body 主体
    * @return 放入的结果
    */
    public ApiResponse post(String url, Object body) { return fetch("apiPost", url, body); }
    /**
    * 删除。
    * @param url url
    * @return 删除的结果
    * @param body 主体
    */
    public ApiResponse put(String url, Object body) { return fetch("apiPut", url, body); }
    /**
    * 删除。
    * @param url url
    * @return 删除的结果
    */
    public ApiResponse delete(String url) { return fetch("apiDelete", url, null); }
/**
* 获取。
* @param action 动作
* @param url url
* @param body 主体
* @return 获取的结果
* @author CH
* @since 4.0.0
 */

    private ApiResponse fetch(String action, String url, Object body) {
        Engine.ApiResponseData d = engine.apiRequest(action, url, body);
        return new ApiResponse(d);
    }

    public static class ApiResponse {
        private final int status;
        private final String body;
/**
* api响应。
* @param d d
* @return 状态的结果
 */

        public ApiResponse(Engine.ApiResponseData d) {
            this.status = d.status;
            this.body = d.body;
        }

        public int status() { return status; }
        /**
        * 主体。
        * @return 主体的结果
        */
        public String body() { return body; }
    }
}
