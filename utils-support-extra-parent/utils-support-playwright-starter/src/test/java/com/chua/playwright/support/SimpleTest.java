package com.chua.playwright.support;

import com.chua.common.support.utils.NativeUtils;

/**
 * SimpleTest。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SimpleTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Simple Test ==="); // [P3C E1 豁免]
        System.out.println("tempRoot: " + NativeUtils.tempRoot()); // [P3C E1 豁免]

        Playwright pw = Playwright.create();
        System.out.println("Mode: " + (Playwright.isNative() ? "NATIVE" : "JAVA")); // [P3C E1 豁免]
        System.out.println("Version: " + Playwright.version()); // [P3C E1 豁免]

        // Just check if we can create playwright without crashing
        System.out.println("=== Test passed ==="); // [P3C E1 豁免]
    }
}
