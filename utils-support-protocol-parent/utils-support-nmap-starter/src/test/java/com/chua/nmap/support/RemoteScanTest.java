package com.chua.nmap.support;

import com.chua.nmap.support.bridge.RustNmapBridge;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 远程主机扫描测试 - 8.139.4.229
 * @author CH
 * @since 4.0.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RemoteScanTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteScanTest.class); // 日志
    private static final String TARGET = "8.139.4.229"; // Target
    private static final boolean NATIVE_LOADED = RustNmapBridge.isLoaded(); // NAT加载

    private RustNmapScanner scanner; // scanner

    /**
     * 设置Up。
     */
    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(NATIVE_LOADED, "跳过：动态库未加载");
        scanner = new RustNmapScanner();
        scanner.setOptions(new NmapScanner.ScanOptions()
                .setTimeout(3000)
                .setConcurrency(200));
    }

    /**
     * ping。
     */
    @Test
    @Order(1)
    @DisplayName("Ping 检测主机存活")
    void ping() {
        NmapScanner.HostInfo info = scanner.ping(TARGET, 5000);
        log.info("[扫描] {} ping: alive={} latency={}ms", TARGET, info.isAlive(), info.getLatency());
    }

    /**
     * scancommonports。
     */
    @Test
    @Order(2)
    @DisplayName("扫描常用端口")
    void scan_common_ports() {
        long t = System.currentTimeMillis();
        NmapScanner.ScanResult result = scanner.scanCommonPorts(TARGET);
        long elapsed = System.currentTimeMillis() - t;

        log.info("[扫描] {} 常用端口: 总={} 开放={} 耗时={}ms",
                TARGET, result.getTotalPorts(), result.getOpenPorts(), elapsed);

        for (NmapScanner.PortInfo p : result.getPorts()) {
            if (p.getState() == NmapScanner.PortState.OPEN) {
                log.info("[扫描]   ✓ {}/{} - {}", p.getPort(), p.getProtocol(), p.getServiceName());
            }
        }

        Assertions.assertNotNull(result);
    }

    /**
     * scanwebports。
     */
    @Test
    @Order(3)
    @DisplayName("扫描 Web 相关端口 (80/443/8080/8443/8888)")
    void scan_web_ports() {
        int[] webPorts = {80, 443, 8080, 8443, 8888, 3000, 9090};
        NmapScanner.ScanResult result = scanner.scanTcpPorts(TARGET, webPorts);

        log.info("[扫描] {} Web端口: 开放={}", TARGET, result.getOpenPorts());
        for (NmapScanner.PortInfo p : result.getPorts()) {
            log.info("[扫描]   端口 {} -> {}", p.getPort(), p.getState());
            if (p.getState() == NmapScanner.PortState.OPEN) {
                // 尝试抓 Banner
                String banner = RustNmapBridge.getBanner(TARGET, p.getPort(), 3000);
                if (banner != null && !banner.isEmpty()) {
                    String preview = banner.length() > 100 ? banner.substring(0, 100) : banner;
                    log.info("[扫描]   Banner: {}", preview.replaceAll("\\s+", " "));
                }
            }
        }
    }

    /**
     * scandbports。
     */
    @Test
    @Order(4)
    @DisplayName("扫描数据库端口 (3306/5432/6379/27017/1433)")
    void scan_db_ports() {
        int[] dbPorts = {3306, 5432, 6379, 27017, 1433, 9200, 2181};
        NmapScanner.ScanResult result = scanner.scanTcpPorts(TARGET, dbPorts);

        log.info("[扫描] {} 数据库端口: 开放={}", TARGET, result.getOpenPorts());
        for (NmapScanner.PortInfo p : result.getPorts()) {
            if (p.getState() == NmapScanner.PortState.OPEN) {
                log.info("[扫描]   ✓ {} ({})", p.getPort(), p.getServiceName());
            }
        }
    }

    /**
     * scanadminports。
     */
    @Test
    @Order(5)
    @DisplayName("扫描运维端口 (22/3389/5900)")
    void scan_admin_ports() {
        int[] adminPorts = {22, 23, 3389, 5900, 5901, 2222};
        NmapScanner.ScanResult result = scanner.scanTcpPorts(TARGET, adminPorts);

        log.info("[扫描] {} 运维端口: 开放={}", TARGET, result.getOpenPorts());
        for (NmapScanner.PortInfo p : result.getPorts()) {
            if (p.getState() == NmapScanner.PortState.OPEN) {
                log.info("[扫描]   ✓ {} ({})", p.getPort(), p.getServiceName());
            }
        }
    }

    /**
     * detectos。
     */
    @Test
    @Order(6)
    @DisplayName("OS 指纹识别")
    void detect_os() {
        NmapScanner.OsInfo os = scanner.detectOs(TARGET);
        log.info("[扫描] {} OS: name={} family={} accuracy={}",
                TARGET, os.getName(), os.getFamily(), os.getAccuracy());
    }

    /**
     * 解析hostname。
     */
    @Test
    @Order(7)
    @DisplayName("DNS 解析")
    void resolve_hostname() {
        String ip = RustNmapBridge.resolveHostname(TARGET);
        String reverse = RustNmapBridge.reverseDns(TARGET);
        log.info("[扫描] {} 正向解析={} 反向DNS={}", TARGET, ip, reverse);
    }
}
