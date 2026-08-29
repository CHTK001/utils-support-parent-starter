package com.chua.playwright.support;

import com.chua.playwright.support.bridge.PlaywrightNative;
import com.chua.playwright.support.spi.Engine;
import com.chua.playwright.support.spi.JavaEngine;
import com.chua.playwright.support.spi.NativeEngine;

/**
 * 对应 playwright-java 的 {@code Playwright} 入口。<br>
 * 双模式：默认 Rust native（headless_chrome CDP 直连），
 * native 加载失败时自动回退到 {@code com.microsoft.playwright}。
 */
public class Playwright {

    private static volatile Engine ENGINE;

    private Playwright() {}

    static Engine getEngine() {
        if (ENGINE == null) {
            synchronized (Playwright.class) {
                if (ENGINE == null) {
                    if (PlaywrightNative.ensureLoaded()) {
                        ENGINE = new NativeEngine();
                    } else {
                        ENGINE = new JavaEngine();
                    }
                }
            }
        }
        return ENGINE;
    }

    /** 当前运行模式 */
    public static boolean isNative() { return ENGINE instanceof NativeEngine; }
    public static boolean isJava()    { return ENGINE instanceof JavaEngine; }

    public static Playwright create() {
        getEngine(); // 触发初始化
        return new Playwright();
    }

    public static String version() { return getEngine().version(); }

    public BrowserType chromium() { return new BrowserType(getEngine()); }
}