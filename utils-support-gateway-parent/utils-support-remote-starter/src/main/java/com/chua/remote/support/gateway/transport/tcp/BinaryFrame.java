package com.chua.remote.support.gateway.transport.tcp;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Agent TCP 二进制帧（经 {@link AgentTcpFrameDecoder} 解码后）
 * <p>解析后的内部负载格式:
 * <pre>
 * [1B frameType][2B sidLen][sid...][2B w][2B h][1B keyFrame][encoded data]
 * </pre>
 * frameType: 0xDF=H264, 0xDE=JPEG
 * </p>
 *
 * @author CH
 */
@Data
@AllArgsConstructor
public class BinaryFrame {
    /** 原始负载数据 */
    /**
     * 载荷
     */
    private final byte[] payload;

    /** H.264 帧类型标记 */
    public static final byte TYPE_H264  = (byte)0xDF;
    /** JPEG 帧类型标记 */
    public static final byte TYPE_JPEG  = (byte)0xDE;

    /** 获取帧类型（H264/JPEG） */
    public byte getFrameType() {
        return payload[0];
    }

    /** 获取会话 ID */
    public String getSessionId() {
        int sidLen = ((payload[1] & 0xFF) << 8) | (payload[2] & 0xFF);
        return new String(payload, 3, sidLen, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 获取帧宽度 */
    public int getWidth() {
        int off = 3 + getSidLen();
        return ((payload[off] & 0xFF) << 8) | (payload[off + 1] & 0xFF);
    }

    /** 获取帧高度 */
    public int getHeight() {
        int off = 3 + getSidLen() + 2;
        return ((payload[off] & 0xFF) << 8) | (payload[off + 1] & 0xFF);
    }

    /** 是否为关键帧 */
    public boolean isKeyFrame() {
        int off = 3 + getSidLen() + 2 + 2;
        return payload[off] == 1;
    }

    /** 获取编码数据（不含任何 header） */
    public byte[] getEncodedData() {
        int off = 3 + getSidLen() + 2 + 2 + 1;
        byte[] data = new byte[payload.length - off];
        System.arraycopy(payload, off, data, 0, data.length);
        return data;
    }

    /** 获取 SID 长度（内部工具方法） */
    private int getSidLen() {
        return ((payload[1] & 0xFF) << 8) | (payload[2] & 0xFF);
    }
}
