package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;
import com.chua.playwright.support.spi.JavaEngine;

/**
 * 对应 playwright-Java 的 {@code Playwright} 入口。
 * 使用 playwright-Java（{@code com.microsoft.playwright}）作为底层引擎。
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
 * 获取engine。
 * @return 获取engine的结果
 */

    static Engine getEngine() {
        if (ENGINE == null) {
            synchronized (Playwright.class) {
                if (ENGINE == null) {
                    ENGINE = new JavaEngine();
                }
            }
        }
        return ENGINE;
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
