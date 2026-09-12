package com.chua.common.support.scatter;

import java.net.InetSocketAddress;

/**
 * seed 地址解析：支持 {@code host:port} 或 {@code host}（端口缺省时回落到节点端口）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SeedAddress {

    private final String host;
    private final int port;

    public SeedAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /**
     * 解析 seed 地址字符串。
     *
     * @param address 如 "192.168.1.10:19000" 或 "192.168.1.10"
     * @return seed 地址，格式非法返回 null
     */
    public static SeedAddress parse(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.startsWith("tcp://") || trimmed.startsWith("udp://")) {
            trimmed = trimmed.substring(trimmed.indexOf("://") + 3);
        }
        int idx = trimmed.lastIndexOf(':');
        if (idx < 0) {
            return new SeedAddress(trimmed, 0);
        }
        String host = trimmed.substring(0, idx);
        String portStr = trimmed.substring(idx + 1);
        if (host.isEmpty()) {
            return null;
        }
        try {
            return new SeedAddress(host, Integer.parseInt(portStr));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取有效端口：显式端口 > 0 用之，否则回落到节点端口。
     *
     * @param fallbackPort 节点端口
     * @return 有效端口
     */
    public int effectivePort(int fallbackPort) {
        return port > 0 ? port : fallbackPort;
    }

    public InetSocketAddress toSocketAddress(int fallbackPort) {
        return new InetSocketAddress(host, effectivePort(fallbackPort));
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
