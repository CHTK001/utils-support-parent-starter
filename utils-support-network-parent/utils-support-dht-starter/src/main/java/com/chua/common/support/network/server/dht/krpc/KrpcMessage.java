package com.chua.common.support.network.server.dht.krpc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KRPC 协议消息模型。
 * <p>
 * 遵循 BitTorrent KRPC 协议格式，支持 query（y='q'）、response（y='r'）和 error（y='e'）三种类型。
 * 用于与标准 BitTorrent DHT 网络进行兼容的 RPC 通信。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KrpcMessage {

    /**
     * 事务 ID
     */
    public String t;

    /**
     * 消息类型：'q' 查询，'r' 响应，'e' 错误
     */
    public char y;

    /**
     * 查询类型（仅 y='q' 时有值，如 "ping"、"find_node"）
     */
    public String q;

    /**
     * 查询参数（仅 y='q' 时有值）
     */
    public Map<String, Object> a;

    /**
     * 响应数据（仅 y='r' 时有值）
     */
    public Map<String, Object> r;

    /**
     * 错误信息 [错误码, 错误描述]（仅 y='e' 时有值）
     */
    public List<Long> e;

    /**
     * 解析 Bencode 编码的 KRPC 消息。
     *
     * @param data Bencode 字节数组
     * @return KrpcMessage 实例
     */
    public static KrpcMessage parse(byte[] data) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) BencodeCodec.decode(data);
        KrpcMessage msg = new KrpcMessage();
        msg.t = bytesOrString(map.get("t"));
        String yStr = bytesOrString(map.get("y"));
        msg.y = yStr != null && yStr.length() > 0 ? yStr.charAt(0) : '?';
        if (msg.y == 'q') {
            msg.q = bytesOrString(map.get("q"));
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) map.get("a");
            msg.a = args;
        } else if (msg.y == 'r') {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = (Map<String, Object>) map.get("r");
            msg.r = resp;
        } else if (msg.y == 'e') {
            @SuppressWarnings("unchecked")
            List<Object> err = (List<Object>) map.get("e");
            msg.e = new ArrayList<>();
            if (err != null) {
                for (Object o : err) {
                    msg.e.add(o instanceof Long ? (Long) o : Long.parseLong(o.toString()));
                }
            }
        }
        return msg;
    }

    /**
     * 编码为 Bencode 格式字节数组。
     *
     * @return Bencode 字节数组
     */
    public byte[] encode() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("t", t != null ? t : "00");
        map.put("y", String.valueOf(y));
        if (y == 'q') {
            map.put("q", q);
            map.put("a", a != null ? a : new LinkedHashMap<>());
        } else if (y == 'r') {
            map.put("r", r != null ? r : new LinkedHashMap<>());
        } else if (y == 'e') {
            map.put("e", e != null ? e : Arrays.asList(201L, "Generic Error"));
        }
        return BencodeCodec.encode(map);
    }

    /**
     * 获取字符串类型的参数值。
     *
     * @param key 参数名
     * @return 参数字符串值
     */
    public String getString(String key) {
        return bytesOrString(get(key));
    }

    /**
     * 获取字节数组类型的参数值。
     *
     * @param key 参数名
     * @return 参数字节数组
     */
    public byte[] getBytes(String key) {
        Object v = get(key);
        if (v instanceof String) {
            return ((String) v).getBytes(StandardCharsets.ISO_8859_1);
        }
        return (byte[]) v;
    }

    /**
     * 获取长整型参数值。
     *
     * @param key 参数名
     * @return 长整型值
     */
    public Long getLong(String key) {
        Object v = get(key);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    /**
     * 根据消息类型获取参数或响应中的值。
     *
     * @param key 参数名
     * @return 值对象
     */
    private Object get(String key) {
        Map<String, Object> m = y == 'q' ? a : r;
        return m != null ? m.get(key) : null;
    }

    /**
     * 设置参数值。
     *
     * @param key   参数名
     * @param value 参数值
     */
    public void put(String key, Object value) {
        Map<String, Object> m = y == 'q' ? a : r;
        if (m == null) {
            m = new LinkedHashMap<>();
            if (y == 'q') {
                a = m;
            } else {
                r = m;
            }
        }
        m.put(key, value);
    }

    /**
     * 将对象转换为字符串（支持 String 和 byte[] 两种存储格式）。
     *
     * @param v 值对象
     * @return 字符串表示
     */
    static String bytesOrString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof byte[]) {
            return new String((byte[]) v, StandardCharsets.ISO_8859_1);
        }
        return v.toString();
    }
}
