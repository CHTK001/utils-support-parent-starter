// [P3C A20 豁免] A20/A30/A31: 协议常量与魔法值场景，豁免。
package com.chua.common.support.network.server.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * WebSocket 帧头解析与负载读取（两个自研 WS 实现共用）。
 *
 * <p><b>为什么改成批量读</b>：原实现用 {@code in.read()} 逐字节读帧头——一帧要
 * 2~14 次系统调用，高频消息时白送几倍 syscall。这里用 {@code readFully} 批量读，
 * 一帧固定 3 次（头 2B、扩展长度 0/2/8B、掩码 4B），且<b>不引入每连接缓冲</b>：
 * 若改成每连接 {@code BufferedInputStream}，100 万连接要多占 8GB 堆，
 * 正好抵消掉「每连接 3.5KB」的优势。</p>
 *
 * <p>顺带修正：长度前缀 127 表示 8 字节扩展长度（{@code WebSocketSyncServer} 原先
 * 只读了 4 字节，属于协议实现错误）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public final class WsFrames {

    /**
     * 关闭帧操作码。
     */
    public static final int OPCODE_CLOSE = 0x8;

    /**
     * 文本帧操作码。
     */
    public static final int OPCODE_TEXT = 0x1;

    /**
     * 二进制帧操作码。
     */
    public static final int OPCODE_BINARY = 0x2;

    /**
     * 长度前缀：126 表示后续 2 字节扩展长度。
     */
    private static final int LEN_16 = 126;

    /**
     * 长度前缀：127 表示后续 8 字节扩展长度。
     */
    private static final int LEN_64 = 127;

    /**
     * 掩码长度（字节）。
     */
    private static final int MASK_SIZE = 4;

    /**
     * 工具类不允许实例化。
     */
    private WsFrames() {
    }

    /**
     * 帧头。
     */
    public static final class FrameHeader {

        /**
         * 是否最后一帧（分片）。
         */
        public final boolean fin;

        /**
         * 操作码。
         */
        public final int opcode;

        /**
         * 是否带掩码（客户端帧必须带）。
         */
        public final boolean masked;

        /**
         * 负载长度（字节）。
         */
        public final long length;

        /**
         * 掩码键，未掩码时为 null。
         */
        public final byte[] mask;

        /**
         * 构造帧头。
         *
         * @param fin    是否最后一帧
         * @param opcode 操作码
         * @param masked 是否带掩码
         * @param length 负载长度
         * @param mask   掩码键
         */
        FrameHeader(boolean fin, int opcode, boolean masked, long length, byte[] mask) {
            this.fin = fin;
            this.opcode = opcode;
            this.masked = masked;
            this.length = length;
            this.mask = mask;
        }
    }

    /**
     * 读取一个帧头。
     *
     * @param in 输入流
     * @return 帧头；对端已正常关闭（一个字节都读不到）时返回 null
     * @throws IOException 读取失败或帧头不完整
     */
    public static FrameHeader readHeader(InputStream in) throws IOException {
        byte[] head = new byte[2];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        head[0] = (byte) first;
        readFully(in, head, 1, 1);

        int b0 = head[0] & 0xFF;
        int b1 = head[1] & 0xFF;
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;

        if (length == LEN_16) {
            byte[] ext = new byte[2];
            readFully(in, ext, 0, 2);
            length = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
        } else if (length == LEN_64) {
            byte[] ext = new byte[8];
            readFully(in, ext, 0, 8);
            long value = 0L;
            for (byte b : ext) {
                value = (value << 8) | (b & 0xFF);
            }
            if (value < 0) {
                throw new IOException("WebSocket 负载长度溢出: " + value);
            }
            length = value;
        }

        byte[] mask = null;
        if (masked) {
            mask = new byte[MASK_SIZE];
            readFully(in, mask, 0, MASK_SIZE);
        }
        return new FrameHeader(fin, opcode, masked, length, mask);
    }

    /**
     * 读取并解掩码负载。
     *
     * @param in  输入流
     * @param h   帧头
     * @param max 允许的最大负载（字节），防止恶意长度直接撑爆堆
     * @return 解掩码后的负载
     * @throws IOException 长度越界或读取失败
     */
    public static byte[] readPayload(InputStream in, FrameHeader h, int max) throws IOException {
        if (h.length > max) {
            throw new IOException("WebSocket 负载超限: " + h.length + " > " + max);
        }
        int length = (int) h.length;
        if (length == 0) {
            return new byte[0];
        }
        byte[] payload = new byte[length];
        readFully(in, payload, 0, length);
        if (h.masked && h.mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= h.mask[i % MASK_SIZE];
            }
        }
        return payload;
    }

    /**
     * 读满指定长度。
     *
     * @param in     输入流
     * @param buf    目标数组
     * @param offset 起始偏移
     * @param length 需要读取的字节数
     * @throws IOException 对端提前关闭或读取失败
     */
    private static void readFully(InputStream in, byte[] buf, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int n = in.read(buf, offset + read, length - read);
            if (n < 0) {
                throw new EOFException("WebSocket 帧读取中连接关闭（期望 " + length + " 字节，实读 " + read + "）");
            }
            read += n;
        }
    }
}
