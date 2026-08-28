package com.chua.playwright.support.bridge;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;

/**
 * 加载 Rust 动态库（rust_playwright.{dll,so}）并提供 JNI 入口。
 * 项目约定：native 模块只放 .dll/.so，Java 桥接在 starter 中用 NativeLoader 加载。
 */
public final class PlaywrightNative {

    private static final String LIBRARY_NAME = "rust_playwright";
    private static volatile boolean loaded = false;

    private PlaywrightNative() {
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        String libFile = NativeUtils.getLibraryFileName(LIBRARY_NAME);
        NativeLoader.of(LIBRARY_NAME)
            .from(PlaywrightNative.class.getClassLoader())
            .glob(libFile)
            .toTarget(NativeUtils.tempRoot().resolve(LIBRARY_NAME))
            .load();
        loaded = true;
    }

    public static native String execute(String command);
    public static native String getVersion();
    public static native boolean isAvailable();
}
