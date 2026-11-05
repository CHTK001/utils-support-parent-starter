// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.browser.cdp;

import com.chua.common.support.browser.BrowserEngine;
import com.chua.common.support.browser.BrowserOptions;
import com.chua.common.support.browser.BrowserPage;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 基于 JDK 的轻量 CDP 浏览器引擎
 *
 * <p>不依赖 Playwright / Jvppeteer / Selenium，只用 JDK 的
 * {@link ProcessBuilder} 启动本机 Chromium，再用 {@link java.net.http.WebSocket}
 * 走 DevTools Protocol。适合「只要能渲染、能截图、能在页面内发请求」的轻量场景。</p>
 *
 * <p>SPI 名称：{@code cdp}。</p>
 *
 * <p>浏览器查找顺序：显式路径 → 系统属性 {@code chua.browser.chrome}（兼容
 * {@code inception.chrome}）→ 环境变量 {@code CHUA_CHROME} / {@code INCEPTION_CHROME} /
 * {@code CHROME_PATH} / {@code CHROME_BIN} → 常见安装路径 → Playwright 缓存。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(BrowserEngine.CDP)
public class CdpBrowserEngine implements BrowserEngine {

    /**
     * 显式指定浏览器路径的系统属性
     */
    public static final String PROPERTY_CHROME = "chua.browser.chrome";

    /**
     * 全局指定 User-Agent 的系统属性
     */
    public static final String PROPERTY_USER_AGENT = "chua.browser.userAgent";

    /**
     * 全局开启 UA 轮换的系统属性
     */
    public static final String PROPERTY_ROTATE_USER_AGENT = "chua.browser.rotateUserAgent";

    /**
     * 等待 DevTools 端口的时间（毫秒）
     */
    private static final long DEVTOOLS_TIMEOUT_MILLIS = 60_000L;

    /**
     * 浏览器进程
     */
    private Process process;

    /**
     * 临时用户目录
     */
    private Path profileDir;

    /**
     * 用户目录是否为引擎创建的临时目录（只有临时目录才在关闭时删除）
     */
    private boolean temporaryProfile;

    /**
     * 真实 User-Agent（无头模式已把 HeadlessChrome 归一化为 Chrome）
     */
    private String realUserAgent;

    /**
     * 浏览器完整版本号，如 {@code 156.0.3521.66}
     */
    private String fullVersion = "";

    /**
     * 浏览器品牌：{@code chrome} 或 {@code edge}
     */
    private String browserBrand = "chrome";

    /**
     * 是否为 Windows 11（影响 UA-CH 的 platformVersion）
     */
    private boolean windows11;

    /**
     * CDP 连接
     */
    private CdpConnection connection;

    /**
     * 启动参数
     */
    private BrowserOptions options;

    /**
     * 是否已注册关闭钩子
     */
    private boolean shutdownHookRegistered;

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    @Override
    public BrowserPage openPage(BrowserOptions options) {
        if (closed) {
            throw new IllegalStateException("浏览器引擎已关闭");
        }
        BrowserOptions actual = options == null ? new BrowserOptions() : options;
        ensureLaunched(actual);

        Duration commandTimeout = actual.getCommandTimeout();
        Map<String, Object> created = connection.call("Target.createTarget",
                Map.of("url", "about:blank"), null, commandTimeout);
        String targetId = asString(created.get("targetId"));
        if (StringUtils.isBlank(targetId)) {
            throw new IllegalStateException("创建浏览器标签页失败: " + Json.toJson(created));
        }

        Map<String, Object> attached = connection.call("Target.attachToTarget",
                Map.of("targetId", targetId, "flatten", true), null, commandTimeout);
        String sessionId = asString(attached.get("sessionId"));
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalStateException("附加浏览器标签页失败: " + Json.toJson(attached));
        }

        connection.call("Page.enable", Map.of(), sessionId, commandTimeout);
        connection.call("Runtime.enable", Map.of(), sessionId, commandTimeout);
        connection.call("Network.enable", Map.of(), sessionId, commandTimeout);
        applyViewport(sessionId, actual, commandTimeout);
        applyUserAgent(sessionId, actual, commandTimeout);
        applyTimezone(sessionId, actual, commandTimeout);
        applyLocale(sessionId, actual, commandTimeout);
        return new CdpPage(connection, targetId, sessionId, commandTimeout);
    }

    /**
     * 应用时区
     *
     * @param sessionId 会话 ID
     * @param options   启动参数
     * @param timeout   超时
     */
    private void applyTimezone(String sessionId, BrowserOptions options, Duration timeout) {
        if (StringUtils.isBlank(options.getTimezoneId())) {
            return;
        }
        try {
            connection.call("Emulation.setTimezoneOverride",
                    Map.of("timezoneId", options.getTimezoneId()), sessionId, timeout);
        } catch (Exception e) {
            log.debug("[cdp] 设置时区失败: {}", e.getMessage());
        }
    }

    /**
     * 应用语言
     *
     * @param sessionId 会话 ID
     * @param options   启动参数
     * @param timeout   超时
     */
    private void applyLocale(String sessionId, BrowserOptions options, Duration timeout) {
        if (StringUtils.isBlank(options.getLocale())) {
            return;
        }
        try {
            connection.call("Emulation.setLocaleOverride",
                    Map.of("locale", options.getLocale()), sessionId, timeout);
        } catch (Exception e) {
            log.debug("[cdp] 设置语言失败: {}", e.getMessage());
        }
    }

    /**
     * 应用视口
     *
     * @param sessionId 会话 ID
     * @param options   启动参数
     * @param timeout   超时
     */
    private void applyViewport(String sessionId, BrowserOptions options, Duration timeout) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("width", Math.max(options.getWidth(), 1));
        params.put("height", Math.max(options.getHeight(), 1));
        params.put("deviceScaleFactor", 1);
        params.put("mobile", false);
        connection.call("Emulation.setDeviceMetricsOverride", params, sessionId, timeout);
    }

    /**
     * 应用 User-Agent
     *
     * <p>优先级：显式 UA &gt; 随机轮换 &gt; 真实浏览器 UA。设置 UA 的同时会写入
     * 匹配的 {@code userAgentMetadata}（{@code navigator.userAgentData}），
     * 避免出现「UA 是 Chrome、CH 品牌为空」这种一眼假的组合。</p>
     *
     * @param sessionId 会话 ID
     * @param options   启动参数
     * @param timeout   超时
     */
    private void applyUserAgent(String sessionId, BrowserOptions options, Duration timeout) {
        String userAgent = chooseUserAgent(options);
        if (StringUtils.isBlank(userAgent)) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userAgent", userAgent);
        params.put("platform", platformToken(userAgent));
        try {
            params.put("userAgentMetadata", buildMetadata(userAgent));
        } catch (RuntimeException e) {
            log.debug("[cdp] 构建 UA-CH 失败: {}", e.getMessage());
        }
        try {
            connection.call("Emulation.setUserAgentOverride", params, sessionId, timeout);
        } catch (RuntimeException e) {
            log.debug("[cdp] 设置 UA-CH 失败，仅覆盖 UA: {}", e.getMessage());
            connection.call("Emulation.setUserAgentOverride",
                    Map.of("userAgent", userAgent), sessionId, timeout);
        }
    }

    /**
     * 选择当前页面使用的 User-Agent
     *
     * @param options 启动参数
     * @return UA；无法确定时返回 null
     */
    private String chooseUserAgent(BrowserOptions options) {
        String custom = options.getUserAgent();
        if (StringUtils.isBlank(custom)) {
            custom = System.getProperty(PROPERTY_USER_AGENT);
        }
        if (StringUtils.isBlank(custom)) {
            custom = System.getenv("CHUA_USER_AGENT");
        }
        if (StringUtils.isNotBlank(custom)) {
            return custom;
        }
        boolean rotate = options.isRotateUserAgent()
                || Boolean.parseBoolean(System.getProperty(PROPERTY_ROTATE_USER_AGENT))
                || Boolean.parseBoolean(System.getenv("CHUA_ROTATE_UA"));
        if (rotate) {
            List<String> pool = options.getUserAgentPool();
            if (pool == null || pool.isEmpty()) {
                pool = defaultUserAgentPool();
            }
            if (!pool.isEmpty()) {
                return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            }
        }
        return realUserAgent;
    }

    /**
     * 内置 UA 池
     *
     * <p>默认只在与真实浏览器同版本、同平台的前提下切换品牌（Chrome / Edge），
     * 保证 UA、{@code navigator.platform} 与 UA-CH 三者一致；需要更大范围时
     * 通过 {@link BrowserOptions#setUserAgentPool(List)} 传入自定义池。</p>
     *
     * @return UA 池
     */
    private List<String> defaultUserAgentPool() {
        List<String> pool = new ArrayList<>();
        if (StringUtils.isBlank(realUserAgent)) {
            return pool;
        }
        pool.add(realUserAgent);
        String alternate = switchBrowserBrand(realUserAgent);
        if (alternate != null && !alternate.equals(realUserAgent)) {
            pool.add(alternate);
        }
        return pool;
    }

    /**
     * 在 Chrome / Edge 之间切换品牌
     *
     * @param ua 原始 UA
     * @return 切换后的 UA；无法识别时返回 null
     */
    private static String switchBrowserBrand(String ua) {
        Matcher matcher = Pattern.compile("Chrome/(\\d+\\.0\\.0\\.0)").matcher(ua);
        if (!matcher.find()) {
            return null;
        }
        String version = matcher.group(1);
        String edgeToken = " Edg/" + version;
        if (ua.contains(edgeToken)) {
            return ua.replace(edgeToken, "");
        }
        return ua + edgeToken;
    }

    /**
     * 构建 UA-CH 元数据
     *
     * @param ua UA
     * @return userAgentMetadata
     */
    private Map<String, Object> buildMetadata(String ua) {
        int major = majorVersion(ua);
        String full = fullVersionFor(major);
        boolean edge = ua.contains("Edg/");
        String brandName = edge ? "Microsoft Edge" : "Google Chrome";

        List<Map<String, String>> brands = new ArrayList<>();
        brands.add(brand("Not_A Brand", "24"));
        brands.add(brand("Chromium", String.valueOf(major)));
        brands.add(brand(brandName, String.valueOf(major)));

        List<Map<String, String>> fullVersionList = new ArrayList<>();
        fullVersionList.add(brand("Not_A Brand", "24.0.0.0"));
        fullVersionList.add(brand("Chromium", full));
        fullVersionList.add(brand(brandName, full));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("brands", brands);
        metadata.put("fullVersionList", fullVersionList);
        metadata.put("fullVersion", full);
        metadata.put("platform", metaPlatform(ua));
        metadata.put("platformVersion", metaPlatformVersion(ua));
        metadata.put("architecture", "x86");
        metadata.put("model", "");
        metadata.put("mobile", ua.contains("Mobile"));
        metadata.put("bitness", "64");
        metadata.put("wow64", false);
        return metadata;
    }

    /**
     * 构造品牌项
     *
     * @param brand   品牌
     * @param version 版本
     * @return 品牌项
     */
    private static Map<String, String> brand(String brand, String version) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("brand", brand);
        item.put("version", version);
        return item;
    }

    /**
     * 解析 UA 主版本号
     *
     * @param ua UA
     * @return 主版本号
     */
    private static int majorVersion(String ua) {
        Matcher matcher = Pattern.compile("(?:Chrome|CriOS)/(\\d+)").matcher(ua);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        matcher = Pattern.compile("(\\d+)").matcher(ua);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /**
     * 当前主版本对应的完整版本号
     *
     * @param major 主版本
     * @return 完整版本
     */
    private String fullVersionFor(int major) {
        if (StringUtils.isNotBlank(fullVersion) && fullVersion.startsWith(major + ".")) {
            return fullVersion;
        }
        return major + ".0.0.0";
    }

    /**
     * 从 {@code Browser.getVersion} 的 product 字段提取完整版本
     *
     * @param product 产品串，如 {@code Edg/156.0.3521.66}
     * @param ua      UA
     * @return 完整版本；解析失败返回空串
     */
    private static String extractFullVersion(String product, String ua) {
        if (StringUtils.isNotBlank(product)) {
            int slash = product.indexOf('/');
            if (slash >= 0 && slash + 1 < product.length()) {
                String version = product.substring(slash + 1).trim();
                if (version.matches("\\d+(\\.\\d+){2,3}")) {
                    return version;
                }
            }
        }
        if (ua != null) {
            Matcher matcher = Pattern.compile("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(ua);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    /**
     * UA-CH 平台名称
     *
     * @param ua UA
     * @return 平台名称
     */
    private static String metaPlatform(String ua) {
        if (ua.contains("Android")) {
            return "Android";
        }
        if (ua.contains("iPhone") || ua.contains("iPad")) {
            return "iOS";
        }
        if (ua.contains("Macintosh")) {
            return "macOS";
        }
        if (ua.contains("Linux")) {
            return "Linux";
        }
        return "Windows";
    }

    /**
     * UA-CH 平台版本
     *
     * @param ua UA
     * @return 平台版本
     */
    private String metaPlatformVersion(String ua) {
        if (ua.contains("Android")) {
            Matcher matcher = Pattern.compile("Android\\s+(\\d+)").matcher(ua);
            return matcher.find() ? matcher.group(1) + ".0.0" : "";
        }
        if (ua.contains("iPhone") || ua.contains("iPad")) {
            Matcher matcher = Pattern.compile("OS (\\d+)_(\\d+)").matcher(ua);
            return matcher.find() ? matcher.group(1) + "." + matcher.group(2) + ".0" : "";
        }
        if (ua.contains("Macintosh")) {
            return "14.0.0";
        }
        if (ua.contains("Linux")) {
            return "6.5.0";
        }
        return windows11 ? "15.0.0" : "10.0.0";
    }

    /**
     * navigator.platform 取值
     *
     * @param ua UA
     * @return 平台标识
     */
    private static String platformToken(String ua) {
        if (ua.contains("Android")) {
            return "Linux armv8l";
        }
        if (ua.contains("iPhone")) {
            return "iPhone";
        }
        if (ua.contains("iPad")) {
            return "iPad";
        }
        if (ua.contains("Macintosh")) {
            return "MacIntel";
        }
        if (ua.contains("Linux")) {
            return "Linux x86_64";
        }
        return "Win32";
    }

    /**
     * 确保浏览器已启动
     *
     * @param actual 启动参数
     */
    private void ensureLaunched(BrowserOptions actual) {
        if (connection != null) {
            return;
        }
        this.options = actual;
        String executable = locateBrowser(actual.getExecutablePath());
        try {
            if (StringUtils.isNotBlank(actual.getUserDataDir())) {
                profileDir = Path.of(actual.getUserDataDir());
                temporaryProfile = false;
                Files.createDirectories(profileDir);
            } else {
                profileDir = Files.createTempDirectory("chua-cdp-");
                temporaryProfile = true;
            }
        } catch (IOException e) {
            throw new IllegalStateException("创建浏览器临时目录失败: " + e.getMessage(), e);
        }

        List<String> command = new ArrayList<>();
        command.add(executable);
        if (actual.isHeadless()) {
            command.add("--headless=new");
        }
        command.add("--remote-debugging-port=0");
        command.add("--user-data-dir=" + profileDir.toAbsolutePath());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-blink-features=AutomationControlled");
        command.add("--disable-dev-shm-usage");
        command.add("--disable-features=Translate,OptimizationHints");
        command.add("--window-size=" + actual.getWidth() + "," + actual.getHeight());
        command.add("--lang=zh-CN");
        if (StringUtils.isNotBlank(actual.getProxy())) {
            command.add("--proxy-server=" + actual.getProxy().trim());
        }
        command.addAll(actual.getArgs());
        command.add("about:blank");

        Path logFile = profileDir.resolve("chrome.log");
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("启动浏览器失败（" + executable + "）: " + e.getMessage(), e);
        }
        registerShutdownHook();

        Path activePortFile = profileDir.resolve("DevToolsActivePort");
        long deadline = System.currentTimeMillis()
                + (actual.getLaunchTimeout() == null ? DEVTOOLS_TIMEOUT_MILLIS : actual.getLaunchTimeout().toMillis());
        String port = null;
        String wsPath = null;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("浏览器进程已退出（exit=" + process.exitValue() + "），日志: "
                        + tail(logFile));
            }
            if (Files.exists(activePortFile)) {
                try {
                    List<String> lines = Files.readAllLines(activePortFile, StandardCharsets.UTF_8);
                    if (lines.size() >= 2) {
                        port = lines.get(0).trim();
                        wsPath = lines.get(1).trim();
                        break;
                    }
                } catch (IOException e) {
                    log.debug("[cdp] 读取 DevToolsActivePort 失败: {}", e.getMessage());
                }
            }
            sleep(200L);
        }
        if (port == null || wsPath == null) {
            throw new IllegalStateException("等待浏览器 DevTools 端口超时，日志: " + tail(logFile));
        }

        URI endpoint = URI.create("ws://127.0.0.1:" + port + wsPath);
        connection = CdpConnection.connect(endpoint, Duration.ofSeconds(30));
        initFingerprint();
        log.debug("[cdp] 浏览器已启动: {} (headless={}, port={}, ua={})",
                executable, actual.isHeadless(), port, realUserAgent);
    }

    /**
     * 初始化指纹基线
     *
     * <p>读取浏览器自身的 UA 与完整版本，作为默认 UA 与 UA-CH 的数据来源；
     * 这样浏览器升级后指纹自动跟着变，不需要在代码里写死版本号。</p>
     */
    private void initFingerprint() {
        try {
            Map<String, Object> version = connection.call("Browser.getVersion", Map.of(), null,
                    Duration.ofSeconds(5));
            Object userAgent = version.get("userAgent");
            if (userAgent != null) {
                realUserAgent = String.valueOf(userAgent).replace("HeadlessChrome", "Chrome");
                browserBrand = realUserAgent.contains("Edg/") ? "edge" : "chrome";
            }
            fullVersion = extractFullVersion(String.valueOf(version.getOrDefault("product", "")),
                    realUserAgent);
        } catch (RuntimeException e) {
            log.debug("[cdp] 读取浏览器版本失败: {}", e.getMessage());
        }
        windows11 = detectWindows11();
    }

    /**
     * 检测是否为 Windows 11
     *
     * <p>优先读 {@code SystemInfo.getInfo} 的系统版本（如 {@code 10.0.22631}），
     * 拿不到时按 Windows 10 处理。</p>
     *
     * @return true 表示 Windows 11
     */
    private boolean detectWindows11() {
        try {
            Map<String, Object> info = connection.call("SystemInfo.getInfo", Map.of(), null,
                    Duration.ofSeconds(3));
            Map<String, Object> os = asMap(info.get("osInfo"));
            if (os == null) {
                return false;
            }
            String version = asString(os.get("platformVersion"));
            if (StringUtils.isBlank(version)) {
                return false;
            }
            String[] parts = version.split("\\.");
            int build = Integer.parseInt(parts[parts.length - 1]);
            return build >= 22000;
        } catch (RuntimeException e) {
            log.debug("[cdp] 读取系统版本失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 把对象转换成 Map
     *
     * @param value 原值
     * @return Map；不是 Map 时返回 null
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (connection != null) {
            connection.close();
            connection = null;
            realUserAgent = null;
            fullVersion = "";
            browserBrand = "chrome";
            windows11 = false;
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
        }
        if (profileDir != null) {
            if (temporaryProfile) {
                deleteQuietly(profileDir);
            }
            profileDir = null;
        }
    }

    /**
     * 注册 JVM 关闭钩子，避免残留浏览器进程
     */
    private void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;
        try {
// [P3C C1 豁免] 关闭钩子必须由 JVM 创建独立线程
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "chua-cdp-cleanup"));
        } catch (Exception e) {
            log.debug("[cdp] 注册关闭钩子失败: {}", e.getMessage());
        }
    }

    /**
     * 定位可用的 Chromium 内核浏览器
     *
     * @param configured 显式配置的路径，可为空
     * @return 可执行文件路径
     */
    public static String locateBrowser(String configured) {
        if (isExecutable(configured)) {
            return configured;
        }
        for (String property : new String[]{PROPERTY_CHROME, "inception.chrome"}) {
            String value = System.getProperty(property);
            if (isExecutable(value)) {
                return value;
            }
        }
        for (String env : new String[]{"CHUA_CHROME", "INCEPTION_CHROME", "CHROME_PATH", "CHROME_BIN"}) {
            String value = System.getenv(env);
            if (isExecutable(value)) {
                return value;
            }
        }
        for (String candidate : candidates()) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }
        String playwright = locatePlaywrightChromium();
        if (playwright != null) {
            return playwright;
        }
        throw new IllegalStateException("未找到可用的 Chrome/Chromium 内核浏览器。"
                + "请设置系统属性 -D" + PROPERTY_CHROME + "=<chrome 可执行文件路径>，"
                + "或环境变量 CHUA_CHROME");
    }

    /**
     * 判断路径是否可执行
     *
     * @param path 路径
     * @return true 表示可用
     */
    private static boolean isExecutable(String path) {
        return StringUtils.isNotBlank(path) && Files.isExecutable(Path.of(path));
    }

    /**
     * 常见安装路径候选
     *
     * @return 候选路径
     */
    private static List<String> candidates() {
        List<String> list = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            addIfPresent(list, localAppData, "Google/Chrome/Application/chrome.exe");
            addIfPresent(list, programFiles, "Google/Chrome/Application/chrome.exe");
            addIfPresent(list, programFilesX86, "Google/Chrome/Application/chrome.exe");
            addIfPresent(list, localAppData, "Chromium/Application/chrome.exe");
            addIfPresent(list, localAppData, "Microsoft/Edge/Application/msedge.exe");
            addIfPresent(list, programFiles, "Microsoft/Edge/Application/msedge.exe");
            addIfPresent(list, programFilesX86, "Microsoft/Edge/Application/msedge.exe");
        } else if (os.contains("mac")) {
            list.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            list.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
            list.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else {
            list.add("/usr/bin/google-chrome");
            list.add("/usr/bin/google-chrome-stable");
            list.add("/usr/bin/chromium");
            list.add("/usr/bin/chromium-browser");
            list.add("/usr/bin/microsoft-edge");
        }
        return list;
    }

    /**
     * 追加候选路径
     *
     * @param list 候选列表
     * @param base 基础目录
     * @param rest 相对路径
     */
    private static void addIfPresent(List<String> list, String base, String rest) {
        if (StringUtils.isBlank(base)) {
            return;
        }
        list.add(Path.of(base, rest.split("/")).toString());
    }

    /**
     * 从 Playwright 缓存里找 Chromium
     *
     * @return 可执行文件路径，找不到返回 null
     */
    private static String locatePlaywrightChromium() {
        String localAppData = System.getenv("LOCALAPPDATA");
        String home = System.getProperty("user.home");
        List<Path> roots = new ArrayList<>();
        if (StringUtils.isNotBlank(localAppData)) {
            roots.add(Path.of(localAppData, "ms-playwright"));
        }
        if (StringUtils.isNotBlank(home)) {
            roots.add(Path.of(home, ".cache", "ms-playwright"));
            roots.add(Path.of(home, "Library", "Caches", "ms-playwright"));
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> dirs;
            try (Stream<Path> stream = Files.list(root)) {
                dirs = stream.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("chromium-"))
                        .sorted(Comparator.comparingLong(CdpBrowserEngine::revisionOf).reversed())
                        .toList();
            } catch (IOException e) {
                continue;
            }
            for (Path dir : dirs) {
                for (String relative : new String[]{"chrome-win64/chrome.exe", "chrome-win/chrome.exe",
                        "chrome-linux/chrome", "chrome-mac/Chromium.app/Contents/MacOS/Chromium"}) {
                    Path executable = dir.resolve(relative.replace('/', java.io.File.separatorChar));
                    if (Files.isExecutable(executable)) {
                        return executable.toString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 解析 Playwright 目录名里的版本号
     *
     * @param dir 目录
     * @return 版本号，解析失败返回 0
     */
    private static long revisionOf(Path dir) {
        String name = dir.getFileName().toString();
        int index = name.lastIndexOf('-');
        if (index < 0) {
            return 0L;
        }
        try {
            return Long.parseLong(name.substring(index + 1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 读取日志尾部
     *
     * @param logFile 日志文件
     * @return 末尾若干行
     */
    private static String tail(Path logFile) {
        try {
            if (!Files.exists(logFile)) {
                return "<无日志>";
            }
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 15);
            return String.join(" | ", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "<读取日志失败: " + e.getMessage() + ">";
        }
    }

    /**
     * 递归删除目录（尽力而为）
     *
     * @param dir 目录
     */
    private static void deleteQuietly(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 浏览器可能仍占用部分文件，忽略即可
                }
            });
        } catch (IOException e) {
            log.debug("[cdp] 清理临时目录失败: {}", e.getMessage());
        }
    }

    /**
     * 安全转换字符串
     *
     * @param value 原始值
     * @return 字符串，类型不符返回 null
     */
    private static String asString(Object value) {
        return value instanceof String str ? str : null;
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