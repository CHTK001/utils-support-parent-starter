package com.chua.network.support.tshark;

import com.chua.common.support.lang.algorithm.crypto.Hex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * tshark {@code -T json -x} 输出的字段取值工具。
 *
 * <p>tshark 的 JSON 输出里，每个字段既可能是纯字符串，也可能是
 * {@code ["十六进制串", "偏移:长度"]} 形式的数组（{@code -x} 模式）。
 * 本类把这两种形态统一收敛成字节数组，并按“应用层载荷优先”的顺序取字节，
 * 使 {@link com.chua.common.support.network.protocol.ProtocolRestorer}
 * 收到的 {@code rawData[0]} 始终是它所理解的协议起始字节。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TsharkFields {

    /**
     * 应用层载荷字段，按优先级排列：先传输层载荷，再通用 data 层，
     * 最后才退回整帧（无传输层的 ARP/ICMP/裸帧场景）
     */
    private static final List<String> PAYLOAD_KEYS = List.of(
            "tcp.payload", "tcp_payload",
            "udp.payload", "udp_payload",
            "http.file_data", "http_file_data",
            "data.data", "data_data"
    );

    /**
     * 整帧兜底字段
     */
    private static final List<String> FRAME_KEYS = List.of(
            "frame_raw", "frame.raw_data", "frame.data"
    );

    /**
     * 禁止实例化
     */
    private TsharkFields() {
    }

    /**
     * 取出数据包的应用层载荷字节。
     *
     * <p>存在传输层载荷时返回传输层载荷（还原器据此从自己协议的第 0 字节解析）；
     * 无传输层时退回整帧字节。</p>
     *
     * @param layers tshark JSON 的 {@code _source.layers} 节点
     * @return 载荷字节，无法提取返回 {@code null}
     */
    public static byte[] payload(Map<String, Object> layers) {
        if (layers == null || layers.isEmpty()) {
            return null;
        }
        for (String key : PAYLOAD_KEYS) {
            byte[] decoded = decode(findDeep(layers, key));
            if (decoded != null && decoded.length > 0) {
                return decoded;
            }
        }
        for (String key : FRAME_KEYS) {
            byte[] decoded = decode(findDeep(layers, key));
            if (decoded != null && decoded.length > 0) {
                return decoded;
            }
        }
        return null;
    }

    /**
     * 取出指定协议层的原始字节。
     *
     * <p>链路层/网络层还原器（ARP、ICMP 等）需要的不是传输层载荷，
     * 用本方法按层名直接取该层的 {@code *_raw} 字节。</p>
     *
     * @param layers    {@code _source.layers} 节点
     * @param layerName 层名，如 {@code arp}、{@code icmp}
     * @return 该层字节，无法提取返回空数组
     */
    public static byte[] layerBytes(Map<String, Object> layers, String layerName) {
        if (layers == null || layerName == null) {
            return new byte[0];
        }
        Object layerNode = layers.get(layerName);
        Map<?, ?> scope = layerNode instanceof Map<?, ?> map ? map : layers;
        for (String key : new String[]{layerName + "_raw", layerName + ".data",
                layerName + "_data", layerName + ".raw_data"}) {
            byte[] decoded = decode(scope.get(key));
            if (decoded != null && decoded.length > 0) {
                return decoded;
            }
        }
        // 退回全局查找，兼容嵌套层级
        for (String key : new String[]{layerName + "_raw", layerName + ".data"}) {
            byte[] decoded = decode(findDeep(layers, key));
            if (decoded != null && decoded.length > 0) {
                return decoded;
            }
        }
        return new byte[0];
    }

    /**
     * 把 tshark 字段值解码为字节数组。
     *
     * @param value 字符串、{@code ["十六进制", "偏移:长度"]} 数组或字节数组
     * @return 解码结果，无法解码返回 {@code null}
     */
    public static byte[] decode(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : decode(list.get(0));
        }
        if (value instanceof Map<?, ?> chunks) {
            return decodeChunks(chunks);
        }
        if (value instanceof String text && !text.isEmpty()) {
            String hex = toHex(text);
            if (hex == null || hex.isEmpty()) {
                return null;
            }
            try {
                return Hex.decodeHex(hex);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 解码按偏移分块的十六进制转储。
     *
     * <p>{@code tshark -x} 会把长字节串拆成多块，键为块起始偏移，需按键升序拼接，
     * 否则拼接顺序随映射实现而变，解码出的载荷是乱序的。</p>
     *
     * @param chunks 偏移量 -&gt; 该块十六进制串
     * @return 拼接解码后的字节，无法解码返回 {@code null}
     */
    private static byte[] decodeChunks(Map<?, ?> chunks) {
        if (chunks.isEmpty()) {
            return null;
        }
        List<String> keys = new ArrayList<>(chunks.size());
        for (Object key : chunks.keySet()) {
            keys.add(String.valueOf(key));
        }
        keys.sort(Comparator.naturalOrder());
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            Object chunk = chunks.get(key);
            if (chunk instanceof String text) {
                sb.append(text);
            } else if (chunk instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof String text) {
                sb.append(text);
            }
        }
        return decode(sb.toString());
    }

    /**
     * 校验并归一化十六进制串。
     *
     * <p>tshark 按输出选项不同使用 {@code ":"} 或空格分隔字节。出现任何其他字符，
     * 或十六进制位数为奇数，说明该字段不是十六进制转储（例如 {@code dns.txt} 的文本值），
     * 返回 {@code null} 交由调用方回退到下一个候选字段。</p>
     *
     * @param text 原始字符串
     * @return 纯十六进制字符，非法返回 {@code null}
     */
    private static String toHex(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.digit(c, 16) >= 0) {
                sb.append(c);
                continue;
            }
            if (c == ':' || c == ' ' || c == '-' || c == '\r' || c == '\n' || c == '\t') {
                continue;
            }
            return null;
        }
        return sb.length() % 2 == 0 ? sb.toString() : null;
    }

    /**
     * 深度查找指定键（含嵌套层）。
     *
     * @param map 起始映射
     * @param key 待查找的键
     * @return 找到的值，未找到返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public static Object findDeep(Map<String, Object> map, String key) {
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
}
