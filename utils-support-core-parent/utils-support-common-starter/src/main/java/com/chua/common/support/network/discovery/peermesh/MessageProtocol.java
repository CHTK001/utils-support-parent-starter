package com.chua.common.support.network.discovery.peermesh;

import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * TCP 消息编解码工具类。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MessageProtocol {

    /**
     * 协议魔数
     */
    public static final int MAGIC = 0x50454D48;

    /**
     * 消息类型：心跳
     */
    public static final byte TYPE_HEARTBEAT = 1;

    /**
     * 消息类型：心跳响应
     */
    public static final byte TYPE_PONG = 2;

    /**
     * 消息类型：新节点通知
     */
    public static final byte TYPE_NEW_PEER = 3;

    /**
     * 消息类型：对等端列表
     */
    public static final byte TYPE_PEER_LIST = 4;

    /**
     * 消息类型：确认
     */
    public static final byte TYPE_ACK = 5;

    /**
     * 消息类型：探测（UDP 广播发现）
     */
    public static final byte TYPE_PROBE = 6;

    /**
     * 协议头长度（魔数 4 + 类型 1 + 长度 4 = 9 字节）
     */
    public static final int HEADER_SIZE = 9;

    /**
     * 消息体。
     *
     * @param type 消息类型
     * @param payload 消息负载（JSON 字符串）
     * @return 结果值
     */
    public record PeerMeshMessage(byte type, String payload) {
    }

    /**
     * 编码消息。
     *
     * @param msg 消息对象
     * @return 字节数组
     */
    public static byte[] encode(PeerMeshMessage msg) {
        byte[] payloadBytes = msg.payload() == null
                ? new byte[0]
                : msg.payload().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payloadBytes.length);
        buf.putInt(MAGIC);
        buf.put(msg.type());
        buf.putInt(payloadBytes.length);
        if (payloadBytes.length > 0) {
            buf.put(payloadBytes);
        }
        return buf.array();
    }

    /**
     * 从 ByteBuffer 中尝试解码一条完整消息。
     * 如果数据不足，返回 null 并重置 position 到调用前位置。
     *
     * @param buffer 输入缓冲区
     * @return 解码后的消息，数据不足时返回 null
     */
    public static PeerMeshMessage decode(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return null;
        }
        buffer.mark();
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            log.warn("收到非法协议包，魔数不匹配: 0x{}", Integer.toHexString(magic));
            buffer.reset();
            return null;
        }
        byte type = buffer.get();
        int length = buffer.getInt();
        if (length < 0 || buffer.remaining() < length) {
            buffer.reset();
            return null;
        }
        byte[] payloadBytes = new byte[length];
        buffer.get(payloadBytes);
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        return new PeerMeshMessage(type, payload);
    }

    /**
     * 从 DataInputStream 中读取一条完整消息。
     *
     * @param in 输入流
     * @return 消息对象
     * @throws IOException IO 异常
     */
    public static PeerMeshMessage read(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("非法协议包，魔数不匹配: 0x" + Integer.toHexString(magic));
        }
        byte type = in.readByte();
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("非法负载长度: " + length);
        }
        if (length == 0) {
            return new PeerMeshMessage(type, "");
        }
        byte[] payloadBytes = new byte[length];
        in.readFully(payloadBytes);
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        return new PeerMeshMessage(type, payload);
    }

    /**
     * 将消息写入 DataOutputStream。
     *
     * @param out 输出流
     * @param msg 消息对象
     * @throws IOException IO 异常
     */
    public static void write(DataOutputStream out, PeerMeshMessage msg) throws IOException {
        byte[] payloadBytes = msg.payload() == null
                ? new byte[0]
                : msg.payload().getBytes(StandardCharsets.UTF_8);
        out.writeInt(MAGIC);
        out.writeByte(msg.type());
        out.writeInt(payloadBytes.length);
        if (payloadBytes.length > 0) {
            out.write(payloadBytes);
        }
        out.flush();
    }
}
