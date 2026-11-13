// [P3C A20 豁免] A20/A30/A31: 含算法名/模式名等协议常量，属非业务魔法值场景。
package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.annotations.Spi;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * 基于 JDK 内置实现的 AES。
 *
 * <p>算法为 {@code AES/CBC/PKCS5Padding}，密钥长度支持 128/192/256 位，
 * 每次加密生成 16 字节随机 IV 并置于密文头部。</p>
 *
 * @author CH
 * @since 2026/07/16
 */
@Spi({"jdk", "default"})
public class JdkAesCipher implements AesCipher {

    /**
     * 算法名称
     */
    private static final String ALGORITHM = "AES";

    /**
     * 加密转换算法
     */
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    /**
     * IV 长度（字节）
     */
    private static final int IV_LENGTH = 16;

    /**
     * 随机数发生器
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public byte[] encrypt(byte[] key, byte[] data) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new SecretKeySpec(key, ALGORITHM),
                    new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(data);
            byte[] result = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, result, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, result, IV_LENGTH, encrypted.length);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("AES 加密失败", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] key, byte[] ciphertext) {
        try {
            if (ciphertext == null || ciphertext.length <= IV_LENGTH) {
                throw new IllegalArgumentException("AES 密文长度不足");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(ciphertext, 0, iv, 0, IV_LENGTH);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM),
                    new IvParameterSpec(iv));
            return cipher.doFinal(ciphertext, IV_LENGTH, ciphertext.length - IV_LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException("AES 解密失败", e);
        }
    }
}
