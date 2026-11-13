// [P3C A20 豁免] A20/A30/A31: 含算法名/帧格式常量，属非业务魔法值场景。
package com.chua.common.support.lang.algorithm.crypto;

import com.chua.common.support.network.crypto.AesGcmUtils;
import com.chua.common.support.network.crypto.Hkdf;
import com.chua.common.support.spi.annotations.Spi;

import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 一次性会话密钥编解码（Codec 二级接口实现）。
 *
 * <p>消息级（{@link Codec}）委托 {@link KeyLogCodec}：临时 ECDH + AEAD，单条消息各自协商；
 * 传输级（{@link StreamCodec}）在连接建立时<b>只握手一次</b>，之后 HKDF 派主流密钥，
 * 复用 AES-256-GCM 帧式流——避免每条消息都做一次 X25519 的吞吐损失。</p>
 *
 * <p><b>握手只发生一次</b>：客户端现场生成随机会话密钥，用服务端公钥经
 * {@link KeyLogCodec} 封进握手帧；服务端用长期私钥解出同一把密钥。会话密钥只在内存、
 * 握手完成后清零中间材料。</p>
 *
 * <h2>握手帧格式</h2>
 * <pre>
 * [4B body 长度][16B salt][4B 握手包长度][握手包 = KeyLogCodec 密文的 UTF-8 字节]
 * </pre>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi({"keylog", "hpke"})
public class KeyLogStreamCodec implements StreamCodec {

    /**
     * 盐长度（字节）
     */
    private static final int SALT_LENGTH = 16;

    /**
     * 会话根密钥长度（字节）
     */
    private static final int ROOT_KEY_LENGTH = 32;

    /**
     * 流密钥长度（字节）
     */
    private static final int STREAM_KEY_LENGTH = 32;

    /**
     * 握手帧上限（字节）
     */
    private static final int MAX_FRAME_LENGTH = 64 * 1024;

    /**
     * HKDF 上下文标签
     */
    private static final String HKDF_INFO = "chua-transport-v1/aes-256-gcm";

    /**
     * AES 算法名
     */
    private static final String AES_ALGORITHM = "AES";

    /**
     * 消息级编解码器（keylog 角色）
     */
    private Codec messageCodec;

    @Override
    public StreamCodec key(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "keylog 传输加密缺少密钥，需 server:<私钥hex> 或 client:<公钥hex>[#指纹]");
        }
        this.messageCodec = Codec.build("keylog", key);
        return this;
    }

    @Override
    public String encodeHex(String data) {
        if (messageCodec == null) {
            throw new IllegalStateException("keylog 编解码尚未绑定密钥");
        }
        return messageCodec.encodeHex(data);
    }

    @Override
    public String decodeHex(String hex) {
        if (messageCodec == null) {
            throw new IllegalStateException("keylog 编解码尚未绑定密钥");
        }
        return messageCodec.decodeHex(hex);
    }

    @Override
    public Streams open(InputStream in, OutputStream out, boolean initiator) throws IOException {
        if (messageCodec == null) {
            throw new IllegalStateException("keylog 传输加密尚未绑定密钥");
        }
        byte[] rootKey;
        byte[] salt;
        if (initiator) {
            rootKey = Hkdf.randomBytes(ROOT_KEY_LENGTH);
            salt = Hkdf.randomBytes(SALT_LENGTH);
            byte[] handshake = messageCodec.encodeHex(Hex.encodeHexString(rootKey))
                    .getBytes(StandardCharsets.UTF_8);
            writeFrame(out, salt, handshake);
            out.flush();
        } else {
            byte[] frame = readFrame(in);
            salt = Arrays.copyOfRange(frame, 0, SALT_LENGTH);
            int handshakeLength = java.nio.ByteBuffer.wrap(frame, SALT_LENGTH, Integer.BYTES).getInt();
            if (handshakeLength <= 0 || SALT_LENGTH + Integer.BYTES + handshakeLength > frame.length) {
                throw new IOException("keylog 握手包长度非法");
            }
            byte[] handshake = Arrays.copyOfRange(frame,
                    SALT_LENGTH + Integer.BYTES, SALT_LENGTH + Integer.BYTES + handshakeLength);
            String rootHex = messageCodec.decodeHex(new String(handshake, StandardCharsets.UTF_8));
            rootKey = Hex.decodeHex(rootHex);
        }
        byte[] streamKey = Hkdf.derive(rootKey, salt, HKDF_INFO, STREAM_KEY_LENGTH);
        Arrays.fill(rootKey, (byte) 0);
        SecretKeySpec keySpec = new SecretKeySpec(streamKey, AES_ALGORITHM);
        return new Streams(AesGcmUtils.decrypting(in, keySpec), AesGcmUtils.encrypting(out, keySpec));
    }

    /**
     * 写握手帧。
     *
     * @param out       输出流
     * @param salt      盐
     * @param handshake 握手包
     * @throws IOException 写入失败
     */
    private static void writeFrame(OutputStream out, byte[] salt, byte[] handshake) throws IOException {
        int bodyLength = SALT_LENGTH + Integer.BYTES + handshake.length;
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(bodyLength);
        data.write(salt);
        data.writeInt(handshake.length);
        data.write(handshake);
        data.flush();
    }

    /**
     * 读握手帧。
     *
     * @param in 输入流
     * @return 帧内容（salt + 长度 + 握手包）
     * @throws IOException 读取失败或长度非法
     */
    private static byte[] readFrame(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int bodyLength = data.readInt();
        if (bodyLength <= SALT_LENGTH + Integer.BYTES || bodyLength > MAX_FRAME_LENGTH) {
            throw new IOException("keylog 握手帧长度非法: " + bodyLength);
        }
        byte[] body = new byte[bodyLength];
        data.readFully(body);
        return body;
    }
}
