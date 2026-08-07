package com.chua.spider.support.model;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 爬虫请求。
 *
 * <p>封装一次爬取请求的全部参数，包括目标 URL、请求头、Cookie、请求方法、
 * 优先级、爬取深度、代理等信息。通过 Builder 模式构建。</p>
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
     * <p>需要爬取的页面完整地址，包含协议、域名、路径和查询参数。</p>
     */
    private String url;

    /**
     * 请求头。
     *
     * <p>自定义 HTTP 请求头，用于模拟浏览器行为或携带认证信息。</p>
     */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    /**
     * Cookie。
     *
     * <p>请求时携带的 Cookie 字符串，用于维持会话状态。</p>
     */
    private String cookies;

    /**
     * 结构化 Cookie 列表。
     *
     * <p>每条 Cookie 包含名称、值、域名、路径等字段，
     * 内部会自动拼接为 {@code Cookie} 请求头发送。</p>
     */
    @Builder.Default
    private java.util.List<SpiderCookie> cookieList = new java.util.ArrayList<>();

    /**
     * 请求方法。
     *
     * <p>HTTP 请求方法，默认 GET。支持 GET、POST 等。</p>
     */
    @Builder.Default
    private String method = "GET";

    /**
     * 请求体。
     *
     * <p>POST 请求时携带的请求体内容。</p>
     */
    private String body;

    /**
     * 优先级。
     *
     * <p>数字越大优先级越高，调度器优先处理高优先级请求。</p>
     */
    @Builder.Default
    private int priority = 0;

    /**
     * 爬取深度。
     *
     * <p>当前请求相对于种子 URL 的链接深度。起始 URL 深度为 0。</p>
     */
    @Builder.Default
    private int depth = 0;

    /**
     * 来源 URL。
     *
     * <p>当前请求是从哪个页面的链接提取出来的，用于追踪爬取路径。</p>
     */
    private String referUrl;

    /**
     * 扩展属性。
     *
     * <p>透传给后续组件的自定义属性，Fetcher、Parser、Pipeline 均可读取。</p>
     */
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    /**
     * 代理配置。
     *
     * <p>为空时表示直连，不为空时请求通过该代理转发。</p>
     */
    private SpiderProxyConfig proxy;
}
