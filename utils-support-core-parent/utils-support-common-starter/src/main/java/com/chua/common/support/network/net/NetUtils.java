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
 */
public final class NetUtils {

    private static final String LOCAL_HOST_CACHE;
    private static final String ANY_HOST = "0.0.0.0";
    private static final String LOCALHOST = "127.0.0.1";
    private static final String LOCALHOST_IPV6 = "0:0:0:0:0:0:0:1";

    static {
        String host = resolveLocalHost();
        LOCAL_HOST_CACHE = host;
    }

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
