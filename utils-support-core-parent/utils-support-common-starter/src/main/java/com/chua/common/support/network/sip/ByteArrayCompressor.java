package com.chua.common.support.network.sip;

import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * SIP 数据面字节数组压缩工具类。
 *
 * <p>专门用于 {@code byte[]} 数组的压缩与解压，配合 {@link SipStreamCompressor}
 * 的 {@code wrap/unwrap} 流式接口使用。当需要在不产生流对象开销的场景下
 * 对完整的 byte 数组进行压缩时使用。</p>
 *
 * <p>帧格式：{@code [4B 压缩后长度][4B 原始长度][压缩数据]}，与 {@link SipStreamCompressor}
 * 的帧格式完全正交，可组合使用。</p>
 *
 * <p>压缩级别 {@link Deflater#BEST_SPEED}（低延迟优先），适合隧道传输场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("byte-array-compressor")
public final class ByteArrayCompressor {

    /**
     * 压缩级别（BEST_SPEED：低延迟优先）
    */
    private static final int LEVEL = Deflater.BEST_SPEED;

    /**
     * 单次压缩上限（防止超大数组导致 OOM）
    */
    private static final int MAX_FRAME_SIZE = 64 * 1024;

    /**
     * 压缩后帧头长度（4B 压缩后长度 + 4B 原始长度）
    */
    public static final int HEADER_LEN = 8;

    /**
     * 单帧明文上限
    */
    public static final int MAX_PLAIN_LEN = 64 * 1024;

    /**
     * 防止实例化
    */
    private ByteArrayCompressor() {
    }

    /**
     * 压缩字节数组为压缩帧。
     *
     * @param data 原始字节数据
     * @return 压缩帧（头部 8B + 压缩数据），若数据为空返回空帧，过大则抛出异常
     * @throws IOException 压缩过程异常
     */
    public static byte[] compressFrame(byte[] data) throws IOException {
        if (data == null) {
            return new byte[0];
        }
        if (data.length == 0) {
            return new byte[HEADER_LEN]; // 空数据也生成头部
        }
        if (data.length > MAX_PLAIN_LEN) {
            throw new IOException("SIP 字节数组过大: " + data.length + " (max: " + MAX_PLAIN_LEN + ")");
        }

        Deflater def = new Deflater(LEVEL);
        try {
            def.setInput(data);
            def.finish();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(data.length / 2 + 32);
            byte[] tmp = new byte[4096];
            while (!def.finished()) {
                int n = def.deflate(tmp);
                buf.write(tmp, 0, n);
            }
            byte[] compressed = buf.toByteArray();
            java.nio.ByteBuffer frame = java.nio.ByteBuffer.allocate(HEADER_LEN + compressed.length);
            frame.putInt(compressed.length);
            frame.putInt(data.length);
            frame.put(compressed);
            return frame.array();
        } catch (Exception e) {
            throw new IOException("SIP 字节数组压缩失败", e);
        } finally {
            def.end();
        }
    }

    /**
     * 解压压缩帧为原始字节数组。
     *
     * @param frame 压缩帧（头部 8B + 压缩数据）
     * @return 原始字节数据
     * @throws IOException 解压过程异常
     */
    public static byte[] decompressFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length < HEADER_LEN) {
            throw new IOException("SIP 压缩帧数据为空或长度不足: " + (frame == null ? "null" : frame.length));
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(frame);
        int compressedLen = buf.getInt();
        int originalLen = buf.getInt();
        if (compressedLen != frame.length - HEADER_LEN) {
            throw new IOException("SIP 压缩帧长度不匹配: header=" + compressedLen + " actual_data=" + (frame.length - HEADER_LEN));
        }
        if (originalLen <= 0 || originalLen > MAX_PLAIN_LEN) {
            throw new IOException("SIP 原始长度非法: " + originalLen);
        }
        byte[] compressed = new byte[compressedLen];
        buf.get(compressed);
        Inflater inf = new Inflater();
        try {
            inf.setInput(compressed);
            byte[] plain = new byte[originalLen];
            int total = 0;
            while (!inf.finished() && total < originalLen) {
                int n = inf.inflate(plain, total, originalLen - total);
                if (n == 0 && inf.needsInput()) {
                    throw new IOException("SIP 解压数据不完整");
                }
                total += n;
            }
            if (total != originalLen) {
                throw new IOException("SIP 解压长度不匹配: 期望=" + originalLen + " 实际=" + total);
            }
            return plain;
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("SIP 解压失败（数据损坏或密钥不匹配）", e);
        } finally {
            inf.end();
        }
    }

    /**
     * 包装输出流：将字节数据压缩后写入目标输出流。
     * <p>注意：此方法为便利方法，实际压缩逻辑在 {@link #compressFrame} 完成，</p>
     * <p>建议直接调用 {@link #compressFrame} 配合 ByteArrayOutputStream 使用。</p>
     *
     * @param out 目标输出流
     * @param data 要压缩的字节数据
     * @throws IOException IO 异常
     */
    public static void wrap(OutputStream out, byte[] data) throws IOException {
        if (out == null) {
            throw new IOException("输出流不能为 null");
        }
        byte[] frame = compressFrame(data);
        out.write(frame);
        out.flush();
    }

    /**
     * 从输入流读取压缩帧并解压。
     * <p>建议直接调用 {@link #decompressFrame} 配合 ByteArrayInputStream 使用。</p>
     *
     * @param in 来源输入流
     * @param dataLen 预期的压缩帧数据长度（不含头部），用于界定读取范围
     * @return 解压后的原始字节数据
     * @throws IOException IO 异常
     */
    public static byte[] unwrap(InputStream in, int dataLen) throws IOException {
        if (in == null) {
            throw new IOException("输入流不能为 null");
        }
        byte[] frame = new byte[HEADER_LEN + dataLen];
        int total = 0;
        while (total < frame.length) {
            int read = in.read(frame, total, frame.length - total);
            if (read == -1) {
                throw new IOException("SIP 压缩帧数据提前结束");
            }
            total += read;
        }
        return decompressFrame(frame);
    }

    /**
     * SPI 名称。
     *
     * @return 压缩器名称
     */
    public String name() {
        return "byte-array-compressor";
    }
}
