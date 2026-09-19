package com.chua.network.support.tshark.restorer;

import com.chua.common.support.network.protocol.ProtocolRestorer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 协议还原器抽象基类。
 *
 * <p>封装 {@link ProtocolRestorer} 的样板方法，提供：
 * <ul>
 *   <li>{@link #bytes(Map)} — 安全提取原始字节数组</li>
 *   <li>{@link #string(Map)} — 将字节数组按 UTF-8 转字符串</li>
 *   <li>{@link #toText(byte[])} — 将字节数组转可打印 ASCII（含中文）</li>
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
     * @return 字节数组，若不存在返回 空
     */
    protected static byte[] bytes(Map<String, Object> protocolInfo) {
        if (protocolInfo == null) {
            return new byte[0];
        }
        Object data = protocolInfo.get("rawData");
        if (data instanceof byte[] bytes) {
            return bytes;
        }
        Object frameData = protocolInfo.get("frame.data");
        if (frameData instanceof byte[] frameBytes) {
            return frameBytes;
        }
        return new byte[0];
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
