package com.chua.common.support.lang.algorithm.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次性会话密钥（keylog）加解密：客户端只持公钥、服务端只持私钥、每次随机、无状态解密。
 *
 * <p>覆盖：正常往返、每次随机、无状态、错私钥、篡改、角色隔离、公钥 pin、时间窗、重放。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class KeyLogCodecTest {

    /**
     * 测试明文
     */
    private static final String PLAIN = "{\"msg\":\"月英的密信\"}";

    /**
     * 服务端长期密钥对
     */
    private static final String[] SERVER_KEYS = KeyLogCodec.generateKeyPair();

    /**
     * 时间窗宽度：10 分钟（毫秒）
     */
    private static final long TEN_MINUTES_MILLIS = 10 * 60 * 1000L;

    /**
     * 客户端实例（只持公钥）
     */
    private static final Codec CLIENT = Codec.build("keylog", "client:" + SERVER_KEYS[0]);

    /**
     * 服务端实例（只持私钥）
     */
    private static final Codec SERVER = Codec.build("keylog", "server:" + SERVER_KEYS[1]);

    /**
     * 客户端加密、服务端解密，明文可还原。
     */
    @Test
    @DisplayName("客户端加密 → 服务端解密可还原")
    void clientEncryptServerDecrypt() {
        String wire = CLIENT.encodeHex(PLAIN);
        assertTrue(wire.startsWith("k2:"));
        assertEquals(PLAIN, SERVER.decodeHex(wire));
    }

    /**
     * 每条消息都生成新临时密钥，同明文两次密文必须不同，且都能解。
     */
    @Test
    @DisplayName("每次随机：同明文两次密文不同且都可解")
    void eachMessageUsesFreshKey() {
        String first = CLIENT.encodeHex(PLAIN);
        String second = CLIENT.encodeHex(PLAIN);
        assertNotEquals(first, second);
        assertEquals(PLAIN, SERVER.decodeHex(first));
        assertEquals(PLAIN, SERVER.decodeHex(second));
    }

    /**
     * 服务端不需要保存会话状态：任意顺序、任意条数都能解。
     */
    @Test
    @DisplayName("服务端无状态解多条")
    void serverIsStateless() {
        String a = CLIENT.encodeHex("第一条");
        String b = CLIENT.encodeHex("第二条");
        String c = CLIENT.encodeHex("第三条");
        assertEquals("第三条", SERVER.decodeHex(c));
        assertEquals("第一条", SERVER.decodeHex(a));
        assertEquals("第二条", SERVER.decodeHex(b));
    }

    /**
     * 另一对密钥的私钥解不开。
     */
    @Test
    @DisplayName("错私钥即拒绝")
    void wrongPrivateKeyRejected() {
        String[] other = KeyLogCodec.generateKeyPair();
        Codec stranger = Codec.build("keylog", "server:" + other[1]);
        String wire = CLIENT.encodeHex(PLAIN);
        assertThrows(RuntimeException.class, () -> stranger.decodeHex(wire));
    }

    /**
     * 篡改密文即认证失败。
     */
    @Test
    @DisplayName("篡改即拒绝")
    void tamperedCipherRejected() {
        String wire = CLIENT.encodeHex(PLAIN);
        char last = wire.charAt(wire.length() - 1);
        String tampered = wire.substring(0, wire.length() - 1) + (last == '0' ? '1' : '0');
        assertThrows(RuntimeException.class, () -> SERVER.decodeHex(tampered));
    }

    /**
     * 客户端只有公钥，不能解密。
     */
    @Test
    @DisplayName("客户端无私钥不能解密")
    void clientCannotDecrypt() {
        String wire = CLIENT.encodeHex(PLAIN);
        assertThrows(IllegalStateException.class, () -> CLIENT.decodeHex(wire));
    }

    /**
     * 服务端只有私钥，不能加密。
     */
    @Test
    @DisplayName("服务端无公钥不能加密")
    void serverCannotEncrypt() {
        assertThrows(IllegalStateException.class, () -> SERVER.encodeHex(PLAIN));
    }

    /**
     * pair 角色可双向，便于单进程回包与测试。
     */
    @Test
    @DisplayName("pair 角色可双向")
    void pairRoleIsBidirectional() {
        Codec both = Codec.build("keylog", "pair:" + SERVER_KEYS[0] + ":" + SERVER_KEYS[1]);
        assertEquals(PLAIN, both.decodeHex(both.encodeHex(PLAIN)));
    }

    /**
     * hpke 是同一实现的别名。
     */
    @Test
    @DisplayName("hpke 别名可用")
    void hpkeAliasWorks() {
        Codec alias = Codec.build("hpke", "client:" + SERVER_KEYS[0]);
        assertEquals(PLAIN, SERVER.decodeHex(alias.encodeHex(PLAIN)));
    }

    /**
     * 公钥 pin 正确时可正常加密。
     */
    @Test
    @DisplayName("公钥 pin 正确可用")
    void pinMatches() {
        String pin = KeyLogCodec.fingerprint(SERVER_KEYS[0]);
        Codec client = Codec.build("keylog", "client:" + SERVER_KEYS[0] + "#" + pin);
        assertEquals(PLAIN, SERVER.decodeHex(client.encodeHex(PLAIN)));
    }

    /**
     * 公钥被替换（pin 不匹配）时必须拒绝加载。
     */
    @Test
    @DisplayName("公钥 pin 不匹配即拒绝")
    void pinMismatchRejected() {
        String pin = KeyLogCodec.fingerprint(SERVER_KEYS[0]);
        String[] other = KeyLogCodec.generateKeyPair();
        assertThrows(IllegalArgumentException.class,
                () -> Codec.build("keylog", "client:" + other[0] + "#" + pin));
    }

    /**
     * 同一密文解第二次必须判为重放。
     */
    @Test
    @DisplayName("重放同一密文即拒绝")
    void replayRejected() {
        String wire = CLIENT.encodeHex(PLAIN);
        assertEquals(PLAIN, SERVER.decodeHex(wire));
        assertThrows(SecurityException.class, () -> SERVER.decodeHex(wire));
    }

    /**
     * 超出时间窗的消息必须拒绝。
     */
    @Test
    @DisplayName("过期消息即拒绝")
    void expiredMessageRejected() {
        KeyLogCodec client = (KeyLogCodec) Codec.build("keylog", "client:" + SERVER_KEYS[0]);
        long expired = System.currentTimeMillis() - TEN_MINUTES_MILLIS;
        String wire = client.encodeHex(PLAIN, expired, 1L);
        assertThrows(SecurityException.class, () -> SERVER.decodeHex(wire));
    }

    /**
     * 关闭时间窗后，过期消息仍可解（用于离线/补发场景）。
     */
    @Test
    @DisplayName("关闭时间窗后允许过期消息")
    void replayWindowCanBeDisabled() {
        KeyLogCodec client = (KeyLogCodec) Codec.build("keylog", "client:" + SERVER_KEYS[0]);
        KeyLogCodec server = ((KeyLogCodec) Codec.build("keylog", "server:" + SERVER_KEYS[1]))
                .replayWindow(0L);
        long expired = System.currentTimeMillis() - TEN_MINUTES_MILLIS;
        String wire = client.encodeHex(PLAIN, expired, 2L);
        assertEquals(PLAIN, server.decodeHex(wire));
    }

    /**
     * 未知角色、缺前缀的密文都直接拒绝。
     */
    @Test
    @DisplayName("非法密钥/密文格式即拒绝")
    void invalidInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> Codec.build("keylog", "unknown:00"));
        assertThrows(IllegalArgumentException.class, () -> Codec.build("keylog", "client:"));
        assertThrows(IllegalArgumentException.class, () -> SERVER.decodeHex("00112233"));
        assertThrows(IllegalArgumentException.class, () -> Codec.build("keylog", ""));
    }

    /**
     * 空值原样返回。
     */
    @Test
    @DisplayName("空值原样返回")
    void nullPassesThrough() {
        assertNull(CLIENT.encodeHex(null));
        assertNull(SERVER.decodeHex(null));
    }
}
