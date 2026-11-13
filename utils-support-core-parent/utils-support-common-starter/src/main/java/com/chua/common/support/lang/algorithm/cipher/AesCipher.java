package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.ServiceProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES 对称加解密接口。
 *
 * <p>实现由 SPI 提供：默认提供者 {@code jdk} 基于 JDK 内置
 * {@code AES/CBC/PKCS5Padding}，密文前 16 字节为随机 IV，字符串模式使用 Base64。
 * 需要别的实现（如硬件加密卡）时，注册同名扩展即可替换。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * AesCipher aes = AesCipher.create("jdk");
 * byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
 * byte[] ciphertext = aes.encrypt(key, plaintext);
 * byte[] plain = aes.decrypt(key, ciphertext);
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
public interface AesCipher extends Cipher {

    /**
     * 创建指定提供者的 AES 实例。
     *
     * @param provider 提供者名称，如 {@code jdk}
     * @return AES 实例
     */
    static AesCipher create(String provider) {
        return ServiceProvider.of(AesCipher.class).getNewExtension(provider);
    }

    /**
     * 加密。
     *
     * @param key  密钥，16/24/32 字节
     * @param data 明文
     * @return 密文，前 16 字节为随机 IV
     */
    byte[] encrypt(byte[] key, byte[] data);

    /**
     * 解密。
     *
     * @param key        密钥
     * @param ciphertext 密文，前 16 字节为随机 IV
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
