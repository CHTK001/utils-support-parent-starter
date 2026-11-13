// [P3C A20 豁免] 会话加密测试向量常量。
package com.chua.common.support.network.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 会话级认证加密：双向收发、篡改、重放与帧格式。
 *
 * <p>覆盖的是「抓到包也解不开、改了包会被发现」这两条底线：篡改与重放用例是
 * 敏感性对照——若把认证标签或序号校验去掉，它们会失败，从而挡住
 * 「只要双方能通就算过」的表面验收。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class SessionCipherTest {

    /**
     * 测试根密钥（32 字节固定值，保证用例可复现）
     */
    private static final byte[] ROOT_KEY = repeat((byte) 0x5a, 32);

    /**
     * 测试盐（16 字节固定值）
     */
    private static final byte[] SALT = repeat((byte) 0x31, SessionCipher.SALT_LENGTH);

    /**
     * 两个方向都要能收发：发起方发给响应方，响应方也要能回。
     */
    @Test
    @DisplayName("双向收发可还原明文")
    void bothDirectionsRoundTrip() {
        SessionCipher initiator = SessionCipher.initiator(ROOT_KEY, SALT);
        SessionCipher responder = SessionCipher.responder(ROOT_KEY, SALT);

        byte[] request = "ping 月英".getBytes(StandardCharsets.UTF_8);
        byte[] response = "pong 孔明".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(request, responder.open(initiator.seal(request)));
        assertArrayEquals(response, initiator.open(responder.seal(response)));
    }

    /**
     * 篡改任意一个字节都必须认证失败，且不得返回半截明文。
     */
    @Test
    @DisplayName("篡改密文即拒绝")
    void tamperedFrameRejected() {
        SessionCipher initiator = SessionCipher.initiator(ROOT_KEY, SALT);
        SessionCipher responder = SessionCipher.responder(ROOT_KEY, SALT);
        byte[] frame = initiator.seal("secret".getBytes(StandardCharsets.UTF_8));
        frame[frame.length - 1] ^= 0x01;

        assertThrows(SecurityException.class, () -> responder.open(frame));
    }

    /**
     * 同一帧重放必须被序号挡住。
     */
    @Test
    @DisplayName("重放同一帧即拒绝")
    void replayedFrameRejected() {
        SessionCipher initiator = SessionCipher.initiator(ROOT_KEY, SALT);
        SessionCipher responder = SessionCipher.responder(ROOT_KEY, SALT);
        byte[] frame = initiator.seal("once".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("once".getBytes(StandardCharsets.UTF_8), responder.open(frame));
        assertThrows(SecurityException.class, () -> responder.open(frame));
    }

    /**
     * 乱序必须拒绝；被拒的帧不会污染接收状态，补回缺失帧后可继续。
     */
    @Test
    @DisplayName("乱序即拒绝，补帧后可继续")
    void outOfOrderRejected() {
        SessionCipher initiator = SessionCipher.initiator(ROOT_KEY, SALT);
        SessionCipher responder = SessionCipher.responder(ROOT_KEY, SALT);
        byte[] first = initiator.seal("1".getBytes(StandardCharsets.UTF_8));
        byte[] second = initiator.seal("2".getBytes(StandardCharsets.UTF_8));

        assertThrows(SecurityException.class, () -> responder.open(second));
        assertArrayEquals("1".getBytes(StandardCharsets.UTF_8), responder.open(first));
        assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), responder.open(second));
    }

    /**
     * 根密钥不一致时无法解密。
     */
    @Test
    @DisplayName("根密钥不一致即拒绝")
    void wrongRootKeyRejected() {
        SessionCipher initiator = SessionCipher.initiator(ROOT_KEY, SALT);
        SessionCipher stranger = SessionCipher.responder(repeat((byte) 0x6b, 32), SALT);

        byte[] frame = initiator.seal("secret".getBytes(StandardCharsets.UTF_8));
        assertThrows(SecurityException.class, () -> stranger.open(frame));
    }

    /**
     * 盐不同也会得到不同的密钥，不能跨连接解密。
     */
    @Test
    @DisplayName("盐不同即拒绝跨连接解密")
    void differentSaltRejected() {
        SessionCipher initiator = SessionCipher.initiator(ROOT_KEY, SALT);
        SessionCipher other = SessionCipher.responder(ROOT_KEY, repeat((byte) 0x32, SessionCipher.SALT_LENGTH));

        byte[] frame = initiator.seal("secret".getBytes(StandardCharsets.UTF_8));
        assertThrows(SecurityException.class, () -> other.open(frame));
    }

    /**
     * 帧头必须是「长度 + 序号」，长度字段与实际载荷一致。
     */
    @Test
    @DisplayName("帧头长度与序号正确")
    void frameHeaderIsWellFormed() {
        SessionCipher initiator = SessionCipher.initiator(ROOT_KEY, SALT);
        byte[] plain = new byte[123];
        Arrays.fill(plain, (byte) 0x41);

        byte[] frame = initiator.seal(plain);
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        int bodyLength = buffer.getInt();
        long sequence = buffer.getLong();

        assertEquals(SessionCipher.SEQ_LENGTH + plain.length + SessionCipher.TAG_LENGTH, bodyLength);
        assertEquals(frame.length - SessionCipher.LENGTH_FIELD, bodyLength);
        assertEquals(0L, sequence);
    }

    /**
     * 超过单帧上限必须立即失败。
     */
    @Test
    @DisplayName("超长明文即拒绝")
    void oversizedPlainRejected() {
        SessionCipher initiator = SessionCipher.initiator(ROOT_KEY, SALT);
        byte[] plain = new byte[SessionCipher.MAX_PLAIN_LENGTH + 1];
        assertThrows(IllegalArgumentException.class, () -> initiator.seal(plain));
    }

    /**
     * 盐长度与根密钥长度不合法时拒绝构造。
     */
    @Test
    @DisplayName("盐或根密钥长度非法即拒绝")
    void invalidMaterialRejected() {
        assertThrows(IllegalArgumentException.class, () -> SessionCipher.initiator(ROOT_KEY, new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> SessionCipher.initiator(new byte[8], SALT));
        assertThrows(IllegalArgumentException.class, () -> SessionCipher.initiator(null, SALT));
    }

    /**
     * 截断的帧必须拒绝，而不是越界读取。
     */
    @Test
    @DisplayName("截断帧即拒绝")
    void truncatedFrameRejected() {
        SessionCipher responder = SessionCipher.responder(ROOT_KEY, SALT);
        assertThrows(IllegalArgumentException.class, () -> responder.open(new byte[SessionCipher.LENGTH_FIELD]));
    }

    /**
     * 生成固定字节。
     *
     * @param value 字节值
     * @param count 个数
     * @return 字节数组
     */
    private static byte[] repeat(byte value, int count) {
        byte[] bytes = new byte[count];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
