package com.chua.jvppeteer.support.spider;

import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.crawler.support.SpiderParser;
import com.chua.crawler.support.model.SpiderResponse;
import com.chua.crawler.support.model.SpiderResult;
import com.chua.jvppeteer.support.JvppeteerSupport;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.entities.WaitForNetworkIdleOptions;
import lombok.extern.slf4j.Slf4j;

/**
 * Jvppeteer 浏览器渲染解析器
 *
 * <p>与 {@code PlaywrightParser} 对等：把已抓到的 HTML 交给真实浏览器重新渲染，
 * 提取执行 JavaScript 之后的标题与纯文本，适用于 SPA / 动态注入内容。</p>
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
public class JvppeteerParser implements SpiderParser {

    /**
     * 支持的内容类型
     */
    private static final String[] SUPPORTED_TYPES = {"text/html", "application/xhtml+xml"};

    /**
     * 网络空闲等待超时（毫秒）
     */
    private static final int IDLE_TIMEOUT_MILLIS = 30_000;

    /**
     * 浏览器实例
     */
    private final Browser browser;

    /**
     * 默认构造器：无头启动本机 Chromium
     */
    public JvppeteerParser() {
        this.browser = JvppeteerSupport.launch(true);
    }

    /**
     * 复用已有浏览器实例
     *
     * @param browser 浏览器实例
     */
    public JvppeteerParser(Browser browser) {
        this.browser = browser;
    }

    /**
     * 解析响应内容
     *
     * @param response 抓取响应
     * @return 解析结果；内容为空或渲染失败返回 null
     */
    @Override
    public SpiderResult parse(SpiderResponse response) {
        String content = response == null ? null : response.getContent();
        if (content == null || content.isEmpty()) {
            return null;
        }
        Page page = null;
        try {
            page = browser.newPage();
            page.setContent(content);

            WaitForNetworkIdleOptions idleOptions = new WaitForNetworkIdleOptions();
            idleOptions.setIdleTime(500);
            idleOptions.setTimeout(IDLE_TIMEOUT_MILLIS);
            page.waitForNetworkIdle(idleOptions);

            String title = page.title();
            Object text = page.evaluate("document.body ? document.body.innerText : ''");

            return SpiderResult.builder()
                    .url(response.getRequest() != null ? response.getRequest().getUrl() : "")
                    .title(title)
                    .text(text == null ? "" : text.toString())
                    .html(content)
                    .contentType(response.getContentType())
                    .extractedAt(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.warn("[jvppeteer] 解析失败: {}",
                    response.getRequest() != null ? response.getRequest().getUrl() : "unknown", e);
            return null;
        } finally {
            closePage(page);
        }
    }

    /**
     * 支持的内容类型
     *
     * @return 内容类型数组
     */
    @Override
    public String[] supportedContentTypes() {
        return SUPPORTED_TYPES;
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
}
