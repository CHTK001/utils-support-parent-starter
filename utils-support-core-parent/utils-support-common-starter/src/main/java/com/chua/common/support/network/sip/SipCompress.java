package com.chua.common.support.network.sip;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * SIP 数据面 zlib 压缩工具（帧式，与 AES-GCM 帧格式正交组合）。
 *
 * <p>帧格式：{@code [4B 压缩后长度][4B 原始长度][压缩数据]}。
 * 压缩级别 {@link Deflater#BEST_SPEED}（隧道场景低延迟优先）。</p>
 *
 * <p>用法（与加密流组合，压缩在内层、加密在外层）：</p>
 * <pre>{@code
 * OutputStream out = AesGcmUtils.encrypting(
 *         SipCompress.compressing(socket.getOutputStream(), key), key);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SipCompress {

    /**
     * 压缩后帧头长度（4B 压缩后长度 + 4B 原始长度）
     */
    public static final int HEADER_LEN = 8;

    /**
     * 单帧明文上限
     */
    public static final int MAX_PLAIN_LEN = 64 * 1024;

    /**
     * 压缩级别（BEST_SPEED：低延迟优先）
     */
    private static final int LEVEL = Deflater.BEST_SPEED;

    private SipCompress() {
    }

    /**
     * 包装输出流：每次 write 产生一个压缩帧。
     *
     * @param out 原始输出流
     * @return 压缩输出流
     */
    public static OutputStream compressing(OutputStream out) {
        return new CompressingOutputStream(out);
    }

    /**
     * 包装输入流：透明解压对端写入的压缩帧。
     *
     * @param in 原始输入流
     * @return 解压输入流
     */
    public static InputStream decompressing(InputStream in) {
        return new DecompressingInputStream(in);
    }

    /**
     * 压缩单帧。
     */
    private static byte[] compressFrame(byte[] plain, int off, int len) throws IOException {
        Deflater def = new Deflater(LEVEL);
        try {
            def.setInput(plain, off, len);
            def.finish();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(len / 2 + 32);
            byte[] tmp = new byte[4096];
            while (!def.finished()) {
                int n = def.deflate(tmp);
                buf.write(tmp, 0, n);
            }
            byte[] compressed = buf.toByteArray();
            java.nio.ByteBuffer frame = java.nio.ByteBuffer.allocate(HEADER_LEN + compressed.length);
            frame.putInt(compressed.length);
            frame.putInt(len);
            frame.put(compressed);
            return frame.array();
        } catch (Exception e) {
            throw new IOException("SIP 压缩失败", e);
        } finally {
            def.end();
        }
    }

    /**
     * 解压单帧（去掉头部）。
     */
    private static byte[] decompressFrame(byte[] frame) throws IOException {
        if (frame.length < HEADER_LEN) {
            throw new IOException("SIP 压缩帧长度非法: " + frame.length);
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(frame);
        int compressedLen = buf.getInt();
        int originalLen = buf.getInt();
        if (compressedLen != frame.length - HEADER_LEN) {
            throw new IOException("SIP 压缩帧长度不匹配: header=" + compressedLen + " actual=" + (frame.length - HEADER_LEN));
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
                    throw new IOException("SIP 压缩帧数据不完整");
                }
                total += n;
            }
            if (total != originalLen) {
                throw new IOException("SIP 解压长度不匹配: 期望=" + originalLen + " 实际=" + total);
            }
            return plain;
        } catch (DataFormatException e) {
            throw new IOException("SIP 解压失败（数据损坏或密钥不匹配）", e);
        } finally {
            inf.end();
        }
    }

    /**
     * 压缩输出流：每次 write 独立成帧，大数组自动分帧。
     */
    private static final class CompressingOutputStream extends OutputStream {

        private final OutputStream out;

        CompressingOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return;
            }
            synchronized (out) {
                int remaining = len;
                while (remaining > 0) {
                    int chunk = Math.min(remaining, MAX_PLAIN_LEN);
                    out.write(compressFrame(b, off + len - remaining, chunk));
                    remaining -= chunk;
                }
                out.flush();
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /**
     * 解压输入流：阻塞读取完整压缩帧并解压，以流语义吐出明文。
     */
    private static final class DecompressingInputStream extends InputStream {

        private final InputStream in;
        private byte[] buf = new byte[0];
        private int pos = 0;

        DecompressingInputStream(InputStream in) {
            this.in = in;
        }

        private boolean fill() throws IOException {
            byte[] header = readFully(HEADER_LEN);
            if (header == null) {
                return false;
            }
            java.nio.ByteBuffer hb = java.nio.ByteBuffer.wrap(header);
            int compressedLen = hb.getInt();
            int originalLen = hb.getInt();
            if (compressedLen <= 0 || compressedLen > MAX_PLAIN_LEN * 2 + 1024 || originalLen <= 0 || originalLen > MAX_PLAIN_LEN) {
                throw new IOException("SIP 压缩帧长度非法: compressed=" + compressedLen + " original=" + originalLen);
            }
            byte[] compressed = readFully(compressedLen);
            if (compressed == null) {
                throw new IOException("SIP 压缩帧不完整，连接提前关闭");
            }
            Inflater inf = new Inflater();
            try {
                inf.setInput(compressed);
                byte[] plain = new byte[originalLen];
                int total = 0;
                while (!inf.finished() && total < originalLen) {
                    int n = inf.inflate(plain, total, originalLen - total);
                    if (n == 0 && inf.needsInput()) {
                        throw new IOException("SIP 压缩帧数据不完整");
                    }
                    total += n;
                }
                if (total != originalLen) {
                    throw new IOException("SIP 解压长度不匹配: 期望=" + originalLen + " 实际=" + total);
                }
                buf = plain;
                pos = 0;
                return true;
            } catch (DataFormatException e) {
                throw new IOException("SIP 解压失败（数据损坏）", e);
            } finally {
                inf.end();
            }
        }

        private byte[] readFully(int n) throws IOException {
            byte[] data = new byte[n];
            int offset = 0;
            while (offset < n) {
                int read = in.read(data, offset, n - offset);
                if (read == -1) {
                    return null;
                }
                offset += read;
            }
            return data;
        }

        @Override
        public int read() throws IOException {
            if (pos >= buf.length && !fill()) {
                return -1;
            }
            return buf[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (pos >= buf.length && !fill()) {
                return -1;
            }
            int available = buf.length - pos;
            int copy = Math.min(len, available);
            System.arraycopy(buf, pos, b, off, copy);
            pos += copy;
            return copy;
        }

        @Override
        public int available() {
            return buf.length - pos;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
