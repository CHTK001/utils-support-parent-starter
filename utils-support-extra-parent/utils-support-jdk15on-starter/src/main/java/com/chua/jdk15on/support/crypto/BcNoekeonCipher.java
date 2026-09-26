package com.chua.jdk15on.support.crypto;

import com.chua.common.support.lang.algorithm.cipher.NoekeonCipher;
import com.chua.common.support.spi.annotations.Spi;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.Security;
import java.security.SecureRandom;

/**
 * 基于 bouncycastle 的 Noekeon 对称加解密实现
 *
 * <p>通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者
 * 实现 Noekeon/ECB/PKCS7Padding 模式的加密与解密。
 * 密钥长度固定为 16 字节（128 位）。
 *
 * <p>不用 ZeroBytePadding：该填充无法区分明文自身的尾部 {@code 0x00} 与填充字节，
 * 解密时会把明文尾部的零当作填充删掉，造成静默截断。
 *
 * @author CH
 * @since 2026/07/16
 */
@Spi({"bc", "bouncycastle"})
public class BcNoekeonCipher implements NoekeonCipher {

    /**
     * 提供者
    */
    private static final String PROVIDER = "BC";
    /**
     * 算法
    */
    private static final String ALGORITHM = "Noekeon";
    /**
     * 转变
    */
    private static final String TRANSFORMATION = "Noekeon/ECB/PKCS7Padding";

    static {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Override
    /**
     * Encrypt
    */
    public byte[] encrypt(byte[] key, byte[] data) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, PROVIDER);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("Noekeon 加密失败", e);
        }
    }

    @Override
    /**
     * Decrypt
    */
    public byte[] decrypt(byte[] key, byte[] ciphertext) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, PROVIDER);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Noekeon 解密失败", e);
        }
    }
}