package com.chua.network.support.tshark;

import com.chua.common.support.network.protocol.ProtocolRestorer;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.lang.algorithm.crypto.Hex;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tshark JSON 数据包解析工具。
 *
 * <p>负责将 TShark 命令行输出的 JSON 格式单包数据解析为结构化对象。</p>
 * <p>支持帧层、IP层、传输层协议检测，以及 TCP flags/lifecycle 计算。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class PacketParserService {

    /**
     * JSON 对象映射器（线程安全，可共享）
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 协议还原器 SPI（懒加载）
     */
    private static final List<ProtocolRestorer> RESTORERS;

    static {
        RESTORERS = ServiceProvider.of(ProtocolRestorer.class).collect();
    }

    /**
     * 协议：HTTP
     */
    private static final String PROTOCOL_HTTP = "HTTP";

    /**
     * 协议：TLS
     */
    private static final String PROTOCOL_TLS = "TLS";

    /**
     * 协议：DNS
     */
    private static final String PROTOCOL_DNS = "DNS";

    /**
     * 协议：TCP
     */
    private static final String PROTOCOL_TCP = "TCP";

    /**
     * 协议：UDP
     */
    private static final String PROTOCOL_UDP = "UDP";

    /**
     * 协议：ICMP
     */
    private static final String PROTOCOL_ICMP = "ICMP";

    /**
     * 协议：ARP
     */
    private static final String PROTOCOL_ARP = "ARP";

    /**
     * 协议：其他
     */
    private static final String PROTOCOL_OTHER = "OTHER";

    /**
     * TCP flag：SYN
     */
    private static final String FLAG_SYN = "SYN ";

    /**
     * TCP flag：ACK
     */
    private static final String FLAG_ACK = "ACK ";

    /**
     * TCP flag：FIN
     */
    private static final String FLAG_FIN = "FIN ";

    /**
     * TCP flag：RST
     */
    private static final String FLAG_RST = "RST ";

    /**
     * TCP flag：PSH
     */
    private static final String FLAG_PSH = "PSH ";

    /**
     * TCP flag：URG
     */
    private static final String FLAG_URG = "URG ";

    /**
     * 禁止实例化
     */
    private PacketParserService() {
 // 工具 类
    }

    /**
     * 解析 tshark 单条 JSON 数据为结构化对象。
     *
     * @param jsonLine tshark 输出的单包 JSON 字符串
     * @return 解析后的数据包记录，解析失败返回 空
     */
    public static PacketRecord parse(String jsonLine) {
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(jsonLine, new TypeReference<>() {});
            Map<String, Object> layers = extractLayers(root);
            if (layers == null) {
                return null;
            }

            String sourceIp = extractIp(layers, "src");
            String destinationIp = extractIp(layers, "dst");
            Integer sourcePort = extractPort(layers, "src");
            Integer destinationPort = extractPort(layers, "dst");
            String protocol = detectProtocol(layers);
            Integer packetLength = extractLength(layers);
            String info = buildInfo(sourceIp, sourcePort, destinationIp, destinationPort, protocol, layers);
            String lifecycleJson = buildLifecycleJson(layers);
            String protocolName = detectProtocol(layers);

            return new PacketRecord(
                    sourceIp,
                    destinationIp,
                    sourcePort,
                    destinationPort,
                    protocol,
                    packetLength,
                    info,
                    jsonLine,
                    lifecycleJson,
                    restoreProtocol(layers)
            );
        } catch (Exception e) {
            log.debug("Failed to parse packet: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 协议还原 ====================

    /**
     * 调用 SPI 注册的 {@link ProtocolRestorer} 对单个 数据包 进行协议还原。
     *
     * <p>遍历所有 {@link RESTORERS}，第一个能匹配的还原器负责还原，
     * 还原结果填入 {@link PacketRecord#restoredText()}。</p>
     *
     * @param layers tshark JSON 中 {@code _source.layers} 节点
     * @return 还原后的人类可读文本，无匹配返回 空
     */
    @SuppressWarnings("unchecked")
    private static String restoreProtocol(Map<String, Object> layers) {
        if (RESTORERS.isEmpty() || layers == null || layers.isEmpty()) {
            return null;
        }
        byte[] rawBytes = extractRawBytesFromLayers(layers);
        if (rawBytes == null || rawBytes.length == 0) {
            return null;
        }
        for (ProtocolRestorer restorer : RESTORERS) {
            try {
                if (restorer.canRestore(layers, rawBytes)) {
                    return restorer.restore(layers, rawBytes);
                }
            } catch (Exception e) {
                log.debug("还原器 {} 跳过: {}", restorer.getProtocolName(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * 从 layers 节点中提取真实 raw bytes。
     *
     * <p>优先读取 tshark -T json -x 输出中的 {@code frame_raw} 数组元素，
     * 其次按需尝试其他常见字段名。</p>
     *
     * @param layers layers 节点
     * @return 字节数组，无法提取返回 空
     */
    @SuppressWarnings("unchecked")
    private static byte[] extractRawBytesFromLayers(Map<String, Object> layers) {
        Object frameRaw = findDeep(layers, "frame_raw");
        if (frameRaw instanceof java.util.List<?> list && !list.isEmpty()) {
            Object first = list.getFirst();
            if (first instanceof String hex && !hex.isEmpty()) {
                try {
                    return Hex.decodeHex(hex.replaceAll(":", ""));
                } catch (Exception ignored) {
                }
            }
        }
        for (String key : new String[]{
                "frame.raw_data", "frame.data", "data.data",
                "tcp.payload", "udp.payload", "http.file_data"}) {
            Object value = findDeep(layers, key);
            if (value instanceof String hex && !hex.isEmpty()) {
                try {
                    return Hex.decodeHex(hex.replaceAll(":", ""));
                } catch (Exception ignored) {
                }
            }
            if (value instanceof byte[] bytes) {
                return bytes;
            }
        }
        return null;
    }

    /**
     * 深度查找指定 键。
     *
     * @param map 起始 映射
     * @param key 待查找的 键
     * @return 找到的值，未找到返回 空
     */
    @SuppressWarnings("unchecked")
    private static Object findDeep(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        if (map.containsKey(key)) {
            return map.get(key);
        }
        for (Object value : map.values()) {
            if (value instanceof Map) {
                Object found = findDeep((Map<String, Object>) value, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ==================== 层解析 ====================

    /**
     * 从根JSON中提取 layers 节点。
     *
     * @param root JSON根对象
     * @return layers 节点，缺失返回 空
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractLayers(Map<String, Object> root) {
        Map<String, Object> source = (Map<String, Object>) root.get("_source");
        if (source == null) {
            return null;
        }
        return (Map<String, Object>) source.get("layers");
    }

    /**
     * 从层数据中提取 IP 地址。
     *
     * @param layers 所有层数据
     * @param role   "src" 或 "dst"
     * @return IP 地址字符串
     */
    @SuppressWarnings("unchecked")
    private static String extractIp(Map<String, Object> layers, String role) {
        Map<String, Object> ipv4 = (Map<String, Object>) layers.get("ip");
        Map<String, Object> ipv6 = (Map<String, Object>) layers.get("ipv6");
        Map<String, Object> ipLayer = ipv4 != null ? ipv4 : ipv6;
        if (ipLayer == null) {
            return null;
        }
        String key = ipv4 != null ? "ip." + role : "ipv6." + role;
        return (String) ipLayer.get(key);
    }

    /**
     * 从层数据中提取端口号。
     *
     * @param layers 所有层数据
     * @param role "src" 或 "dst"
     * @return 端口号，不存在返回 空
     */
    @SuppressWarnings("unchecked")
    private static Integer extractPort(Map<String, Object> layers, String role) {
        Map<String, Object> tcp = (Map<String, Object>) layers.get("tcp");
        Map<String, Object> udp = (Map<String, Object>) layers.get("udp");
        Map<String, Object> transportLayer = tcp != null ? tcp : udp;
        if (transportLayer == null) {
            return null;
        }
        String key = tcp != null ? "tcp." + role + "port" : "udp." + role + "port";
        return parseInteger(transportLayer.get(key));
    }

    /**
     * 从帧层提取数据包长度。
     *
     * @param layers 所有层数据
     * @return 长度值，不存在返回 空
     */
    @SuppressWarnings("unchecked")
    private static Integer extractLength(Map<String, Object> layers) {
        Map<String, Object> frame = (Map<String, Object>) layers.get("frame");
        if (frame == null) {
            return null;
        }
        return parseInteger(frame.get("frame.len"));
    }

    // ==================== 协议与元数据构建 ====================

    /**
     * 根据层类型推断协议名称。
     *
     * @param layers 所有层数据
     * @return 协议名称
     */
    private static String detectProtocol(Map<String, Object> layers) {
        if (layers.containsKey("http")) {
            return PROTOCOL_HTTP;
        }
        if (layers.containsKey("tls")) {
            return PROTOCOL_TLS;
        }
        if (layers.containsKey("dns")) {
            return PROTOCOL_DNS;
        }
        if (layers.containsKey("tcp")) {
            return PROTOCOL_TCP;
        }
        if (layers.containsKey("udp")) {
            return PROTOCOL_UDP;
        }
        if (layers.containsKey("icmp")) {
            return PROTOCOL_ICMP;
        }
        if (layers.containsKey("arp")) {
            return PROTOCOL_ARP;
        }
        return PROTOCOL_OTHER;
    }

    /**
     * 构建TCP flags字符串。
     *
     * @param tcp TCP层数据
     * @return flags字符串（空格分隔），无flags返回空字符串
     */
    @SuppressWarnings("unchecked")
    private static String buildTcpFlags(Map<String, Object> tcp) {
        StringBuilder sb = new StringBuilder();
        if ("1".equals(tcp.get("tcp.flags.syn"))) {
            sb.append(FLAG_SYN);
        }
        if ("1".equals(tcp.get("tcp.flags.ack"))) {
            sb.append(FLAG_ACK);
        }
        if ("1".equals(tcp.get("tcp.flags.fin"))) {
            sb.append(FLAG_FIN);
        }
        if ("1".equals(tcp.get("tcp.flags.reset"))) {
            sb.append(FLAG_RST);
        }
        if ("1".equals(tcp.get("tcp.flags.push"))) {
            sb.append(FLAG_PSH);
        }
        if ("1".equals(tcp.get("tcp.flags.urg"))) {
            sb.append(FLAG_URG);
        }
        return sb.toString().trim();
    }

    /**
     * 计算TCP连接生命周期阶段。
     *
     * @param tcp TCP层数据
     * @return 生命周期阶段名称（SYN/SYN-ACK/FIN/RST/ACK/DATA）
     */
    private static String calculateTcpLifecycle(Map<String, Object> tcp) {
        boolean syn = "1".equals(tcp.get("tcp.flags.syn"));
        boolean ack = "1".equals(tcp.get("tcp.flags.ack"));
        boolean fin = "1".equals(tcp.get("tcp.flags.fin"));
        boolean rst = "1".equals(tcp.get("tcp.flags.reset"));

        if (syn && !ack) {
            return "SYN";
        }
        if (syn && ack) {
            return "SYN-ACK";
        }
        if (fin) {
            return "FIN";
        }
        if (rst) {
            return "RST";
        }
        if (ack) {
            return "ACK";
        }
        return "DATA";
    }

    /**
     * 构建数据包摘要信息字符串。
     *
     * @param sourceIp       源IP
     * @param sourcePort     源端口
     * @param destinationIp  目的IP
     * @param destinationPort 目的端口
     * @param protocol       协议名称
     * @param layers         所有层数据
     * @return 摘要字符串
     */
    @SuppressWarnings("unchecked")
    private static String buildInfo(
            String sourceIp, Integer sourcePort,
            String destinationIp, Integer destinationPort,
            String protocol, Map<String, Object> layers) {
        StringBuilder sb = new StringBuilder();
        appendAddress(sb, sourceIp, sourcePort);
        sb.append(" -> ");
        appendAddress(sb, destinationIp, destinationPort);
        sb.append(" [").append(protocol).append("]");
        return sb.toString();
    }

    /**
     * 拼接地址:端口到字符串构建器。
     *
     * @param sb   目标字符串构建器
     * @param ip   IP地址
     * @param port 端口号
     */
    private static void appendAddress(StringBuilder sb, String ip, Integer port) {
        if (ip != null) {
            sb.append(ip);
            if (port != null) {
                sb.append(":").append(port);
            }
        }
    }

    /**
     * 追加HTTP协议信息（方法/URI/状态码）。
     *
     * @param sb     目标字符串构建器
     * @param layers 所有层数据
     */
    @SuppressWarnings("unchecked")
    private static void appendHttpInfo(StringBuilder sb, Map<String, Object> layers) {
        if (!layers.containsKey("http")) {
            return;
        }
        Map<String, Object> http = (Map<String, Object>) layers.get("http");
        if (http == null) {
            return;
        }
        String method = (String) http.get("http.request.method");
        String uri = (String) http.get("http.request.uri");
        if (method != null) {
            sb.append(" ").append(method).append(" ").append(uri);
        }
        String responseCode = (String) http.get("http.response.code");
        if (responseCode != null) {
            sb.append(" ").append(responseCode);
        }
    }

    /**
     * 构建生命周期JSON字符串。
     *
     * @param layers 所有层数据
     * @return JSON字符串，包含tcp_flags和lifecycle字段
     */
    @SuppressWarnings("unchecked")
    private static String buildLifecycleJson(Map<String, Object> layers) {
        if (!layers.containsKey("tcp")) {
            return "{}";
        }
        Map<String, Object> tcp = (Map<String, Object>) layers.get("tcp");
        if (tcp == null) {
            return "{}";
        }
        Map<String, String> lifecycleMap = new LinkedHashMap<>();
        lifecycleMap.put("tcp_flags", buildTcpFlags(tcp));
        lifecycleMap.put("lifecycle", calculateTcpLifecycle(tcp));
        try {
            return OBJECT_MAPPER.writeValueAsString(lifecycleMap);
        } catch (Exception e) {
            log.debug("Failed to build lifecycle JSON: {}", e.getMessage());
            return "{}";
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 安全解析整数字符串。
     *
     * @param value 原始值
     * @return 解析后的整数，解析失败返回 空
     */
    private static Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
