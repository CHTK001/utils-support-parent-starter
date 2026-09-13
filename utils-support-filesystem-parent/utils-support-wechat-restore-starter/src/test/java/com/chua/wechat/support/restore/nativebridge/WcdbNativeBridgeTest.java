package com.chua.wechat.support.restore.nativebridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WcdbNativeBridgeTest {

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

    @Test
    void testRcConstants() {
        assertEquals(0, WcdbNativeBridge.RC_OK);
        assertEquals(-1006, WcdbNativeBridge.RC_INIT_FAIL);
    }
}