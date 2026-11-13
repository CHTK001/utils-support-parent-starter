package com.chua.common.support.network.server.impl;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP 长度帧（4 字节大端长度头 + body）的共享写出工具。
 *
 * <p>与 {@link JdkTcpServer} 的帧写出保持一致的写法，供其余 TCP 服务器实现复用，
 * 避免每个类各自写一套重复分配的帧拼接与空响应逻辑。</p>
 *
 * <ul>
 *   <li>{@link #EMPTY_RESPONSE_BYTES}：空响应帧体只读常量，避免每帧 {@code getBytes()}。</li>
 *   <li>{@link #encodeFrame(byte[])}：把 {@code 4 字节长度头 + body} 拼成单块字节，供写出层一次性发出。</li>
 *   <li>{@link #toBytes(ByteBuffer)}：无副作用地把 {@link ByteBuffer} 剩余字节转为数组。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class TcpFrameWriter {

    private TcpFrameWriter() {
    }

    /**
     * 空响应帧体（200 OK + Content-Length: 0 + 空行），只读常量。
     *
     * <p>各实现按需调用 {@link #encodeFrame(byte[])} 再加上此常量即可得到完整的
     * 长度帧，无需每次 {@code "...".getBytes()}</p>
     */
    public static final byte[] EMPTY_RESPONSE_BYTES =
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /**
     * 把 body 拼成 TCP 长度帧：4 字节大端长度头 + body。
     *
     * @param body 帧体字节，不允许为 null
     * @return 4 + body.length 的完整帧字节
     */
    public static byte[] encodeFrame(byte[] body) {
        byte[] frame = new byte[4 + body.length];
        // [P3C A20 豁免]
        frame[0] = (byte) (body.length >>> 24);
        // [P3C A20 豁免]
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, 4, body.length);
        return frame;
    }

    /**
     * 把 {@link ByteBuffer} 剩余字节转为数组，不改变原 buffer 的 position/limit（读取前会
     * 先 {@link ByteBuffer#duplicate()}，所以对调用方无副作用）。
     *
     * @param buffer 缓冲，可为 null
     * @return 剩余字节数组，buffer 为 null 或无剩余时返回空数组
     */
    public static byte[] toBytes(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return new byte[0];
        }
        ByteBuffer dup = buffer.duplicate();
        if (dup.hasArray()) {
            return java.util.Arrays.copyOfRange(dup.array(), dup.position(), dup.limit());
        }
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return bytes;
    }

    /**
     * 连接级写出上下文，把“由 worker 写、IO 线程读”的 {@link ByteBuffer} 与“连接预算
     * 是否已归还”的 CAS 标记封装在一起，供各实现按需复用。</p>
     *
     * <p>{@code pendingWrite} 为 {@code volatile}，保证 worker 线程写、IO Selector 线程读
     * 时可见；{@code budgetReleased} 用 CAS 保证 {@code releaseConnection()} 严格只调用一次。</p>
     *
     * @author CH
     * @since 4.0.0.50
     */
    public static class Attachment {
        /**
         * 未写完的响应字节（非阻塞写入的剩余部分，注册 OP_WRITE 后由事件循环续写）。
         */
        public volatile ByteBuffer pendingWrite;

        /**
         * 连接预算是否已归还：关闭可能同时发生在 IO 线程与 worker 线程，
         * 用 CAS 保证 releaseConnection() 严格只调用一次。
         */
        public final AtomicBoolean budgetReleased = new AtomicBoolean();
    }
}
