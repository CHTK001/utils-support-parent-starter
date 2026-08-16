package com.chua.common.support.network.net;

import com.chua.common.support.utils.StringUtils;

import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User-Agent 解析工具对象。
 *
 * <p>通过 {@link #parse(String)} 将 HTTP {@code User-Agent} 请求头字符串解析为结构化对象，
 * 提供浏览器、操作系统、设备类型识别，以及真实浏览器 / 爬虫等常用判断能力。</p>
 *
 * <p>除纯字符串解析外，还提供基于<b>请求头集合</b>的判定能力：</p>
 * <ul>
 *   <li>基础请求头校验 —— 真实浏览器必备的 {@code User-Agent} / {@code Accept} /
 *       {@code Accept-Language} / {@code Accept-Encoding} 四项任一缺失，判为非浏览器客户端</li>
 *   <li>Sec-Fetch 校验 —— UA 自称现代浏览器（Chrome / Edge / Opera / Firefox）却缺少
 *       {@code Sec-Fetch-Site} / {@code Sec-Fetch-Mode} / {@code Sec-Fetch-Dest} 头，
 *       属伪造 UA 的脚本 / 爬虫特征</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * UserAgent ua = UserAgent.parse(request.getHeader("User-Agent"));
 * if (ua.isCrawler()) {
 *     // 搜索引擎爬虫，跳过访问统计
 * }
 * if (ua.isBrowser() && ua.isMobile()) {
 *     // 手机端真实浏览器
 * }
 * // 基于请求头集合的判定（含基础头 / Sec-Fetch 校验）
 * boolean crawler = UserAgent.isCrawler(headers);
 * }</pre>
 *
 * <p><b>不可变性与线程安全：</b>本类所有字段均为 {@code final}，实例创建后不可变，
 * 天然线程安全，可安全地在多线程间共享。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see NetAddress
 */
public class UserAgent implements Serializable {

    /**
     * 序列化版本号，用于 {@link Serializable} 反序列化时的版本兼容性校验。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 真实浏览器请求必备的基础请求头（键名不区分大小写）。
     *
     * <p>任意浏览器发起的页面 / 子资源 / AJAX 请求都会携带这些头；
     * 缺失其中任意一项，通常意味着请求来自 API 客户端、脚本或爬虫。</p>
     */
    private static final String[] BASIC_HEADERS = {
            "User-Agent", "Accept", "Accept-Language", "Accept-Encoding"
    };

    /**
     * 现代浏览器（Chromium 系与 Firefox）请求必备的 Sec-Fetch 元数据头（键名不区分大小写）。
     *
     * <p>自 Chrome 80 / Firefox 90 起，真实浏览器发出的几乎所有请求都会携带
     * {@code Sec-Fetch-Site} / {@code Sec-Fetch-Mode} / {@code Sec-Fetch-Dest} 三个头，
     * 用于声明请求的发起方上下文。UA 自称 Chrome/Edge/Firefox 却缺少这些头，
     * 通常是脚本或爬虫伪造浏览器 UA 的特征。</p>
     */
    private static final String[] SEC_FETCH_HEADERS = {
            "Sec-Fetch-Site", "Sec-Fetch-Mode", "Sec-Fetch-Dest"
    };

    /**
     * 爬虫关键字表（小写匹配）。
     *
     * <p>覆盖三类非浏览器客户端：</p>
     * <ul>
     *   <li>搜索引擎爬虫 —— 如 {@code googlebot}、{@code baiduspider}、{@code yandex}、
     *       {@code bytespider} 等（均含 {@code bot} / {@code spider} / {@code crawl} 通用词）</li>
     *   <li>命令行 / 编程语言 HTTP 客户端 —— 如 {@code curl}、{@code wget}、
     *       {@code python-requests}、{@code okhttp}、{@code go-http-client} 等</li>
     *   <li>无头浏览器 / 自动化工具 —— 如 {@code headlesschrome}、{@code phantomjs}、
     *       {@code scrapy}、{@code postman} 等</li>
     * </ul>
     */
    private static final String[] CRAWLER_KEYWORDS = {
            "bot", "spider", "crawl", "slurp", "bingpreview", "yandex", "googlebot",
            "baiduspider", "sogou", "360spider", "bytespider", "petalbot", "applebot",
            "duckduckbot", "facebookexternalhit", "linkedinbot", "twitterbot",
            "python-requests", "python-urllib", "curl", "wget", "java/", "okhttp",
            "go-http-client", "node-fetch", "axios", "headlesschrome", "phantomjs",
            "httpclient", "postman", "apache-http", "libwww", "scrapy", "httpie", "urllib"
    };

    /**
     * 原始 User-Agent 字符串，即 {@link #parse(String)} 传入的原始值；
     * 未解析出有效 UA 时可能为 {@code null} 或空串。
     */
    private final String raw;
    /**
     * 解析出的浏览器类型，无法识别时为 {@link Browser#UNKNOWN}。
     */
    private final Browser browser;
    /**
     * 解析出的浏览器版本号（如 {@code "120.0.0.0"}），无法识别时为 {@code null}。
     */
    private final String browserVersion;
    /**
     * 解析出的操作系统类型，无法识别时为 {@link OperatingSystem#UNKNOWN}。
     */
    private final OperatingSystem operatingSystem;
    /**
     * 解析出的操作系统版本号（如 {@code "10.0"}、{@code "16.6"}），无法识别时为 {@code null}。
     */
    private final String osVersion;
    /**
     * 解析出的设备类型，无法识别时为 {@link Device#UNKNOWN}。
     */
    private final Device device;
    /**
     * 是否命中爬虫关键字（见 {@link #CRAWLER_KEYWORDS}）。
     */
    private final boolean crawler;

    /**
     * 私有构造器：解析 User-Agent 字符串并填充全部字段。
     *
     * <p>解析过程（按顺序）：</p>
     * <ol>
     *   <li>将 UA 转为小写（{@link Locale#ROOT}，避免土耳其语等区域的大小写陷阱）</li>
     *   <li>用 {@link #CRAWLER_KEYWORDS} 做子串匹配，得出爬虫标记</li>
     *   <li>按 {@link #resolveBrowser(String)} 顺序识别浏览器内核</li>
     *   <li>按内核类型提取浏览器版本号</li>
     *   <li>按 {@link #resolveOperatingSystem(String)} 顺序识别操作系统（iOS 先于 macOS）</li>
     *   <li>提取操作系统版本号</li>
     *   <li>按 {@link #resolveDevice(String)} 识别设备类型</li>
     * </ol>
     *
     * @param userAgent User-Agent 字符串，可为 {@code null} 或空（此时得到空解析结果）
     */
    private UserAgent(String userAgent) {
        this.raw = userAgent;
        String ua = StringUtils.isNullOrEmpty(userAgent) ? "" : userAgent.toLowerCase(Locale.ROOT);
        this.crawler = containsAny(ua, CRAWLER_KEYWORDS);
        this.browser = resolveBrowser(ua);
        this.browserVersion = resolveBrowserVersion(this.browser, ua);
        this.operatingSystem = resolveOperatingSystem(ua);
        this.osVersion = resolveOsVersion(ua);
        this.device = resolveDevice(ua);
    }

    /**
     * 解析 User-Agent 字符串。
     *
     * <p>解析结果包含：浏览器与版本、操作系统与版本、设备类型，以及爬虫标记。</p>
     *
     * @param userAgent User-Agent 字符串，可为 {@code null} 或空
     * @return 解析后的 {@link UserAgent} 对象，不会返回 {@code null}
     */
    public static UserAgent parse(String userAgent) {
        return new UserAgent(userAgent);
    }

    /**
     * 直接判断字符串是否为爬虫 UA（便捷静态方法）。
     *
     * <p>内部调用 {@link #parse(String)} 后取 {@link #isCrawler()} 结果，
     * 适用于仅需布尔结论、无需完整解析结果的场景。</p>
     *
     * @param userAgent User-Agent 字符串，可为 {@code null}
     * @return 命中爬虫关键字返回 {@code true}；{@code null} 或空串返回 {@code false}
     */
    public static boolean isCrawler(String userAgent) {
        return parse(userAgent).isCrawler();
    }

    /**
     * 直接判断字符串是否为真实浏览器 UA（便捷静态方法）。
     *
     * <p>内部调用 {@link #parse(String)} 后取 {@link #isBrowser()} 结果。</p>
     *
     * @param userAgent User-Agent 字符串，可为 {@code null}
     * @return 识别为真实浏览器且非爬虫返回 {@code true}；否则返回 {@code false}
     */
    public static boolean isBrowser(String userAgent) {
        return parse(userAgent).isBrowser();
    }

    /**
     * 从请求头集合中解析 {@code User-Agent} 头并返回 {@link UserAgent} 对象。
     *
     * <p>头键名不区分大小写；未携带 {@code User-Agent} 头时返回空解析结果
     * （浏览器 / 操作系统均为 UNKNOWN）。</p>
     *
     * @param headers 请求头集合（键值对，键名忽略大小写），可为 {@code null}
     * @return 解析后的 {@link UserAgent} 对象，不会返回 {@code null}
     */
    public static UserAgent parse(Map<String, String> headers) {
        return parse(getHeader(headers, "User-Agent"));
    }

    /**
     * 判断请求头集合是否缺少真实浏览器必备的基础请求头。
     *
     * <p>基础请求头为 {@code User-Agent} / {@code Accept} / {@code Accept-Language} /
     * {@code Accept-Encoding} 四项，任意一项缺失（或头集合为 {@code null} / 空）即返回 {@code true}。</p>
     *
     * @param headers 请求头集合（键名忽略大小写），可为 {@code null}
     * @return 缺少任意基础请求头返回 {@code true}；四项齐全返回 {@code false}
     */
    public static boolean missingBasicHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return true;
        }
        for (String header : BASIC_HEADERS) {
            if (StringUtils.isNullOrEmpty(getHeader(headers, header))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断请求头集合是否缺少现代浏览器必备的 Sec-Fetch 元数据头。
     *
     * <p>Sec-Fetch 头为 {@code Sec-Fetch-Site} / {@code Sec-Fetch-Mode} /
     * {@code Sec-Fetch-Dest} 三项，任意一项缺失（或头集合为 {@code null} / 空）即返回 {@code true}。</p>
     *
     * @param headers 请求头集合（键名忽略大小写），可为 {@code null}
     * @return 缺少任意 Sec-Fetch 头返回 {@code true}；三项齐全返回 {@code false}
     */
    public static boolean missingSecFetchHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return true;
        }
        for (String header : SEC_FETCH_HEADERS) {
            if (StringUtils.isNullOrEmpty(getHeader(headers, header))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 基于请求头集合判断是否为爬虫 / 非浏览器客户端。
     *
     * <p>满足以下任一条件即判定为爬虫：</p>
     * <ul>
     *   <li>缺少真实浏览器必备的基础请求头（见 {@link #missingBasicHeaders(Map)})</li>
     *   <li>{@code User-Agent} 头命中爬虫关键字</li>
     *   <li>UA 自称现代浏览器（Chrome / Edge / Opera / Firefox）却缺少 Sec-Fetch 元数据头
     *       （见 {@link #missingSecFetchHeaders(Map)}），属伪造 UA 的脚本 / 爬虫特征</li>
     * </ul>
     *
     * @param headers 请求头集合（键名忽略大小写），可为 {@code null}
     * @return 是否爬虫 / 非浏览器客户端
     * @see #missingBasicHeaders(Map)
     * @see #missingSecFetchHeaders(Map)
     */
    public static boolean isCrawler(Map<String, String> headers) {
        if (missingBasicHeaders(headers)) {
            return true;
        }
        UserAgent ua = parse(getHeader(headers, "User-Agent"));
        if (ua.isCrawler()) {
            return true;
        }
        // 自称现代浏览器却缺少 Sec-Fetch 头 -> 伪造 UA 的脚本 / 爬虫
        return isSecFetchBrowser(ua.getBrowser()) && missingSecFetchHeaders(headers);
    }

    /**
     * 基于请求头集合判断是否为真实浏览器。
     *
     * <p>要求基础请求头齐全、{@code User-Agent} 识别为真实浏览器内核、非爬虫，
     * 且自称现代浏览器时 Sec-Fetch 头齐全。</p>
     *
     * @param headers 请求头集合（键名忽略大小写），可为 {@code null}
     * @return 是否真实浏览器
     * @see #isCrawler(Map)
     */
    public static boolean isBrowser(Map<String, String> headers) {
        if (missingBasicHeaders(headers)) {
            return false;
        }
        UserAgent ua = parse(getHeader(headers, "User-Agent"));
        if (!ua.isBrowser()) {
            return false;
        }
        // 自称现代浏览器却缺少 Sec-Fetch 头 -> 非真实浏览器
        return !(isSecFetchBrowser(ua.getBrowser()) && missingSecFetchHeaders(headers));
    }

    /**
     * 判断是否为真实浏览器。
     *
     * <p>识别出明确浏览器内核（Chrome / Firefox / Safari / Edge / Opera / IE / 微信等）
     * 且非爬虫时返回 {@code true}；命令行客户端（curl / wget 等）、无头浏览器
     * （HeadlessChrome 等）与搜索引擎爬虫均返回 {@code false}。</p>
     *
     * @return 是否真实浏览器
     */
    public boolean isBrowser() {
        return !crawler && browser != Browser.UNKNOWN;
    }

    /**
     * 判断是否为爬虫（搜索引擎爬虫 / 命令行客户端 / 编程语言 HTTP 库 / 无头浏览器）。
     *
     * @return 是否爬虫
     */
    public boolean isCrawler() {
        return crawler;
    }

    /**
     * 判断是否为移动端设备（手机）。
     *
     * <p>判定依据见 {@link Device#MOBILE}。</p>
     *
     * @return 是否移动端
     */
    public boolean isMobile() {
        return device == Device.MOBILE;
    }

    /**
     * 判断是否为平板设备。
     *
     * <p>判定依据见 {@link Device#TABLET}。</p>
     *
     * @return 是否平板
     */
    public boolean isTablet() {
        return device == Device.TABLET;
    }

    /**
     * 判断是否为桌面设备。
     *
     * <p>判定依据见 {@link Device#DESKTOP}。</p>
     *
     * @return 是否桌面
     */
    public boolean isDesktop() {
        return device == Device.DESKTOP;
    }

    /**
     * 判断是否为微信内置浏览器（MicroMessenger / X5 内核）。
     *
     * @return 是否微信
     */
    public boolean isWechat() {
        return browser == Browser.WECHAT;
    }

    /**
     * 判断是否为 Chrome 浏览器。
     *
     * @return 是否 Chrome
     */
    public boolean isChrome() {
        return browser == Browser.CHROME;
    }

    /**
     * 判断是否为 Firefox 浏览器。
     *
     * @return 是否 Firefox
     */
    public boolean isFirefox() {
        return browser == Browser.FIREFOX;
    }

    /**
     * 判断是否为 Safari 浏览器。
     *
     * @return 是否 Safari
     */
    public boolean isSafari() {
        return browser == Browser.SAFARI;
    }

    /**
     * 判断是否为 Edge 浏览器。
     *
     * @return 是否 Edge
     */
    public boolean isEdge() {
        return browser == Browser.EDGE;
    }

    /**
     * 判断是否为 Opera 浏览器。
     *
     * @return 是否 Opera
     */
    public boolean isOpera() {
        return browser == Browser.OPERA;
    }

    /**
     * 判断是否为 IE 浏览器（含 Trident 内核）。
     *
     * @return 是否 IE
     */
    public boolean isIe() {
        return browser == Browser.IE;
    }

    /**
     * 判断是否为 Windows 操作系统。
     *
     * @return 是否 Windows
     */
    public boolean isWindows() {
        return operatingSystem == OperatingSystem.WINDOWS;
    }

    /**
     * 判断是否为 macOS 操作系统。
     *
     * @return 是否 macOS
     */
    public boolean isMacOs() {
        return operatingSystem == OperatingSystem.MACOS;
    }

    /**
     * 判断是否为 Linux 操作系统（不含 Android）。
     *
     * @return 是否 Linux
     */
    public boolean isLinux() {
        return operatingSystem == OperatingSystem.LINUX;
    }

    /**
     * 判断是否为 Android 操作系统。
     *
     * @return 是否 Android
     */
    public boolean isAndroid() {
        return operatingSystem == OperatingSystem.ANDROID;
    }

    /**
     * 判断是否为 iOS 操作系统（iPhone / iPad / iPod）。
     *
     * @return 是否 iOS
     */
    public boolean isIos() {
        return operatingSystem == OperatingSystem.IOS;
    }

    /**
     * 获取原始 User-Agent 字符串。
     *
     * @return 原始字符串，可能为 {@code null} 或空串
     */
    public String getRaw() {
        return raw;
    }

    /**
     * 获取浏览器类型。
     *
     * @return 浏览器类型，无法识别时为 {@link Browser#UNKNOWN}
     */
    public Browser getBrowser() {
        return browser;
    }

    /**
     * 获取浏览器版本号。
     *
     * @return 浏览器版本号，无法识别时为 {@code null}
     */
    public String getBrowserVersion() {
        return browserVersion;
    }

    /**
     * 获取操作系统类型。
     *
     * @return 操作系统类型，无法识别时为 {@link OperatingSystem#UNKNOWN}
     */
    public OperatingSystem getOperatingSystem() {
        return operatingSystem;
    }

    /**
     * 获取操作系统版本号。
     *
     * @return 操作系统版本号，无法识别时为 {@code null}
     */
    public String getOsVersion() {
        return osVersion;
    }

    /**
     * 获取设备类型。
     *
     * @return 设备类型，无法识别时为 {@link Device#UNKNOWN}
     */
    public Device getDevice() {
        return device;
    }

    /**
     * 返回原始 User-Agent 字符串。
     *
     * @return 原始字符串，可能为 {@code null}
     */
    @Override
    public String toString() {
        return raw;
    }

    /**
     * 识别 UA 字符串中的浏览器内核类型。
     *
     * <p>判定顺序（<b>顺序敏感</b>，先命中的优先）：</p>
     * <ol>
     *   <li>{@code micromessenger} → 微信内置浏览器（X5 内核）</li>
     *   <li>{@code edg/} → Edge（Chromium 版，必须先于 Chrome 判定，因其 UA 同时含 Chrome 标记）</li>
     *   <li>{@code opr/} 或 {@code opera} → Opera（Chromium 版，同样先于 Chrome）</li>
     *   <li>{@code ucbrowser} → UC 浏览器</li>
     *   <li>{@code qqbrowser} → QQ 浏览器</li>
     *   <li>{@code chrome/} 且非 {@code chromium} → Chrome</li>
     *   <li>{@code firefox/} → Firefox</li>
     *   <li>{@code msie} 或 {@code trident} → IE（含 Trident 内核的 Edge 旧版）</li>
     *   <li>{@code safari/} 且不含 chrome / edg / opr → Safari（排除伪装）</li>
     *   <li>以上均未命中 → {@link Browser#UNKNOWN}</li>
     * </ol>
     *
     * @param ua 已转为小写的 User-Agent 字符串（非空）
     * @return 识别出的浏览器类型
     */
    private static Browser resolveBrowser(String ua) {
        if (ua.contains("micromessenger")) {
            return Browser.WECHAT;
        }
        if (ua.contains("edg/")) {
            return Browser.EDGE;
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return Browser.OPERA;
        }
        if (ua.contains("ucbrowser")) {
            return Browser.UC;
        }
        if (ua.contains("qqbrowser")) {
            return Browser.QQ;
        }
        if (ua.contains("chrome/") && !ua.contains("chromium")) {
            return Browser.CHROME;
        }
        if (ua.contains("firefox/")) {
            return Browser.FIREFOX;
        }
        if (ua.contains("msie") || ua.contains("trident")) {
            return Browser.IE;
        }
        if (ua.contains("safari/") && !ua.contains("chrome") && !ua.contains("edg") && !ua.contains("opr")) {
            return Browser.SAFARI;
        }
        return Browser.UNKNOWN;
    }

    /**
     * 根据浏览器类型提取对应的版本号。
     *
     * <p>各浏览器版本号提取规则：</p>
     * <ul>
     *   <li>Chrome / Edge / Firefox / Opera / 微信 / UC / QQ —— 从对应内核标记后的版本段提取
     *       （如 {@code chrome/120.0.0.0} → {@code 120.0.0.0}）</li>
     *   <li>Safari —— 从 {@code Version/x.y} 段提取（UA 中 Safari/ 后是 WebKit 版本，非浏览器版本）</li>
     *   <li>IE —— 优先取 {@code MSIE x.y}，Trident 版回退取 {@code rv:x.y}</li>
     *   <li>其他类型 —— 返回 {@code null}</li>
     * </ul>
     *
     * @param browser 已识别的浏览器类型
     * @param ua      已转为小写的 User-Agent 字符串（非空）
     * @return 浏览器版本号，无法提取时为 {@code null}
     */
    private static String resolveBrowserVersion(Browser browser, String ua) {
        switch (browser) {
            case CHROME:
                return match(ua, "chrome/([\\d.]+)");
            case EDGE:
                return match(ua, "edg/([\\d.]+)");
            case FIREFOX:
                return match(ua, "firefox/([\\d.]+)");
            case OPERA:
                return match(ua, "opr/([\\d.]+)");
            case SAFARI:
                return match(ua, "version/([\\d.]+)");
            case IE: {
                String v = match(ua, "msie\\s([\\d.]+)");
                return v != null ? v : match(ua, "rv:([\\d.]+)");
            }
            case WECHAT:
                return match(ua, "micromessenger/([\\d.]+)");
            case UC:
                return match(ua, "ucbrowser/([\\d.]+)");
            case QQ:
                return match(ua, "qqbrowser/([\\d.]+)");
            default:
                return null;
        }
    }

    /**
     * 识别 UA 字符串中的操作系统类型。
     *
     * <p>判定顺序（<b>顺序敏感</b>，先命中的优先）：</p>
     * <ol>
     *   <li>{@code windows} → Windows</li>
     *   <li>{@code android} → Android（必须先于 Linux，因其 UA 同时含 Linux 标记）</li>
     *   <li>{@code iphone} / {@code ipad} / {@code ipod} / {@code ios} → iOS
     *       （<b>必须先于 macOS</b>，iPhone/iPad UA 中通常含 {@code like Mac OS X}，避免误判）</li>
     *   <li>{@code mac os} 或 {@code macintosh} → macOS</li>
     *   <li>{@code linux} 或 {@code x11} → Linux</li>
     *   <li>以上均未命中 → {@link OperatingSystem#UNKNOWN}</li>
     * </ol>
     *
     * @param ua 已转为小写的 User-Agent 字符串（非空）
     * @return 识别出的操作系统类型
     */
    private static OperatingSystem resolveOperatingSystem(String ua) {
        if (ua.contains("windows")) {
            return OperatingSystem.WINDOWS;
        }
        if (ua.contains("android")) {
            return OperatingSystem.ANDROID;
        }
        // iOS 必须在 macOS 之前判断：iPhone/iPad UA 中通常含 "like Mac OS X"，
        // 若先匹配 mac os 会把 iOS 设备误判为 macOS
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod") || ua.contains("ios")) {
            return OperatingSystem.IOS;
        }
        if (ua.contains("mac os") || ua.contains("macintosh")) {
            return OperatingSystem.MACOS;
        }
        if (ua.contains("linux") || ua.contains("x11")) {
            return OperatingSystem.LINUX;
        }
        return OperatingSystem.UNKNOWN;
    }

    /**
     * 根据操作系统类型提取对应的版本号。
     *
     * <p>各系统版本号提取规则：</p>
     * <ul>
     *   <li>Windows —— 从 {@code Windows NT x.y} 段提取（如 {@code 10.0}）</li>
     *   <li>macOS —— 从 {@code Mac OS X x_y_z} 段提取，并将下划线转为点号（如 {@code 10.15.7}）</li>
     *   <li>Android —— 从 {@code Android x.y} 段提取（如 {@code 13}）</li>
     *   <li>iOS —— 从 {@code CPU iPhone OS x_y} 或 {@code CPU OS x_y} 段提取（如 {@code 16.6}）</li>
     *   <li>其他情况 —— 返回 {@code null}</li>
     * </ul>
     *
     * @param ua 已转为小写的 User-Agent 字符串（非空）
     * @return 操作系统版本号，无法提取时为 {@code null}
     */
    private static String resolveOsVersion(String ua) {
        if (ua.contains("windows")) {
            return match(ua, "windows nt ([\\d.]+)");
        }
        if (ua.contains("mac os x")) {
            String v = match(ua, "mac os x ([\\d_]+)");
            return v != null ? v.replace('_', '.') : null;
        }
        if (ua.contains("android")) {
            return match(ua, "android ([\\d.]+)");
        }
        if (ua.contains("cpu iphone os") || ua.contains("cpu os")) {
            String v = match(ua, "os ([\\d_]+)");
            return v != null ? v.replace('_', '.') : null;
        }
        return null;
    }

    /**
     * 识别 UA 字符串中的设备类型。
     *
     * <p>判定顺序（<b>顺序敏感</b>）：</p>
     * <ol>
     *   <li>{@code ipad} / {@code tablet} / {@code silk} → {@link Device#TABLET}</li>
     *   <li>{@code mobile} / {@code iphone} / {@code ipod} / {@code windows phone} /
     *       {@code opera mini} / {@code opera mobi} → {@link Device#MOBILE}</li>
     *   <li>{@code android} / {@code linux} / {@code windows} / {@code mac os} /
     *       {@code macintosh} / {@code x11} → {@link Device#DESKTOP}</li>
     *   <li>以上均未命中 → {@link Device#UNKNOWN}</li>
     * </ol>
     *
     * @param ua 已转为小写的 User-Agent 字符串（非空）
     * @return 识别出的设备类型
     */
    private static Device resolveDevice(String ua) {
        if (ua.contains("ipad") || ua.contains("tablet") || ua.contains("silk")) {
            return Device.TABLET;
        }
        if (ua.contains("mobile") || ua.contains("iphone") || ua.contains("ipod")
                || ua.contains("windows phone") || ua.contains("opera mini") || ua.contains("opera mobi")) {
            return Device.MOBILE;
        }
        if (ua.contains("android") || ua.contains("linux") || ua.contains("windows")
                || ua.contains("mac os") || ua.contains("macintosh") || ua.contains("x11")) {
            return Device.DESKTOP;
        }
        return Device.UNKNOWN;
    }

    /**
     * 判断 UA 声明的浏览器是否属于现代浏览器（Chrome / Edge / Opera / Firefox）。
     *
     * <p>这些浏览器自 Chrome 80 / Firefox 90 起必然携带 Sec-Fetch 头，
     * 因此是 Sec-Fetch 缺失校验的适用对象。Safari（16.4 前不发 Sec-Fetch）与
     * 微信内置浏览器被有意排除，避免误伤。</p>
     *
     * @param browser 解析出的浏览器类型
     * @return 是否现代浏览器
     */
    private static boolean isSecFetchBrowser(Browser browser) {
        return browser == Browser.CHROME
                || browser == Browser.EDGE
                || browser == Browser.OPERA
                || browser == Browser.FIREFOX;
    }

    /**
     * 从请求头集合中按名称（不区分大小写）获取请求头值。
     *
     * <p>HTTP 规范要求头名大小写不敏感（{@code user-agent} 与 {@code User-Agent} 等价），
     * 因此查找时逐项使用 {@link String#equalsIgnoreCase(String)} 匹配。</p>
     *
     * @param headers 请求头集合，可为 {@code null}
     * @param name    请求头名称，可为 {@code null}
     * @return 匹配到的请求头值；集合为空 / 未命中 / 名称为 {@code null} 时返回 {@code null}
     */
    private static String getHeader(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 判断字符串是否包含关键字表中的任意一项。
     *
     * <p>逐项调用 {@link String#contains(CharSequence)}，命中即返回 {@code true}。</p>
     *
     * @param ua       被检查的小写字符串（非空）
     * @param keywords 关键字表
     * @return 命中任意关键字返回 {@code true}；关键字表为空返回 {@code false}
     */
    private static boolean containsAny(String ua, String[] keywords) {
        for (String keyword : keywords) {
            if (ua.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从字符串中提取首个正则匹配的捕获组。
     *
     * <p>每次调用实时编译正则（内部方法，调用频率低，无需缓存）。</p>
     *
     * @param ua    被匹配的小写字符串（非空）
     * @param regex 含一个捕获组的正则表达式
     * @return 首个匹配的捕获组内容；无匹配时返回 {@code null}
     */
    private static String match(String ua, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(ua);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 浏览器类型枚举。
     */
    public enum Browser {
        /**
         * Google Chrome（Blink 内核）
         */
        CHROME,
        /**
         * Microsoft Edge（Chromium 内核）
         */
        EDGE,
        /**
         * Mozilla Firefox（Gecko 内核）
         */
        FIREFOX,
        /**
         * Apple Safari（WebKit 内核）
         */
        SAFARI,
        /**
         * Opera（Chromium 内核）
         */
        OPERA,
        /**
         * IE（含 Trident 内核的旧版 Edge）
         */
        IE,
        /**
         * 微信内置浏览器（MicroMessenger / X5 内核）
         */
        WECHAT,
        /**
         * UC 浏览器
         */
        UC,
        /**
         * QQ 浏览器
         */
        QQ,
        /**
         * 无法识别的浏览器
         */
        UNKNOWN
    }

    /**
     * 操作系统类型枚举。
     */
    public enum OperatingSystem {
        /**
         * Microsoft Windows
         */
        WINDOWS,
        /**
         * Apple macOS
         */
        MACOS,
        /**
         * Linux（不含 Android）
         */
        LINUX,
        /**
         * Google Android
         */
        ANDROID,
        /**
         * Apple iOS（iPhone / iPad / iPod）
         */
        IOS,
        /**
         * 无法识别的操作系统
         */
        UNKNOWN
    }

    /**
     * 设备类型枚举。
     */
    public enum Device {
        /**
         * 桌面设备（台式机 / 笔记本）
         */
        DESKTOP,
        /**
         * 移动设备（手机）
         */
        MOBILE,
        /**
         * 平板设备
         */
        TABLET,
        /**
         * 无法识别的设备
         */
        UNKNOWN
    }
}
