package com.chua.playwright.support.spider;

import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.spider.support.SpiderParser;
import com.chua.spider.support.model.SpiderResponse;
import com.chua.spider.support.model.SpiderResult;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import lombok.extern.slf4j.Slf4j;

/**
 * Playwright 浏览器渲染解析器。
 *
 * <p>使用 Playwright 重新渲染已抓取的 HTML 内容，
 * 提取 JS 执行后的页面标题和纯文本。
 * 适用于 SPA 或动态页面内容的提取。
 *
 * <p>SPI 名称：{@code playwright}，配合 {@link PlaywrightFetcher} 使用。
 * 内部持有 Playwright 浏览器实例，{@link #close()} 可释放资源。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("playwright")
@ConditionalOnClass("com.microsoft.playwright.Playwright")
public class PlaywrightParser implements SpiderParser {

    /**
     * supported types
     */
    private static final String[] SUPPORTED_TYPES = {"text/html", "application/xhtml+xml"};

    /**
     * Playwright 实例
     */
    private final Playwright playwright;
    /**
     * 浏览器实例
     */
    private final Browser browser;

    /**
     * 默认构造器，启动 Chromium 浏览器。
     */
    public PlaywrightParser() {
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
    public PlaywrightParser(Playwright playwright, Browser browser) {
        this.playwright = playwright;
        this.browser = browser;
    }

    @Override
    /** 解析 */
    public SpiderResult parse(SpiderResponse response) {
        String content = response.getContent();
        if (content == null || content.isEmpty()) {
            return null;
        }

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.setContent(content);
            page.waitForLoadState(LoadState.NETWORKIDLE);

            String title = page.title();
            String text = "";
            Object evalResult = page.evaluate(
                    "() => document.body ? document.body.innerText : ''");
            if (evalResult != null) {
                text = evalResult.toString();
            }

            return SpiderResult.builder()
                    .url(response.getRequest() != null ? response.getRequest().getUrl() : "")
                    .title(title)
                    .text(text)
                    .html(content)
                    .contentType(response.getContentType())
                    .extractedAt(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.warn("Playwright 解析失败: {}",
                    response.getRequest() != null ? response.getRequest().getUrl() : "unknown", e);
            return null;
        }
    }

    @Override
    /** SupportedContentTypes */
    public String[] supportedContentTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * 释放浏览器资源。
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
