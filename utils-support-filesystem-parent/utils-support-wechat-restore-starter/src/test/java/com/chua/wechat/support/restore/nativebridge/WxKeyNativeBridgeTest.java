package com.chua.wechat.support.restore.nativebridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wx键NativeBridge测试类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
class WxKeyNativeBridgeTest {

    /**
     * 测试：是否Supported。
     */
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

    /**
     * 测试：键模式。
     */
    @Test
    void testKeyPattern() {
        assertTrue("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".matches("^[0-9a-fA-F]{64}$"));
        assertFalse("short".matches("^[0-9a-fA-F]{64}$"));
        assertFalse("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde".matches("^[0-9a-fA-F]{64}$"));
    }
}