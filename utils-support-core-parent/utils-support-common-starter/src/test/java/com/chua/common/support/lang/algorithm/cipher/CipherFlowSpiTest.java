package com.chua.common.support.lang.algorithm.cipher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AES / DES 已经走 SPI 解析，而不是直接 new。
 *
 * <p>这条用例覆盖扩展文件是否真的被加载：删掉
 * {@code META-INF/extensions/...AesCipher} 后，{@code create("jdk")} 返回 null，
 * 用例会直接 NPE，而不是悄悄回退到别的实现。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class CipherFlowSpiTest {

    /**
     * AES 经 SPI 取到 JDK 实现并完成往返。
     */
    @Test
    @DisplayName("AES 走 SPI 且可往返")
    void aesResolvedBySpi() {
        AesCipher aes = CipherFlow.of("aes").aes();
        assertTrue(aes instanceof JdkAesCipher);

        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] plain = "月英".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(plain, aes.decrypt(key, aes.encrypt(key, plain)));
    }

    /**
     * DES 经 SPI 取到 JDK 实现并完成往返。
     */
    @Test
    @DisplayName("DES 走 SPI 且可往返")
    void desResolvedBySpi() {
        DesCipher des = CipherFlow.of("des").des();
        assertTrue(des instanceof JdkDesCipher);

        byte[] key = "12345678".getBytes(StandardCharsets.UTF_8);
        byte[] plain = "孔明".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(plain, des.decrypt(key, des.encrypt(key, plain)));
    }

    /**
     * 未知算法名直接失败。
     */
    @Test
    @DisplayName("未知算法即失败")
    void unknownAlgorithmRejected() {
        assertThrows(IllegalArgumentException.class, () -> CipherFlow.of("rc4").resolve());
    }
}
