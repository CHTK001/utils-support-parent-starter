package com.chua.common.support.lang.algorithm.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加解密 SPI：类型解析、真实加密、旧数据兼容与失败不降级。
 *
 * <p>三条敏感性对照，缺一条就会放过「假加密」：</p>
 * <ul>
 *   <li>密文不等于明文 hex —— 任一类型退回十六进制编码即失败；</li>
 *   <li>AES-CBC 密文与 JDK 按「SHA-256(key) 派生 key/IV」算出的基准逐字节一致 ——
 *       改 IV 或换模式就会破坏前端契约；</li>
 *   <li>未知类型抛异常 —— 挡住静默回退。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
class CodecSpiTest {

    /**
     * 测试密钥
     */
    private static final String KEY = "unit-test-codec-key";

    /**
     * 测试明文
     */
    private static final String PLAIN = "月英的字段";

    /**
     * AES 走真实加密且可还原。
     */
    @Test
    @DisplayName("aes 真实加密且可还原")
    void aesRoundTrip() {
        Codec codec = Codec.build("aes", KEY);
        String cipher = codec.encodeHex(PLAIN);
        assertNotEquals(hexOf(PLAIN), cipher);
        assertEquals(PLAIN, codec.decodeHex(cipher));
    }

    /**
     * AES-CBC 密文必须与前端 CryptoJS 的 key/IV 派生逐字节一致。
     */
    @Test
    @DisplayName("aes 密文与前端 CBC 口径一致")
    void aesMatchesFrontendContract() throws Exception {
        assertEquals(expectedCbc(PLAIN), Codec.build("aes", KEY).encodeHex(PLAIN));
    }

    /**
     * 落库用的 GCM 实现带 g1: 前缀，且可还原。
     */
    @Test
    @DisplayName("aes-gcm 带前缀且可还原")
    void aesGcmRoundTrip() {
        Codec codec = Codec.build("aes-gcm", KEY);
        String cipher = codec.encodeHex(PLAIN);
        assertTrue(cipher.startsWith("g1:"));
        assertEquals(PLAIN, codec.decodeHex(cipher));
    }

    /**
     * 同明文两次 GCM 加密结果必须不同，否则 IV 又被写死了。
     */
    @Test
    @DisplayName("aes-gcm 每次密文不同")
    void aesGcmUsesRandomIv() {
        Codec codec = Codec.build("aes-gcm", KEY);
        assertNotEquals(codec.encodeHex(PLAIN), codec.encodeHex(PLAIN));
    }

    /**
     * 旧 CBC 密文（无前缀、固定 IV）必须仍可解密。
     */
    @Test
    @DisplayName("aes-gcm 兼容旧 CBC 密文")
    void aesGcmReadsLegacyCipher() throws Exception {
        String legacy = legacyCbc(PLAIN);
        assertEquals(PLAIN, Codec.build("aes-gcm", KEY).decodeHex(legacy));
    }

    /**
     * 篡改 GCM 密文必须认证失败，而不是解出半截明文。
     */
    @Test
    @DisplayName("aes-gcm 篡改即失败")
    void aesGcmTamperRejected() {
        Codec codec = Codec.build("aes-gcm", KEY);
        String cipher = codec.encodeHex(PLAIN);
        char last = cipher.charAt(cipher.length() - 1);
        String tampered = cipher.substring(0, cipher.length() - 1) + (last == '0' ? '1' : '0');
        assertThrows(RuntimeException.class, () -> codec.decodeHex(tampered));
    }

    /**
     * SM4 走 SPI 的真实实现，密文可还原；缺少 BouncyCastle 时必须显式报错而不是退回 hex。
     *
     * <p>common-starter 本身不含 {@code jdk15on} 依赖，所以这里两种结果都接受，
     * 但绝不允许「悄悄输出十六进制明文」。</p>
     */
    @Test
    @DisplayName("sm4 要么真加密，要么显式缺提供者")
    void sm4NeverFallsBackToHex() {
        Codec codec = Codec.build("sm4", KEY);
        try {
            String cipher = codec.encodeHex(PLAIN);
            assertNotEquals(hexOf(PLAIN), cipher);
            assertEquals(PLAIN, codec.decodeHex(cipher));
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("jdk15on-starter"), "缺提供者时应提示依赖，实际: " + e.getMessage());
        }
    }

    /**
     * hex 类型是显式编码，仅用于未配置密钥的兼容路径。
     */
    @Test
    @DisplayName("hex 是编码不是加密")
    void hexIsExplicitEncoding() {
        Codec codec = Codec.build("hex", null);
        String encoded = codec.encodeHex(PLAIN);
        assertEquals(hexOf(PLAIN), encoded);
        assertEquals(PLAIN, codec.decodeHex(encoded));
    }

    /**
     * 未知类型必须失败，不能静默退化成 hex。
     */
    @Test
    @DisplayName("未知类型即失败")
    void unknownTypeRejected() {
        assertThrows(IllegalArgumentException.class, () -> Codec.build("rc4", KEY));
    }

    /**
     * 空密钥必须失败，不能拿空串当密钥加密。
     */
    @Test
    @DisplayName("空密钥即失败")
    void emptyKeyRejected() {
        Codec codec = Codec.build("aes", "");
        assertThrows(RuntimeException.class, () -> codec.encodeHex(PLAIN));
    }

    /**
     * 明文转小写十六进制。
     *
     * @param plain 明文
     * @return 十六进制
     */
    private static String hexOf(String plain) {
        return Hex.encodeHexString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 用 JDK 按前端同一套派生规则生成基准密文。
     *
     * @param plain 明文
     * @return 十六进制密文
     * @throws Exception 加密失败
     */
    private static String expectedCbc(String plain) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(KEY.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(digest, "AES"),
                // [P3C A20 豁免]
                new IvParameterSpec(Arrays.copyOf(digest, 16)));
        return Hex.encodeHexString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 按旧实现生成 CBC 密文：IV 取密钥摘要前 16 字节，密文不含 IV。
     *
     * @param plain 明文
     * @return 十六进制密文
     * @throws Exception 加密失败
     */
    private static String legacyCbc(String plain) throws Exception {
        return expectedCbc(plain);
    }
}
