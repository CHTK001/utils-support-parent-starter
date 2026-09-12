package com.chua.jdk15on.support.crypto;

import com.chua.common.support.lang.algorithm.cipher.TwofishCipher;
import com.chua.common.support.spi.annotations.Spi;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Security;
import java.security.SecureRandom;

/**
   * 基于 bouncycastle 的 Twofish 对称加解密实现
 *
 * <p>通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者
 * 实现 Twofish/CBC/PKCS7Padding 模式的加密与解密。
 * 密钥长度支持 16、24 或 32 字节。
 *
 * @author CH
 * @since 2026/07/16
 */
@Spi({"bc", "bouncycastle"})
public class BcTwofishCipher implements TwofishCipher {

    /** 提供者 */
    private static final String PROVIDER = "BC";
    /** 算法 */
    private static final String ALGORITHM = "Twofish";
    /** 转变 */
    private static final String TRANSFORMATION = "Twofish/CBC/PKCS7Padding";

    static {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Override
    /** Encrypt */
    public byte[] encrypt(byte[] key, byte[] data) {
        try {
            byte[] iv = new byte[16];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, PROVIDER);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(data);
            byte[] result = new byte[16 + encrypted.length];
            System.arraycopy(iv, 0, result, 0, 16);
            System.arraycopy(encrypted, 0, result, 16, encrypted.length);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Twofish 加密失败", e);
        }
    }

    @Override
    /** Decrypt */
    public byte[] decrypt(byte[] key, byte[] ciphertext) {
        try {
            byte[] iv = new byte[16];
            System.arraycopy(ciphertext, 0, iv, 0, 16);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, PROVIDER);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(ciphertext, 16, ciphertext.length - 16);
        } catch (Exception e) {
            throw new RuntimeException("Twofish 解密失败", e);
        }
    }
}