package com.chua.common.support.network.net;

import com.chua.common.support.utils.StringUtils;

import java.util.Map;

/**
 * IPv4 / CIDR 规范化与匹配，以及客户端真实 IP 解析。
 *
 * <p>仅支持严格 IPv4：不接受 IPv6、不接受非数字段、不接受超过 255 的段。
 * 规范化统一用于「名单条目」与「请求来源 IP」的比较，避免 {@code 192.168.001.001}
 * 与 {@code 192.168.1.1} 因写法差异被当成两个地址。</p>
 *
 * <p>匹配类方法（{@link #matches}、{@link #matchesEntry}）对非法输入返回
 * {@code false} 而不抛异常：调用方通常是过滤器里的名单判断，
 * 一条脏数据不应该让整个请求失败。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class IpCidrUtils {

    /**
     * IPv4 全掩码
     */
    private static final long IPV4_MASK = 0xFFFFFFFFL;
    /**
     * IPv4 位宽
     */
    private static final int IPV4_BITS = 32;
    /**
     * 取不到客户端 IP 时的占位值
     */
    private static final String UNKNOWN = "unknown";
    /**
     * 客户端 IP 头链，按优先级从高到低
     */
    private static final String[] CLIENT_IP_HEADERS = {
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP"
    };

    private IpCidrUtils() {
    }

    /**
     * 规范化 IP 或 CIDR。
     *
     * <p>含 {@code '/'} 时按 CIDR 处理并落到网络地址（如 {@code 10.1.2.99/24}
     * 规范化为 {@code 10.1.2.0/24}），否则按精确 IPv4 处理。</p>
     *
     * @param value 待规范化的值
     * @return 规范化结果
     * @throws IllegalArgumentException 值为空或格式非法
     */
    public static String canonicalize(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("IP不能为空");
        }
        String normalized = value.trim();
        if (normalized.indexOf('/') >= 0) {
            return canonicalizeCidr(normalized);
        }
        return formatIpv4(parseIpv4(normalized));
    }

    /**
     * 规范化精确 IPv4 地址。
     *
     * @param value 待规范化的地址
     * @return 规范化结果，如 {@code 192.168.001.001} → {@code 192.168.1.1}
     * @throws IllegalArgumentException 值为空或格式非法
     */
    public static String canonicalizeIpv4(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("IP不能为空");
        }
        return formatIpv4(parseIpv4(value.trim()));
    }

    /**
     * 规范化 CIDR，返回网络地址 + 前缀。
     *
     * @param value 待规范化的 CIDR
     * @return 规范化结果，如 {@code 10.1.2.99/24} → {@code 10.1.2.0/24}
     * @throws IllegalArgumentException 值为空、分隔符重复或前缀越界
     */
    public static String canonicalizeCidr(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("CIDR不能为空");
        }
        String normalized = value.trim();
        int separator = normalized.indexOf('/');
        if (separator <= 0 || separator != normalized.lastIndexOf('/') || separator == normalized.length() - 1) {
            throw new IllegalArgumentException("CIDR格式错误");
        }
        long address = parseIpv4(normalized.substring(0, separator));
        int prefix = parsePrefix(normalized.substring(separator + 1));
        long mask = maskOf(prefix);
        return formatIpv4(address & mask) + "/" + prefix;
    }

    /**
     * 判断 IPv4 是否落在 CIDR 网段内。
     *
     * @param candidateIp 待判断地址
     * @param cidr        网段
     * @return 是否命中；任一参数非法时返回 false
     */
    public static boolean matches(String candidateIp, String cidr) {
        try {
            String canonicalCidr = canonicalizeCidr(cidr);
            String canonicalIp = canonicalizeIpv4(candidateIp);
            int separator = canonicalCidr.indexOf('/');
            long address = parseIpv4(canonicalIp);
            long network = parseIpv4(canonicalCidr.substring(0, separator));
            int prefix = parsePrefix(canonicalCidr.substring(separator + 1));
            long mask = maskOf(prefix);
            return (address & mask) == (network & mask);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 判断 IPv4 是否命中名单条目。
     *
     * <p>条目可以是精确 IP 或 CIDR；精确 IP 走规范化比较，
     * 这样 {@code 192.168.001.001} 与 {@code 192.168.1.1} 视为同一条目。</p>
     *
     * @param candidateIp 待判断地址
     * @param entry       名单条目（精确 IP 或 CIDR）
     * @return 是否命中；空条目或非法条目返回 false
     */
    public static boolean matchesEntry(String candidateIp, String entry) {
        if (StringUtils.isBlank(entry)) {
            return false;
        }
        String normalized = entry.trim();
        if (normalized.indexOf('/') >= 0) {
            return matches(candidateIp, normalized);
        }
        try {
            return canonicalizeIpv4(candidateIp).equals(canonicalizeIpv4(normalized));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 解析客户端真实 IP。
     *
     * <p>顺序：{@code X-Forwarded-For} → {@code Proxy-Client-IP} →
     * {@code WL-Proxy-Client-IP} → {@code HTTP_CLIENT_IP} → {@code remoteAddr}；
     * 头值为 {@code unknown}（忽略大小写）时跳过，取到多值时只取逗号前第一段
     * （最靠近客户端的那个）。全部取不到时返回 {@code "unknown"} 而不是 null，
     * 让统计侧不必再判空。</p>
     *
     * @param headers    请求头，可为 null
     * @param remoteAddr 直连地址，可为 null
     * @return 客户端 IP；解析不到时返回 {@code "unknown"}
     */
    public static String resolveClientIp(Map<String, String> headers, String remoteAddr) {
        String ip = firstNonBlank(headers);
        if (StringUtils.isBlank(ip)) {
            ip = remoteAddr;
        }
        if (StringUtils.isBlank(ip)) {
            return UNKNOWN;
        }
        int comma = ip.indexOf(',');
        if (comma >= 0) {
            ip = ip.substring(0, comma);
        }
        ip = ip.trim();
        return ip.isEmpty() || UNKNOWN.equalsIgnoreCase(ip) ? UNKNOWN : ip;
    }

    /**
     * 按头链顺序取第一个有效值。
     *
     * @param headers 请求头
     * @return 第一个非空白且非 unknown 的头值；没有返回 null
     */
    private static String firstNonBlank(Map<String, String> headers) {
        if (null == headers) {
            return null;
        }
        for (String header : CLIENT_IP_HEADERS) {
            String value = headers.get(header);
            if (StringUtils.hasText(value) && !UNKNOWN.equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 前缀长度转掩码。
     *
     * @param prefix 前缀长度
     * @return 掩码
     */
    private static long maskOf(int prefix) {
        return prefix == 0 ? 0L : (IPV4_MASK << (IPV4_BITS - prefix)) & IPV4_MASK;
    }

    /**
     * 解析 IPv4 为数值。
     *
     * @param value 地址
     * @return 数值
     * @throws IllegalArgumentException 格式非法
     */
    private static long parseIpv4(String value) {
        String[] segments = value.split("\\.", -1);
        if (segments.length != 4) {
            throw new IllegalArgumentException("IPv4格式错误");
        }
        long result = 0L;
        for (String segment : segments) {
            int octet = parseOctet(segment);
            result = (result << 8) | octet;
        }
        return result;
    }

    /**
     * 解析单个八位组。
     *
     * @param value 八位组
     * @return 数值
     * @throws IllegalArgumentException 格式非法或越界
     */
    private static int parseOctet(String value) {
        if (null == value || value.isEmpty() || value.length() > 3) {
            throw new IllegalArgumentException("IPv4格式错误");
        }
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current < '0' || current > '9') {
                throw new IllegalArgumentException("IPv4格式错误");
            }
            result = result * 10 + current - '0';
        }
        if (result > 255) {
            throw new IllegalArgumentException("IPv4格式错误");
        }
        return result;
    }

    /**
     * 解析 CIDR 前缀长度。
     *
     * @param value 前缀文本
     * @return 前缀长度
     * @throws IllegalArgumentException 格式非法或越界
     */
    private static int parsePrefix(String value) {
        if (null == value || value.isEmpty() || value.length() > 2) {
            throw new IllegalArgumentException("CIDR前缀长度错误");
        }
        int prefix = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current < '0' || current > '9') {
                throw new IllegalArgumentException("CIDR前缀长度错误");
            }
            prefix = prefix * 10 + current - '0';
        }
        if (prefix < 0 || prefix > IPV4_BITS) {
            throw new IllegalArgumentException("CIDR前缀长度错误");
        }
        return prefix;
    }

    /**
     * 数值转 IPv4 文本。
     *
     * @param value 数值
     * @return 地址文本
     */
    private static String formatIpv4(long value) {
        return ((value >> 24) & 0xFF) + "."
                + ((value >> 16) & 0xFF) + "."
                + ((value >> 8) & 0xFF) + "."
                + (value & 0xFF);
    }
}
