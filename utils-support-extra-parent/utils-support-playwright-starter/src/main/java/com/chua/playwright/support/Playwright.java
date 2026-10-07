package com.chua.playwright.support;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.playwright.support.engine.Engine;
import com.chua.playwright.support.engine.JavaEngine;
import com.chua.playwright.support.engine.NativeEngine;

/**
 * 对应 playwright-Java 的 {@code Playwright} 入口。
 *
 * <p>引擎选择通过 SPI（{@link ServiceProvider}）完成：<strong>原生优先</strong> ——
 * 先按 {@code META-INF/extensions/com.chua.playwright.support.engine.Engine} 中的
 * {@code native} 别名取 {@link NativeEngine} 并做可用性校验（原生库抽取 + 符号解析成功），
 * 校验失败则回退到默认的 {@link JavaEngine}（playwright-Java 1.63.0）。
 * {@link #isNative()} 反映当前生效的引擎类型。</p>
 *
 * @author CH
 * @since 4.0.0
 * @return 版本的结果
 */
public class Playwright {

    private static volatile Engine ENGINE; // ENGINE
/**
 * Playwright。
 */

    private Playwright() {}
/**
 * 获取engine：原生引擎可用则优先，否则回退 Java引擎。
 * @return 获取engine的结果
 */

    static Engine getEngine() {
        if (ENGINE == null) {
            synchronized (Playwright.class) {
                if (ENGINE == null) {
                    ENGINE = createEngine();
                }
            }
        }
        return ENGINE;
    }

    /**
     * 按 SPI 选择引擎：native 别名的 NativeEngine 校验通过则用之，否则回退默认引擎。
     *
     * @return 生效的引擎实例
     */
    private static Engine createEngine() {
        try {
            Engine nativeEngine = ServiceProvider.of(Engine.class).getExtension("native");
            if (nativeEngine instanceof NativeEngine impl && impl.isAvailable()) {
                return impl;
            }
        } catch (Throwable e) {
            // 原生引擎不可用（缺依赖 / 平台不支持 / 抽取失败）→ 回退 Java引擎
        }
        try {
            Engine fallback = ServiceProvider.of(Engine.class).getSpiService();
            if (fallback != null) {
                return fallback;
            }
        } catch (Throwable e) {
            // SPI 未注册（如 classpath 缺注册文件）→ 直接构造 JavaEngine
        }
        return new JavaEngine();
    }

    /**
     * 当前生效的引擎是否为原生（Rust FFM）引擎。
     *
     * @return 原生引擎返回 true
     */
    public static boolean isNative() {
        return getEngine() instanceof NativeEngine;
    }

    /**
     * 创建。
     *
     * @return Playwright 对象
     */
    public static Playwright create() {
        getEngine();
        return new Playwright();
    }

    public static String version() { return getEngine().version(); }

    /**
     * 铬。
     * @return 铬的结果
     */
    public BrowserType chromium() { return new BrowserType(getEngine()); }
}
