package com.chua.common.support.network.discovery.peermesh;

import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 网卡选择器：优先使用配置 IP，否则自动选择私网 IPv4 地址。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class InterfaceSelector {


    /**
     * 默认构造函数。
     */
    public InterfaceSelector() {
    }

    /**
     * 选择合适的本地 IP 地址。
     *
     * @param bindIp        配置的绑定 IP（可为 null）
     * @param bindInterface 配置的绑定网卡名称（可为 null）
     * @return 选中的 IP 地址字符串
     * @throws SocketException 网络异常
     * @throws IllegalStateException 无法找到合适的 IP
     */
    public String select(String bindIp, String bindInterface) throws SocketException {
        // 1. 若指定了 bindIp，直接返回
        if (bindIp != null && !bindIp.isBlank()) {
            return bindIp;
        }

        // 2. 若指定了 bindInterface，从该网卡中寻找私网 IPv4
        if (bindInterface != null && !bindInterface.isBlank()) {
            NetworkInterface nif = findInterfaceByName(bindInterface);
            if (nif != null) {
                String ip = selectPrivateIPv4(nif);
                if (ip != null) {
                    return ip;
                }
                throw new IllegalStateException("在网卡 " + bindInterface + " 上未找到私网 IPv4 地址");
            }
            throw new IllegalStateException("未找到指定网卡: " + bindInterface);
        }

        // 3. 遍历所有网卡，寻找私网 IPv4
        Enumeration<NetworkInterface> allIfaces = NetworkInterface.getNetworkInterfaces();
        if (allIfaces == null) {
            throw new IllegalStateException("系统无可用网络接口");
        }
        List<String> privateIps = new ArrayList<>();
        for (NetworkInterface nif : Collections.list(allIfaces)) {
            if (nif.isLoopback() || !nif.isUp()) {
                continue;
            }
            String ip = selectPrivateIPv4(nif);
            if (ip != null) {
                privateIps.add(ip);
            }
        }
        if (!privateIps.isEmpty()) {
            log.debug("找到私网 IPv4 地址集合: {}", privateIps);
            return privateIps.get(0);
        }

        // 4. 退回首个可用的非环回 IPv4（即使公网）
        Enumeration<NetworkInterface> allIfaces2 = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface nif : Collections.list(allIfaces2)) {
            if (nif.isLoopback() || !nif.isUp()) {
                continue;
            }
            Enumeration<InetAddress> addrs = nif.getInetAddresses();
            for (InetAddress addr : Collections.list(addrs)) {
                if (addr instanceof Inet4Address) {
                    log.warn("未找到私网 IPv4，回退到公网地址: {}", addr.getHostAddress());
                    return addr.getHostAddress();
                }
            }
        }

        throw new IllegalStateException("未找到任何 IPv4 地址");
    }

    /**
     * 根据名称查找网卡（不区分大小写）。
     *
     * @param name 网卡名称
     * @return NetworkInterface 或 null
     * @throws SocketException 网络异常
     */
    private NetworkInterface findInterfaceByName(String name) throws SocketException {
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        if (all == null) {
            return null;
        }
        for (NetworkInterface nif : Collections.list(all)) {
            if (name.equalsIgnoreCase(nif.getName()) || name.equalsIgnoreCase(nif.getDisplayName())) {
                return nif;
            }
        }
        return null;
    }

    /**
     * 从指定网卡中选择私网 IPv4 地址。
     *
     * @param nif NetworkInterface
     * @return IPv4 地址字符串或 null
     */
    private String selectPrivateIPv4(NetworkInterface nif) {
        try {
            Enumeration<InetAddress> addrs = nif.getInetAddresses();
            for (InetAddress addr : Collections.list(addrs)) {
                if (addr instanceof Inet4Address
                        && !addr.isLoopbackAddress()
                        && isPrivateIPv4(addr)) {
                    return addr.getHostAddress();
                }
            }
        } catch (Exception e) {
            log.debug("忽略网卡 {} 异常: {}", nif.getName(), e.getMessage());
        }
        return null;
    }

    /**
     * 判断是否为私网 IPv4 地址。
     *
     * @param addr InetAddress
     * @return true 表示私网
     */
    private boolean isPrivateIPv4(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b == null || b.length != 4) {
            return false;
        }
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        // 10.0.0.0/8
        if (first == 10) {
            return true;
        }
        // 172.16.0.0/12 (172.16-31)
        if (first == 172 && (second >= 16 && second <= 31)) {
            return true;
        }
        // 192.168.0.0/16
        if (first == 192 && second == 168) {
            return true;
        }
        return false;
    }
}
