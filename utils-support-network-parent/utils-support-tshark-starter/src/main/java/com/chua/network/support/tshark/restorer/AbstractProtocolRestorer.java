package com.chua.network.support.tshark.restorer;

import com.chua.common.support.network.protocol.ProtocolRestorer;
import com.chua.network.support.tshark.TsharkFields;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 协议还原器抽象基类。
 *
 * <p>封装 {@link ProtocolRestorer} 的样板方法，提供：
 * <ul>
 *   <li>{@link #bytes(Map)} — 提取传输层载荷字节（优先 {@code rawData}，回退层字段解析）</li>
 *   <li>{@link #layerBytes(Map, String)} — 按层名提取该层原始字节，供链路层/网络层还原器使用</li>
 *   <li>{@link #string(Map, String)} — 安全读取协议信息中的字符串字段</li>
 *   <li>{@link #toText(byte[])} — 将字节数组转可打印 ASCII，控制字符替换为 {@code '.'}</li>
 *   <li>{@link #utf8(byte[])} — 按 UTF-8 解码字节数组</li>
 *   <li>{@link #contains(Map, String)} — 检查 protocolInfo 是否包含指定 layer</li>
 * </ul>
 * 子类仅需实现 {@link #getProtocolName()} 与 {@link #restore(Map, byte[])}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractProtocolRestorer implements ProtocolRestorer {

    /**
     * 从 协议信息 中取出原始字节数组。
     *
     * @param protocolInfo 协议信息
     * @return 字节数组，若不存在返回空数组
     */
    protected static byte[] bytes(Map<String, Object> protocolInfo) {
        if (protocolInfo == null) {
            return new byte[0];
        }
        byte[] direct = TsharkFields.decode(protocolInfo.get("rawData"));
        if (direct != null && direct.length > 0) {
            return direct;
        }
        byte[] payload = TsharkFields.payload(protocolInfo);
        return payload == null ? new byte[0] : payload;
    }

    /**
     * 取出指定协议层的原始字节（链路层/网络层还原器使用）。
     *
     * @param protocolInfo 协议信息
     * @param layerName    层名，如 {@code arp}、{@code icmp}
     * @return 该层字节，无法提取返回空数组
     */
    protected static byte[] layerBytes(Map<String, Object> protocolInfo, String layerName) {
        return TsharkFields.layerBytes(protocolInfo, layerName);
    }

    /**
     * 从 协议信息 安全提取字符串字段。
     *
     * @param protocolInfo 协议信息
     * @param key           字段名
     * @return 字符串值，不存在返回 空
     */
    protected static String string(Map<String, Object> protocolInfo, String key) {
        if (protocolInfo == null) {
            return null;
        }
        Object value = protocolInfo.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 判断 协议信息 是否包含指定 layer（用于 能否restore 短路）。
     *
     * @param protocolInfo 协议信息
     * @param layerName    layer 名称
     * @return true 表示包含
     */
    protected static boolean contains(Map<String, Object> protocolInfo, String layerName) {
        return protocolInfo != null && protocolInfo.containsKey(layerName);
    }

    /**
     * 将字节数组转可读 ASCII 文本：可打印字符原样保留，控制字符替换为 '.'。
     *
     * @param data 原始字节
     * @return 可读字符串
     */
    protected static String toText(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length);
        for (byte b : data) {
            int v = b & 0xff;
            if (v >= 0x20 && v < 0x7f) {
                sb.append((char) v);
            } else if (v == 0x0a || v == 0x0d || v == 0x09) {
                sb.append((char) v);
            } else {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    /**
     * 将字节数组按 UTF-8 解码（用于 HTTP 主体 等场景）。
     *
     * @param data 字节数组
     * @return UTF-8 字符串
     */
    protected static String utf8(byte[] data) {
        if (data == null) {
            return "";
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 默认 能否restore：仅判断 协议信息 包含指定 layer。
     *
     * @param protocolInfo 协议信息
     * @param rawData      原始字节
     * @return true 表示可还原
     */
    @Override
    public boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData) {
        return protocolInfo != null && !protocolInfo.isEmpty();
    }
}
