package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.ServiceProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * DES 对称加解密接口。
 *
 * <p>默认实现由 SPI 提供者 {@code jdk} 承担（{@code DES/CBC/PKCS5Padding}），
 * 密文前 8 字节为随机 IV，字符串模式使用 Base64。</p>
 *
 * <p><b>DES 密钥空间过小，新项目请用 AES 或 SM4</b>；本接口保留只为兼容历史链路。</p>
 *
 * @author CH
 * @since 2026/07/16
 * @deprecated 请改用 {@link AesCipher} 或 {@link Sm4Cipher}
 */
@Deprecated(since = "4.0.0.50", forRemoval = false)
public interface DesCipher extends Cipher {

    /**
     * 创建指定提供者的 DES 实例。
     *
     * @param provider 提供者名称，如 {@code jdk}
     * @return DES 实例
     */
    static DesCipher create(String provider) {
        return ServiceProvider.of(DesCipher.class).getNewExtension(provider);
    }

    /**
     * 加密。
     *
     * @param key  密钥，8 字节
     * @param data 明文
     * @return 密文，前 8 字节为随机 IV
     */
    byte[] encrypt(byte[] key, byte[] data);

    /**
     * 解密。
     *
     * @param key        密钥
     * @param ciphertext 密文，前 8 字节为随机 IV
     * @return 明文
     */
    byte[] decrypt(byte[] key, byte[] ciphertext);

    /**
     * 加密字符串，结果 Base64。
     *
     * @param key  密钥
     * @param data 明文
     * @return Base64 密文
     */
    default String encryptToString(byte[] key, String data) {
        return Base64.getEncoder().encodeToString(encrypt(key, data.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 解密 Base64 密文。
     *
     * @param key          密钥
     * @param ciphertext64 Base64 密文
     * @return 明文
     */
    default String decryptToString(byte[] key, String ciphertext64) {
        return new String(decrypt(key, Base64.getDecoder().decode(ciphertext64)), StandardCharsets.UTF_8);
    }
}
