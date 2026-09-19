package com.chua.playwright.support.spider;

import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.spider.support.SpiderFetcher;
import com.chua.spider.support.model.SpiderRequest;
import com.chua.spider.support.model.SpiderResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Playwright 浏览器渲染抓取器。
 *
 * <p>基于 Playwright 实现支持 JavaScript 渲染的页面抓取。
 * 适用于需要执行 JS 才能获取完整内容的 水疗中心 或动态页面。
 * 内部持有 Playwright 浏览器实例，{@link #close()} 可释放资源。
 *
 * <p>SPI 名称：{@code playwright}，配合 {@link PlaywrightParser} 使用。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("playwright")
@ConditionalOnClass("com.microsoft.playwright.Playwright")
public class PlaywrightFetcher implements SpiderFetcher {

    /**
     * nav 超时时间
     */
    private static final Duration NAV_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 加载 超时时间
     */
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Playwright 实例
     */
    private final Playwright playwright;
    /**
     * 浏览器实例
     */
    private final Browser browser;

    /**
     * 默认构造器，启动 铬 浏览器。
     */
    public PlaywrightFetcher() {
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
    }

    /**
     * 构造器，使用已有 Playwright 和浏览器实例。
     *
     * @param playwright Playwright 实例
     * @param browser    浏览器实例
     */
    public PlaywrightFetcher(Playwright playwright, Browser browser) {
        this.playwright = playwright;
        this.browser = browser;
    }

    @Override
    /**
     * 获取
    */
    public SpiderResponse fetch(SpiderRequest request) {
        long startTime = System.currentTimeMillis();
        SpiderResponse.SpiderResponseBuilder builder = SpiderResponse.builder()
                .request(request);

        try (BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(1280, 720))) {

            context.setDefaultNavigationTimeout(NAV_TIMEOUT.toMillis());
            context.setDefaultTimeout(LOAD_TIMEOUT.toMillis());

            Page page = context.newPage();

            if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
                context.setExtraHTTPHeaders(request.getHeaders());
            }

            page.navigate(request.getUrl());
            page.waitForLoadState(LoadState.NETWORKIDLE);

            // 额外等待动态渲染
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            String html = page.content();
            long elapsed = System.currentTimeMillis() - startTime;

            builder.statusCode(200)
                    .content(html)
                    .rawContent(html.getBytes())
                    .contentType("text/html; charset=UTF-8")
                    .fetchTimeMs(elapsed);

            log.info("Playwright 抓取完成: {} ({}ms)", request.getUrl(), elapsed);

        } catch (Exception e) {
            builder.statusCode(0)
                    .error("Playwright 渲染异常: " + e.getMessage())
                    .fetchTimeMs(System.currentTimeMillis() - startTime);
            log.warn("Playwright 渲染失败: {} - {}", request.getUrl(), e.getMessage());
        }

        return builder.build();
    }

    /**
     * 释放浏览器资源。
     *
     * <p>爬虫结束后调用，释放 Playwright 和浏览器实例。
     */
    public void close() {
        Exception ex = null;
        try {
            browser.close();
        } catch (Exception e) {
            ex = e;
        }
        try {
            playwright.close();
        } catch (Exception e) {
            if (ex == null) {
                ex = e;
            }
        }
        if (ex != null) {
            log.warn("释放 Playwright 资源失败", ex);
        }
    }
}
