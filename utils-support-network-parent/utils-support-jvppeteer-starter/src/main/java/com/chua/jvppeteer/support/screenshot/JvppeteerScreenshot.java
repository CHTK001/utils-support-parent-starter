package com.chua.jvppeteer.support.screenshot;

import com.chua.common.support.utils.StringUtils;
import com.chua.jvppeteer.support.JvppeteerSupport;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.entities.GoToOptions;
import com.ruiyun.jvppeteer.cdp.entities.ScreenshotOptions;
import com.ruiyun.jvppeteer.cdp.entities.Viewport;
import com.ruiyun.jvppeteer.cdp.entities.WaitForNetworkIdleOptions;
import com.ruiyun.jvppeteer.cdp.entities.WaitForSelectorOptions;
import com.ruiyun.jvppeteer.common.PuppeteerLifeCycle;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Jvppeteer 截图工具
 *
 * <p>与 {@code PlaywrightScreenshot} 对等：URL 转图片、整页长截图、
 * 等待指定元素出现后截图。适用于报表导出、页面归档、视觉回归等场景。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 *   JvppeteerScreenshot.screenshot("https://example.com", "D:/tmp/a.png");
 *   JvppeteerScreenshot.fullPageScreenshot("https://example.com", "D:/tmp/full.png",
 *       config -> config.setWaitSelector("#main").setExtraWaitMillis(1000));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class JvppeteerScreenshot {

    /**
     * 导航超时（毫秒）
     */
    private static final int NAVIGATION_TIMEOUT_MILLIS = 60_000;

    /**
     * 加载超时（毫秒）
     */
    private static final int DEFAULT_TIMEOUT_MILLIS = 30_000;

    /**
     * 工具类，禁止实例化
     */
    private JvppeteerScreenshot() {
    }

    /**
     * 截取 URL 页面（视口大小）
     *
     * @param url     页面地址
     * @param outFile 输出文件路径
     */
    public static void screenshot(String url, String outFile) {
        screenshot(url, outFile, null);
    }

    /**
     * 截取 URL 页面
     *
     * @param url     页面地址
     * @param outFile 输出文件路径
     * @param options 配置回调，可设置视口、等待条件等
     */
    public static void screenshot(String url, String outFile, Consumer<ScreenshotConfig> options) {
        ScreenshotConfig config = new ScreenshotConfig();
        config.setBaseUrl(url);
        if (options != null) {
            options.accept(config);
        }

        List<String> args = new ArrayList<>();
        if (StringUtils.isNotBlank(config.getLocale())) {
            args.add("--lang=" + config.getLocale());
        }
        Browser browser = JvppeteerSupport.launch(true, config.getExecutablePath(),
                args.isEmpty() ? null : args, (int) config.getSlowMo());
        Page page = null;
        try {
            page = browser.newPage();
            Viewport viewport = new Viewport();
            viewport.setWidth(config.getViewportWidth());
            viewport.setHeight(config.getViewportHeight());
            page.setViewport(viewport);
            if (StringUtils.isNotBlank(config.getTimezoneId())) {
                page.emulateTimezone(config.getTimezoneId());
            }
            if (config.getExtraHttpHeaders() != null && !config.getExtraHttpHeaders().isEmpty()) {
                page.setExtraHTTPHeaders(config.getExtraHttpHeaders());
            }
            page.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MILLIS);
            page.setDefaultTimeout(DEFAULT_TIMEOUT_MILLIS);

            GoToOptions goToOptions = new GoToOptions();
            goToOptions.setTimeout(NAVIGATION_TIMEOUT_MILLIS);
            page.goTo(config.getBaseUrl() == null ? "" : config.getBaseUrl(), goToOptions);

            if (StringUtils.isNotBlank(config.getWaitSelector())) {
                WaitForSelectorOptions selectorOptions = new WaitForSelectorOptions();
                selectorOptions.setTimeout((int) config.getWaitTimeout());
                page.waitForSelector(config.getWaitSelector(), selectorOptions);
            } else {
                WaitForNetworkIdleOptions idleOptions = new WaitForNetworkIdleOptions();
                idleOptions.setIdleTime(500);
                idleOptions.setTimeout(DEFAULT_TIMEOUT_MILLIS);
                page.waitForNetworkIdle(idleOptions);
            }

            if (config.getExtraWaitMillis() > 0) {
                sleep(config.getExtraWaitMillis());
            }

            ScreenshotOptions screenshotOptions = new ScreenshotOptions();
            screenshotOptions.setPath(outFile);
            screenshotOptions.setFullPage(config.getFullPage());
            page.screenshot(screenshotOptions);
            log.info("[jvppeteer] 截图已保存: {}", outFile);
        } catch (Exception e) {
            log.error("[jvppeteer] 截图失败: {}", url, e);
            printManualInstallGuide();
            throw new RuntimeException("截图失败: " + url, e);
        } finally {
            closePage(page);
            JvppeteerSupport.closeQuietly(browser);
        }
    }

    /**
     * 整页长截图
     *
     * @param url     页面地址
     * @param outFile 输出文件路径
     */
    public static void fullPageScreenshot(String url, String outFile) {
        screenshot(url, outFile, config -> config.setFullPage(true));
    }

    /**
     * 整页长截图（可配置）
     *
     * @param url     页面地址
     * @param outFile 输出文件路径
     * @param options 配置回调
     */
    public static void fullPageScreenshot(String url, String outFile, Consumer<ScreenshotConfig> options) {
        /**
         * screenshot
         * @param outFile 参数
         * @param url 参数
         * @param { 参数
         * @return 返回值
        */
        screenshot(url, outFile, config -> {
            config.setFullPage(true);
            if (options != null) {
                options.accept(config);
            }
        });
    }

    /**
     * 打印浏览器安装指引
     *
     * <p>自动下载失败时提示手动准备 Chromium 的三种方式。</p>
     */
    public static void printManualInstallGuide() {
        log.warn("========== Jvppeteer 浏览器安装指引 ==========");
        log.warn("方式1（推荐）：安装 Chrome / Edge，程序会自动探测");
        log.warn("方式2：指定已有浏览器路径");
        log.warn("  -Djvppeteer.executablePath=/path/to/chrome");
        log.warn("  或环境变量 CHROME_PATH / CHROME_BIN");
        log.warn("方式3：允许 jvppeteer 自动下载（需要网络，首次约 150MB）");
        log.warn("  JvppeteerSupport.launch(true) 会回退到下载");
        log.warn("==============================================");
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

    /**
     * 截图配置
     *
     * <p>字段与 {@code PlaywrightScreenshot.ScreenshotConfig} 保持一致，
     * 便于两套实现互换。</p>
     */
    public static class ScreenshotConfig {

        /**
         * 视口宽度
         */
        private int viewportWidth = 1280;

        /**
         * 视口高度
         */
        private int viewportHeight = 720;

        /**
         * 是否整页截图
         */
        private boolean fullPage;

        /**
         * 等待的选择器
         */
        private String waitSelector;

        /**
         * 等待选择器超时（毫秒）
         */
        private long waitTimeout = DEFAULT_TIMEOUT_MILLIS;

        /**
         * 额外等待（毫秒）
         */
        private long extraWaitMillis;

        /**
         * 语言
         */
        private String locale = "zh-CN";

        /**
         * 时区
         */
        private String timezoneId;

        /**
         * 页面地址
         */
        private String baseUrl;

        /**
         * 额外请求头
         */
        private Map<String, String> extraHttpHeaders;

        /**
         * 每步减速（毫秒）
         */
        private long slowMo;

        /**
         * 浏览器路径
         */
        private String executablePath;

        /**
         * get Viewport Width
         * @return 返回值
        */
        public int getViewportWidth() {
            return viewportWidth;
        }

        /**
         * set Viewport Width
         * @param viewportWidth 整数
         * @return 返回值
        */
        public ScreenshotConfig setViewportWidth(int viewportWidth) {
            this.viewportWidth = viewportWidth;
            return this;
        }

        /**
         * get Viewport Height
         * @return 返回值
        */
        public int getViewportHeight() {
            return viewportHeight;
        }

        /**
         * set Viewport Height
         * @param viewportHeight 整数
         * @return 返回值
        */
        public ScreenshotConfig setViewportHeight(int viewportHeight) {
            this.viewportHeight = viewportHeight;
            return this;
        }

        /**
         * get Full Page
         * @return 返回值
        */
        public boolean getFullPage() {
            return fullPage;
        }

        /**
         * set Full Page
         * @param fullPage 布尔值
         * @return 返回值
        */
        public ScreenshotConfig setFullPage(boolean fullPage) {
            this.fullPage = fullPage;
            return this;
        }

        /**
         * get Wait Selector
         * @return 返回值
        */
        public String getWaitSelector() {
            return waitSelector;
        }

        /**
         * set Wait Selector
         * @param waitSelector 字符串
         * @return 返回值
        */
        public ScreenshotConfig setWaitSelector(String waitSelector) {
            this.waitSelector = waitSelector;
            return this;
        }

        /**
         * get Wait Timeout
         * @return 返回值
        */
        public long getWaitTimeout() {
            return waitTimeout;
        }

        /**
         * set Wait Timeout
         * @param waitTimeout 长整数
         * @return 返回值
        */
        public ScreenshotConfig setWaitTimeout(long waitTimeout) {
            this.waitTimeout = waitTimeout;
            return this;
        }

        /**
         * get Extra Wait Millis
         * @return 返回值
        */
        public long getExtraWaitMillis() {
            return extraWaitMillis;
        }

        /**
         * set Extra Wait Millis
         * @param extraWaitMillis 长整数
         * @return 返回值
        */
        public ScreenshotConfig setExtraWaitMillis(long extraWaitMillis) {
            this.extraWaitMillis = extraWaitMillis;
            return this;
        }

        /**
         * get Locale
         * @return 返回值
        */
        public String getLocale() {
            return locale;
        }

        /**
         * set Locale
         * @param locale 字符串
         * @return 返回值
        */
        public ScreenshotConfig setLocale(String locale) {
            this.locale = locale;
            return this;
        }

        /**
         * get Timezone Id
         * @return 返回值
        */
        public String getTimezoneId() {
            return timezoneId;
        }

        /**
         * set Timezone Id
         * @param timezoneId 字符串
         * @return 返回值
        */
        public ScreenshotConfig setTimezoneId(String timezoneId) {
            this.timezoneId = timezoneId;
            return this;
        }

        /**
         * get Base Url
         * @return 返回值
        */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * set Base Url
         * @param baseUrl 字符串
         * @return 返回值
        */
        public ScreenshotConfig setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * get Extra Http Headers
         * @return 返回值
        */
        public Map<String, String> getExtraHttpHeaders() {
            return extraHttpHeaders;
        }

        /**
         * set Extra Http Headers
         * @param Map<String 参数
         * @param extraHttpHeaders 参数
         * @return 返回值
        */
        public ScreenshotConfig setExtraHttpHeaders(Map<String, String> extraHttpHeaders) {
            this.extraHttpHeaders = extraHttpHeaders;
            return this;
        }

        /**
         * get Slow Mo
         * @return 返回值
        */
        public long getSlowMo() {
            return slowMo;
        }

        /**
         * set Slow Mo
         * @param slowMo 长整数
         * @return 返回值
        */
        public ScreenshotConfig setSlowMo(long slowMo) {
            this.slowMo = slowMo;
            return this;
        }

        /**
         * get Executable Path
         * @return 返回值
        */
        public String getExecutablePath() {
            return executablePath;
        }

        /**
         * set Executable Path
         * @param executablePath 字符串
         * @return 返回值
        */
        public ScreenshotConfig setExecutablePath(String executablePath) {
            this.executablePath = executablePath;
            return this;
        }
    }
}
