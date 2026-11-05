// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.cdp.support.spider;

import com.chua.common.support.browser.BrowserEngine;
import com.chua.common.support.browser.BrowserOptions;
import com.chua.common.support.browser.BrowserPage;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.crawler.support.SpiderFetcher;
import com.chua.crawler.support.model.SpiderRequest;
import com.chua.crawler.support.model.SpiderResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * CDP 浏览器渲染抓取器
 *
 * <p>与 {@code PlaywrightFetcher} / {@code JvppeteerFetcher} 对等，但底层使用
 * common-starter 自带的轻量 CDP 引擎（JDK ProcessBuilder + WebSocket），
 * 不引入 Playwright / Jvppeteer / Selenium 等任何第三方浏览器依赖。</p>
 *
 * <p>SPI 名称：{@code cdp}。抓取时复用同一个浏览器进程，只在需要时创建标签页；
 * 用完调用 {@link #close()} 释放浏览器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("cdp")
@ConditionalOnClass("com.chua.common.support.browser.BrowserEngine")
public class CdpFetcher implements SpiderFetcher, AutoCloseable {

    /**
     * 浏览器引擎
     */
    private final BrowserEngine engine;

    /**
     * 默认构造器：使用内置轻量 CDP 引擎
     */
    public CdpFetcher() {
        this(BrowserEngine.cdp());
    }

    /**
     * 复用已有浏览器引擎
     *
     * @param engine 浏览器引擎
     */
    public CdpFetcher(BrowserEngine engine) {
        this.engine = engine;
    }

    /**
     * 抓取页面
     *
     * @param request 抓取请求
     * @return 抓取响应（失败时 statusCode=0 且带 error）
     */
    @Override
    public SpiderResponse fetch(SpiderRequest request) {
        long startTime = System.currentTimeMillis();
        SpiderResponse.SpiderResponseBuilder builder = SpiderResponse.builder().request(request);
        BrowserPage page = null;
        try {
            page = engine.openPage(new BrowserOptions());
            if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
                page.setExtraHeaders(request.getHeaders());
            }
            page.navigate(request.getUrl());
            String html = page.content();
            long elapsed = System.currentTimeMillis() - startTime;
            builder.statusCode(200)
                    .content(html)
                    .rawContent(html == null ? new byte[0] : html.getBytes())
                    .contentType("text/html; charset=UTF-8")
                    .fetchTimeMs(elapsed);
            log.info("[cdp] 抓取完成: {} ({}ms)", request.getUrl(), elapsed);
        } catch (Exception e) {
            builder.statusCode(0)
                    .error("CDP 渲染异常: " + e.getMessage())
                    .fetchTimeMs(System.currentTimeMillis() - startTime);
            log.warn("[cdp] 渲染失败: {} - {}", request.getUrl(), e.getMessage());
        } finally {
            closePage(page);
        }
        return builder.build();
    }

    /**
     * 释放浏览器资源
     */
    @Override
    public void close() {
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * 关闭页面
     *
     * @param page 页面
     */
    private static void closePage(BrowserPage page) {
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (Exception e) {
            log.debug("[cdp] 关闭页面失败: {}", e.getMessage());
        }
    }
}