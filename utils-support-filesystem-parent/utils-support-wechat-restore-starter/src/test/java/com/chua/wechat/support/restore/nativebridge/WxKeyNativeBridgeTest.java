package com.chua.wechat.support.restore.nativebridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WxKeyNativeBridgeTest {

    @Test
    void testIsSupported() {
        boolean supported = WxKeyNativeBridge.isSupported();
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            assertTrue(supported);
        } else {
            assertFalse(supported);
        }
    }

    @Test
    void testKeyPattern() {
        assertTrue("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".matches("^[0-9a-fA-F]{64}$"));
        assertFalse("short".matches("^[0-9a-fA-F]{64}$"));
        assertFalse("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde".matches("^[0-9a-fA-F]{64}$"));
    }
}