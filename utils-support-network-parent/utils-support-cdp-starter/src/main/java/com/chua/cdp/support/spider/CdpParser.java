package com.chua.cdp.support.spider;

import com.chua.common.support.browser.BrowserEngine;
import com.chua.common.support.browser.BrowserOptions;
import com.chua.common.support.browser.BrowserPage;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.crawler.support.SpiderParser;
import com.chua.crawler.support.model.SpiderResponse;
import com.chua.crawler.support.model.SpiderResult;
import lombok.extern.slf4j.Slf4j;

/**
 * CDP 浏览器渲染解析器
 *
 * <p>与 {@code PlaywrightParser} / {@code JvppeteerParser} 对等：把已抓到的 HTML
 * 交给真实浏览器重新渲染，提取执行 JavaScript 之后的标题与纯文本。
 * 底层使用 common-starter 自带的轻量 CDP 引擎，不引入第三方浏览器依赖。</p>
 *
 * <p>SPI 名称：{@code cdp}。用完调用 {@link #close()} 释放浏览器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("cdp")
@ConditionalOnClass("com.chua.common.support.browser.BrowserEngine")
public class CdpParser implements SpiderParser, AutoCloseable {

    /**
     * 支持的内容类型
     */
    private static final String[] SUPPORTED_TYPES = {"text/html", "application/xhtml+xml"};

    /**
     * 渲染后等待时间（毫秒）
     */
    private static final long RENDER_WAIT_MILLIS = 1000L;

    /**
     * 浏览器引擎
     */
    private final BrowserEngine engine;

    /**
     * 默认构造器：使用内置轻量 CDP 引擎
     */
    public CdpParser() {
        this(BrowserEngine.cdp());
    }

    /**
     * 复用已有浏览器引擎
     *
     * @param engine 浏览器引擎
     */
    public CdpParser(BrowserEngine engine) {
        this.engine = engine;
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
        BrowserPage page = null;
        try {
            page = engine.openPage(new BrowserOptions());
            page.evaluate("document.open();document.write(" + Json.toJson(content)
                    + ");document.close();", false);
            sleep(RENDER_WAIT_MILLIS);
            String title = page.title();
            String text = page.evaluate("document.body ? document.body.innerText : ''", false);
            return SpiderResult.builder()
                    .url(response.getRequest() != null ? response.getRequest().getUrl() : "")
                    .title(title)
                    .text(text == null ? "" : text)
                    .html(content)
                    .contentType(response.getContentType())
                    .extractedAt(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.warn("[cdp] 解析失败: {}",
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
