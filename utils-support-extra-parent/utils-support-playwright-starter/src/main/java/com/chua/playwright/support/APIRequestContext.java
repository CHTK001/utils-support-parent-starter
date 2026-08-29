package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;

/**
 * 双模式 {@code APIRequestContext}。
 */
public class APIRequestContext {

    private final Engine engine;
    private final long handle;

    APIRequestContext(Engine engine, long handle) { this.engine = engine; this.handle = handle; }

    public ApiResponse get(String url) { return fetch("apiGet", url, null); }
    public ApiResponse post(String url, Object body) { return fetch("apiPost", url, body); }
    public ApiResponse put(String url, Object body) { return fetch("apiPut", url, body); }
    public ApiResponse delete(String url) { return fetch("apiDelete", url, null); }

    private ApiResponse fetch(String action, String url, Object body) {
        Engine.ApiResponseData d = engine.apiRequest(action, url, body);
        return new ApiResponse(d);
    }

    public static class ApiResponse {
        private final int status;
        private final String body;

        public ApiResponse(Engine.ApiResponseData d) {
            this.status = d.status;
            this.body = d.body;
        }

        public int status() { return status; }
        public String body() { return body; }
    }
}