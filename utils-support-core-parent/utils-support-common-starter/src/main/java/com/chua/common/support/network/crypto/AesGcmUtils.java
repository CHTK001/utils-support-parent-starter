package com.chua.common.support.network.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
* AES-256-GCM 流加解密工具：为任意 TCP 流提供帧式端到端加密能力。
*
* <p>帧格式：{@code [4B 长度][12B nonce][密文+16B 认证标签]}，长度字段为 nonce 与密文总长。
* 每帧使用随机 nonce，认证标签保证完整性，篡改即解密失败断链。</p>
*
* <p>典型用法（客户端与服务端共享同一密钥短语）：</p>
* <pre>{@code
* SecretKeySpec key = AesGcmUtils.deriveKey("shared-secret");
* OutputStream out = AesGcmUtils.encrypting(socket.getOutputStream(), key);
* InputStream  in  = AesGcmUtils.decrypting(socket.getInputStream(), key);
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public final class AesGcmUtils {

    /**
    * GCM nonce 长度（字节）
     */
    public static final int NONCE_LEN = 12;

    /**
    * GCM 认证标签长度（字节）
     */
    public static final int TAG_LEN = 16;

    /**
    * 长度头长度（字节）
     */
    public static final int HEADER_LEN = 4;

    /**
    * 单帧明文上限（字节），超出自动分帧
     */
    public static final int MAX_PLAIN_LEN = 64 * 1024;

    /**
    * 单帧总长上限（含 nonce 与标签），超出视为协议错误
     */
    public static final int MAX_FRAME_LEN = MAX_PLAIN_LEN + NONCE_LEN + TAG_LEN;

    /**
    * 随机数发生器
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcmUtils() {
    }

    /**
    * 由密钥短语派生 AES-256 密钥（SHA-256）。
    *
    * @param secret 密钥短语（如共享 token）
    * @return AES 密钥
     */
    public static SecretKeySpec deriveKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new SecretKeySpec(digest.digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("AES 密钥派生失败", e);
        }
    }

    /**
    * 将输出流包装为加密输出流：每次 {@code write} 产生一个独立加密帧。
    *
    * @param out 原始输出流
    * @param key AES 密钥
    * @return 加密输出流
     */
    public static OutputStream encrypting(OutputStream out, SecretKeySpec key) {
        return new EncryptingOutputStream(out, key);
    }

    /**
    * 将输入流包装为解密输入流：透明解密对端写入的加密帧。
    *
    * @param in  原始输入流
    * @param key AES 密钥
    * @return 解密输入流
     */
    public static InputStream decrypting(InputStream in, SecretKeySpec key) {
        return new DecryptingInputStream(in, key);
    }

    /**
    * 加密单帧。
    *
    * @param cipher 加密器
    * @param key    密钥
    * @param plain  明文（1..MAX_PLAIN_LEN 字节）
    * @return 完整帧（含长度头）
    * @throws IOException 加密失败
     */
    private static byte[] encryptFrame(Cipher cipher, SecretKeySpec key, byte[] plain) throws IOException {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN * 8, nonce));
            byte[] cipherText = cipher.doFinal(plain);
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_LEN + NONCE_LEN + cipherText.length);
            buffer.putInt(NONCE_LEN + cipherText.length);
            buffer.put(nonce);
            buffer.put(cipherText);
            return buffer.array();
        } catch (Exception e) {
            throw new IOException("AES-GCM 帧加密失败", e);
        }
    }

    /**
    * 加密输出流：每次 write 独立成帧，大数组自动按 MAX_PLAIN_LEN 分帧。
     */
    private static final class EncryptingOutputStream extends OutputStream {

        /**
        * 原始输出流
         */
        private final OutputStream out;

        /**
        * 加密器（线程安全使用：所有 write 串行化）
         */
        private final Cipher cipher;

        /**
        * AES 密钥
         */
        private final SecretKeySpec key;

        private EncryptingOutputStream(OutputStream out, SecretKeySpec key) {
            this.out = out;
            this.key = key;
            try {
                this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (Exception e) {
                throw new IllegalStateException("AES-GCM 初始化失败", e);
            }
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
                    byte[] plain = Arrays.copyOfRange(b, off + len - remaining, off + len - remaining + chunk);
                    out.write(encryptFrame(cipher, key, plain));
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
    * 解密输入流：阻塞读取完整帧并解密，以流语义吐出明文。
     */
    private static final class DecryptingInputStream extends InputStream {

        /**
        * 原始输入流
         */
        private final InputStream in;

        /**
        * 解密器
         */
        private final Cipher cipher;

        /**
        * AES 密钥
         */
        private final SecretKeySpec key;

        /**
        * 当前明文缓冲
         */
        private byte[] buf = new byte[0];

        /**
        * 缓冲读取位置
         */
        private int pos = 0;

        private DecryptingInputStream(InputStream in, SecretKeySpec key) {
            this.in = in;
            this.key = key;
            try {
                this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (Exception e) {
                throw new IllegalStateException("AES-GCM 初始化失败", e);
            }
        }

        /**
        * 读取并解密下一帧到内部缓冲。
        *
        * @return false 表示流已结束
        * @throws IOException IO 异常或帧非法/认证失败
         */
        private boolean fill() throws IOException {
            byte[] header = readFully(HEADER_LEN);
            if (header == null) {
                return false;
            }
            int len = ByteBuffer.wrap(header).getInt();
            if (len <= 0 || len > MAX_FRAME_LEN) {
                throw new IOException("AES-GCM 帧长度非法: " + len);
            }
            byte[] frame = readFully(len);
            if (frame == null) {
                throw new IOException("AES-GCM 帧不完整，连接提前关闭");
            }
            byte[] nonce = Arrays.copyOfRange(frame, 0, NONCE_LEN);
            byte[] cipherText = Arrays.copyOfRange(frame, NONCE_LEN, len);
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN * 8, nonce));
                buf = cipher.doFinal(cipherText);
                pos = 0;
                return true;
            } catch (Exception e) {
                throw new IOException("AES-GCM 帧解密失败（密钥不匹配或数据被篡改）", e);
            }
        }

        /**
        * 阻塞读满指定长度。
        *
        * @param n 期望长度
        * @return 数据；流结束时返回 null
        * @throws IOException IO 异常
         */
        private byte[] readFully(int n) throws IOException {
            byte[] data = new byte[n];
            int offset = 0;
            while (offset < n) {
                int read = in.read(data, offset, n - offset);
                if (read == -1) {
                    if (offset == 0) {
                        return null;
                    }
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
