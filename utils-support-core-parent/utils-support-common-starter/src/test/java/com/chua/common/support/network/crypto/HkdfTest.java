// [P3C A20 豁免] RFC 5869 测试向量常量。
package com.chua.common.support.network.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HKDF-SHA256 的正确性与边界。
 *
 * <p>用 RFC 5869 Test Case 1 的公开向量做锚点：只测「两次输出一样」证明不了
 * 实现符合标准，换一个 HMAC 参数顺序、漏掉计数器字节，自测仍然会过，
 * 但和任何标准实现都互不通。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class HkdfTest {

    /**
     * RFC 5869 Test Case 1：IKM 22 字节 0x0b，盐 13 字节，info 10 字节，输出 42 字节。
     */
    @Test
    @DisplayName("RFC 5869 Test Case 1 向量")
    void matchesRfc5869CaseOne() {
        byte[] ikm = repeat((byte) 0x0b, 22);
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");

        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
                toHex(Hkdf.extract(salt, ikm)));
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                toHex(Hkdf.expand(Hkdf.extract(salt, ikm), info, 42)));
    }

    /**
     * 不同 info 必须派生不同密钥，否则方向隔离就是空的。
     */
    @Test
    @DisplayName("info 不同则子密钥不同")
    void differentInfoYieldsDifferentKey() {
        byte[] rootKey = repeat((byte) 0x2a, 32);
        byte[] salt = Hkdf.randomBytes(16);

        byte[] client = Hkdf.derive(rootKey, salt, "chua/session/v1/initiator-to-responder", 32);
        byte[] server = Hkdf.derive(rootKey, salt, "chua/session/v1/responder-to-initiator", 32);

        assertEquals(32, client.length);
        assertNotEquals(toHex(client), toHex(server));
    }

    /**
     * 同输入必须可复现，否则两端算不出同一把密钥。
     */
    @Test
    @DisplayName("同输入可复现")
    void derivationIsDeterministic() {
        byte[] rootKey = repeat((byte) 0x17, 32);
        byte[] salt = hex("00112233445566778899aabbccddeeff");
        byte[] first = Hkdf.derive(rootKey, salt, "chua/test", 20);
        byte[] second = Hkdf.derive(rootKey, salt, "chua/test", 20);
        assertArrayEquals(first, second);
    }

    /**
     * 长度越界必须立即失败，而不是静默截断。
     */
    @Test
    @DisplayName("派生长度越界即拒绝")
    void rejectsInvalidLength() {
        byte[] rootKey = repeat((byte) 0x01, 32);
        assertThrows(IllegalArgumentException.class, () -> Hkdf.derive(rootKey, null, "x", 0));
        assertThrows(IllegalArgumentException.class, () -> Hkdf.derive(rootKey, null, "x", 255 * 32 + 1));
        assertThrows(IllegalArgumentException.class, () -> Hkdf.extract(null, new byte[0]));
    }

    /**
     * 空盐按 RFC 5869 使用 HashLen 个零字节。
     */
    @Test
    @DisplayName("空盐等价于全零盐")
    void emptySaltEqualsZeroSalt() {
        byte[] rootKey = "root-key-for-test".getBytes(StandardCharsets.UTF_8);
        byte[] zeroSalt = new byte[Hkdf.HASH_LENGTH];
        assertArrayEquals(Hkdf.extract(zeroSalt, rootKey), Hkdf.extract(null, rootKey));
    }

    /**
     * 生成指定长度的字节。
     *
     * @param value 字节值
     * @param count 个数
     * @return 字节数组
     */
    private static byte[] repeat(byte value, int count) {
        byte[] bytes = new byte[count];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    /**
     * 十六进制转字节。
     *
     * @param hex 十六进制字符串
     * @return 字节数组
     */
    private static byte[] hex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    /**
     * 字节转小写十六进制。
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
