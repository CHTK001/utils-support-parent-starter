package com.chua.playwright.support;

import java.util.Map;

/**
 * 双模式 {@code Response}。
 * @author CH
 * @since 4.0.0
 * @return 状态的结果
 */
public class Response {

    private final int status; // 状态
    private final String url; // url

    Response(int status, String url) {
        this.status = status;
        this.url = url;
    }

    Response(Map<String, Object> d) {
        this.status = d.get("status") == null ? 0 : ((Number) d.get("status")).intValue();
        this.url = (String) d.get("url");
    }

    Response(com.chua.playwright.support.spi.Engine.ResponseData d) {
        this.status = d.status;
        this.url = d.url;
    }

    public int status() { return status; }
    /**
     * url。
     * @return url的结果
     */
    public String url() { return url; }
}