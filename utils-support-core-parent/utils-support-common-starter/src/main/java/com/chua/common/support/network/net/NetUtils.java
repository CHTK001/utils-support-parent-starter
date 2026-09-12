package com.chua.common.support.network.net;

import com.chua.common.support.utils.StringUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
* 网络工具类
*
* @author CH
* @since 4.0.0.42
 */
public final class NetUtils {

    /** Local_host_cache */
    private static final String LOCAL_HOST_CACHE;
    /** Any_host */
    private static final String ANY_HOST = "0.0.0.0";
    /** Localhost */
    private static final String LOCALHOST = "127.0.0.1";
    /** Localhost_ipv6 */
    private static final String LOCALHOST_IPV6 = "0:0:0:0:0:0:0:1";

    static {
        String host = resolveLocalHost();
        LOCAL_HOST_CACHE = host;
    }

    /** 创建 NetUtils 实例 */
    private NetUtils() {
    }

    /**
    * 获取本地主机地址
    *
    * @return 本地主机地址
     */
    public static String getLocalHost() {
        return LOCAL_HOST_CACHE;
    }

    /**
    * 获取公网地址
    *
    * @return 公网地址，获取失败时返回本地地址
     */
    public static String getPublicAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || networkInterface.isVirtual() || !networkInterface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String hostAddress = address.getHostAddress();
                        if (!hostAddress.startsWith("10.") && !hostAddress.startsWith("172.")
                                && !hostAddress.startsWith("192.168.") && !hostAddress.startsWith("127.")) {
                            return hostAddress;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            // ignore
        }
        return getLocalHost();
    }

    /**
    * 判断是否为本地地址
    *
    * @param host 主机地址
    * @return 是否本地地址
     */
    public static boolean isLocalHost(String host) {
        if (StringUtils.isEmpty(host)) {
            return false;
        }
        return LOCALHOST.equals(host)
                || LOCALHOST_IPV6.equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "localhost.localdomain".equalsIgnoreCase(host);
    }

    /**
    * 判断是否为任意地址
    *
    * @param host 主机地址
    * @return 是否任意地址
     */
    public static boolean isAnyHost(String host) {
        return ANY_HOST.equals(host) || "0.0.0.0".equals(host);
    }

    /**
    * 枚举本机全部网卡 IPv4（非回环——多网卡全部返回，供白名单等"命中其一"场景）。
    *
    * @return 本机全部 IPv4 列表（可能为空）
     */
    public static java.util.List<String> getLocalIps() {
        java.util.List<String> ips = new java.util.ArrayList<>();
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String ip = address.getHostAddress();
                        if (ip != null && !ips.contains(ip)) {
                            ips.add(ip);
                        }
                    }
                }
            }
        } catch (SocketException ignored) {
            // 网卡枚举失败——返回空列表
        }
        return ips;
    }

    /**
    * IPv4 地址转 long（用于区间比较）。
    *
    * @param ip IPv4 地址（如 192.168.1.5）
    * @return 数值（非法输入抛出异常）
     */
    public static long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | (Integer.parseInt(parts[i]) & 0xFF);
        }
        return value;
    }

    /**
    * 判断 IP 是否在 [start, end] 区间内（IPv4）。
    *
    * @param ip    待判断 IP
    * @param start 区间起点
    * @param end   区间终点
    * @return 是否在区间内（非法输入返回 false）
     */
    public static boolean ipInRange(String ip, String start, String end) {
        try {
            long value = ipToLong(ip);
            return ipToLong(start) <= value && value <= ipToLong(end);
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析LocalHost */
    private static String resolveLocalHost() {
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            if (localHost != null) {
                return localHost.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return LOCALHOST;
    }
}
