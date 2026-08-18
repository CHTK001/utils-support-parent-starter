package com.chua.jdk15on.support.crypto;

import com.chua.common.support.lang.algorithm.cipher.Sm4Cipher;
import com.chua.common.support.spi.annotations.Spi;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.Security;

/**
 * 基于 BouncyCastle 的 SM4 对称加解密实现
 *
 * <p>通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者
 * 实现 SM4/ECB/PKCS7Padding 模式的加密与解密。
 *
 * @author CH
 * @since 2026/07/15
 */
@Spi({"bc", "bouncycastle"})
public class BcSm4Cipher implements Sm4Cipher {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /** 算法 */
    private static final String ALGORITHM = "SM4";
    /** Transformation */
    private static final String TRANSFORMATION = "SM4/ECB/PKCS7Padding";

    @Override
    public byte[] encrypt(byte[] key, byte[] data) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(validateKey(key), ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, "BC");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("SM4 加密失败", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] key, byte[] ciphertext) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(validateKey(key), ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, "BC");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("SM4 解密失败", e);
        }
    }

    /**
     * 校验密钥长度
     *
     * @param key 密钥字节
     * @return 原密钥
     */
    private static byte[] validateKey(byte[] key) {
        if (key == null || key.length != 16) {
            throw new IllegalArgumentException("SM4 密钥长度必须为 16 字节（128 位）");
        }
        return key;
    }
}
