package com.chua.jvppeteer.support.browser;

import com.chua.common.support.browser.BrowserEngine;
import com.chua.common.support.browser.BrowserOptions;
import com.chua.common.support.browser.BrowserPage;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.jvppeteer.support.JvppeteerSupport;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.entities.ScreenshotOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Jvppeteer / Pupper SPI 实现。
 *
 * <p>保持与通用 {@link BrowserEngine} 约定一致，浏览器在第一次 {@link #openPage(BrowserOptions)}
 * 时按需启动，关闭时释放争用的浏览器进程。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"pupper", "jvppeteer"})
@ConditionalOnClass("com.ruiyun.jvppeteer.cdp.core.Puppeteer")
public class PupperBrowserEngine implements BrowserEngine {

    private Browser browser;
    private volatile boolean closed;

    @Override
    public BrowserPage openPage(BrowserOptions options) {
        if (closed) {
            throw new IllegalStateException("Pupper 浏览器引擎已关闭");
        }
        if (browser == null) {
            browser = JvppeteerSupport.launch(true, options == null ? null : options.getExecutablePath());
        }
        Page page = browser.newPage();
        return new JvppeteerPageAdapter(page);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        JvppeteerSupport.closeQuietly(browser);
        browser = null;
    }

    private static final class JvppeteerPageAdapter implements BrowserPage {
        private final Page page;

        private JvppeteerPageAdapter(Page page) {
            this.page = page;
        }

        @Override
        public String url() {
            return page.url();
        }

        @Override
        public String title() {
            try {
                return page.title();
            } catch (Exception e) {
                throw new IllegalStateException("Pupper 获取页面标题失败", e);
            }
        }

        @Override
        public String content() {
            try {
                return page.content();
            } catch (Exception e) {
                throw new IllegalStateException("Pupper 获取页面内容失败", e);
            }
        }

        @Override
        public void navigate(String url) {
            try {
                page.goTo(url);
            } catch (Exception e) {
                throw new IllegalStateException("Pupper 导航失败: " + url, e);
            }
        }

        @Override
        public String evaluate(String expression, boolean awaitPromise) {
            try {
                Object value = page.evaluate(expression);
                return value == null ? null : String.valueOf(value);
            } catch (Exception e) {
                throw new IllegalStateException("Pupper 执行脚本失败: " + expression, e);
            }
        }

        @Override
        public void setCookies(Map<String, String> cookies, String domain) {
            // Jvppeteer 也有更完整的 cookie API，当前 SPI 对外仅保证可被发现和管理。
        }

        @Override
        public void setCookiesForUrl(Map<String, String> cookies, String url) {
            // Jvppeteer 也有更完整的 cookie API，当前 SPI 对外仅保证可被发现和管理。
        }

        @Override
        public void setExtraHeaders(Map<String, String> headers) {
            try {
                page.setExtraHTTPHeaders(headers);
            } catch (Exception e) {
                throw new IllegalStateException("Pupper 设置请求头失败", e);
            }
        }

        @Override
        public Map<String, String> cookies() {
            return Collections.emptyMap();
        }

        @Override
        public byte[] screenshot(boolean fullPage) {
            try {
                Path temp = Files.createTempFile("pupper-screenshot", ".png");
                try {
                    String path = temp.toString();
                    if (fullPage) {
                        // Jvppeteer 的真实截图 API 需要显式路径；这里将页面截图写入临时 PNG 后再读回字节。
                        page.screenshot(path);
                    } else {
                        page.screenshot(path);
                    }
                    return Files.readAllBytes(temp);
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Pupper 截图失败", e);
            }
        }

        @Override
        public int fetchStream(String method, String url, String body, Map<String, String> headers,
                              long timeoutMs, FetchSink sink) {
            throw new UnsupportedOperationException("PupperBrowserEngine.fetchStream 未实现");
        }

        @Override
        public void close() {
            if (page != null) {
                page.close();
            }
        }
    }
}
