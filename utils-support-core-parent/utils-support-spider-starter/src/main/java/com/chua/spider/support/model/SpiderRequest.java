package com.chua.spider.support.model;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 爬虫请求。
 *
 * <p>封装一次爬取请求的全部参数，包括目标 URL、请求头、Cookie、请求方法、
 * 优先级、爬取深度等信息。通过 Builder 模式构建。
 *
 * @author CH
 * @since 2026/07/17
 */
@Data
@Builder(toBuilder = true)
public class SpiderRequest {

    /**
     * 目标 URL。
     *
     * <p>需要爬取的页面完整地址，包含协议、域名、路径和查询参数。
     */
    private String url;

    /**
     * 请求头。
     *
     * <p>自定义 HTTP 请求头，用于模拟浏览器行为或携带认证信息。
     */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    /**
     * Cookie。
     *
     * <p>请求时携带的 Cookie 字符串，用于维持会话状态。
     */
    private String cookies;

    /**
     * 请求方法。
     *
     * <p>HTTP 请求方法，默认 GET。支持 GET、POST 等。
     */
    @Builder.Default
    private String method = "GET";

    /**
     * 请求体。
     *
     * <p>POST 请求时携带的请求体内容。
     */
    private String body;

    /**
     * 优先级。
     *
     * <p>数字越大优先级越高，调度器优先处理高优先级请求。
     */
    @Builder.Default
    private int priority = 0;

    /**
     * 爬取深度。
     *
     * <p>当前请求相对于种子 URL 的链接深度。起始 URL 深度为 0。
     */
    @Builder.Default
    private int depth = 0;

    /**
     * 来源 URL。
     *
     * <p>当前请求是从哪个页面的链接提取出来的，用于追踪爬取路径。
     */
    private String referUrl;

    /**
     * 扩展属性。
     *
     * <p>透传给后续组件的自定义属性，Fetcher、Parser、Pipeline 均可读取。
     */
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
}
