package com.chua.nmap.support;

import com.chua.nmap.support.bridge.RustNmapBridge;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
* rustnmapscanner 测试
* 动态库未加载时验证降级行为，加载时跑真实扫描
* @author CH
* @since 4.0.0
 */
class RustNmapScannerTest {

    private static final Logger log = LoggerFactory.getLogger(RustNmapScannerTest.class); // 日志
    private static final boolean NATIVE_LOADED = RustNmapBridge.isLoaded(); // NAT加载

    private RustNmapScanner scanner; // scanner

    /**
     * 设置Up。
     */
    @BeforeEach
    void setUp() {
        scanner = new RustNmapScanner();
    }

    // ── 配置测试（不依赖动态库）──────────────────────────────────────────────

    /**
     * default选项。
     */
    @Test
    @DisplayName("默认选项正确")
    void default_options() {
        NmapScanner.ScanOptions opts = scanner.getOptions();
        assertNotNull(opts);
        assertEquals(1000, opts.getTimeout());
    }

    /**
     * 设置选项chaining。
     */
    @Test
    @DisplayName("setOptions 链式调用")
    void set_options_chaining() {
        NmapScanner.ScanOptions opts = NmapScanner.ScanOptions.fast();
        NmapScanner result = scanner.setOptions(opts);
        assertSame(scanner, result);
        assertEquals(500, scanner.getOptions().getTimeout());
    }

    // ── 动态库未加载时的降级行为 ──────────────────────────────────────────────

    /**
     * scanTcpPortsgracefulwhennotloaded。
     */
    @Test
    @DisplayName("动态库未加载时 scanTcpPorts 返回空结果不抛异常")
    void scanTcpPorts_graceful_when_not_loaded() {
        Assumptions.assumeFalse(NATIVE_LOADED, "跳过：动态库已加载，走集成测试");
        NmapScanner.ScanResult result = scanner.scanTcpPorts("127.0.0.1", new int[]{80, 443});
        assertNotNull(result);
        assertNotNull(result.getPorts());
        log.info("[RustNmap] 降级扫描结果: host={} ports={}", result.getHost(), result.getPorts().size());
    }

    /**
     * pinggracefulwhennotloaded。
     */
    @Test
    @DisplayName("动态库未加载时 ping 返回 HostInfo 不抛异常")
    void ping_graceful_when_not_loaded() {
        Assumptions.assumeFalse(NATIVE_LOADED, "跳过：动态库已加载");
        NmapScanner.HostInfo info = scanner.ping("127.0.0.1");
        assertNotNull(info);
        assertEquals("127.0.0.1", info.getIp());
    }

    /**
     * scanSubnetgracefulwhennotloaded。
     */
    @Test
    @DisplayName("动态库未加载时 scanSubnet 返回空列表不抛异常")
    void scanSubnet_graceful_when_not_loaded() {
        Assumptions.assumeFalse(NATIVE_LOADED, "跳过：动态库已加载");
        List<NmapScanner.HostInfo> hosts = scanner.scanSubnet("192.168.1.0/24");
        assertNotNull(hosts);
    }

    /**
     * detectOsgracefulwhennotloaded。
     */
    @Test
    @DisplayName("动态库未加载时 detectOs 返回 OsInfo 不抛异常")
    void detectOs_graceful_when_not_loaded() {
        Assumptions.assumeFalse(NATIVE_LOADED, "跳过：动态库已加载");
        NmapScanner.OsInfo os = scanner.detectOs("127.0.0.1");
        assertNotNull(os);
    }

    // ── 集成测试（需要动态库）────────────────────────────────────────────────

    /**
     * scancommonportslocalhost。
     */
    @Test
    @DisplayName("扫描本地常用端口")
    void scan_common_ports_localhost() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        long t = System.currentTimeMillis();
        NmapScanner.ScanResult result = scanner.scanCommonPorts("127.0.0.1");
        long elapsed = System.currentTimeMillis() - t;

        assertNotNull(result);
        assertNotNull(result.getPorts());
        assertEquals("127.0.0.1", result.getHost());
        assertTrue(result.getDuration() >= 0);

        log.info("[RustNmap] 本地常用端口扫描: 总端口={} 开放={} 耗时={}ms",
                result.getTotalPorts(), result.getOpenPorts(), elapsed);

        for (NmapScanner.PortInfo p : result.getPorts()) {
            if (p.getState() == NmapScanner.PortState.OPEN) {
                log.info("[RustNmap]   开放: {}/{} ({})", p.getPort(), p.getProtocol(), p.getServiceName());
            }
        }
    }

    /**
     * scan端口range。
     */
    @Test
    @DisplayName("扫描端口范围 80-90")
    void scan_port_range() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        NmapScanner.ScanResult result = scanner.scanTcpPorts("127.0.0.1", 80, 90);
        assertNotNull(result);
        assertEquals(11, result.getTotalPorts());
        log.info("[RustNmap] 端口范围80-90: 开放={}", result.getOpenPorts());
    }

    /**
     * pinglocalhost。
     */
    @Test
    @DisplayName("ping 本地回环")
    void ping_localhost() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        NmapScanner.HostInfo info = scanner.ping("127.0.0.1", 2000);
        assertNotNull(info);
        assertEquals("127.0.0.1", info.getIp());
        log.info("[RustNmap] ping 127.0.0.1: alive={} latency={}ms", info.isAlive(), info.getLatency());
    }

    /**
     * detect服务localhost。
     */
    @Test
    @DisplayName("detectService 检测本地端口服务")
    void detect_service_localhost() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        NmapScanner.ServiceInfo service = scanner.detectService("127.0.0.1", 80);
        assertNotNull(service);
        assertEquals(80, service.getPort());
        log.info("[RustNmap] 服务检测 127.0.0.1:80 name={}", service.getName());
    }

    /**
     * asyncscancompletes。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    @DisplayName("异步扫描完成并返回结果")
    void async_scan_completes() throws Exception {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        CompletableFuture<NmapScanner.ScanResult> future =
                scanner.scanTcpPortsAsync("127.0.0.1", new int[]{80, 443, 22});
        NmapScanner.ScanResult result = future.get(30, TimeUnit.SECONDS);
        assertNotNull(result);
        log.info("[RustNmap] 异步扫描完成: 开放={}", result.getOpenPorts());
    }

    /**
     * scanwithprogress回调。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    @DisplayName("带进度回调的扫描")
    void scan_with_progress_callback() throws Exception {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        AtomicInteger callbackCount = new AtomicInteger(0);
        int[] ports = {80, 443, 22, 8080};

        CompletableFuture<NmapScanner.ScanResult> future = scanner.scanWithProgress(
                "127.0.0.1", ports,
                progress -> {
                    callbackCount.incrementAndGet();
                    assertTrue(progress.getProgress() >= 0 && progress.getProgress() <= 100);
                    assertTrue(progress.getTotalPorts() == ports.length);
                }
        );

        NmapScanner.ScanResult result = future.get(30, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(ports.length, callbackCount.get(), "回调次数应等于端口数");
        log.info("[RustNmap] 进度回调次数={} 开放={}", callbackCount.get(), result.getOpenPorts());
    }

    /**
     * detectos编号exception。
     */
    @Test
    @DisplayName("detectOs 不抛异常")
    void detect_os_no_exception() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        NmapScanner.OsInfo os = scanner.detectOs("127.0.0.1");
        assertNotNull(os);
        log.info("[RustNmap] OS检测: name={} accuracy={}", os.getName(), os.getAccuracy());
    }
}
