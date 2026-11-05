// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.jvppeteer.support.spider;

import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.crawler.support.SpiderFetcher;
import com.chua.crawler.support.model.SpiderRequest;
import com.chua.crawler.support.model.SpiderResponse;
import com.chua.jvppeteer.support.JvppeteerSupport;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.entities.GoToOptions;
import com.ruiyun.jvppeteer.common.PuppeteerLifeCycle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Jvppeteer 浏览器渲染抓取器
 *
 * <p>与 {@code PlaywrightFetcher} 对等：用真实 Chromium 打开目标页面，
 * 等网络空闲后再取回渲染后的 HTML，适用于必须执行 JavaScript 才能拿到内容的页面。</p>
 *
 * <p>SPI 名称：{@code jvppeteer}。内部持有浏览器实例，
 * 用完调用 {@link #close()} 释放。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jvppeteer")
@ConditionalOnClass("com.ruiyun.jvppeteer.cdp.core.Puppeteer")
public class JvppeteerFetcher implements SpiderFetcher {

    /**
     * 导航超时（毫秒）
     */
    private static final int NAV_TIMEOUT_MILLIS = 60_000;

    /**
     * 浏览器实例
     */
    private final Browser browser;

    /**
     * 默认构造器：无头启动本机 Chromium
     */
    public JvppeteerFetcher() {
        this.browser = JvppeteerSupport.launch(true);
    }

    /**
     * 复用已有浏览器实例
     *
     * @param browser 浏览器实例
     */
    public JvppeteerFetcher(Browser browser) {
        this.browser = browser;
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
        Page page = null;
        try {
            page = browser.newPage();
            page.setDefaultNavigationTimeout(NAV_TIMEOUT_MILLIS);
            if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
                page.setExtraHTTPHeaders(request.getHeaders());
            }

            GoToOptions options = new GoToOptions();
            options.setTimeout(NAV_TIMEOUT_MILLIS);
            options.setWaitUntil(List.of(PuppeteerLifeCycle.networkIdle));
            page.goTo(request.getUrl(), options);

            // 给动态渲染留一点时间，与 Playwright 版本保持一致
            sleep(1000L);

            String html = page.content();
            long elapsed = System.currentTimeMillis() - startTime;
            builder.statusCode(200)
                    .content(html)
                    .rawContent(html.getBytes())
                    .contentType("text/html; charset=UTF-8")
                    .fetchTimeMs(elapsed);
            log.info("[jvppeteer] 抓取完成: {} ({}ms)", request.getUrl(), elapsed);
        } catch (Exception e) {
            builder.statusCode(0)
                    .error("Jvppeteer 渲染异常: " + e.getMessage())
                    .fetchTimeMs(System.currentTimeMillis() - startTime);
            log.warn("[jvppeteer] 渲染失败: {} - {}", request.getUrl(), e.getMessage(), e);
        } finally {
            closePage(page);
        }
        return builder.build();
    }

    /**
     * 释放浏览器资源
     */
    public void close() {
        JvppeteerSupport.closeQuietly(browser);
    }

    /**
     * 关闭页面
     *
     * @param page 页面
     */
    private static void closePage(Page page) {
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (Exception e) {
            log.debug("[jvppeteer] 关闭页面失败: {}", e.getMessage());
        }
    }

    /**
     * 休眠
     *
     * @param millis 毫秒
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}