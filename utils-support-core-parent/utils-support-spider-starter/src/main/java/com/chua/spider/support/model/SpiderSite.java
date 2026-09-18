package com.chua.spider.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
* 站点配置。
*
* <p>描述目标站点的爬取规则和限制，包括请求间隔、重试策略、
* 用户-Agent、Cookie 等。每个域名可配置独立的站点信息。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SpiderSite {

    /**
    * 目标域名。
    *
    * <p>如 "example.com"，用于匹配该站点的爬取请求。
    */
    private String domain;

    /**
    * 用户-Agent。
    *
    * <p>爬取该站点时使用的 User-Agent 字符串，用于模拟浏览器。
    */
    @Builder.Default
    /** 用户Agent */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
    * 请求间隔。
    *
    * <p>连续两次请求之间的最小间隔时间，单位为毫秒。
    * 用于避免对目标服务器造成过大压力。
    */
    @Builder.Default
    /** 间隔 */
    private int interval = 1000;

    /**
    * 重试次数。
    *
    * <p>请求失败时的最大重试次数。超过此次数后丢弃该请求。
    */
    @Builder.Default
    /** 重试时间 */
    private int retryTimes = 3;

    /**
    * 请求超时时间。
    *
    * <p>单次请求的超时时长，单位为毫秒。
    */
    @Builder.Default
    /** 超时 */
    private int timeout = 30000;

    /**
    * Cookie。
    *
    * <p>爬取该站点时携带的 Cookie 字符串。
    */
    private String cookies;

    /**
    * 自定义请求头。
    *
    * <p>爬取该站点时附加的 HTTP 请求头。
    */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>(); // 头部

    /**
    * 是否遵守 robots.txt。
    *
    * <p>为 true 时，爬虫会检查并遵守目标站点的 robots.txt 规则。
    */
    @Builder.Default
    /** respectrobotstxt */
    private boolean respectRobotsTxt = false;

    /**
    * 最大爬取深度。
    *
    * <p>从种子 URL 开始的最大链接追踪深度。0 表示只爬取种子页面。
    * 负数表示不限制深度。
    */
    @Builder.Default
    /** 最大值深度 */
    private int maxDepth = -1;

    /**
    * 最大爬取页面数。
    *
    * <p>限制本次爬取任务最多抓取的页面数量。0 或负数表示不限制。
    */
    @Builder.Default
    /** 最大值pages */
    private int maxPages = 0;
}
