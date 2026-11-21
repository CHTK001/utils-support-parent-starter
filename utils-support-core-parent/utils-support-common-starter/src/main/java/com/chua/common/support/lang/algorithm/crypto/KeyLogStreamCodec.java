// [P3C A20 豁免] A20/A30/A31: 含算法名/帧格式常量，属非业务魔法值场景。
package com.chua.common.support.lang.algorithm.crypto;

import com.chua.common.support.network.crypto.Hkdf;
import com.chua.common.support.network.crypto.SessionCipher;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 一次性会话密钥编解码（Codec 二级接口实现）。
 *
 * <p>消息级（{@link Codec}）委托 {@link KeyLogCodec}：临时 ECDH + AEAD，单条消息各自协商；
 * 传输级（{@link StreamCodec}）在连接建立时<b>只握手一次</b>，之后按方向派生子密钥，
 * 复用 {@link SessionCipher} 的帧式流——避免每条消息都做一次 X25519 的吞吐损失。</p>
 *
 * <p><b>握手只发生一次</b>：客户端现场生成随机会话根密钥，用服务端公钥经
 * {@link KeyLogCodec} 封进握手帧；服务端用长期私钥解出同一把根密钥。会话密钥只在内存、
 * 握手完成后清零中间材料。</p>
 *
 * <h2>方向隔离与重放保护</h2>
 * <p>根密钥 + 每次连接随机的盐交给 {@link SessionCipher}：它按方向派生
 * 「发起方→响应方」与「响应方→发起方」两把<b>互不相同</b>的子密钥，并且每帧带单调序号、
 * 序号与方向一起进 AAD。因此：</p>
 * <ul>
 *   <li>链路上的攻击者把客户端自己的密文原样弹回给客户端，<b>解不开</b>（方向密钥不同）；
 *       同一对密钥也不会让两端互相冒充。</li>
 *   <li>截获的帧重复投递、调换顺序、截断，接收侧都会因为序号不连续直接断链，
 *       而不是解出一段旧指令。</li>
 *   <li>其它连接的密文（不同根密钥或不同盐）无法解开。</li>
 * </ul>
 * <p>这些保护不依赖时间戳，长连接上同样有效；消息级 {@link KeyLogCodec} 的时间窗与去重表
 * 只负责单条消息的过期与重放。</p>
 *
 * <h2>握手帧格式</h2>
 * <pre>
 * [4B body 长度][1B 协议版本][16B salt][4B 握手包长度][握手包 = KeyLogCodec 密文的 UTF-8 字节]
 * </pre>
 * <p>协议版本用于让新旧两端不匹配时立即失败，而不是各自用不同派生方式静默解出乱码。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi({"keylog", "hpke"})
public class KeyLogStreamCodec implements StreamCodec {

    /**
     * 握手协议版本
     */
    private static final byte PROTOCOL_VERSION = 2;

    /**
     * 盐长度（字节）
     */
    private static final int SALT_LENGTH = 16;

    /**
     * 会话根密钥长度（字节）
     */
    private static final int ROOT_KEY_LENGTH = 32;

    /**
     * 握手帧上限（字节）
     */
    private static final int MAX_FRAME_LENGTH = 64 * 1024;

    /**
     * 消息级编解码器（keylog 角色）
     */
    private Codec messageCodec;

    @Override
    public StreamCodec key(String key) {
        if (StringUtils.isBlank(key)) {
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
            if (frame[0] != PROTOCOL_VERSION) {
                throw new IOException("keylog 传输协议版本不匹配: " + frame[0]
                        + "，期望 " + PROTOCOL_VERSION);
            }
            salt = Arrays.copyOfRange(frame, 1, 1 + SALT_LENGTH);
            int offset = 1 + SALT_LENGTH;
            int handshakeLength = ByteBuffer.wrap(frame, offset, Integer.BYTES).getInt();
            offset += Integer.BYTES;
            if (handshakeLength <= 0 || offset + handshakeLength > frame.length) {
                throw new IOException("keylog 握手包长度非法");
            }
            byte[] handshake = Arrays.copyOfRange(frame, offset, offset + handshakeLength);
            String rootHex = messageCodec.decodeHex(new String(handshake, StandardCharsets.UTF_8));
            rootKey = Hex.decodeHex(rootHex);
        }
        SessionCipher session;
        try {
            session = initiator
                    ? SessionCipher.initiator(rootKey, salt)
                    : SessionCipher.responder(rootKey, salt);
        } finally {
            Arrays.fill(rootKey, (byte) 0);
        }
        return new Streams(session.decrypting(in), session.encrypting(out));
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
        int bodyLength = 1 + SALT_LENGTH + Integer.BYTES + handshake.length;
        var data = new DataOutputStream(out);
        data.writeInt(bodyLength);
        data.writeByte(PROTOCOL_VERSION);
        data.write(salt);
        data.writeInt(handshake.length);
        data.write(handshake);
        data.flush();
    }

    /**
     * 读握手帧。
     *
     * @param in 输入流
     * @return 帧内容（版本 + salt + 长度 + 握手包）
     * @throws IOException 读取失败或长度非法
     */
    private static byte[] readFrame(InputStream in) throws IOException {
        var data = new DataInputStream(in);
        int bodyLength = data.readInt();
        if (bodyLength <= 1 + SALT_LENGTH + Integer.BYTES || bodyLength > MAX_FRAME_LENGTH) {
            throw new IOException("keylog 握手帧长度非法: " + bodyLength);
        }
        byte[] body = new byte[bodyLength];
        data.readFully(body);
        return body;
    }
}
