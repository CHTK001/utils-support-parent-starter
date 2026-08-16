package com.chua.common.support.constant;

import com.chua.common.support.utils.DigestUtils;
import com.chua.common.support.lang.algorithm.crypto.Hex;
import com.chua.common.support.utils.IdUtils;
import com.chua.common.support.utils.StringUtils;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * 项目信息工具类，提供获取当前项目的进程ID、本机地址、JDK版本等运行时环境信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Projects {

    /**
     * 是否为 JDK 8
     */
    public static final boolean JDK_8 = getJdkMajorVersion() == 8;

    /**
     * 本机 IPv4 地址的十六进制表示（每段2位，共8位），如 C0A80101<br>
     * 无法获取时默认值为 "00000000"
     */
    static final String ADDRESS;

    /**
     * 生成随机标识签名
     *
     * @return 基于时间ID和进程ID的SHA1十六进制签名
     */
    static String randomIdSign() {
        String randomId = IdUtils.timeId();
        String tempToken = System.getProperty("SERVER_TEMP_TOKEN");
        return Optional.ofNullable(tempToken).orElseGet(() -> Hex.encodeHexString(DigestUtils.sha1Bytes(getPid() + randomId)));
    }

    static {
        InetAddress addr = null;
        try {
            addr = InetAddress.getLocalHost();
        } catch (UnknownHostException ignored) {
        }

        if (null == addr) {
            ADDRESS = "00000000";
        } else {
            String addrStr = addr.getHostAddress();
            if (null == addrStr) {
                ADDRESS = "00000000";
            } else {
                String[] addrArr = addrStr.split("\\.");
                String sb = StringUtils.leftPad(Integer.toHexString(Integer.valueOf(addrArr[0])), 2, "0") +
                        StringUtils.leftPad(Integer.toHexString(Integer.valueOf(addrArr[1])), 2, "0") +
                        StringUtils.leftPad(Integer.toHexString(Integer.valueOf(addrArr[2])), 2, "0") +
                        StringUtils.leftPad(Integer.toHexString(Integer.valueOf(addrArr[3])), 2, "0");

                ADDRESS = sb;
            }
        }

    }
    /**
     * 获取当前进程ID
     *
     * @return 进程ID字符串
     */
    static String getPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return name.split("@")[0];
    }

    /**
     * 获取本机所有非回环、非链路本地地址的网卡IP地址列表
     *
     * @return IP地址列表，可能为空列表
     */
    static List<InetAddress> getAddressList() {
        List<InetAddress> ipList = new ArrayList<>();
        Enumeration<NetworkInterface> networkInterfaces = null;
        try {
            networkInterfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            return ipList;
        }
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface networkInterface = networkInterfaces.nextElement();
            Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress inetAddress = inetAddresses.nextElement();
                if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress()) {
                    // 过滤掉回环地址和链路本地地址，只保留有效的可路由IP
                    ipList.add(inetAddress);
                }
            }
        }

        return ipList;
    }

    public static int getJdkMajorVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            // JDK 8          : 1.8.0_xxx -> 8
            version = version.substring(2, 3);
        } else {
            // JDK 9+: 17.0.1 -> 17
            int dotIndex = version.indexOf('.');
            if (dotIndex != -1) {
                version = version.substring(0, dotIndex);
            }
        }
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    public static CharSequence getUserHomePath() {
        return System.getProperty("user.home");
    }

    /**
     * 获取系统默认字符编码
     *
     * @return 默认字符编码
     */
    public static java.nio.charset.Charset defaultCharset() {
        return java.nio.charset.Charset.defaultCharset();
    }
}
