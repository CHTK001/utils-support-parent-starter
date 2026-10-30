package com.chua.playwright.support.browser;

import com.chua.common.support.browser.BrowserEngine;
import com.chua.common.support.browser.BrowserOptions;
import com.chua.common.support.browser.BrowserPage;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Playwright SPI 实现。
 *
 * <p>保持与通用 {@link BrowserEngine} 约定一致，浏览器在第一次 {@link #openPage(BrowserOptions)}
 * 时按需启动；调用方负责 {@link #close()} 清理资源。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("playwright")
@ConditionalOnClass("com.microsoft.playwright.Playwright")
public class PlaywrightBrowserEngine implements BrowserEngine {

    private Playwright playwright;
    private Browser browser;
    private volatile boolean closed;

    @Override
    public BrowserPage openPage(BrowserOptions options) {
        if (closed) {
            throw new IllegalStateException("Playwright 浏览器引擎已关闭");
        }
        ensureStarted(options);
        BrowserContext context = browser.newContext();
        Page page = context.newPage();
        return new PlaywrightPageAdapter(context, page);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }

    /**
     * ensure Started
     * @param options 参数
     * @return 返回值
    */
    private void ensureStarted(BrowserOptions options) {
        if (browser != null) {
            return;
        }
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions();
        if (options != null) {
            launchOptions.setHeadless(options.isHeadless());
            if (options.getArgs() != null && !options.getArgs().isEmpty()) {
                launchOptions.setArgs(options.getArgs());
            }
            if (options.getExecutablePath() != null && !options.getExecutablePath().isBlank()) {
                launchOptions.setExecutablePath(Path.of(options.getExecutablePath()));
            }
        } else {
            launchOptions.setHeadless(true);
        }
        playwright = Playwright.create();
        browser = playwright.chromium().launch(launchOptions);
    }

    private static final class PlaywrightPageAdapter implements BrowserPage {
        private final BrowserContext context;
        private final Page page;

        private PlaywrightPageAdapter(BrowserContext context, Page page) {
            this.context = context;
            this.page = page;
        }

        @Override
        public String url() {
            return page.url();
        }

        @Override
        public String title() {
            return page.title();
        }

        @Override
        public String content() {
            return page.content();
        }

        @Override
        public void navigate(String url) {
            page.navigate(url);
        }

        @Override
        public String evaluate(String expression, boolean awaitPromise) {
            Object value = page.evaluate(expression);
            return value == null ? null : String.valueOf(value);
        }

        @Override
        public void setCookies(Map<String, String> cookies, String domain) {
            // Playwright 有更完整的 cookie API，但本次 SPI 只用于解析/注册，不在这里做细粒度实现。
        }

        @Override
        public void setCookiesForUrl(Map<String, String> cookies, String url) {
            // Playwright 有更完整的 cookie API，但本次 SPI 只用于解析/注册，不在这里做细粒度实现。
        }

        @Override
        public void setExtraHeaders(Map<String, String> headers) {
            page.setExtraHTTPHeaders(headers);
        }

        @Override
        public Map<String, String> cookies() {
            return Collections.emptyMap();
        }

        @Override
        public byte[] screenshot(boolean fullPage) {
            return page.screenshot(new Page.ScreenshotOptions().setFullPage(fullPage)
                    .setType(com.microsoft.playwright.options.ScreenshotType.PNG));
        }

        @Override
        public int fetchStream(String method, String url, String body, Map<String, String> headers,
                              long timeoutMs, FetchSink sink) {
            throw new UnsupportedOperationException("PlaywrightBrowserEngine.fetchStream 未实现");
        }

        @Override
        public void close() {
            if (page != null) {
                page.close();
            }
            if (context != null) {
                context.close();
            }
        }
    }
}
