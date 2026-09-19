package com.chua.common.support.scatter.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * scatter 帧编解码（最小编码，见 {@link ScatterProtocol}）。
 *
 * <p>帧布局：magic(1) type(1) requestId(4) pathLen(1) path(N) payloadLen(4) payload(M)。
 * 支持 REQ/PUSH/RESP/ACK/ELEC 五种类型；payload 为业务序列化数据（JSON 字节）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ScatterFrame {

    private final byte type;
    private final int requestId;
    private final String path;
    private final byte[] payload;

    /**
     * 构造方法，创建 ScatterFrame 实例。
     *
     * @param type 类型，不允许为 null
     * @param requestId 请求ID，不允许为 null
     * @param path 路径，不允许为 null
     * @param payload 方法入参 payload
     */
    public ScatterFrame(byte type, int requestId, String path, byte[] payload) {
        this.type = type;
        this.requestId = requestId;
        this.path = path == null ? "" : path;
        this.payload = payload == null ? new byte[0] : payload;
    }

    /**
     * 获取类型。
     *
     * @return 结果值
     */
    public byte getType() {
        return type;
    }

    /**
     * 获取请求ID。
     *
     * @return 结果数值
     */
    public int getRequestId() {
        return requestId;
    }

    /**
     * 获取路径。
     *
     * @return 结果字符串
     */
    public String getPath() {
        return path;
    }

    /**
     * 获取Payload。
     *
     * @return 结果值
     */
    public byte[] getPayload() {
        return payload;
    }

    /**
     * 编码为字节数组。
     *
     * @return 帧字节
     */
    public byte[] encode() {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        if (pathBytes.length > 255) {
            throw new IllegalArgumentException("path 过长: " + pathBytes.length);
        }
        int total = 1 + 1 + 4 + 1 + pathBytes.length + 4 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.put(ScatterProtocol.MAGIC);
        buf.put(type);
        buf.putInt(requestId);
        buf.put((byte) pathBytes.length);
        buf.put(pathBytes);
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    /**
     * 解码字节数组为帧。
     *
     * @param data 帧字节
     * @return 帧对象
     */
    public static ScatterFrame decode(byte[] data) {
        if (data == null || data.length < 11) {
            throw new IllegalArgumentException("帧过短: " + (data == null ? 0 : data.length));
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte magic = buf.get();
        if (magic != ScatterProtocol.MAGIC) {
            throw new IllegalArgumentException("帧魔数错误: 0x" + Integer.toHexString(magic & 0xff));
        }
        byte type = buf.get();
        int requestId = buf.getInt();
        int pathLen = buf.get() & 0xff;
        if (buf.remaining() < pathLen + 4) {
            throw new IllegalArgumentException("帧 path 长度越界");
        }
        byte[] pathBytes = new byte[pathLen];
        buf.get(pathBytes);
        String path = new String(pathBytes, StandardCharsets.UTF_8);
        int payloadLen = buf.getInt();
        if (buf.remaining() < payloadLen) {
            throw new IllegalArgumentException("帧 payload 长度越界");
        }
        byte[] payload = new byte[payloadLen];
        buf.get(payload);
        return new ScatterFrame(type, requestId, path, payload);
    }

    @Override
    public String toString() {
        return "ScatterFrame{type=" + type + ", requestId=" + requestId
                + ", path='" + path + "', payloadLen=" + payload.length + '}';
    }
}
