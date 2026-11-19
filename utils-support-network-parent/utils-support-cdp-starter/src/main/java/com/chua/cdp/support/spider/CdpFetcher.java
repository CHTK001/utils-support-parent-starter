// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.cdp.support.spider;

import com.chua.common.support.browser.BrowserEngine;
import com.chua.common.support.browser.BrowserOptions;
import com.chua.common.support.browser.BrowserPage;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.crawler.support.SpiderFetcher;
import com.chua.crawler.support.model.SpiderCookie;
import com.chua.crawler.support.model.SpiderProxyConfig;
import com.chua.crawler.support.model.SpiderRequest;
import com.chua.crawler.support.model.SpiderResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

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
            BrowserOptions options = new BrowserOptions();
            applyProxy(options, request.getProxy());
            page = engine.openPage(options);
            Map<String, String> headers = request.getHeaders() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getHeaders());
            if (StringUtils.isNotBlank(request.getReferUrl())
                    && headers.keySet().stream().noneMatch(key -> "referer".equalsIgnoreCase(key))) {
                headers.put("Referer", request.getReferUrl());
            }
            if (!headers.isEmpty()) {
                page.setExtraHeaders(headers);
            }
            applyCookies(page, request);
            page.navigate(request.getUrl());
            String html = page.content();
            long elapsed = System.currentTimeMillis() - startTime;
            builder.statusCode(200)
                    .content(html)
                    .rawContent(html == null ? new byte[0] : html.getBytes(StandardCharsets.UTF_8))
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
     * 应用请求里的代理配置
     *
     * @param options 启动参数
     * @param proxy   代理配置，可为空
     */
    private static void applyProxy(BrowserOptions options, SpiderProxyConfig proxy) {
        if (proxy == null || StringUtils.isBlank(proxy.getProxyHost()) || proxy.getProxyPort() == null) {
            return;
        }
        String protocol = "socks5".equalsIgnoreCase(proxy.getProxyProtocol()) ? "socks5" : "http";
        options.setProxy(protocol + "://" + proxy.getProxyHost() + ":" + proxy.getProxyPort());
        if (StringUtils.isNotBlank(proxy.getProxyUsername())) {
            log.debug("[cdp] 代理需要认证，Chromium 启动参数不携带凭据，请通过系统代理或环境处理");
        }
    }

    /**
     * 把请求里的 Cookie 写入页面
     *
     * @param page    页面
     * @param request 抓取请求
     */
    private static void applyCookies(BrowserPage page, SpiderRequest request) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(request.getCookies())) {
            for (String pair : request.getCookies().split(";")) {
                int index = pair.indexOf('=');
                if (index > 0) {
                    cookies.put(pair.substring(0, index).trim(), pair.substring(index + 1).trim());
                }
            }
        }
        if (request.getCookieList() != null) {
            for (SpiderCookie cookie : request.getCookieList()) {
                if (cookie != null && StringUtils.isNotBlank(cookie.getName())) {
                    cookies.put(cookie.getName(), cookie.getValue() == null ? "" : cookie.getValue());
                }
            }
        }
        if (!cookies.isEmpty()) {
            page.setCookiesForUrl(cookies, request.getUrl());
        }
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