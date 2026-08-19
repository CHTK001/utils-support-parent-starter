package com.chua.common.support.scatter;

import com.chua.common.support.utils.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seed 节点地址值对象。
 * <p>统一解析 host 或 host:port 格式，支持 IPv4 与 IPv6（如 [::1]:19001）。</p>
 *
 * @since 4.0.0.42
 */
public final class SeedAddress {

    /**
     * IPv6 带端口匹配： [host]:port
     */
    private static final Pattern IPV6_WITH_PORT = Pattern.compile("^\\[([^]]+)](?::(\\d+))?$");

    /**
     * host:port 匹配
     */
    private static final Pattern HOST_WITH_PORT = Pattern.compile("^(.+):(\\d+)$");

    /**
     * 主机地址
     */
    private final String host;

    /**
     * 端口，未指定时为 -1
     */
    private final int port;

    /**
     * 是否显式指定了端口
     */
    private final boolean portSpecified;

    /**
     * 构造地址。
     *
     * @param host          主机地址
     * @param port          端口
     * @param portSpecified 是否显式指定端口
     */
    private SeedAddress(String host, int port, boolean portSpecified) {
        this.host = host;
        this.port = port;
        this.portSpecified = portSpecified;
    }

    /**
     * 解析地址字符串。
     *
     * @param address 地址，支持 host、host:port、[ipv6]、[ipv6]:port
     * @return 地址对象，无法解析时返回 null
     */
    public static SeedAddress parse(String address) {
        if (StringUtils.isBlank(address)) {
            return null;
        }
        String trimmed = address.trim();

        // IPv6 格式：[::1]:port
        Matcher ipv6 = IPV6_WITH_PORT.matcher(trimmed);
        if (ipv6.matches()) {
            String host = ipv6.group(1);
            String portStr = ipv6.group(2);
            if (portStr != null) {
                return new SeedAddress(host, Integer.parseInt(portStr), true);
            }
            return new SeedAddress(host, -1, false);
        }

        // 纯 IPv6（无端口）已经包含多个冒号
        if (countColons(trimmed) >= 2) {
            return new SeedAddress(trimmed, -1, false);
        }

        // host:port
        Matcher hostPort = HOST_WITH_PORT.matcher(trimmed);
        if (hostPort.matches()) {
            String host = hostPort.group(1);
            // 空 host 或已含冒号视为无效（如 ":8080"、":80:8080"）
            if (host.isEmpty() || host.contains(":")) {
                return null;
            }
            int port;
            try {
                port = Integer.parseInt(hostPort.group(2));
            } catch (NumberFormatException e) {
                return null;
            }
            return new SeedAddress(host, port, true);
        }

        // 纯 host
        if (trimmed.startsWith(":") || trimmed.endsWith(":")) {
            return null;
        }
        return new SeedAddress(trimmed, -1, false);
    }

    /**
     * 统计冒号数量。
     *
     * @param s 字符串
     * @return 冒号数量
     */
    private static int countColons(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ':') {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取有效端口，未指定时使用默认端口。
     *
     * @param defaultPort 默认端口
     * @return 有效端口
     */
    public int effectivePort(int defaultPort) {
        return portSpecified ? port : defaultPort;
    }

    /**
     * 获取主机地址。
     *
     * @return host
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取端口。
     *
     * @return port，未指定时为 -1
     */
    public int getPort() {
        return port;
    }

    /**
     * 是否显式指定了端口。
     *
     * @return true 指定
     */
    public boolean isPortSpecified() {
        return portSpecified;
    }

    /**
     * 生成节点ID。
     *
     * @param defaultPort 默认端口
     * @return host:port
     */
    public String nodeId(int defaultPort) {
        return host + ":" + effectivePort(defaultPort);
    }

    @Override
    public String toString() {
        return portSpecified ? host + ":" + port : host;
    }
}
