// [P3C A20 豁免] A20/A30/A31: 含算法名/模式名等协议常量，属非业务魔法值场景。
package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.annotations.Spi;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * 基于 JDK 内置实现的 DES。
 *
 * <p>算法为 {@code DES/CBC/PKCS5Padding}，密钥 8 字节，每次加密生成 8 字节随机 IV
 * 并置于密文头部。</p>
 *
 * @author CH
 * @since 2026/07/16
 * @deprecated 请改用 {@link AesCipher} 或 {@link Sm4Cipher}
 */
@Deprecated(since = "4.0.0.50", forRemoval = false)
@Spi({"jdk", "default"})
public class JdkDesCipher implements DesCipher {

    /**
     * 算法名称
     */
    private static final String ALGORITHM = "DES";

    /**
     * 加密转换算法
     */
    private static final String TRANSFORMATION = "DES/CBC/PKCS5Padding";

    /**
     * IV 长度（字节）
     */
    private static final int IV_LENGTH = 8;

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
            throw new IllegalStateException("DES 加密失败", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] key, byte[] ciphertext) {
        try {
            if (ciphertext == null || ciphertext.length <= IV_LENGTH) {
                throw new IllegalArgumentException("DES 密文长度不足");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(ciphertext, 0, iv, 0, IV_LENGTH);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM),
                    new IvParameterSpec(iv));
            return cipher.doFinal(ciphertext, IV_LENGTH, ciphertext.length - IV_LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException("DES 解密失败", e);
        }
    }
}
