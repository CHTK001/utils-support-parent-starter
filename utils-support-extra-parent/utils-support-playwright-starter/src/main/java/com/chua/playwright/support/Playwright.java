package com.chua.playwright.support;

import com.chua.playwright.support.spi.Engine;
import com.chua.playwright.support.spi.JavaEngine;

/**
 * 对应 playwright-java 的 {@code Playwright} 入口。
 * 使用 playwright-java（{@code com.microsoft.playwright}）作为底层引擎。
 */
public class Playwright {

    private static volatile Engine ENGINE;

    private Playwright() {}

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

    public static Playwright create() {
        getEngine();
        return new Playwright();
    }

    public static String version() { return getEngine().version(); }

    public BrowserType chromium() { return new BrowserType(getEngine()); }
}