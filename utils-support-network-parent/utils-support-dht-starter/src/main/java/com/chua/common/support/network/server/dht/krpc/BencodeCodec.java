package com.chua.common.support.network.server.dht.krpc;

import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
   * 钻头torrent Bencode 编解码器，基于 {@link Bencode} 三方库。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BencodeCodec {

    /**
     * 底层编解码实例，使用 ISO_8859_1 保证字节串无损往返。
     */
    private static final Bencode INSTANCE = new Bencode(StandardCharsets.ISO_8859_1);

    /**
     * 将 Bencode 编码的字节数组解码为 Java 对象。
     * <p>
     * 根据首字节自动识别类型：字典、列表、整数或字符串。
     * </p>
     *
     * @param data Bencode 编码的字节数组
     * @return 解码后的对象
     */
    @SuppressWarnings("unchecked")
    public static Object decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty bencode data");
        }
        byte b = data[0];
        if (b == 'd') {
            return INSTANCE.decode(data, Type.DICTIONARY);
        }
        if (b == 'l') {
            return INSTANCE.decode(data, Type.LIST);
        }
        if (b == 'i') {
            return INSTANCE.decode(data, Type.NUMBER);
        }
        if (b >= '0' && b <= '9') {
            return INSTANCE.decode(data, Type.STRING);
        }
        throw new IllegalArgumentException("Unknown bencode type: " + (char) b);
    }

    /**
     * 将 Java 对象编码为 Bencode 格式的字节数组。
     *
     * @param obj 要编码的对象
     * @return Bencode 格式的字节数组
     */
    public static byte[] encode(Object obj) {
        if (obj instanceof Map) {
            return INSTANCE.encode((Map<?, ?>) obj);
        }
        if (obj instanceof List) {
            return INSTANCE.encode((List<?>) obj);
        }
        if (obj instanceof Number) {
            return INSTANCE.encode(((Number) obj).longValue());
        }
        if (obj instanceof String) {
            return INSTANCE.encode((String) obj);
        }
        throw new IllegalArgumentException("Cannot bencode type: " + obj.getClass().getName());
    }

    /**
     * 将长整型编码为 Bencode 整数。
     *
     * @param value 整数值
     * @return Bencode 字节数组
     */
    public static byte[] encode(long value) {
        return INSTANCE.encode(value);
    }
}
