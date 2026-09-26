package com.chua.playwright.support;

import com.chua.common.support.utils.NativeUtils;

public class SimpleTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Simple Test ===");
        System.out.println("tempRoot: " + NativeUtils.tempRoot());
        
        Playwright pw = Playwright.create();
        System.out.println("Mode: " + (Playwright.isNative() ? "NATIVE" : "JAVA"));
        System.out.println("Version: " + Playwright.version());
        
        // Just check if we can create playwright without crashing
        System.out.println("=== Test passed ===");
    }
}
