package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.ServiceProvider;
import org.jspecify.annotations.NullUnmarked;

/**
 * Twofish 对称加解密 SPI 接口
 *
 * <p>基于 SPI 机制加载实现，支持 Twofish/CBC/PKCS7Padding 模式的加密与解密。
 * Twofish 是 AES 评选入围算法之一，密钥长度支持 128、192 和 256 位。
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建 Twofish 加解密实例
 * TwofishCipher cipher = TwofishCipher.create("bc");
 *
 * // 加密
 * byte[] ciphertext = cipher.encrypt(key, plaintext);
 *
 * // 解密
 * byte[] decrypted = cipher.decrypt(key, ciphertext);
 *
 * // 字符串模式
 * String encryptedStr = cipher.encryptToString(key, "明文数据");
 * String decryptedStr = cipher.decryptToString(key, encryptedStr);
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public interface TwofishCipher extends Cipher {

    /**
     * 创建指定提供者的 Twofish 加解密实例
     *
     * @param provider 提供者名称，如 "bc"（BouncyCastle）
     * @return TwofishCipher 实例
     * @throws com.chua.common.support.spi.ExtensionNotFoundException 当指定提供者不存在时抛出
     */
    static TwofishCipher create(String provider) {
        return ServiceProvider.of(TwofishCipher.class).getNewExtension(provider);
    }

    /**
     * Twofish 加密
     *
     * @param key  加密密钥，支持 16、24 或 32 字节
     * @param data 待加密的明文数据
     * @return 加密后的密文数据
     */
    byte[] encrypt(byte[] key, byte[] data);

    /**
     * Twofish 解密
     *
     * @param key        解密密钥，必须与加密时使用的密钥一致
     * @param ciphertext 待解密的密文数据
     * @return 解密后的明文数据
     */
    byte[] decrypt(byte[] key, byte[] ciphertext);

    /**
     * Twofish 加密（字符串模式）
     *
     * @param key  加密密钥
     * @param data 待加密的明文字符串
     * @return Base64 编码的密文字符串
     */
    default String encryptToString(byte[] key, String data) {
        return java.util.Base64.getEncoder().encodeToString(
                encrypt(key, data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * Twofish 解密（字符串模式）
     *
     * @param key          解密密钥，必须与加密时使用的密钥一致
     * @param ciphertext64 Base64 编码的密文字符串
     * @return 解密后的明文字符串
     */
    default String decryptToString(byte[] key, String ciphertext64) {
        return new String(
                decrypt(key, java.util.Base64.getDecoder().decode(ciphertext64)),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}