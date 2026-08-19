package com.chua.common.support.lang.algorithm.cipher;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * DES 对称加解密实现
 *
 * <p>基于 JDK 内置 {@link javax.crypto.Cipher} 实现，支持 DES/CBC/PKCS5Padding 模式的加密与解密。
 * DES 密钥长度为 8 字节（56 位有效密钥 + 8 位奇偶校验）。
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建实例
 * DesCipher des = new DesCipher();
 *
 * // 加密
 * byte[] key = "12345678".getBytes(StandardCharsets.UTF_8); // 8 字节
 * byte[] ciphertext = des.encrypt(key, plaintext);
 *
 * // 解密
 * byte[] decrypted = des.decrypt(key, ciphertext);
 *
 * // 字符串模式
 * String encryptedStr = des.encryptToString(key, "明文数据");
 * String decryptedStr = des.decryptToString(key, encryptedStr);
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
public class DesCipher implements com.chua.common.support.lang.algorithm.cipher.Cipher {

    /**
     * 算法名称
     */
    private static final String ALGORITHM = "DES";
    /** 加密转换算法 */
    private static final String TRANSFORMATION = "DES/CBC/PKCS5Padding";

    /**
     * DES 加密
     *
     * @param key  加密密钥，长度必须为 8 字节（56 位有效密钥）
     * @param data 待加密的明文数据
     * @return 加密后的密文数据（前 8 字节为随机 IV）
     */
    public byte[] encrypt(byte[] key, byte[] data) {
        try {
            byte[] iv = new byte[8];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(data);
            byte[] result = new byte[8 + encrypted.length];
            System.arraycopy(iv, 0, result, 0, 8);
            System.arraycopy(encrypted, 0, result, 8, encrypted.length);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("DES 加密失败", e);
        }
    }

    /**
     * DES 加密（字符串模式）
     *
     * @param key  加密密钥，长度必须为 8 字节
     * @param data 待加密的明文字符串
     * @return Base64 编码的密文字符串
     */
    public String encryptToString(byte[] key, String data) {
        return java.util.Base64.getEncoder().encodeToString(
                encrypt(key, data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * DES 解密
     *
     * @param key        解密密钥，必须与加密时使用的密钥一致
     * @param ciphertext 待解密的密文数据（前 8 字节为随机 IV）
     * @return 解密后的明文数据
     */
    public byte[] decrypt(byte[] key, byte[] ciphertext) {
        try {
            byte[] iv = new byte[8];
            System.arraycopy(ciphertext, 0, iv, 0, 8);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(ciphertext, 8, ciphertext.length - 8);
        } catch (Exception e) {
            throw new RuntimeException("DES 解密失败", e);
        }
    }

    /**
     * DES 解密（字符串模式）
     *
     * @param key          解密密钥，必须与加密时使用的密钥一致
     * @param ciphertext64 Base64 编码的密文字符串
     * @return 解密后的明文字符串
     */
    public String decryptToString(byte[] key, String ciphertext64) {
        return new String(
                decrypt(key, java.util.Base64.getDecoder().decode(ciphertext64)),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
