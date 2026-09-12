package com.chua.nmap.support;

import com.chua.nmap.support.bridge.RustNmapBridge;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RustNmapBridge 测试
 * 动态库存在时跑集成测试，不存在时只验证加载状态
 */
class RustNmapBridgeTest {

    private static final Logger log = LoggerFactory.getLogger(RustNmapBridgeTest.class);
    private static final boolean NATIVE_LOADED = RustNmapBridge.isLoaded();

    @BeforeAll
    static void logLoadStatus() {
        if (NATIVE_LOADED) {
            log.info("[RustNmap] 动态库加载成功");
        } else {
            log.warn("[RustNmap] 动态库未加载: {}",
                    RustNmapBridge.getLoadError() != null
                            ? RustNmapBridge.getLoadError().getMessage()
                            : "unknown");
        }
    }

    // ── 加载状态 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isLoaded 返回明确的布尔值")
    void isLoaded_returns_boolean() {
        // 不管加载成功与否，isLoaded 必须返回确定值
        boolean loaded = RustNmapBridge.isLoaded();
        log.info("[RustNmap] isLoaded={}", loaded);
        // 只要不抛异常即通过
    }

    @Test
    @DisplayName("未加载时 ensureLoaded 抛出 UnsupportedOperationException")
    void ensureLoaded_throws_when_not_loaded() {
        if (NATIVE_LOADED) {
            // 已加载时不应抛异常
            assertDoesNotThrow(RustNmapBridge::ensureLoaded);
        } else {
            assertThrows(UnsupportedOperationException.class, RustNmapBridge::ensureLoaded);
        }
    }

    @Test
    @DisplayName("getLoadError 在未加载时返回非null")
    void getLoadError_when_not_loaded() {
        if (!NATIVE_LOADED) {
            assertNotNull(RustNmapBridge.getLoadError());
            log.info("[RustNmap] 加载错误: {}", RustNmapBridge.getLoadError().getMessage());
        }
    }

    // ── 集成测试（需要动态库）────────────────────────────────────────────────

    @Test
    @DisplayName("getVersion 返回版本字符串")
    void getVersion_returns_string() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        String version = RustNmapBridge.getVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
        log.info("[RustNmap] 版本: {}", version);
    }

    @Test
    @DisplayName("isValidIp 验证合法IP")
    void isValidIp_valid() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        assertTrue(RustNmapBridge.isValidIp("192.168.1.1"));
        assertTrue(RustNmapBridge.isValidIp("127.0.0.1"));
        assertTrue(RustNmapBridge.isValidIp("8.8.8.8"));
    }

    @Test
    @DisplayName("isValidIp 拒绝非法IP")
    void isValidIp_invalid() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        assertFalse(RustNmapBridge.isValidIp("not-an-ip"));
        assertFalse(RustNmapBridge.isValidIp("999.999.999.999"));
        assertFalse(RustNmapBridge.isValidIp(""));
    }

    @Test
    @DisplayName("isValidSubnet 验证合法CIDR")
    void isValidSubnet_valid() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        assertTrue(RustNmapBridge.isValidSubnet("192.168.1.0/24"));
        assertTrue(RustNmapBridge.isValidSubnet("10.0.0.0/8"));
    }

    @Test
    @DisplayName("isValidSubnet 拒绝非法格式")
    void isValidSubnet_invalid() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        assertFalse(RustNmapBridge.isValidSubnet("192.168.1.1"));
        assertFalse(RustNmapBridge.isValidSubnet("not-a-subnet"));
    }

    @Test
    @DisplayName("scanSingleTcpPort 扫描本地回环端口")
    void scanSingleTcpPort_localhost() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        // 端口 65534 通常关闭
        int result = RustNmapBridge.scanSingleTcpPort("127.0.0.1", 65534, 500);
        // 0=open, 1=closed, -1=error，只要不抛异常
        assertTrue(result >= -1 && result <= 2, "状态码应在合法范围: " + result);
        log.info("[RustNmap] 127.0.0.1:65534 状态码={}", result);
    }

    @Test
    @DisplayName("pingHost 返回JSON结果")
    void pingHost_returns_json() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        String result = RustNmapBridge.pingHost("127.0.0.1", 1000);
        // 可能为null（主机不可达），但不应抛异常
        log.info("[RustNmap] pingHost 127.0.0.1 结果: {}", result);
    }

    @Test
    @DisplayName("getLocalIps 返回JSON数组")
    void getLocalIps_returns_json() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        String result = RustNmapBridge.getLocalIps();
        log.info("[RustNmap] getLocalIps: {}", result);
        // 不抛异常即通过
    }
}
