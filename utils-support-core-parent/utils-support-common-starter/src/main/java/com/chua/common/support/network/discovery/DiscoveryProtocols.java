// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.discovery;

import com.chua.common.support.utils.StringUtils;

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * registry-hub / registry-client 共用的地址解析与传输协议判定工具。
 *
 * <p>地址格式：{@code [protocol://]host[:port]}。带 scheme 时按 scheme 指定传输协议
 * （ws/wss/websocket 归一为 SPI 实现名 {@code websocket}）；不带 scheme 时按目标地址
 * 自动选择默认协议，即 tcp/udp 双协议自动切换：</p>
 * <ul>
 *     <li>局域网地址（回环、私网 IPv4 10/8、172.16/12、192.168/16、链路本地、
 *     IPv6 唯一本地 fc00::/7、通配绑定 0.0.0.0/::、可解析为上述地址的主机名）
 *     → 默认 {@code udp}；</li>
 *     <li>其余（公网 IP、解析失败的主机名）→ 默认 {@code tcp}。</li>
 * </ul>
 *
 * <p>显式 scheme 优先级最高，可用于强制切换协议；hub 与 client 对同一地址字符串
 * 会得到相同的默认协议，两端无需额外配置即可保持一致。注意：hub 侧通配绑定
 * （0.0.0.0）按局域网部署口径默认 udp，若客户端经公网地址接入，请两端显式
 * 指定相同 scheme。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public final class DiscoveryProtocols {

    /**
     * 传输协议：TCP（公网/跨网段默认）
     */
    public static final String TCP = "tcp";

    /**
     * 传输协议：UDP（局域网默认）
     */
    public static final String UDP = "udp";

    /**
     * 传输协议：WebSocket（ws/wss/websocket 统一后的 SPI 实现名）
     */
    public static final String WEBSOCKET = "websocket";

    /**
     * 工具类，禁止实例化。
     */
    private DiscoveryProtocols() {
    }

    /**
     * 解析注册中心地址为协议、主机与端口。
     *
     * <p>地址形如 {@code [protocol://]host[:port]}，支持 IPv6 括号写法
     * {@code [::1]:9600}；未携带 scheme 时按 {@link #defaultProtocolFor(String)}
     * 依据主机地址自动选择默认协议（局域网 udp、其余 tcp）。</p>
     *
     * @param address     地址字符串，允许为空（空时全部取默认值）
     * @param defaultHost 默认主机（address 未给出主机部分时使用）
     * @param defaultPort 默认端口（address 未给出端口部分时使用）
     * @return 解析结果，不允许为 null
     */
    public static ParsedAddress parse(String address, String defaultHost, int defaultPort) {
        String scheme = null;
        String host = defaultHost;
        int port = defaultPort;
        if (StringUtils.isNotBlank(address)) {
            String rest = address.trim();
            int schemeIdx = rest.indexOf("://");
            if (schemeIdx > 0) {
                scheme = rest.substring(0, schemeIdx);
                rest = rest.substring(schemeIdx + 3);
            }
            int slashIdx = rest.indexOf('/');
            if (slashIdx >= 0) {
                rest = rest.substring(0, slashIdx);
            }
            if (rest.startsWith("[")) {
                // IPv6 形如 [::1]:9600
                int end = rest.indexOf(']');
                if (end > 0) {
                    host = rest.substring(1, end);
                    int colon = rest.indexOf(':', end);
                    if (colon > 0) {
                        port = Integer.parseInt(rest.substring(colon + 1));
                    }
                } else {
                    host = rest;
                }
            } else {
                int colon = rest.lastIndexOf(':');
                if (colon > 0) {
                    host = rest.substring(0, colon);
                    port = Integer.parseInt(rest.substring(colon + 1));
                } else {
                    host = rest;
                }
            }
        }
        String protocol = scheme != null ? scheme : defaultProtocolFor(host);
        return new ParsedAddress(scheme, normalizeProtocol(protocol), host, port);
    }

    /**
     * 归一化协议名：ws/wss/websocket 统一为 SPI 实现名 {@link #WEBSOCKET}，其余原样返回。
     *
     * @param protocol 协议名，允许为 null
     * @return 归一化后的协议名
     */
    public static String normalizeProtocol(String protocol) {
        if (protocol == null) {
            return null;
        }
        if ("ws".equalsIgnoreCase(protocol) || "wss".equalsIgnoreCase(protocol)
                || "websocket".equalsIgnoreCase(protocol)) {
            return WEBSOCKET;
        }
        return protocol;
    }

    /**
     * 按目标地址选择默认传输协议（tcp/udp 双协议自动切换）：
     * 局域网地址默认 {@code udp}，其余默认 {@code tcp}。
     *
     * @param host 目标主机地址或主机名
     * @return 默认传输协议名
     */
    public static String defaultProtocolFor(String host) {
        return isLanAddress(host) ? UDP : TCP;
    }

    /**
     * 判断目标地址是否局域网地址。
     *
     * <p>覆盖：回环地址、IPv4 私网段（10/8、172.16/12、192.168/16）、链路本地地址、
     * IPv6 唯一本地地址（fc00::/7）与通配地址（0.0.0.0/::，按局域网部署口径）。
     * 主机名会做一次 DNS 解析后判定；解析失败按公网（非局域网）处理。</p>
     *
     * @param host 目标主机地址或主机名，空视为本机/局域网
     * @return 属于局域网地址返回 true
     */
    public static boolean isLanAddress(String host) {
        if (StringUtils.isBlank(host)) {
            // 未指定目标：按本机/局域网部署口径
            return true;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(host.trim());
        } catch (Exception e) {
            // 域名解析失败：无法判定属于局域网，按公网（tcp）口径
            return false;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        if (address instanceof Inet6Address) {
            // isSiteLocalAddress 只覆盖已废弃的 fec0::/10，这里补上唯一本地地址 fc00::/7
            byte[] bytes = address.getAddress();
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    /**
     * 地址解析结果。
     *
     * @param scheme   地址中的原始协议 scheme（如 {@code ws} / {@code wss}），未携带时为 {@code null}
     * @param protocol 归一化后的传输协议名（SPI 实现名）
     * @param host     主机地址
     * @param port     端口
     */
    public record ParsedAddress(String scheme, String protocol, String host, int port) {
    }
}
