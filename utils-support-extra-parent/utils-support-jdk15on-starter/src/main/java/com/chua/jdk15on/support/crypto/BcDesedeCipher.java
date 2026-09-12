package com.chua.jdk15on.support.crypto;

import com.chua.common.support.lang.algorithm.cipher.DesedeCipher;
import com.chua.common.support.spi.annotations.Spi;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Security;
import java.security.SecureRandom;

/**
   * 基于 bouncycastle 的 3DES 对称加解密实现
 *
 * <p>通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者
   * 实现 desede/CBC/PKCS7Padding 模式的加密与解密。
 * 密钥长度为 24 字节（192 位），分组长度为 64 位。
 *
 * @author CH
 * @since 2026/07/16
 */
@Spi({"bc", "bouncycastle"})
public class BcDesedeCipher implements DesedeCipher {

    /** 提供者 */
    private static final String PROVIDER = "BC";
    /** 算法 */
    private static final String ALGORITHM = "DESede";
    /** 转变 */
    private static final String TRANSFORMATION = "DESede/CBC/PKCS7Padding";

    static {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Override
    /** Encrypt */
    public byte[] encrypt(byte[] key, byte[] data) {
        try {
            byte[] iv = new byte[8];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, PROVIDER);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(data);
            byte[] result = new byte[8 + encrypted.length];
            System.arraycopy(iv, 0, result, 0, 8);
            System.arraycopy(encrypted, 0, result, 8, encrypted.length);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("3DES 加密失败", e);
        }
    }

    @Override
    /** Decrypt */
    public byte[] decrypt(byte[] key, byte[] ciphertext) {
        try {
            byte[] iv = new byte[8];
            System.arraycopy(ciphertext, 0, iv, 0, 8);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, PROVIDER);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(ciphertext, 8, ciphertext.length - 8);
        } catch (Exception e) {
            throw new RuntimeException("3DES 解密失败", e);
        }
    }
}
