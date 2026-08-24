package com.chua.common.support.datasearch.pricing.spi;

/**
 * 定价页渲染器 SPI：为 JavaScript 渲染的动态定价页提供完整 HTML。
 *
 * <p>普通 HTTP 抓取拿不到有效表格时（SPA / 反爬站点），基类会尝试通过本接口
 * 借助无头浏览器渲染页面。默认无实现；引入
 * {@code utils-support-playwright-starter} 后自动启用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface PricingPageRenderer {

    /**
     * 渲染指定页面并返回最终 DOM 的 HTML。
     *
     * @param url 页面地址
     * @return 渲染后的 HTML，渲染失败或不可用时返回 null
     */
    String render(String url);

    /**
     * 渲染器名称。
     *
     * @return SPI 名称
     */
    String name();
}
