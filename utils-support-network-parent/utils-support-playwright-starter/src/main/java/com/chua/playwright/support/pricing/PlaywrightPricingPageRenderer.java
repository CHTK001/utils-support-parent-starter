package com.chua.playwright.support.pricing;

import com.chua.common.support.datasearch.pricing.spi.PricingPageRenderer;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.playwright.support.spider.PlaywrightFetcher;
import com.chua.spider.support.model.SpiderRequest;
import com.chua.spider.support.model.SpiderResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于无头浏览器的定价页渲染器。
 *
 * <p>为 datasearch 定价提供者提供 JavaScript 渲染能力：普通 HTTP 抓取
 * 拿不到有效表格时（SPA / 反爬站点），由本渲染器借助 Playwright
 * 渲染页面后返回完整 HTML。</p>
 *
 * <p>SPI 名称：{@code playwright}。引入本模块即自动启用；
 * 运行环境缺少 Playwright 浏览器驱动时 {@link #render(String)} 返回 null，
 * 定价提供者自动回退到内置基线数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("playwright")
@ConditionalOnClass("com.microsoft.playwright.Playwright")
public class PlaywrightPricingPageRenderer implements PricingPageRenderer {

    /**
     * 浏览器渲染抓取器（惰性初始化）
     */
    private PlaywrightFetcher fetcher;

    /**
     * 释放浏览器资源。
     *
     * <p>批量同步完成后调用；未初始化过渲染器时为空操作。</p>
     */
    public void close() {
        if (fetcher != null) {
            fetcher.close();
            fetcher = null;
        }
    }

    /**
     * 获取或初始化浏览器渲染抓取器。
     *
     * @return 渲染抓取器，Playwright 不可用时返回 null
     */
    protected synchronized PlaywrightFetcher obtainFetcher() {
        if (fetcher != null) {
            return fetcher;
        }
        try {
            fetcher = new PlaywrightFetcher();
            return fetcher;
        } catch (Throwable t) {
            // 缺少 playwright 依赖或浏览器驱动时优雅降级
            log.debug("Playwright 初始化失败: {}", t.getMessage());
            return null;
        }
    }

    /**
     * 渲染指定页面并返回最终 DOM 的 HTML。
     *
     * @param url 页面地址
     * @return 渲染后的 HTML，渲染失败或不可用时返回 null
     */
    @Override
    public String render(String url) {
        PlaywrightFetcher current = obtainFetcher();
        if (current == null) {
            return null;
        }
        try {
            SpiderResponse response = current.fetch(SpiderRequest.builder().url(url).build());
            if (response == null || response.getStatusCode() != 200) {
                log.debug("渲染失败: url={}, msg={}", url, response == null ? "null" : response.getError());
                return null;
            }
            return response.getContent();
        } catch (Exception e) {
            log.debug("渲染异常: url={}, msg={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 返回渲染器名称。
     *
     * @return SPI 名称
     */
    @Override
    public String name() {
        return "playwright";
    }
}
