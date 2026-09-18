package com.chua.wechat.support.restore.nativebridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WcdbNativeBridge测试类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
class WcdbNativeBridgeTest {

    /**
     * 测试：是否Supported。
     */
    @Test
    void testIsSupported() {
        boolean supported = WcdbNativeBridge.isSupported();
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            assertTrue(supported);
        } else {
            assertFalse(supported);
        }
    }

    /**
     * 测试：RcConstants。
     */
    @Test
    void testRcConstants() {
        assertEquals(0, WcdbNativeBridge.RC_OK);
        assertEquals(-1006, WcdbNativeBridge.RC_INIT_FAIL);
    }
}