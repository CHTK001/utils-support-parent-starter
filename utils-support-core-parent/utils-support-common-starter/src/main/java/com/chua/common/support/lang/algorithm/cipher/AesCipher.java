package com.chua.common.support.lang.algorithm.cipher;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static java.util.Base64.*;

/**
 * AES 对称加解密实现
 *
 * <p>基于 JDK 内置 {@link Cipher} 实现，支持 AES/CBC/PKCS5Padding 模式的加密与解密。
 * 密钥长度支持 128 位、192 位和 256 位。
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建实例
 * AesCipher aes = new AesCipher();
 *
 * // 加密
 * byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8); // 16 字节 = 128 位
 * byte[] ciphertext = aes.encrypt(key, plaintext);
 *
 * // 解密
 * byte[] decrypted = aes.decrypt(key, ciphertext);
 *
 * // 字符串模式
 * String encryptedStr = aes.encryptToString(key, "明文数据");
 * String decryptedStr = aes.decryptToString(key, encryptedStr);
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
public class AesCipher implements com.chua.common.support.lang.algorithm.cipher.Cipher {

    /**
     * 算法名称
     */
    private static final String ALGORITHM = "AES";
    /**
     * 加密转换算法
    */
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    /**
     * AES 加密
     *
     * @param key  加密密钥，支持 16 字节（128 位）、24 字节（192 位）或 32 字节（256 位）
     * @param data 待加密的明文数据
     * @return 加密后的密文数据（前 16 字节为随机 IV）
     */
    public byte[] encrypt(byte[] key, byte[] data) {
        try {
            byte[] iv = new byte[16];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            var cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(data);
            byte[] result = new byte[16 + encrypted.length];
            System.arraycopy(iv, 0, result, 0, 16);
            System.arraycopy(encrypted, 0, result, 16, encrypted.length);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("AES 加密失败", e);
        }
    }

    /**
     * AES 加密（字符串模式）
     *
     * @param key  加密密钥
     * @param data 待加密的明文字符串
     * @return Base64 编码的密文字符串
     */
    public String encryptToString(byte[] key, String data) {
        return getEncoder().encodeToString(
                encrypt(key, data.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * AES 解密
     *
     * @param key        解密密钥，必须与加密时使用的密钥一致
     * @param ciphertext 待解密的密文数据（前 16 字节为随机 IV）
     * @return 解密后的明文数据
     */
    public byte[] decrypt(byte[] key, byte[] ciphertext) {
        try {
            byte[] iv = new byte[16];
            System.arraycopy(ciphertext, 0, iv, 0, 16);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            var cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(ciphertext, 16, ciphertext.length - 16);
        } catch (Exception e) {
            throw new RuntimeException("AES 解密失败", e);
        }
    }

    /**
     * AES 解密（字符串模式）
     *
     * @param key          解密密钥，必须与加密时使用的密钥一致
     * @param ciphertext64 Base64 编码的密文字符串
     * @return 解密后的明文字符串
     */
    public String decryptToString(byte[] key, String ciphertext64) {
        return new String(
                decrypt(key, getDecoder().decode(ciphertext64)),
                StandardCharsets.UTF_8);
    }
}
