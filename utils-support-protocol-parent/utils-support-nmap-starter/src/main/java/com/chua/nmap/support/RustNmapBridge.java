package com.chua.nmap.support;

import com.chua.common.support.nativeapi.NativeServices;
import com.chua.common.support.nativeapi.NmapSpi;

/**
 * Nmap 原生库门面。
 *
 * <p>实现由 {@code utils-support-native-nmap} 通过 SPI 注册；本类只依赖
 * {@link NmapSpi} 契约，不再反射 native 类。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class RustNmapBridge {

    private static final String CAPABILITY = "Nmap 扫描能力";

    private RustNmapBridge() {
    }

    static void ensureLoaded() {
        NmapSpi spi = require();
        spi.ensureLoaded();
        if (!spi.isLoaded()) {
            throw new IllegalStateException(CAPABILITY + "不可用：原生库未加载");
        }
    }

    static int scanSingleTcpPort(String host, int port, int timeout) {
        return require().scanSingleTcpPort(host, port, timeout);
    }

    static String scanTcpPortRange(String host, int startPort, int endPort, int timeout, int concurrency) {
        return require().scanTcpPortRange(host, startPort, endPort, timeout, concurrency);
    }

    static String pingHost(String host, int timeout) {
        return require().pingHost(host, timeout);
    }

    static String scanSubnet(String subnet, int timeout, int concurrency) {
        return require().scanSubnet(subnet, timeout, concurrency);
    }

    static String detectService(String host, int port, int timeout) {
        return require().detectService(host, port, timeout);
    }

    private static NmapSpi require() {
        return NativeServices.require(NmapSpi.class, CAPABILITY);
    }
}
