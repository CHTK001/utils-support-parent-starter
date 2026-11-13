package com.chua.common.support.browser;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CDP 截图工具
 *
 * <p>与 {@code PlaywrightScreenshot} / {@code JvppeteerScreenshot} 对等：
 * URL 转图片、整页长截图、等待指定元素出现后截图。区别是底层用本模块自带的
 * 轻量 CDP 引擎，不依赖 Playwright / Jvppeteer。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * CdpScreenshot.screenshot("https://example.com", "D:/tmp/a.png");
 * CdpScreenshot.fullPageScreenshot("https://example.com", "D:/tmp/full.png",
 *         config -> config.setWaitSelector("#main").setExtraWaitMillis(1000));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class CdpScreenshot {

    /**
     * 默认超时（毫秒）
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    /**
     * 工具类，禁止实例化
     */
    private CdpScreenshot() {
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
     * @param options 配置回调，可为空
     */
    public static void screenshot(String url, String outFile, Consumer<ScreenshotConfig> options) {
        ScreenshotConfig config = new ScreenshotConfig();
        config.setBaseUrl(url);
        if (options != null) {
            options.accept(config);
        }

        BrowserOptions browserOptions = new BrowserOptions()
                .setHeadless(true)
                .setExecutablePath(config.getExecutablePath())
                .setWidth(config.getViewportWidth())
                .setHeight(config.getViewportHeight())
                .setTimezoneId(config.getTimezoneId())
                .setLocale(config.getLocale())
                .setCommandTimeout(java.time.Duration.ofMillis(Math.max(config.getWaitTimeout(),
                        DEFAULT_TIMEOUT_MILLIS)));

        try (BrowserEngine engine = BrowserEngine.cdp();
             BrowserPage page = engine.openPage(browserOptions)) {
            if (config.getExtraHttpHeaders() != null && !config.getExtraHttpHeaders().isEmpty()) {
                page.setExtraHeaders(config.getExtraHttpHeaders());
            }
            page.navigate(config.getBaseUrl() == null ? "about:blank" : config.getBaseUrl());
            waitForSelector(page, config);
            if (config.getExtraWaitMillis() > 0) {
                sleep(config.getExtraWaitMillis());
            }
            byte[] png = page.screenshot(config.getFullPage());
            Path target = Path.of(outFile);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, png);
            log.info("[cdp] 截图已保存: {}", outFile);
        } catch (IOException e) {
            throw new IllegalStateException("写入截图失败: " + outFile, e);
        } catch (Exception e) {
            log.error("[cdp] 截图失败: {}", url, e);
            printManualInstallGuide();
            throw new IllegalStateException("截图失败: " + url, e);
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
     * 等待选择器出现
     *
     * @param page   页面
     * @param config 配置
     */
    private static void waitForSelector(BrowserPage page, ScreenshotConfig config) {
        if (StringUtils.isBlank(config.getWaitSelector())) {
            return;
        }
        String expression = "document.querySelector(" + Json.toJson(config.getWaitSelector()) + ") != null";
        long deadline = System.currentTimeMillis() + config.getWaitTimeout();
        while (System.currentTimeMillis() < deadline) {
            try {
                if ("true".equals(page.evaluate(expression, false))) {
                    return;
                }
            } catch (Exception e) {
                log.debug("[cdp] 查询等待选择器失败: {}", e.getMessage());
            }
            // [P3C A20 豁免]
            sleep(200L);
        }
        log.warn("[cdp] 等待选择器超时: {}", config.getWaitSelector());
    }

    /**
     * 打印浏览器安装指引
     */
    public static void printManualInstallGuide() {
        log.warn("========== CDP 浏览器安装指引 ==========");
        log.warn("方式1（推荐）：安装 Chrome / Edge，程序会自动探测");
        log.warn("方式2：指定已有浏览器路径");
        log.warn("  -D{}=/path/to/chrome", "chua.browser.chrome");
        log.warn("  或环境变量 CHUA_CHROME / CHROME_PATH / CHROME_BIN");
        log.warn("方式3：安装 Playwright 浏览器（本引擎会直接复用其 Chromium）");
        log.warn("========================================");
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
     * 便于三套实现互换。</p>
     */
    public static class ScreenshotConfig {

        /**
         * 视口宽度
         */
        // [P3C A20 豁免]
        private int viewportWidth = 1280;

        /**
         * 视口高度
         */
        // [P3C A20 豁免]
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
         * 每步减速（毫秒），CDP 实现忽略该参数
         */
        private long slowMo;

        /**
         * 浏览器路径
         */
        private String executablePath;

        /**
         * 视口宽度
         *
         * @return 宽度
         */
        public int getViewportWidth() {
            return viewportWidth;
        }

        /**
         * 设置视口宽度
         *
         * @param viewportWidth 宽度
         * @return 当前对象
         */
        public ScreenshotConfig setViewportWidth(int viewportWidth) {
            this.viewportWidth = viewportWidth;
            return this;
        }

        /**
         * 视口高度
         *
         * @return 高度
         */
        public int getViewportHeight() {
            return viewportHeight;
        }

        /**
         * 设置视口高度
         *
         * @param viewportHeight 高度
         * @return 当前对象
         */
        public ScreenshotConfig setViewportHeight(int viewportHeight) {
            this.viewportHeight = viewportHeight;
            return this;
        }

        /**
         * 是否整页截图
         *
         * @return true 表示整页
         */
        public boolean getFullPage() {
            return fullPage;
        }

        /**
         * 设置是否整页截图
         *
         * @param fullPage 是否整页
         * @return 当前对象
         */
        public ScreenshotConfig setFullPage(boolean fullPage) {
            this.fullPage = fullPage;
            return this;
        }

        /**
         * 等待的选择器
         *
         * @return 选择器
         */
        public String getWaitSelector() {
            return waitSelector;
        }

        /**
         * 设置等待的选择器
         *
         * @param waitSelector 选择器
         * @return 当前对象
         */
        public ScreenshotConfig setWaitSelector(String waitSelector) {
            this.waitSelector = waitSelector;
            return this;
        }

        /**
         * 等待超时
         *
         * @return 毫秒
         */
        public long getWaitTimeout() {
            return waitTimeout;
        }

        /**
         * 设置等待超时
         *
         * @param waitTimeout 毫秒
         * @return 当前对象
         */
        public ScreenshotConfig setWaitTimeout(long waitTimeout) {
            this.waitTimeout = waitTimeout;
            return this;
        }

        /**
         * 额外等待
         *
         * @return 毫秒
         */
        public long getExtraWaitMillis() {
            return extraWaitMillis;
        }

        /**
         * 设置额外等待
         *
         * @param extraWaitMillis 毫秒
         * @return 当前对象
         */
        public ScreenshotConfig setExtraWaitMillis(long extraWaitMillis) {
            this.extraWaitMillis = extraWaitMillis;
            return this;
        }

        /**
         * 语言
         *
         * @return 语言
         */
        public String getLocale() {
            return locale;
        }

        /**
         * 设置语言
         *
         * @param locale 语言
         * @return 当前对象
         */
        public ScreenshotConfig setLocale(String locale) {
            this.locale = locale;
            return this;
        }

        /**
         * 时区
         *
         * @return 时区
         */
        public String getTimezoneId() {
            return timezoneId;
        }

        /**
         * 设置时区
         *
         * @param timezoneId 时区
         * @return 当前对象
         */
        public ScreenshotConfig setTimezoneId(String timezoneId) {
            this.timezoneId = timezoneId;
            return this;
        }

        /**
         * 页面地址
         *
         * @return 地址
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 设置页面地址
         *
         * @param baseUrl 地址
         * @return 当前对象
         */
        public ScreenshotConfig setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 额外请求头
         *
         * @return 请求头
         */
        public Map<String, String> getExtraHttpHeaders() {
            return extraHttpHeaders;
        }

        /**
         * 设置额外请求头
         *
         * @param extraHttpHeaders 请求头
         * @return 当前对象
         */
        public ScreenshotConfig setExtraHttpHeaders(Map<String, String> extraHttpHeaders) {
            this.extraHttpHeaders = extraHttpHeaders;
            return this;
        }

        /**
         * 每步减速
         *
         * @return 毫秒
         */
        public long getSlowMo() {
            return slowMo;
        }

        /**
         * 设置每步减速
         *
         * @param slowMo 毫秒
         * @return 当前对象
         */
        public ScreenshotConfig setSlowMo(long slowMo) {
            this.slowMo = slowMo;
            return this;
        }

        /**
         * 浏览器路径
         *
         * @return 路径
         */
        public String getExecutablePath() {
            return executablePath;
        }

        /**
         * 设置浏览器路径
         *
         * @param executablePath 路径
         * @return 当前对象
         */
        public ScreenshotConfig setExecutablePath(String executablePath) {
            this.executablePath = executablePath;
            return this;
        }
    }
}
