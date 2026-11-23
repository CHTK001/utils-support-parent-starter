// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.scatter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * scatter 网络工具：本机 IPv4 地址与网段推断（自动发现用）。
 *
 * <p>自动发现模式下，未显式配置 {@code subnet} 时默认取本机非回环 IPv4 所在网段；
 * 未显式配置 {@code host}/{@code announceHost} 时默认以本机非回环 IPv4 对外宣告。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class ScatterNetworks {

    /**
     * 本机 IPv4 缓存（进程生命周期内不变）
     */
    private static volatile Set<String> cachedLocalIPv4s;

    /**
     * 构造方法，创建 ScatterNetworks 实例。
     */
    private ScatterNetworks() {
    }

    /**
     * 获取本机所有非回环 IPv4 地址（进程生命周期内缓存）。
     *
     * @return IPv4 地址集合，无数据时为空集合
     */
    public static Set<String> localIPv4s() {
        Set<String> cache = cachedLocalIPv4s;
        if (cache != null) {
            return cache;
        }
        Set<String> result = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()
                            && !addr.isAnyLocalAddress()) {
                        result.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            // 网卡枚举失败：返回已收集结果
        }
        cachedLocalIPv4s = Collections.unmodifiableSet(result);
        return cachedLocalIPv4s;
    }

    /**
     * 获取本机首个非回环 IPv4 地址。
     *
     * @return IPv4 地址，无则 null
     */
    public static String localIPv4() {
        return localIPv4s().stream().findFirst().orElse(null);
    }

    /**
     * 推断本机所在 IPv4 网段（如 "192.168.1.0/24"），取首个非回环 IPv4 及其前缀长度。
     *
     * @return CIDR 网段，推断失败返回 null
     */
    public static String localSubnetCidr() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()
                            && !addr.isAnyLocalAddress()) {
                        int prefix = ia.getNetworkPrefixLength();
                        if (prefix <= 0 || prefix > 32) {
                            prefix = 24;
                        }
                        byte[] b = addr.getAddress();
                        int ip = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16)
                                | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
                        int mask = prefix >= 32 ? -1 : ~((1 << (32 - prefix)) - 1);
                        int net = ip & mask;
                        return ((net >>> 24) & 0xff) + "." + ((net >>> 16) & 0xff) + "."
                                + ((net >>> 8) & 0xff) + "." + (net & 0xff) + "/" + prefix;
                    }
                }
            }
        } catch (SocketException e) {
            return null;
        }
        return null;
    }
}
