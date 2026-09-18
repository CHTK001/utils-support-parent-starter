package com.chua.playwright.support.screenshot;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.function.Consumer;

/**
* Playwright 截图工具
*
* <p>基于 Playwright 实现 URL 转图片、长截图、页面加载等待等功能。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class PlaywrightScreenshot {

    /**
    * 默认 超时时间
    */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
    * 默认 navigation 超时时间
    */
    private static final Duration DEFAULT_NAVIGATION_TIMEOUT = Duration.ofSeconds(60);

    /**
    * Playwright 版本，浏览器下载地址使用
    */
    private static final String PLAYWRIGHT_VERSION = "1.49.0";

    /**
    * 截取 URL 页面截图（视口大小）
    *
    * @param url     页面地址
    * @param outFile 输出文件路径
    */
    public static void screenshot(String url, String outFile) {
        screenshot(url, outFile, null);
    }

    /**
    * 截取 URL 页面截图
    *
    * @param url     页面地址
    * @param outFile 输出文件路径
    * @param options 配置回调，可设置视口大小、等待条件等
    */
    public static void screenshot(String url, String outFile, Consumer<ScreenshotConfig> options) {
        ScreenshotConfig config = new ScreenshotConfig();
        config.setBaseUrl(url);
        if (options != null) {
            options.accept(config);
        }

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(true);

            if (config.getExecutablePath() != null && !config.getExecutablePath().isEmpty()) {
                launchOptions.setExecutablePath(Paths.get(config.getExecutablePath()));
                log.debug("使用指定浏览器路径: {}", config.getExecutablePath());
            }

            if (config.getSlowMo() > 0) {
                launchOptions.setSlowMo(config.getSlowMo());
            }

            try (Browser browser = playwright.chromium().launch(launchOptions)) {
                BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                        .setViewportSize(config.getViewportWidth(), config.getViewportHeight())
                        .setLocale(config.getLocale())
                        .setTimezoneId(config.getTimezoneId())
                );

                if (config.getExtraHttpHeaders() != null) {
                    context.setExtraHTTPHeaders(config.getExtraHttpHeaders());
                }

                context.setDefaultNavigationTimeout(DEFAULT_NAVIGATION_TIMEOUT.toMillis());
                context.setDefaultTimeout(DEFAULT_TIMEOUT.toMillis());

                Page page = context.newPage();

                // 等待页面加载
                waitForPageLoad(page, config);

                // 截图
                Path outputPath = Paths.get(outFile);
                if (config.getFullPage()) {
                    page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(true)
                        .setPath(outputPath));
                } else {
                    page.screenshot(new Page.ScreenshotOptions()
                        .setPath(outputPath));
                }

                log.info("截图已保存: {}", outFile);
            }
        } catch (Exception e) {
            log.error("截图失败: {}", url, e);
            printManualInstallGuide();
            throw new RuntimeException("截图失败: " + url, e);
        }
    }

    /**
    * 长截图（全页面）
    *
    * @param url     页面地址
    * @param outFile 输出文件路径
    */
    public static void fullPageScreenshot(String url, String outFile) {
        screenshot(url, outFile, config -> config.setFullPage(true));
    }

    /**
    * 长截图（全页面）带自定义配置
    *
    * @param url     页面地址
    * @param outFile 输出文件路径
    * @param options 配置回调
    */
    public static void fullPageScreenshot(String url, String outFile, Consumer<ScreenshotConfig> options) {
        screenshot(url, outFile, config -> {
            config.setFullPage(true);
            if (options != null) {
                options.accept(config);
            }
        });
    }

    /**
    * 打印 Playwright 浏览器手动安装指引
    *
    * <p>当自动安装失败时，打印下载地址和放置路径，方便离线环境手动安装。</p>
    */
    public static void printManualInstallGuide() {
        String osName = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");

        log.warn("========== Playwright 浏览器手动安装指引 ==========");
        log.warn("Playwright 版本: {}", PLAYWRIGHT_VERSION);

        // 确定浏览器存放目录
        String browserDir;
        if (osName.contains("win")) {
            browserDir = userHome + "\\AppData\\Local\\ms-playwright";
        } else if (osName.contains("mac")) {
            browserDir = userHome + "/Library/Caches/ms-playwright";
        } else {
            browserDir = userHome + "/.cache/ms-playwright";
        }

        log.warn("浏览器存放目录: {}", browserDir);
        log.warn("");
        log.warn("方式1 (推荐): 命令行安装（需要网络）");
        log.warn("  npm init -y && npm install playwright@{}", PLAYWRIGHT_VERSION);
        log.warn("  npx playwright install chromium");
        log.warn("");
        log.warn("方式2: 手动下载安装（离线环境）");
        log.warn("  1. 在有网络的机器上执行: npx playwright install chromium");
        log.warn("  2. 将浏览器目录复制到离线机器的相同路径:");
        log.warn("     {}", browserDir);
        log.warn("  3. 目录结构示例:");
        log.warn("     {}/", browserDir);
        log.warn("       chromium-xxxx/");
        log.warn("         chrome-linux/");
        log.warn("           chrome  (Linux)");
        log.warn("         chrome-win/");
        log.warn("           chrome.exe  (Windows)");
        log.warn("         chrome-mac/");
        log.warn("           Chromium.app/  (macOS)");
        log.warn("");
        log.warn("方式3: 指定已有的 Chrome/Chromium 路径");
        log.warn("  使用 PlaywrightScreenshot 时通过 config 设置:");
        log.warn("  config.setExecutablePath(\"/path/to/chrome\")");
        log.warn("====================================================");
    }

    /**
    * 等待页面加载完成
    *
    * @param page   Playwright Page 对象
    * @param config 截图配置
    */
    private static void waitForPageLoad(Page page, ScreenshotConfig config) {
        String waitSelector = config.getWaitSelector();
        long waitTimeout = config.getWaitTimeout();

        // 先导航到页面
        page.navigate(config.getBaseUrl() != null ? config.getBaseUrl() : "");

        // 根据配置等待页面加载
        if (waitSelector != null && !waitSelector.isEmpty()) {
            // 等待特定元素出现
            log.debug("等待元素出现: {}", waitSelector);
            page.waitForSelector(waitSelector, new Page.WaitForSelectorOptions()
                .setTimeout(waitTimeout));
        } else {
            // 等待网络空闲
            log.debug("等待网络空闲");
            page.waitForLoadState(LoadState.NETWORKIDLE);
        }

        // 额外等待时间（用于动态内容渲染）
        if (config.getExtraWaitMillis() > 0) {
            try {
                log.debug("额外等待 {} ms", config.getExtraWaitMillis());
                Thread.sleep(config.getExtraWaitMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
    * 截图配置
    * @author CH
    * @since 4.0.0
    */
    public static class ScreenshotConfig {
        /**
        * viewport Width
        */
        private int viewportWidth = 1280;
        /**
        * viewport Height
        */
        private int viewportHeight = 720;
        /**
        * 完整 Page
        */
        private boolean fullPage = false;
        /**
        * wait Selector
        */
        private String waitSelector;
        /**
        * wait超时时间
        */
        private long waitTimeout = 30000;
        /**
        * extrawait（毫秒）
        */
        private long extraWaitMillis = 0;
        /**
        * 区域
        */
        private String locale = "zh-CN";
        /**
        * timezone 标识
        */
        private String timezoneId = "Asia/Shanghai";
        /**
        * 基础地址
        */
        private String baseUrl;
        /**
        * extra Http 头部
        */
        private java.util.Map<String, String> extraHttpHeaders;
        /**
        * slow Mo
        */
        private long slowMo = 0;
        /**
        * 浏览器可执行文件路径
        */
        private String executablePath;

        /**
        * 获取viewportwidth
        *
        * @return 获取viewportwidth的结果
        */
        public int getViewportWidth() {
            return viewportWidth;
        }

        /**
        * 设置viewportwidth
        *
        * @param viewportWidth viewportwidth
        * @return 设置viewportwidth的结果
        */
        public ScreenshotConfig setViewportWidth(int viewportWidth) {
            this.viewportWidth = viewportWidth;
            return this;
        }

        /**
        * 获取viewportheight
        *
        * @return 获取viewportheight的结果
        */
        public int getViewportHeight() {
            return viewportHeight;
        }

        /**
        * 设置viewportheight
        *
        * @param viewportHeight viewportheight
        * @return 设置viewportheight的结果
        */
        public ScreenshotConfig setViewportHeight(int viewportHeight) {
            this.viewportHeight = viewportHeight;
            return this;
        }

        /**
        * 获取完整page
        *
        * @return 获取完整page的结果
        */
        public boolean getFullPage() {
            return fullPage;
        }

        /**
        * 设置完整page
        *
        * @param fullPage 完整page
        * @return 设置完整page的结果
        */
        public ScreenshotConfig setFullPage(boolean fullPage) {
            this.fullPage = fullPage;
            return this;
        }

        /**
        * 获取waitselector
        *
        * @return 获取waitselector的结果
        */
        public String getWaitSelector() {
            return waitSelector;
        }

        /**
        * 设置waitselector
        *
        * @param waitSelector waitselector
        * @return 设置waitselector的结果
        */
        public ScreenshotConfig setWaitSelector(String waitSelector) {
            this.waitSelector = waitSelector;
            return this;
        }

        /**
        * 获取wait超时
        *
        * @return 获取wait超时的结果
        */
        public long getWaitTimeout() {
            return waitTimeout;
        }

        /**
        * 设置wait超时
        *
        * @param waitTimeout wait超时
        * @return 设置wait超时的结果
        */
        public ScreenshotConfig setWaitTimeout(long waitTimeout) {
            this.waitTimeout = waitTimeout;
            return this;
        }

        /**
        * 获取extrawaitmillis
        *
        * @return 获取extrawaitmillis的结果
        */
        public long getExtraWaitMillis() {
            return extraWaitMillis;
        }

        /**
        * 设置extrawaitmillis
        *
        * @param extraWaitMillis extrawaitmillis
        * @return 设置extrawaitmillis的结果
        */
        public ScreenshotConfig setExtraWaitMillis(long extraWaitMillis) {
            this.extraWaitMillis = extraWaitMillis;
            return this;
        }

        /**
        * 获取区域
        *
        * @return 获取区域的结果
        */
        public String getLocale() {
            return locale;
        }

        /**
        * 设置区域
        *
        * @param locale 区域
        * @return 设置区域的结果
        */
        public ScreenshotConfig setLocale(String locale) {
            this.locale = locale;
            return this;
        }

        /**
        * 获取timezoneid
        *
        * @return 获取timezoneid的结果
        */
        public String getTimezoneId() {
            return timezoneId;
        }

        /**
        * 设置timezoneid
        *
        * @param timezoneId timezoneid
        * @return 设置timezoneid的结果
        */
        public ScreenshotConfig setTimezoneId(String timezoneId) {
            this.timezoneId = timezoneId;
            return this;
        }

        /**
        * 获取baseurl
        *
        * @return 获取baseurl的结果
        */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
        * 设置baseurl
        *
        * @param baseUrl baseurl
        * @return 设置baseurl的结果
        */
        public ScreenshotConfig setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
        * 获取extrahttp头部
        *
        * @return 获取extrahttp头部的结果
        */
        public java.util.Map<String, String> getExtraHttpHeaders() {
            return extraHttpHeaders;
        }

        /**
        * 设置extrahttp头部
        *
        * @param extraHttpHeaders extrahttp头部
        * @return 设置extrahttp头部的结果
        */
        public ScreenshotConfig setExtraHttpHeaders(java.util.Map<String, String> extraHttpHeaders) {
            this.extraHttpHeaders = extraHttpHeaders;
            return this;
        }

        /**
        * 获取slowmo
        *
        * @return 获取slowmo的结果
        */
        public long getSlowMo() {
            return slowMo;
        }

        /**
        * 设置slowmo
        *
        * @param slowMo slowmo
        * @return 设置slowmo的结果
        */
        public ScreenshotConfig setSlowMo(long slowMo) {
            this.slowMo = slowMo;
            return this;
        }

        /**
        * 获取executable路径
        *
        * @return 获取executable路径的结果
        */
        public String getExecutablePath() {
            return executablePath;
        }

        /**
        * 设置executable路径
        *
        * @param executablePath executable路径
        * @return 设置executable路径的结果
        */
        public ScreenshotConfig setExecutablePath(String executablePath) {
            this.executablePath = executablePath;
            return this;
        }
    }
}
