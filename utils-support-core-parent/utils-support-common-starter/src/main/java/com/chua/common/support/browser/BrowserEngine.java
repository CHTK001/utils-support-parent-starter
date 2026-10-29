package com.chua.common.support.browser;

import com.chua.common.support.spi.ServiceProvider;

/**
 * 浏览器引擎 SPI
 *
 * <p>把「谁来驱动浏览器」抽象成可替换的服务：</p>
 * <ul>
 *   <li>{@code cdp} —— 本模块自带的轻量实现，只用 JDK（ProcessBuilder + WebSocket），
 *       适合不想引入 Playwright / Jvppeteer 的场景；</li>
 *   <li>其它实现（如 Playwright、Jvppeteer）可在各自模块注册同名接口的实现。</li>
 * </ul>
 *
 * <p>通过 {@link #of(String)} 按名称获取，通过 {@link #cdp()} 获取内置实现。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * try (BrowserEngine engine = BrowserEngine.cdp();
 *      BrowserPage page = engine.openPage(new BrowserOptions())) {
 *     page.navigate("https://example.com");
 *     System.out.println(page.title());
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface BrowserEngine extends AutoCloseable {

    /**
     * 内置轻量实现名称
     */
    String CDP = "cdp";

    /**
     * 按名称获取浏览器引擎
     *
     * <p>每次调用返回一个新实例，由调用方负责 {@link #close()}；浏览器进程
     * 在首次 {@link #openPage(BrowserOptions)} 时才启动。</p>
     *
     * @param name 实现名称，如 {@code cdp}
     * @return 浏览器引擎
     * @throws IllegalStateException 找不到实现时抛出
     */
    static BrowserEngine of(String name) {
        BrowserEngine engine = ServiceProvider.of(BrowserEngine.class).getNewExtension(name);
        if (engine == null) {
            throw new IllegalStateException("未找到浏览器引擎实现: " + name
                    + "，请确认对应模块在类路径中");
        }
        return engine;
    }

    /**
     * 获取内置轻量实现
     *
     * @return 基于 JDK 的 CDP 浏览器引擎
     */
    static BrowserEngine cdp() {
        return of(CDP);
    }

    /**
     * 打开一个页面
     *
     * <p>引擎内部复用浏览器进程；不同页面之间相互独立。</p>
     *
     * @param options 启动参数，为空时使用默认值
     * @return 页面
     */
    BrowserPage openPage(BrowserOptions options);

    /**
     * 关闭引擎，释放浏览器进程与临时目录
     */
    @Override
    void close();
}
