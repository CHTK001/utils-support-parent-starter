package com.chua.common.support.browser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器启动参数
 *
 * <p>描述「启动一个真实 Chromium」所需的全部配置：无头模式、可执行文件路径、
 * 代理、User-Agent、视口、额外启动参数与超时。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * BrowserOptions options = new BrowserOptions()
 *         .setHeadless(true)
 *         .setWidth(1280)
 *         .setHeight(900);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BrowserOptions {

    /**
     * 是否无头模式
     */
    private boolean headless = true;

    /**
     * 浏览器可执行文件路径，为空时自动探测
     */
    private String executablePath;

    /**
     * 浏览器代理地址，如 {@code http://127.0.0.1:7890}
     */
    private String proxy;

    /**
     * User-Agent，为空时使用浏览器默认值
     */
    private String userAgent;

    /**
     * 是否按页面随机轮换 User-Agent
     *
     * <p>开启后每打开一个页面就从 UA 池里随机选一个；显式设置了 {@link #userAgent}
     * 时以显式值为准，不再轮换。</p>
     */
    private boolean rotateUserAgent;

    /**
     * 自定义 UA 池，为空时使用引擎根据真实浏览器生成的内置池
     */
    private List<String> userAgentPool = new ArrayList<>();

    /**
     * 视口宽度
     */
    // [P3C A20 豁免]
    private int width = 1280;

    /**
     * 视口高度
     */
    // [P3C A20 豁免]
    private int height = 900;

    /**
     * 用户数据目录，为空时使用临时目录并在关闭时删除
     */
    private String userDataDir;

    /**
     * 时区，如 {@code Asia/Shanghai}
     */
    private String timezoneId;

    /**
     * 语言，如 {@code zh-CN}
     */
    private String locale;

    /**
     * 额外启动参数
     */
    private final List<String> args = new ArrayList<>();

    /**
     * 启动连接超时
     */
    // [P3C A20 豁免]
    private Duration launchTimeout = Duration.ofSeconds(60);

    /**
     * 单条 CDP 命令超时
     */
    // [P3C A20 豁免]
    private Duration commandTimeout = Duration.ofSeconds(60);

    /**
     * 是否无头模式
     *
     * @return true 表示无头
     */
    public boolean isHeadless() {
        return headless;
    }

    /**
     * 设置是否无头模式
     *
     * @param headless 是否无头
     * @return 当前对象
     */
    public BrowserOptions setHeadless(boolean headless) {
        this.headless = headless;
        return this;
    }

    /**
     * 浏览器可执行文件路径
     *
     * @return 路径，可能为空
     */
    public String getExecutablePath() {
        return executablePath;
    }

    /**
     * 设置浏览器可执行文件路径
     *
     * @param executablePath 路径
     * @return 当前对象
     */
    public BrowserOptions setExecutablePath(String executablePath) {
        this.executablePath = executablePath;
        return this;
    }

    /**
     * 浏览器代理地址
     *
     * @return 代理地址，可能为空
     */
    public String getProxy() {
        return proxy;
    }

    /**
     * 设置浏览器代理地址
     *
     * @param proxy 代理地址
     * @return 当前对象
     */
    public BrowserOptions setProxy(String proxy) {
        this.proxy = proxy;
        return this;
    }

    /**
     * User-Agent
     *
     * @return UA，可能为空
     */
    public String getUserAgent() {
        return userAgent;
    }

    /**
     * 设置 User-Agent
     *
     * @param userAgent UA
     * @return 当前对象
     */
    public BrowserOptions setUserAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    /**
     * 是否按页面随机轮换 User-Agent
     *
     * @return true 表示轮换
     */
    public boolean isRotateUserAgent() {
        return rotateUserAgent;
    }

    /**
     * 设置是否按页面随机轮换 User-Agent
     *
     * @param rotateUserAgent 是否轮换
     * @return 当前对象
     */
    public BrowserOptions setRotateUserAgent(boolean rotateUserAgent) {
        this.rotateUserAgent = rotateUserAgent;
        return this;
    }

    /**
     * 自定义 UA 池
     *
     * @return UA 列表，可能为空
     */
    public List<String> getUserAgentPool() {
        return userAgentPool;
    }

    /**
     * 设置自定义 UA 池
     *
     * <p>为空时使用引擎内置池（根据真实浏览器版本生成，保持品牌、版本与
     * {@code Sec-CH-UA} 一致）。</p>
     *
     * @param userAgentPool UA 列表
     * @return 当前对象
     */
    public BrowserOptions setUserAgentPool(List<String> userAgentPool) {
        this.userAgentPool = userAgentPool == null ? new ArrayList<>() : new ArrayList<>(userAgentPool);
        return this;
    }

    /**
     * 视口宽度
     *
     * @return 宽度
     */
    public int getWidth() {
        return width;
    }

    /**
     * 设置视口宽度
     *
     * @param width 宽度
     * @return 当前对象
     */
    public BrowserOptions setWidth(int width) {
        this.width = width;
        return this;
    }

    /**
     * 视口高度
     *
     * @return 高度
     */
    public int getHeight() {
        return height;
    }

    /**
     * 设置视口高度
     *
     * @param height 高度
     * @return 当前对象
     */
    public BrowserOptions setHeight(int height) {
        this.height = height;
        return this;
    }

    /**
     * 用户数据目录
     *
     * @return 目录，可能为空
     */
    public String getUserDataDir() {
        return userDataDir;
    }

    /**
     * 设置用户数据目录
     *
     * @param userDataDir 目录
     * @return 当前对象
     */
    public BrowserOptions setUserDataDir(String userDataDir) {
        this.userDataDir = userDataDir;
        return this;
    }

    /**
     * 额外启动参数
     *
     * @return 参数列表
     */
    public List<String> getArgs() {
        return args;
    }

    /**
     * 时区
     *
     * @return 时区，可能为空
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
    public BrowserOptions setTimezoneId(String timezoneId) {
        this.timezoneId = timezoneId;
        return this;
    }

    /**
     * 语言
     *
     * @return 语言，可能为空
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
    public BrowserOptions setLocale(String locale) {
        this.locale = locale;
        return this;
    }

    /**
     * 追加启动参数
     *
     * @param arg 参数
     * @return 当前对象
     */
    public BrowserOptions addArg(String arg) {
        this.args.add(arg);
        return this;
    }

    /**
     * 启动连接超时
     *
     * @return 超时
     */
    public Duration getLaunchTimeout() {
        return launchTimeout;
    }

    /**
     * 设置启动连接超时
     *
     * @param launchTimeout 超时
     * @return 当前对象
     */
    public BrowserOptions setLaunchTimeout(Duration launchTimeout) {
        this.launchTimeout = launchTimeout;
        return this;
    }

    /**
     * 单条 CDP 命令超时
     *
     * @return 超时
     */
    public Duration getCommandTimeout() {
        return commandTimeout;
    }

    /**
     * 设置单条 CDP 命令超时
     *
     * @param commandTimeout 超时
     * @return 当前对象
     */
    public BrowserOptions setCommandTimeout(Duration commandTimeout) {
        this.commandTimeout = commandTimeout;
        return this;
    }
}
