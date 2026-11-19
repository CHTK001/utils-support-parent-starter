package com.chua.common.support.browser.cdp;

import com.chua.common.support.browser.BrowserOptions;
import com.chua.common.support.browser.BrowserPage;

import java.util.concurrent.atomic.AtomicReference;

/**
 * CDP 浏览器快捷入口
 *
 * <p>提供一行式 API，方便其它页面、服务直接嵌入使用。<strong>入口方法必须传入目标地址</strong>。
 * 默认<strong>无头模式</strong>启动 Chromium，不弹出真实浏览器窗口，也不经过
 * {@link CdpScreenshot} 截图流程；拿到的 {@link BrowserPage} 可直接做导航、取内容、
 * 执行脚本、截图等操作。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * // 一行打开页面（传入目标地址），用完自动回收标签页
 * try (BrowserPage page = Cdp.open("https://example.com")) {
 *     System.out.println(page.title());
 *     System.out.println(page.content());
 * }
 *
 * // 共享同一个浏览器进程，连续打开多个页面
 * BrowserPage a = Cdp.open("https://a.com");
 * BrowserPage b = Cdp.open("https://b.com");
 * Cdp.close();   // 释放共享浏览器（JVM 退出时也会自动释放）
 * }</pre>
 *
 * <p>说明：</p>
 * <ul>
 *   <li>{@link #open} 系列底层复用一个<strong>共享引擎</strong>：多个页面共用同一
 *       浏览器进程，开销低、启动快；关闭单个页面不影响共享浏览器。</li>
 *   <li>进程级参数（无头、代理、可执行文件路径、启动参数等）以浏览器
 *       <strong>首次启动</strong>时传入的为准；视口、UA、时区、语言等页面级参数
 *       每次 {@link #open} 都会生效。</li>
 *   <li>需要完全隔离的浏览器（如不同代理）时，直接
 *       {@code new CdpBrowserEngine()} 自行管理生命周期。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class Cdp {

    /**
     * 共享浏览器引擎
     */
    private static final AtomicReference<CdpBrowserEngine> SHARED = new AtomicReference<>();

    /**
     * 是否已注册 JVM 关闭钩子
     */
    private static volatile boolean shutdownHookRegistered;

    /**
     * 工具类禁止实例化
     */
    private Cdp() {
    }

    /**
     * 打开一个页面并导航到目标地址（默认参数、无头模式）
     *
     * @param url 目标地址，如 {@code https://example.com}；不能为空
     * @return 页面；导航失败时页面会被关闭后抛出异常
     */
    public static BrowserPage open(String url) {
        return open(null, url);
    }

    /**
     * 按自定义参数打开一个页面并导航到目标地址
     *
     * @param options 启动参数，为空时使用默认值
     * @param url     目标地址，不能为空
     * @return 页面；导航失败时页面会被关闭后抛出异常
     */
    public static BrowserPage open(BrowserOptions options, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("目标地址 url 不能为空");
        }
        CdpBrowserEngine engine = browser(options);
        BrowserPage page = engine.openPage(options);
        if (url != null && !url.isBlank()) {
            try {
                page.navigate(url);
            } catch (RuntimeException e) {
                try {
                    page.close();
                } catch (Exception ignored) {
                    // 保留原始导航异常
                }
                throw e;
            }
        }
        return page;
    }

    /**
     * 获取（必要时启动）共享浏览器引擎
     *
     * <p>首次调用会以默认参数启动浏览器进程；之后重复调用复用同一进程。</p>
     *
     * @return 共享浏览器引擎
     */
    public static CdpBrowserEngine browser() {
        return browser(null);
    }

    /**
     * 获取（必要时启动）共享浏览器引擎
     *
     * @param options 启动参数；浏览器已启动时仅页面级参数生效，
     *                进程级参数以首次启动为准
     * @return 共享浏览器引擎
     */
    public static CdpBrowserEngine browser(BrowserOptions options) {
        CdpBrowserEngine engine = SHARED.get();
        if (engine != null && engine.isRunning()) {
            return engine;
        }
        synchronized (Cdp.class) {
            engine = SHARED.get();
            if (engine != null && engine.isRunning()) {
                return engine;
            }
            CdpBrowserEngine created = new CdpBrowserEngine();
            created.start(options);
            SHARED.set(created);
            registerShutdownHook();
            return created;
        }
    }

    /**
     * 共享浏览器是否已启动且可用
     *
     * @return true 表示可用
     */
    public static boolean isRunning() {
        CdpBrowserEngine engine = SHARED.get();
        return engine != null && engine.isRunning();
    }

    /**
     * 关闭共享浏览器，释放浏览器进程与临时目录
     *
     * <p>幂等：未启动时调用无任何效果。之后再次 {@link #open} 会重新启动浏览器。</p>
     */
    public static void close() {
        CdpBrowserEngine engine = SHARED.getAndSet(null);
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * 注册 JVM 关闭钩子，避免嵌入方忘记释放时残留浏览器进程
     */
    private static void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(Cdp::close, "chua-cdp-shutdown"));
    }
}
